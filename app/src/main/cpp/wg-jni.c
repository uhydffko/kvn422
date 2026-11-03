#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "AwgJniBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef int (*wg_turn_on_fn)(const char *ifname, int tun_fd, const char *settings);
typedef void (*wg_turn_off_fn)(int handle);
typedef int (*wg_get_socket_fn)(int handle);
typedef char *(*wg_version_fn)(void);

static void *g_wg_lib_handle = NULL;
static wg_turn_on_fn g_wg_turn_on = NULL;
static wg_turn_off_fn g_wg_turn_off = NULL;
static wg_get_socket_fn g_wg_get_socket_v4 = NULL;
static wg_get_socket_fn g_wg_get_socket_v6 = NULL;
static wg_version_fn g_wg_version = NULL;

static int ensure_wg_go_loaded(void) {
    if (g_wg_lib_handle != NULL && g_wg_turn_on != NULL) {
        return 0;
    }

    dlerror();
    g_wg_lib_handle = dlopen("libwg-go.so", RTLD_NOW | RTLD_GLOBAL);
    if (g_wg_lib_handle == NULL) {
        const char *err = dlerror();
        LOGE("dlopen(libwg-go.so) failed: %s", err ? err : "unknown error");
        return -1;
    }

    g_wg_turn_on = (wg_turn_on_fn)dlsym(g_wg_lib_handle, "wgTurnOn");
    g_wg_turn_off = (wg_turn_off_fn)dlsym(g_wg_lib_handle, "wgTurnOff");
    g_wg_get_socket_v4 = (wg_get_socket_fn)dlsym(g_wg_lib_handle, "wgGetSocketV4");
    g_wg_get_socket_v6 = (wg_get_socket_fn)dlsym(g_wg_lib_handle, "wgGetSocketV6");
    g_wg_version = (wg_version_fn)dlsym(g_wg_lib_handle, "wgVersion");

    if (!g_wg_turn_on || !g_wg_turn_off || !g_wg_get_socket_v4 || !g_wg_get_socket_v6) {
        LOGE("Required symbols missing in libwg-go.so");
        return -2;
    }

    LOGI("libwg-go.so loaded successfully on Android API %d", __ANDROID_API__);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_awg_legacy_AwgVpnService_jniTurnOn(
        JNIEnv *env,
        jclass clazz,
        jstring j_ifname,
        jint tun_fd,
        jstring j_settings) {
    (void)clazz;
    if (ensure_wg_go_loaded() != 0) {
        return -100;
    }
    if (j_ifname == NULL || j_settings == NULL) {
        LOGE("jniTurnOn received NULL string parameter");
        return -101;
    }

    const char *c_ifname = (*env)->GetStringUTFChars(env, j_ifname, NULL);
    const char *c_settings = (*env)->GetStringUTFChars(env, j_settings, NULL);
    if (c_ifname == NULL || c_settings == NULL) {
        if (c_ifname != NULL) (*env)->ReleaseStringUTFChars(env, j_ifname, c_ifname);
        if (c_settings != NULL) (*env)->ReleaseStringUTFChars(env, j_settings, c_settings);
        return -102;
    }

    int handle = g_wg_turn_on(c_ifname, (int)tun_fd, c_settings);

    (*env)->ReleaseStringUTFChars(env, j_ifname, c_ifname);
    (*env)->ReleaseStringUTFChars(env, j_settings, c_settings);

    LOGI("wgTurnOn returned handle=%d", handle);
    return (jint)handle;
}

JNIEXPORT void JNICALL
Java_com_awg_legacy_AwgVpnService_jniTurnOff(
        JNIEnv *env,
        jclass clazz,
        jint handle) {
    (void)env;
    (void)clazz;
    if (ensure_wg_go_loaded() != 0 || g_wg_turn_off == NULL) {
        return;
    }
    LOGI("wgTurnOff(handle=%d)", (int)handle);
    g_wg_turn_off((int)handle);
}

JNIEXPORT jint JNICALL
Java_com_awg_legacy_AwgVpnService_jniGetSocketV4(
        JNIEnv *env,
        jclass clazz,
        jint handle) {
    (void)env;
    (void)clazz;
    if (ensure_wg_go_loaded() != 0 || g_wg_get_socket_v4 == NULL) {
        return -1;
    }
    return (jint)g_wg_get_socket_v4((int)handle);
}

JNIEXPORT jint JNICALL
Java_com_awg_legacy_AwgVpnService_jniGetSocketV6(
        JNIEnv *env,
        jclass clazz,
        jint handle) {
    (void)env;
    (void)clazz;
    if (ensure_wg_go_loaded() != 0 || g_wg_get_socket_v6 == NULL) {
        return -1;
    }
    return (jint)g_wg_get_socket_v6((int)handle);
}

JNIEXPORT jstring JNICALL
Java_com_awg_legacy_AwgVpnService_jniVersion(
        JNIEnv *env,
        jclass clazz) {
    (void)clazz;
    if (ensure_wg_go_loaded() != 0 || g_wg_version == NULL) {
        return (*env)->NewStringUTF(env, "amneziawg-jni-api17 (unloaded)");
    }
    char *ver = g_wg_version();
    if (ver == NULL) {
        return (*env)->NewStringUTF(env, "amneziawg-go-api17");
    }
    jstring result = (*env)->NewStringUTF(env, ver);
    free(ver);
    return result;
}
