import React, { useMemo, useState } from 'react';
import {
  Check,
  Copy,
  Download,
  FileCode,
  FolderGit2,
  Play,
  ShieldCheck,
  Terminal,
  AlertTriangle,
  Archive,
  BookOpen,
} from 'lucide-react';

import workflowYml from '../.github/workflows/build.yml?raw';
import buildNativeSh from '../scripts/build-native.sh?raw';
import wgJniC from '../app/src/main/cpp/wg-jni.c?raw';
import settingsGradle from '../settings.gradle?raw';
import rootBuildGradle from '../build.gradle?raw';
import appBuildGradle from '../app/build.gradle?raw';
import androidManifestXml from '../app/src/main/AndroidManifest.xml?raw';
import stringsXml from '../app/src/main/res/values/strings.xml?raw';
import stylesXml from '../app/src/main/res/values/styles.xml?raw';
import mainActivityJava from '../app/src/main/java/com/awg/legacy/MainActivity.java?raw';
import awgVpnServiceJava from '../app/src/main/java/com/awg/legacy/AwgVpnService.java?raw';
import awgConfigJava from '../app/src/main/java/com/awg/legacy/AwgConfig.java?raw';
import gitattributesRaw from '../.gitattributes?raw';

interface RepoFile {
  path: string;
  category: 'CI / Native' | 'Gradle / Manifest' | 'Java SDK (API 17)';
  language: string;
  description: string;
  content: string;
}

const REPO_FILES: RepoFile[] = [
  {
    path: '.github/workflows/build.yml',
    category: 'CI / Native',
    language: 'yaml',
    description: 'Пайплайн GitHub Actions: JDK 8, Go 1.20, Android NDK r20b, Gradle 6.7.1, сборка и публикация app-debug.apk.',
    content: workflowYml,
  },
  {
    path: '.gitattributes',
    category: 'CI / Native',
    language: 'plaintext',
    description: 'Защита от Windows CRLF переводов строк (предотвращает ошибки exit code 127 в Linux-скриптах).',
    content: gitattributesRaw,
  },
  {
    path: 'scripts/build-native.sh',
    category: 'CI / Native',
    language: 'bash',
    description: 'Автономная сборка libwg-go.so и libwg-jni.so через NDK r20b под armeabi-v7a (Android 4.2.2 API 17).',
    content: buildNativeSh,
  },
  {
    path: 'app/src/main/cpp/wg-jni.c',
    category: 'CI / Native',
    language: 'c',
    description: 'JNI-мост на C для связки AwgVpnService и нативной библиотеки libwg-go.so.',
    content: wgJniC,
  },
  {
    path: 'settings.gradle',
    category: 'Gradle / Manifest',
    language: 'groovy',
    description: 'Подключение модуля :app для Gradle 6.7.1.',
    content: settingsGradle,
  },
  {
    path: 'build.gradle',
    category: 'Gradle / Manifest',
    language: 'groovy',
    description: 'Корневой конфиг сборки с Android Gradle Plugin 4.0.2.',
    content: rootBuildGradle,
  },
  {
    path: 'app/build.gradle',
    category: 'Gradle / Manifest',
    language: 'groovy',
    description: 'Конфиг модуля: minSdkVersion 17, targetSdkVersion 28, compileSdkVersion 28, без внешних зависимостей.',
    content: appBuildGradle,
  },
  {
    path: 'app/src/main/AndroidManifest.xml',
    category: 'Gradle / Manifest',
    language: 'xml',
    description: 'Системная иконка @android:drawable/sym_def_app_icon и тема @android:style/Theme.Holo.',
    content: androidManifestXml,
  },
  {
    path: 'app/src/main/res/values/strings.xml',
    category: 'Gradle / Manifest',
    language: 'xml',
    description: 'Ресурс имени приложения для компилятора ресурсов AAPT2.',
    content: stringsXml,
  },
  {
    path: 'app/src/main/res/values/styles.xml',
    category: 'Gradle / Manifest',
    language: 'xml',
    description: 'Стиль приложения, наследующий системную тему Theme.Holo.',
    content: stylesXml,
  },
  {
    path: 'app/src/main/java/com/awg/legacy/MainActivity.java',
    category: 'Java SDK (API 17)',
    language: 'java',
    description: 'Экран клиента: поле для .conf, сохранение в SharedPreferences, кнопка Connect/Disconnect и лог.',
    content: mainActivityJava,
  },
  {
    path: 'app/src/main/java/com/awg/legacy/AwgVpnService.java',
    category: 'Java SDK (API 17)',
    language: 'java',
    description: 'Системный сервис VpnService: поднятие TUN-интерфейса awg0, защита UDP-сокетов и вызов JNI.',
    content: awgVpnServiceJava,
  },
  {
    path: 'app/src/main/java/com/awg/legacy/AwgConfig.java',
    category: 'Java SDK (API 17)',
    language: 'java',
    description: 'Парсер AmneziaWG 1.5 / 2.0 / 3.0 / 3.1 (Jc, Jmin, Jmax, S1-S4, диапазоны H1-H4, CPS I1-I5) в формат UAPI.',
    content: awgConfigJava,
  },
];

// Pure TypeScript ZIP generator (STORE method with CRC32) for zero-dependency archive export
function crc32(bytes: Uint8Array): number {
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) {
    c ^= bytes[i];
    for (let k = 0; k < 8; k++) {
      c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
    }
  }
  return (c ^ 0xffffffff) >>> 0;
}

function buildZipBlob(files: Array<{ path: string; content: string }>): Blob {
  const encoder = new TextEncoder();
  const localParts: Uint8Array[] = [];
  const centralParts: Uint8Array[] = [];
  let offset = 0;

  for (const file of files) {
    const nameBytes = encoder.encode(file.path);
    const dataBytes = encoder.encode(file.content);
    const crc = crc32(dataBytes);
    const isExecutable = file.path.endsWith('.sh');
    const externalAttr = isExecutable ? (0o100755 << 16) >>> 0 : (0o100644 << 16) >>> 0;

    const localHeader = new Uint8Array(30 + nameBytes.length);
    const lv = new DataView(localHeader.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true);
    lv.setUint16(6, 0x0800, true); // UTF-8
    lv.setUint16(8, 0, true); // STORE
    lv.setUint16(10, 0, true);
    lv.setUint16(12, 0x5421, true);
    lv.setUint32(14, crc, true);
    lv.setUint32(18, dataBytes.length, true);
    lv.setUint32(22, dataBytes.length, true);
    lv.setUint16(26, nameBytes.length, true);
    lv.setUint16(28, 0, true);
    localHeader.set(nameBytes, 30);

    const centralHeader = new Uint8Array(46 + nameBytes.length);
    const cv = new DataView(centralHeader.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 0x031e, true); // Unix
    cv.setUint16(6, 20, true);
    cv.setUint16(8, 0x0800, true); // UTF-8
    cv.setUint16(10, 0, true); // STORE
    cv.setUint16(12, 0, true);
    cv.setUint16(14, 0x5421, true);
    cv.setUint32(16, crc, true);
    cv.setUint32(20, dataBytes.length, true);
    cv.setUint32(24, dataBytes.length, true);
    cv.setUint16(28, nameBytes.length, true);
    cv.setUint16(30, 0, true);
    cv.setUint16(32, 0, true);
    cv.setUint16(34, 0, true);
    cv.setUint16(36, 0, true);
    cv.setUint32(38, externalAttr, true);
    cv.setUint32(42, offset, true);
    centralHeader.set(nameBytes, 46);

    localParts.push(localHeader, dataBytes);
    centralParts.push(centralHeader);
    offset += localHeader.length + dataBytes.length;
  }

  let centralSize = 0;
  for (const c of centralParts) centralSize += c.length;

  const eocd = new Uint8Array(22);
  const ev = new DataView(eocd.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(4, 0, true);
  ev.setUint16(6, 0, true);
  ev.setUint16(8, files.length, true);
  ev.setUint16(10, files.length, true);
  ev.setUint32(12, centralSize, true);
  ev.setUint32(16, offset, true);
  ev.setUint16(20, 0, true);

  return new Blob([...localParts, ...centralParts, eocd], { type: 'application/zip' });
}

const PRESET_CONFIGS: Record<string, { label: string; conf: string }> = {
  awg31: {
    label: 'AmneziaWG 3.1 (CPS + S3/S4 + Range H1-H4)',
    conf: `[Interface]
PrivateKey = YXdnLWxlZ2FjeS1hbmRyb2lkLTQyMi1hcGkxNy1rZXk=
Address = 10.8.1.2/32
DNS = 1.1.1.1, 1.0.0.1
MTU = 1280
Jc = 5
Jmin = 40
Jmax = 700
S1 = 44
S2 = 92
S3 = 28
S4 = 16
H1 = 10203040-20304050
H2 = 20304051-30405060
H3 = 30405061-40506070
H4 = 40506071-50607080
I1 = <b 0xc700000001><rc 8><t><r 32>
I2 = <b 0x16030100><r 16>
I3 = <b 0x1703030020><r 32>

[Peer]
PublicKey = cGVlci1wdWJsaWMta2V5LWFtbmV6aWF3Zy1hbmRyb2lk=
PresharedKey = cHJlc2hhcmVkLWtleS1hbW5lemlhd2ctYW5kcm9pZDE=
Endpoint = 198.51.100.42:51820
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25`,
  },
  awg20: {
    label: 'AmneziaWG 2.0 (Range Headers + S1-S4)',
    conf: `[Interface]
PrivateKey = YXdnLWxlZ2FjeS1hbmRyb2lkLTQyMi1hcGkxNy1rZXk=
Address = 10.9.0.4/32
DNS = 9.9.9.9
MTU = 1360
Jc = 4
Jmin = 50
Jmax = 1000
S1 = 30
S2 = 60
S3 = 20
S4 = 12
H1 = 100000-200000
H2 = 200001-300000
H3 = 300001-400000
H4 = 400001-500000

[Peer]
PublicKey = cGVlci1wdWJsaWMta2V5LWFtbmV6aWF3Zy1hbmRyb2lk=
Endpoint = 203.0.113.88:443
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 20`,
  },
  awg15: {
    label: 'AmneziaWG 1.5 (Classic Obfuscation)',
    conf: `[Interface]
PrivateKey = YXdnLWxlZ2FjeS1hbmRyb2lkLTQyMi1hcGkxNy1rZXk=
Address = 10.7.0.2/24
DNS = 1.1.1.1
MTU = 1280
Jc = 3
Jmin = 40
Jmax = 500
S1 = 15
S2 = 24
H1 = 123456789
H2 = 234567890
H3 = 345678901
H4 = 456789012

[Peer]
PublicKey = cGVlci1wdWJsaWMta2V5LWFtbmV6aWF3Zy1hbmRyb2lk=
Endpoint = 192.0.2.15:51820
AllowedIPs = 0.0.0.0/0`,
  },
};

function base64OrHexToHex(input: string, fieldName: string, lineNum: number): string {
  const cleaned = input.trim();
  if (/^[0-9a-fA-F]{64}$/.test(cleaned)) {
    return cleaned.toLowerCase();
  }
  try {
    const bin = atob(cleaned);
    if (bin.length !== 32) {
      throw new Error(`${fieldName} at line ${lineNum} must decode to 32 bytes (got ${bin.length}).`);
    }
    let hex = '';
    for (let i = 0; i < bin.length; i++) {
      hex += bin.charCodeAt(i).toString(16).padStart(2, '0');
    }
    return hex;
  } catch (e) {
    throw new Error(e instanceof Error ? e.message : `Invalid key in ${fieldName} at line ${lineNum}`);
  }
}

function compileAwgConfToUapi(rawConf: string): {
  uapi: string;
  revision: string;
  obfuscationKeys: Record<string, string>;
  passthroughCount: number;
} {
  const lines = rawConf.split(/\r?\n/);
  let section = '';
  let privateKeyHex = '';
  let listenPort = 0;
  const awgObfuscation: Record<string, string> = {};
  const interfacePassthrough: Record<string, string> = {};
  const peers: Array<{
    publicKeyHex: string;
    presharedKeyHex?: string;
    endpoint?: string;
    keepalive?: number;
    allowedIps: string[];
    passthrough: Record<string, string>;
  }> = [];

  const knownAwg = new Set([
    'jc', 'jmin', 'jmax',
    's1', 's2', 's3', 's4',
    'h1', 'h2', 'h3', 'h4',
    'i1', 'i2', 'i3', 'i4', 'i5',
  ]);

  lines.forEach((rawLine, idx) => {
    const lineNum = idx + 1;
    let trimmed = rawLine.trim();
    const hashIdx = trimmed.indexOf('#');
    if (hashIdx === 0) return;
    if (hashIdx > 0 && /\s/.test(trimmed[hashIdx - 1])) {
      trimmed = trimmed.slice(0, hashIdx).trim();
    }
    if (!trimmed) return;

    if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
      const sec = trimmed.slice(1, -1).trim().toLowerCase();
      section = sec;
      if (sec === 'peer') {
        peers.push({ publicKeyHex: '', allowedIps: [], passthrough: {} });
      }
      return;
    }

    const eq = trimmed.indexOf('=');
    if (eq <= 0) {
      throw new Error(`Malformed entry on line ${lineNum}: ${trimmed}`);
    }
    const key = trimmed.slice(0, eq).trim().toLowerCase();
    const val = trimmed.slice(eq + 1).trim();

    if (section === 'interface') {
      if (key === 'privatekey') {
        privateKeyHex = base64OrHexToHex(val, 'PrivateKey', lineNum);
      } else if (key === 'listenport') {
        listenPort = parseInt(val, 10) || 0;
      } else if (key === 'address' || key === 'dns' || key === 'mtu') {
        // Handled by VpnService.Builder
      } else if (knownAwg.has(key)) {
        awgObfuscation[key] = val;
      } else {
        interfacePassthrough[key] = val;
      }
    } else if (section === 'peer' && peers.length > 0) {
      const p = peers[peers.length - 1];
      if (key === 'publickey') {
        p.publicKeyHex = base64OrHexToHex(val, 'PublicKey', lineNum);
      } else if (key === 'presharedkey') {
        p.presharedKeyHex = base64OrHexToHex(val, 'PresharedKey', lineNum);
      } else if (key === 'endpoint') {
        p.endpoint = val;
      } else if (key === 'persistentkeepalive') {
        p.keepalive = parseInt(val, 10) || 0;
      } else if (key === 'allowedips') {
        val.split(',').map((s) => s.trim()).filter(Boolean).forEach((ip) => p.allowedIps.push(ip));
      } else {
        p.passthrough[key] = val;
      }
    }
  });

  if (!privateKeyHex) throw new Error('Missing PrivateKey in [Interface] section.');
  if (peers.length === 0 || !peers[0].publicKeyHex) {
    throw new Error('At least one [Peer] with a valid PublicKey is required.');
  }

  const out: string[] = [];
  out.push(`private_key=${privateKeyHex}`);
  if (listenPort > 0) out.push(`listen_port=${listenPort}`);
  Object.entries(awgObfuscation).forEach(([k, v]) => out.push(`${k}=${v}`));
  Object.entries(interfacePassthrough).forEach(([k, v]) => out.push(`${k}=${v}`));
  out.push('replace_peers=true');

  let passthroughCount = Object.keys(interfacePassthrough).length;
  peers.forEach((p) => {
    out.push(`public_key=${p.publicKeyHex}`);
    if (p.presharedKeyHex) out.push(`preshared_key=${p.presharedKeyHex}`);
    if (p.endpoint) out.push(`endpoint=${p.endpoint}`);
    if (p.keepalive && p.keepalive > 0) out.push(`persistent_keepalive_interval=${p.keepalive}`);
    out.push('replace_allowed_ips=true');
    p.allowedIps.forEach((cidr) => out.push(`allowed_ip=${cidr}`));
    Object.entries(p.passthrough).forEach(([k, v]) => {
      out.push(`${k}=${v}`);
      passthroughCount++;
    });
  });

  const hasCps = ['i1', 'i2', 'i3', 'i4', 'i5'].some((k) => k in awgObfuscation);
  const hasS3S4 = 's3' in awgObfuscation || 's4' in awgObfuscation;
  const hasRangeH = ['h1', 'h2', 'h3', 'h4'].some((k) => (awgObfuscation[k] || '').includes('-'));

  let revision = 'Standard WireGuard';
  if (hasCps && (hasS3S4 || hasRangeH)) revision = 'AmneziaWG 3.1 (CPS + S3/S4 + Range H1-H4)';
  else if (hasCps) revision = 'AmneziaWG 3.0 (CPS Signature Packets)';
  else if (hasS3S4 || hasRangeH) revision = 'AmneziaWG 2.0 (S3/S4 / Range Headers)';
  else if (Object.keys(awgObfuscation).length > 0) revision = 'AmneziaWG 1.5 (Jc/Jmin/Jmax/S1/S2/H1-H4)';

  return {
    uapi: out.join('\n'),
    revision,
    obfuscationKeys: awgObfuscation,
    passthroughCount,
  };
}

export default function App() {
  const [selectedPath, setSelectedPath] = useState<string>(REPO_FILES[0].path);
  const [copiedPath, setCopiedPath] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<'guide' | 'files' | 'simulator'>('guide');
  const [confInput, setConfInput] = useState<string>(PRESET_CONFIGS.awg31.conf);
  const [tunnelActive, setTunnelActive] = useState<boolean>(false);

  const selectedFile = useMemo(
    () => REPO_FILES.find((f) => f.path === selectedPath) || REPO_FILES[0],
    [selectedPath]
  );

  const parsedResult = useMemo(() => {
    try {
      return { ok: true as const, data: compileAwgConfToUapi(confInput) };
    } catch (err) {
      return { ok: false as const, error: err instanceof Error ? err.message : 'Parse error' };
    }
  }, [confInput]);

  const handleCopyFile = async (file: RepoFile) => {
    await navigator.clipboard.writeText(file.content);
    setCopiedPath(file.path);
    setTimeout(() => setCopiedPath(null), 1800);
  };

  const handleDownloadSingle = (file: RepoFile) => {
    const blob = new Blob([file.content], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = file.path.split('/').pop() || 'file.txt';
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleDownloadZip = () => {
    const blob = buildZipBlob(
      REPO_FILES.map((f) => ({
        path: f.path,
        content: f.content,
      }))
    );
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'amneziawg-android-api17-repo.zip';
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col">
      {/* Top Bar Contract: 3 zones */}
      <header className="flex items-center justify-between gap-8 px-6 py-4 border-b border-slate-800 bg-slate-900/80">
        <a
          href="#top"
          className="text-base font-bold tracking-tight text-slate-100 whitespace-nowrap shrink-0"
        >
          AmneziaWG Legacy API 17
        </a>

        <nav className="hidden md:flex items-center gap-6 text-sm font-medium text-slate-400">
          <button
            onClick={() => setActiveTab('guide')}
            className={`hover:text-slate-100 transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
              activeTab === 'guide' ? 'text-slate-100 underline underline-offset-8 decoration-sky-400' : ''
            }`}
          >
            Как собрать APK на GitHub
          </button>
          <button
            onClick={() => setActiveTab('files')}
            className={`hover:text-slate-100 transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
              activeTab === 'files' ? 'text-slate-100 underline underline-offset-8 decoration-sky-400' : ''
            }`}
          >
            Файлы проекта (10)
          </button>
          <button
            onClick={() => setActiveTab('simulator')}
            className={`hover:text-slate-100 transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
              activeTab === 'simulator' ? 'text-slate-100 underline underline-offset-8 decoration-sky-400' : ''
            }`}
          >
            Тест конфига (.conf)
          </button>
        </nav>

        <div className="flex items-center gap-3 shrink-0">
          <button
            onClick={handleDownloadZip}
            className="px-4 py-2 text-xs font-semibold text-slate-950 bg-sky-400 rounded-lg hover:bg-sky-300 transition-colors whitespace-nowrap shrink-0 flex items-center gap-2 cursor-pointer"
          >
            <Archive className="w-3.5 h-3.5" />
            Скачать ZIP для GitHub
          </button>
        </div>
      </header>

      {/* Main Content */}
      <main className="flex-1 max-w-[1400px] w-full mx-auto px-6 py-6 flex flex-col gap-6">
        {/* Spec Bar */}
        <section className="grid grid-cols-1 md:grid-cols-4 gap-4 p-4 rounded-xl bg-slate-900 border border-slate-800">
          <div>
            <div className="text-xs text-slate-400">Целевая ОС и архитектура</div>
            <div className="text-sm font-semibold text-slate-100 mt-0.5 font-mono tabular-nums">
              Android 4.2.2 (API 17) · armeabi-v7a
            </div>
          </div>
          <div>
            <div className="text-xs text-slate-400">Облачный стек GitHub Actions</div>
            <div className="text-sm font-semibold text-slate-100 mt-0.5 font-mono tabular-nums">
              NDK r20b · Go 1.20 · Gradle 6.7.1
            </div>
          </div>
          <div>
            <div className="text-xs text-slate-400">Ревизии протокола</div>
            <div className="text-sm font-semibold text-slate-100 mt-0.5 font-mono tabular-nums">
              AmneziaWG 1.5 · 2.0 · 3.0 · 3.1 (CPS)
            </div>
          </div>
          <div>
            <div className="text-xs text-slate-400">Готовый файл на выходе</div>
            <div className="text-sm font-semibold text-emerald-400 mt-0.5 font-mono tabular-nums">
              app-debug.apk (в Artifacts)
            </div>
          </div>
        </section>

        {activeTab === 'guide' && (
          <section className="bg-slate-900 border border-slate-800 rounded-xl p-6 flex flex-col gap-6">
            <div className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-800 pb-4">
              <div>
                <h1 className="text-xl font-bold text-slate-100 flex items-center gap-2.5">
                  <BookOpen className="w-5 h-5 text-sky-400" />
                  Пошаговая инструкция: Как получить готовый APK прямо в браузере через GitHub
                </h1>
                <p className="text-sm text-slate-400 mt-1">
                  Никаких программ на компьютере устанавливать не нужно — всю сборку выполнит сервер GitHub Actions за 2–3 минуты.
                </p>
              </div>
              <button
                onClick={handleDownloadZip}
                className="px-4 py-2.5 text-xs font-semibold text-slate-950 bg-emerald-400 hover:bg-emerald-300 rounded-lg transition-colors flex items-center gap-2 whitespace-nowrap shrink-0 cursor-pointer"
              >
                <Download className="w-4 h-4" />
                1. Скачать архив amneziawg-android-api17-repo.zip
              </button>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
              <div className="p-5 rounded-xl bg-slate-950/70 border border-slate-800 flex flex-col gap-3">
                <div className="text-sm font-bold text-sky-400">Шаг 1. Выгрузите проект на GitHub</div>
                <p className="text-xs text-slate-300 leading-relaxed">
                  Есть два способа (выберите любой удобный):
                </p>
                <ul className="text-xs text-slate-300 space-y-2 list-disc pl-4 leading-relaxed">
                  <li>
                    <strong>Способ А (в 1 клик из AI Studio):</strong> Нажмите иконку <strong>GitHub / Export</strong> в правом верхнем углу окна AI Studio — она сама создаст репозиторий в вашем аккаунте GitHub и отправит туда все файлы.
                  </li>
                  <li>
                    <strong>Способ Б (через ZIP-архив):</strong> Нажмите синюю кнопку <strong>«Скачать ZIP для GitHub»</strong> выше, распакуйте папку на ПК/телефоне, создайте пустой репозиторий на <code className="text-sky-300">github.com/new</code>, нажмите <strong>uploading an existing file</strong> и перетащите туда содержимое распакованной папки (включая папку <code className="text-sky-300">.github</code>).
                  </li>
                </ul>
              </div>

              <div className="p-5 rounded-xl bg-slate-950/70 border border-slate-800 flex flex-col gap-3">
                <div className="text-sm font-bold text-sky-400">Шаг 2. Дождитесь окончания сборки в Actions</div>
                <ul className="text-xs text-slate-300 space-y-2 list-disc pl-4 leading-relaxed">
                  <li>
                    Откройте ваш репозиторий на GitHub и перейдите на вкладку <strong>Actions</strong> сверху.
                  </li>
                  <li>
                    Сразу после загрузки файлов автоматически запустится воркфлоу <strong>Build AmneziaWG Legacy APK (API 17)</strong> (либо нажмите на него слева и нажмите кнопку <strong>Run workflow</strong>).
                  </li>
                  <li>
                    Сервер GitHub сам скачает Android NDK r20b, скомпилирует <code className="text-sky-300">libwg-go.so</code> и <code className="text-sky-300">libwg-jni.so</code> под Android 4.2.2 и соберет APK через Gradle 6.7.1.
                  </li>
                </ul>
              </div>

              <div className="p-5 rounded-xl bg-slate-950/70 border border-slate-800 flex flex-col gap-3">
                <div className="text-sm font-bold text-emerald-400">Шаг 3. Скачайте готовый APK на телефон</div>
                <ul className="text-xs text-slate-300 space-y-2 list-disc pl-4 leading-relaxed">
                  <li>
                    Когда сборка во вкладке <strong>Actions</strong> загорится зеленой галочкой (обычно через 2–3 минуты), кликните по ней.
                  </li>
                  <li>
                    Прокрутите страницу вниз до блока <strong>Artifacts</strong>.
                  </li>
                  <li>
                    Нажмите на <strong>AmneziaWG-Legacy-API17-Debug-APK</strong> — скачается архив, внутри которого лежит готовый к установке файл <code className="text-emerald-300">app-debug.apk</code> для Android 4.2.2+.
                  </li>
                </ul>
              </div>
            </div>

            <div className="flex items-center justify-between pt-2 border-t border-slate-800 text-xs text-slate-400">
              <span>Хотите посмотреть или скопировать отдельные файлы вручную?</span>
              <button
                onClick={() => setActiveTab('files')}
                className="text-sky-400 hover:text-sky-300 font-semibold cursor-pointer"
              >
                Перейти к просмотру всех 10 файлов проекта →
              </button>
            </div>
          </section>
        )}

        {activeTab === 'files' && (
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
            {/* Sidebar File Tree */}
            <aside className="lg:col-span-4 bg-slate-900 border border-slate-800 rounded-xl p-4 flex flex-col gap-4">
              <div className="flex items-center justify-between">
                <h2 className="text-sm font-semibold text-slate-200 flex items-center gap-2">
                  <FolderGit2 className="w-4 h-4 text-sky-400" />
                  Файлы репозитория ({REPO_FILES.length})
                </h2>
                <span className="text-xs text-slate-400 font-mono tabular-nums">100% Ready</span>
              </div>

              <div className="flex flex-col gap-1.5">
                {REPO_FILES.map((file) => {
                  const active = file.path === selectedFile.path;
                  return (
                    <button
                      key={file.path}
                      onClick={() => setSelectedPath(file.path)}
                      className={`w-full text-left px-3 py-2.5 rounded-lg transition-colors flex flex-col gap-0.5 cursor-pointer ${
                        active
                          ? 'bg-sky-500/15 border border-sky-500/40 text-slate-100'
                          : 'hover:bg-slate-800/70 text-slate-300 border border-transparent'
                      }`}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-xs font-mono font-medium truncate">{file.path}</span>
                        <span className="text-[11px] text-slate-400 shrink-0">{file.language}</span>
                      </div>
                      <span className="text-[11px] text-slate-400 line-clamp-1">{file.description}</span>
                    </button>
                  );
                })}
              </div>
            </aside>

            {/* Code Viewer */}
            <section className="lg:col-span-8 bg-slate-900 border border-slate-800 rounded-xl overflow-hidden flex flex-col">
              <div className="px-5 py-3.5 border-b border-slate-800 flex flex-wrap items-center justify-between gap-4 bg-slate-900">
                <div>
                  <div className="text-sm font-mono font-semibold text-slate-100">{selectedFile.path}</div>
                  <div className="text-xs text-slate-400 mt-0.5">{selectedFile.description}</div>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => handleCopyFile(selectedFile)}
                    className="px-3 py-1.5 text-xs font-medium text-slate-200 bg-slate-800 hover:bg-slate-700 rounded-lg transition-colors flex items-center gap-1.5 whitespace-nowrap shrink-0 cursor-pointer"
                  >
                    {copiedPath === selectedFile.path ? (
                      <>
                        <Check className="w-3.5 h-3.5 text-emerald-400" />
                        Скопировано
                      </>
                    ) : (
                      <>
                        <Copy className="w-3.5 h-3.5" />
                        Копировать код
                      </>
                    )}
                  </button>
                  <button
                    onClick={() => handleDownloadSingle(selectedFile)}
                    className="px-3 py-1.5 text-xs font-medium text-slate-200 bg-slate-800 hover:bg-slate-700 rounded-lg transition-colors flex items-center gap-1.5 whitespace-nowrap shrink-0 cursor-pointer"
                  >
                    <FileCode className="w-3.5 h-3.5" />
                    Скачать файл
                  </button>
                </div>
              </div>

              <pre className="p-5 text-xs leading-relaxed font-mono text-slate-200 overflow-x-auto max-h-[680px] overflow-y-auto bg-slate-950/60">
                <code>{selectedFile.content}</code>
              </pre>
            </section>
          </div>
        )}

        {activeTab === 'simulator' && (
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
            <section className="lg:col-span-6 bg-slate-900 border border-slate-800 rounded-xl p-5 flex flex-col gap-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <h2 className="text-base font-semibold text-slate-100">
                    Проверка вашего конфига AmneziaWG (.conf)
                  </h2>
                  <p className="text-xs text-slate-400 mt-0.5">
                    Точная эмуляция парсера AwgConfig.java (AWG 1.5, 2.0, 3.0, 3.1).
                  </p>
                </div>
                <div className="flex items-center gap-1.5">
                  {Object.entries(PRESET_CONFIGS).map(([key, preset]) => (
                    <button
                      key={key}
                      onClick={() => setConfInput(preset.conf)}
                      className="px-2.5 py-1 text-xs font-medium bg-slate-800 hover:bg-slate-700 text-slate-200 rounded-md transition-colors whitespace-nowrap shrink-0 cursor-pointer"
                    >
                      {key.toUpperCase()}
                    </button>
                  ))}
                </div>
              </div>

              <textarea
                value={confInput}
                onChange={(e) => setConfInput(e.target.value)}
                rows={18}
                spellCheck={false}
                className="w-full p-3.5 rounded-lg bg-slate-950 border border-slate-800 text-xs font-mono text-slate-200 focus:outline-none focus:border-sky-500"
              />

              <div className="flex items-center justify-between gap-4 pt-1">
                <div className="text-xs text-slate-400">
                  Статус:{' '}
                  <span className={tunnelActive ? 'text-emerald-400 font-semibold' : 'text-slate-300'}>
                    {tunnelActive ? 'CONNECTED (TUN awg0 fd=23)' : 'DISCONNECTED'}
                  </span>
                </div>
                <button
                  onClick={() => {
                    if (parsedResult.ok) {
                      setTunnelActive((prev) => !prev);
                    }
                  }}
                  disabled={!parsedResult.ok}
                  className={`px-4 py-2 text-xs font-semibold rounded-lg transition-colors flex items-center gap-2 whitespace-nowrap shrink-0 cursor-pointer ${
                    tunnelActive
                      ? 'bg-rose-500 hover:bg-rose-400 text-slate-950'
                      : 'bg-emerald-400 hover:bg-emerald-300 text-slate-950 disabled:opacity-40'
                  }`}
                >
                  <Play className="w-3.5 h-3.5" />
                  {tunnelActive ? 'Disconnect AwgVpnService' : 'Connect AwgVpnService'}
                </button>
              </div>
            </section>

            <section className="lg:col-span-6 bg-slate-900 border border-slate-800 rounded-xl p-5 flex flex-col gap-4">
              <div className="flex items-center justify-between">
                <h2 className="text-base font-semibold text-slate-100 flex items-center gap-2">
                  <Terminal className="w-4 h-4 text-sky-400" />
                  Сгенерированный UAPI-поток для wgTurnOn()
                </h2>
                {parsedResult.ok ? (
                  <span className="text-xs text-emerald-400 flex items-center gap-1 font-medium">
                    <ShieldCheck className="w-4 h-4" />
                    {parsedResult.data.revision}
                  </span>
                ) : (
                  <span className="text-xs text-rose-400 flex items-center gap-1 font-medium">
                    <AlertTriangle className="w-4 h-4" />
                    Ошибка в конфиге
                  </span>
                )}
              </div>

              {parsedResult.ok ? (
                <>
                  <div className="text-xs text-slate-400">
                    Параметры обфускации ({Object.keys(parsedResult.data.obfuscationKeys).length}):{' '}
                    <span className="text-slate-200 font-mono">
                      {Object.keys(parsedResult.data.obfuscationKeys).join(' · ') || 'нет'}
                    </span>
                  </div>
                  <pre className="p-4 rounded-lg bg-slate-950 border border-slate-800 text-xs font-mono text-sky-300 overflow-x-auto leading-relaxed">
                    <code>{parsedResult.data.uapi}</code>
                  </pre>
                </>
              ) : (
                <div className="p-4 rounded-lg bg-rose-950/40 border border-rose-800/60 text-xs font-mono text-rose-300">
                  {parsedResult.error}
                </div>
              )}
            </section>
          </div>
        )}
      </main>
    </div>
  );
}
