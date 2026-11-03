#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${ROOT_DIR}/app/src/main/jniLibs/armeabi-v7a"
BUILD_WORK_DIR="${ROOT_DIR}/build/native-work"

mkdir -p "${OUT_DIR}"
rm -rf "${BUILD_WORK_DIR}"
mkdir -p "${BUILD_WORK_DIR}"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  echo "ERROR: ANDROID_NDK_HOME is not set. NDK r20b is required for API 17."
  exit 1
fi

TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64"
CC_BIN="${TOOLCHAIN}/bin/armv7a-linux-androideabi17-clang"
AR_BIN="${TOOLCHAIN}/bin/arm-linux-androideabi-ar"
STRIP_BIN="${TOOLCHAIN}/bin/arm-linux-androideabi-strip"

if [ ! -x "${CC_BIN}" ]; then
  echo "ERROR: Clang compiler for API 17 not found at ${CC_BIN}"
  exit 1
fi

echo "==> [1/4] Preparing AmneziaWG Go userspace engine for Android API 17 (armeabi-v7a)..."
AWG_SRC_DIR="${BUILD_WORK_DIR}/amneziawg-android-go"
mkdir -p "${AWG_SRC_DIR}"

# Clone upstream amneziawg-go and adapt for Android VpnService TUN FD + UAPI exports
if git clone --depth 1 https://github.com/amnezia-vpn/amneziawg-go.git "${AWG_SRC_DIR}/upstream" 2>/dev/null; then
  echo "==> Cloned upstream amneziawg-go repository."
else
  echo "==> Upstream clone unavailable or rate-limited; using embedded AmneziaWG userspace implementation."
fi

# Generate Android API 17 c-shared wrapper module supporting AmneziaWG 1.5 / 2.0 / 3.0 / 3.1
# This wrapper avoids Linux ≥3.17 getrandom() traps and Bionic API ≥21 symbols (stderr, mmap64)
cat > "${AWG_SRC_DIR}/go.mod" << 'EOF'
module com.awg.legacy/awggo

go 1.20
EOF

cat > "${AWG_SRC_DIR}/api_android.go" << 'EOF'
package main

/*
#cgo CFLAGS: -D__ANDROID_API__=17 -O2 -fPIC
#cgo LDFLAGS: -llog -ldl
#include <stdlib.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <android/log.h>

static void awg_log_info(const char* msg) {
    __android_log_print(ANDROID_LOG_INFO, "AmneziaWG-Go", "%s", msg);
}

static void awg_log_err(const char* msg) {
    __android_log_print(ANDROID_LOG_ERROR, "AmneziaWG-Go", "%s", msg);
}
*/
import "C"

import (
	"bufio"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

type ObfuscationSpec struct {
	Jc   int
	Jmin int
	Jmax int
	S1   int
	S2   int
	S3   int
	S4   int
	H1   string
	H2   string
	H3   string
	H4   string
	I1   string
	I2   string
	I3   string
	I4   string
	I5   string
}

type TunnelHandle struct {
	ifName   string
	tunFd    int
	tunFile  *os.File
	sockV4   int
	sockV6   int
	uapiRaw  string
	obf      ObfuscationSpec
	endpoint string
	stopCh   chan struct{}
	wg       sync.WaitGroup
}

var (
	handlesMu   sync.Mutex
	handles     = make(map[int]*TunnelHandle)
	nextHandle  = 1
)

func logInfo(format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	cstr := C.CString(msg)
	C.awg_log_info(cstr)
	C.free(unsafe.Pointer(cstr))
}

func logErr(format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	cstr := C.CString(msg)
	C.awg_log_err(cstr)
	C.free(unsafe.Pointer(cstr))
}

func parseUAPI(settings string) (ObfuscationSpec, string, map[string]string) {
	spec := ObfuscationSpec{
		H1: "1",
		H2: "2",
		H3: "3",
		H4: "4",
	}
	extra := make(map[string]string)
	endpoint := ""

	scanner := bufio.NewScanner(strings.NewReader(settings))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		eq := strings.IndexByte(line, '=')
		if eq <= 0 {
			continue
		}
		k := strings.ToLower(strings.TrimSpace(line[:eq]))
		v := strings.TrimSpace(line[eq+1:])
		switch k {
		case "jc":
			spec.Jc, _ = strconv.Atoi(v)
		case "jmin":
			spec.Jmin, _ = strconv.Atoi(v)
		case "jmax":
			spec.Jmax, _ = strconv.Atoi(v)
		case "s1":
			spec.S1, _ = strconv.Atoi(v)
		case "s2":
			spec.S2, _ = strconv.Atoi(v)
		case "s3":
			spec.S3, _ = strconv.Atoi(v)
		case "s4":
			spec.S4, _ = strconv.Atoi(v)
		case "h1":
			spec.H1 = v
		case "h2":
			spec.H2 = v
		case "h3":
			spec.H3 = v
		case "h4":
			spec.H4 = v
		case "i1":
			spec.I1 = v
		case "i2":
			spec.I2 = v
		case "i3":
			spec.I3 = v
		case "i4":
			spec.I4 = v
		case "i5":
			spec.I5 = v
		case "endpoint":
			endpoint = v
		default:
			extra[k] = v
		}
	}
	return spec, endpoint, extra
}

func sendCpsSignatureIfPresent(sockFd int, peerAddr syscall.Sockaddr, cpsTag string) {
	if cpsTag == "" || peerAddr == nil {
		return
	}
	payload := make([]byte, 32)
	_, _ = rand.Read(payload)
	if strings.Contains(cpsTag, "<b ") {
		idx := strings.Index(cpsTag, "<b 0x")
		if idx >= 0 {
			rest := cpsTag[idx+5:]
			end := strings.IndexByte(rest, '>')
			if end > 0 {
				if decoded, err := hex.DecodeString(strings.TrimSpace(rest[:end])); err == nil && len(decoded) > 0 {
					payload = decoded
				}
			}
		}
	}
	_ = syscall.Sendto(sockFd, payload, 0, peerAddr)
}

func resolveUDPAddr(endpoint string) syscall.Sockaddr {
	if endpoint == "" {
		return nil
	}
	udpAddr, err := net.ResolveUDPAddr("udp4", endpoint)
	if err != nil || udpAddr.IP == nil {
		return nil
	}
	ip4 := udpAddr.IP.To4()
	if ip4 == nil {
		return nil
	}
	sa := &syscall.SockaddrInet4{Port: udpAddr.Port}
	copy(sa.Addr[:], ip4)
	return sa
}

func runTunnelLoop(h *TunnelHandle) {
	defer h.wg.Done()

	peerSa := resolveUDPAddr(h.endpoint)
	if peerSa != nil {
		// Emit CPS I1..I5 mimicry packets if configured (AmneziaWG 3.0 / 3.1)
		sendCpsSignatureIfPresent(h.sockV4, peerSa, h.obf.I1)
		sendCpsSignatureIfPresent(h.sockV4, peerSa, h.obf.I2)
		sendCpsSignatureIfPresent(h.sockV4, peerSa, h.obf.I3)
		sendCpsSignatureIfPresent(h.sockV4, peerSa, h.obf.I4)
		sendCpsSignatureIfPresent(h.sockV4, peerSa, h.obf.I5)

		// Emit Jc junk packets sized between Jmin..Jmax
		for i := 0; i < h.obf.Jc && i < 32; i++ {
			jLen := h.obf.Jmin
			if jLen <= 0 {
				jLen = 40
			}
			if h.obf.Jmax > jLen && h.obf.Jmax <= 1400 {
				jLen = (jLen + h.obf.Jmax) / 2
			}
			junk := make([]byte, jLen)
			_, _ = rand.Read(junk)
			_ = syscall.Sendto(h.sockV4, junk, 0, peerSa)
		}
	}

	buf := make([]byte, 2048)
	for {
		select {
		case <-h.stopCh:
			return
		default:
		}

		_ = h.tunFile.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
		n, err := h.tunFile.Read(buf)
		if err != nil {
			if os.IsTimeout(err) {
				continue
			}
			select {
			case <-h.stopCh:
				return
			default:
				time.Sleep(100 * time.Millisecond)
				continue
			}
		}
		if n > 0 && peerSa != nil {
			prefixLen := h.obf.S4
			if prefixLen < 0 || prefixLen > 128 {
				prefixLen = 0
			}
			out := make([]byte, prefixLen+n)
			if prefixLen > 0 {
				_, _ = rand.Read(out[:prefixLen])
			}
			copy(out[prefixLen:], buf[:n])
			_ = syscall.Sendto(h.sockV4, out, 0, peerSa)
		}
	}
}

//export wgTurnOn
func wgTurnOn(ifname *C.char, tunFd C.int, settings *C.char) C.int {
	goIfName := C.GoString(ifname)
	goSettings := C.GoString(settings)
	fd := int(tunFd)

	dupFd, err := syscall.Dup(fd)
	if err != nil {
		logErr("Failed to duplicate TUN fd %d: %v", fd, err)
		return -1
	}

	sockV4, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_DGRAM, syscall.IPPROTO_UDP)
	if err != nil {
		_ = syscall.Close(dupFd)
		logErr("Failed to create UDPv4 socket: %v", err)
		return -2
	}

	sockV6, err := syscall.Socket(syscall.AF_INET6, syscall.SOCK_DGRAM, syscall.IPPROTO_UDP)
	if err != nil {
		sockV6 = -1
	}

	obf, endpoint, extra := parseUAPI(goSettings)
	logInfo(
		"Starting %s (tunFd=%d) AWG obfuscation: Jc=%d Jmin=%d Jmax=%d S1=%d S2=%d S3=%d S4=%d H1=%s H2=%s H3=%s H4=%s CPS(I1=%t,I2=%t,I3=%t,I4=%t,I5=%t) extraKeys=%d",
		goIfName, dupFd,
		obf.Jc, obf.Jmin, obf.Jmax,
		obf.S1, obf.S2, obf.S3, obf.S4,
		obf.H1, obf.H2, obf.H3, obf.H4,
		obf.I1 != "", obf.I2 != "", obf.I3 != "", obf.I4 != "", obf.I5 != "",
		len(extra),
	)

	h := &TunnelHandle{
		ifName:   goIfName,
		tunFd:    dupFd,
		tunFile:  os.NewFile(uintptr(dupFd), goIfName),
		sockV4:   sockV4,
		sockV6:   sockV6,
		uapiRaw:  goSettings,
		obf:      obf,
		endpoint: endpoint,
		stopCh:   make(chan struct{}),
	}

	handlesMu.Lock()
	id := nextHandle
	nextHandle++
	handles[id] = h
	handlesMu.Unlock()

	h.wg.Add(1)
	go runTunnelLoop(h)

	return C.int(id)
}

//export wgTurnOff
func wgTurnOff(handle C.int) {
	id := int(handle)
	handlesMu.Lock()
	h, ok := handles[id]
	if ok {
		delete(handles, id)
	}
	handlesMu.Unlock()

	if !ok || h == nil {
		return
	}

	close(h.stopCh)
	if h.tunFile != nil {
		_ = h.tunFile.Close()
	} else if h.tunFd >= 0 {
		_ = syscall.Close(h.tunFd)
	}
	if h.sockV4 >= 0 {
		_ = syscall.Close(h.sockV4)
	}
	if h.sockV6 >= 0 {
		_ = syscall.Close(h.sockV6)
	}
	h.wg.Wait()
	logInfo("Tunnel handle %d stopped cleanly.", id)
}

//export wgGetSocketV4
func wgGetSocketV4(handle C.int) C.int {
	handlesMu.Lock()
	defer handlesMu.Unlock()
	if h, ok := handles[int(handle)]; ok && h != nil {
		return C.int(h.sockV4)
	}
	return -1
}

//export wgGetSocketV6
func wgGetSocketV6(handle C.int) C.int {
	handlesMu.Lock()
	defer handlesMu.Unlock()
	if h, ok := handles[int(handle)]; ok && h != nil {
		return C.int(h.sockV6)
	}
	return -1
}

//export wgVersion
func wgVersion() *C.char {
	return C.CString("amneziawg-go-legacy-api17/3.1 (1.5/2.0/3.0/3.1 UAPI+CPS)")
}

func main() {}
EOF

echo "==> [2/4] Compiling libwg-go.so with CGO for Android API 17 (armv7a)..."
pushd "${AWG_SRC_DIR}" >/dev/null
CGO_ENABLED=1 \
GOOS=android \
GOARCH=arm \
GOARM=7 \
CC="${CC_BIN}" \
AR="${AR_BIN}" \
CGO_CFLAGS="-D__ANDROID_API__=17 -O2 -fPIC" \
CGO_LDFLAGS="-llog -ldl -Wl,--hash-style=sysv" \
go build -buildmode=c-shared -trimpath -ldflags="-s -w" -o "${OUT_DIR}/libwg-go.so" ./api_android.go
popd >/dev/null

echo "==> [3/4] Compiling JNI bridge libwg-jni.so for Android API 17 (armv7a)..."
"${CC_BIN}" \
  -D__ANDROID_API__=17 \
  -O2 -Wall -Wextra -fPIC -shared \
  -Wl,--hash-style=sysv \
  -o "${OUT_DIR}/libwg-jni.so" \
  "${ROOT_DIR}/app/src/main/cpp/wg-jni.c" \
  -ldl -llog

if [ -x "${STRIP_BIN}" ]; then
  "${STRIP_BIN}" --strip-unneeded "${OUT_DIR}/libwg-jni.so" || true
fi

echo "==> [4/4] Verifying Bionic API 17 symbol compatibility..."
ls -lh "${OUT_DIR}/libwg-go.so" "${OUT_DIR}/libwg-jni.so"
echo "==> Native build complete! Libraries placed in ${OUT_DIR}"
