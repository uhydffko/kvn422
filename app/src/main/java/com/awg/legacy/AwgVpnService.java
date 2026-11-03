package com.awg.legacy;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;

/**
 * Zero-dependency Android VpnService for Android 4.2.2 (API 17, Jelly Bean) and newer.
 * Bridges the Android TUN file descriptor with libwg-go.so / libwg-jni.so.
 */
public class AwgVpnService extends VpnService {

    public static final String ACTION_CONNECT = "com.awg.legacy.ACTION_CONNECT";
    public static final String ACTION_DISCONNECT = "com.awg.legacy.ACTION_DISCONNECT";
    public static final String ACTION_STATUS_BROADCAST = "com.awg.legacy.ACTION_STATUS_BROADCAST";

    public static final String EXTRA_CONFIG_TEXT = "com.awg.legacy.EXTRA_CONFIG_TEXT";
    public static final String EXTRA_CONNECTED = "com.awg.legacy.EXTRA_CONNECTED";
    public static final String EXTRA_LOG_MESSAGE = "com.awg.legacy.EXTRA_LOG_MESSAGE";

    private static final String TAG = "AwgVpnService";
    private static final int NOTIFICATION_ID = 1701;

    private static volatile boolean sIsConnected = false;
    private int mTunnelHandle = -1;
    private ParcelFileDescriptor mTunInterface = null;

    static {
        try {
            System.loadLibrary("wg-go");
            System.loadLibrary("wg-jni");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native AmneziaWG libraries: " + e.getMessage());
        }
    }

    public static native int jniTurnOn(String ifName, int tunFd, String settings);
    public static native void jniTurnOff(int handle);
    public static native int jniGetSocketV4(int handle);
    public static native int jniGetSocketV6(int handle);
    public static native String jniVersion();

    public static boolean isTunnelRunning() {
        return sIsConnected;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            stopTunnel("Disconnected by user request.");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_CONNECT.equals(action)) {
            String rawConf = intent.getStringExtra(EXTRA_CONFIG_TEXT);
            startTunnel(rawConf);
            return START_STICKY;
        }

        return START_NOT_STICKY;
    }

    private synchronized void startTunnel(String rawConf) {
        if (mTunnelHandle >= 0) {
            stopTunnel("Restarting active tunnel...");
        }

        final AwgConfig config;
        try {
            config = AwgConfig.parse(rawConf);
        } catch (IllegalArgumentException e) {
            broadcastState(false, "Config Error: " + e.getMessage());
            return;
        }

        broadcastState(false, "Parsed " + config.detectProtocolRevision() + ". Building TUN interface...");

        try {
            Builder builder = new Builder();
            builder.setSession("AmneziaWG-API17");
            builder.setMtu(config.mtu > 0 ? config.mtu : 1280);

            for (int i = 0; i < config.addresses.size(); i++) {
                AwgConfig.CidrAddress addr = config.addresses.get(i);
                builder.addAddress(addr.address, addr.prefixLength);
            }

            if (config.dnsServers.isEmpty()) {
                builder.addDnsServer("1.1.1.1");
            } else {
                for (int i = 0; i < config.dnsServers.size(); i++) {
                    builder.addDnsServer(config.dnsServers.get(i));
                }
            }

            boolean addedRoute = false;
            for (int i = 0; i < config.peers.size(); i++) {
                AwgConfig.PeerSection peer = config.peers.get(i);
                for (int j = 0; j < peer.allowedIps.size(); j++) {
                    AwgConfig.CidrAddress route = peer.allowedIps.get(j);
                    builder.addRoute(route.address, route.prefixLength);
                    addedRoute = true;
                }
            }
            if (!addedRoute) {
                builder.addRoute("0.0.0.0", 0);
            }

            mTunInterface = builder.establish();
            if (mTunInterface == null) {
                broadcastState(false, "VpnService.Builder.establish() returned null (VPN permission not granted).");
                return;
            }

            int tunFd = mTunInterface.getFd();
            String uapiConfig = config.toUapiString();

            int handle = jniTurnOn("awg0", tunFd, uapiConfig);
            if (handle < 0) {
                closeTunDescriptor();
                broadcastState(false, "Native wgTurnOn failed with error code: " + handle);
                return;
            }

            mTunnelHandle = handle;

            int sockV4 = jniGetSocketV4(mTunnelHandle);
            if (sockV4 >= 0) {
                boolean protectedV4 = protect(sockV4);
                Log.i(TAG, "Protected UDPv4 socket fd=" + sockV4 + ": " + protectedV4);
            }
            int sockV6 = jniGetSocketV6(mTunnelHandle);
            if (sockV6 >= 0) {
                boolean protectedV6 = protect(sockV6);
                Log.i(TAG, "Protected UDPv6 socket fd=" + sockV6 + ": " + protectedV6);
            }

            startLegacyForegroundNotification(config.detectProtocolRevision());
            sIsConnected = true;

            String nativeVer;
            try {
                nativeVer = jniVersion();
            } catch (Throwable t) {
                nativeVer = "libwg-go-api17";
            }

            broadcastState(true, "Connected [handle=" + mTunnelHandle + "] Engine: " + nativeVer
                    + "\nMode: " + config.detectProtocolRevision()
                    + "\nObfuscation keys active: " + config.awgObfuscation.keySet());

        } catch ( Exception e) {
            Log.e(TAG, "Failed starting AmneziaWG tunnel", e);
            stopTunnel("Tunnel exception: " + e.getMessage());
        }
    }

    private synchronized void stopTunnel(String reason) {
        if (mTunnelHandle >= 0) {
            try {
                jniTurnOff(mTunnelHandle);
            } catch (Throwable t) {
                Log.e(TAG, "Error in jniTurnOff", t);
            }
            mTunnelHandle = -1;
        }
        closeTunDescriptor();
        sIsConnected = false;
        stopForeground(true);
        broadcastState(false, reason);
    }

    private void closeTunDescriptor() {
        if (mTunInterface != null) {
            try {
                mTunInterface.close();
            } catch (IOException ignored) {
            }
            mTunInterface = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void startLegacyForegroundNotification(String modeSummary) {
        Intent launchIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        // Notification.Builder.build() is available since API 16 (Jelly Bean 4.1)
        Notification notification = new Notification.Builder(this)
                .setContentTitle("AmneziaWG Legacy Active")
                .setContentText(modeSummary)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private void broadcastState(boolean connected, String logMessage) {
        Intent broadcast = new Intent(ACTION_STATUS_BROADCAST);
        broadcast.putExtra(EXTRA_CONNECTED, connected);
        broadcast.putExtra(EXTRA_LOG_MESSAGE, logMessage);
        sendBroadcast(broadcast);
    }

    @Override
    public void onRevoke() {
        stopTunnel("VPN permission revoked by Android OS.");
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopTunnel("Service destroyed.");
        super.onDestroy();
    }
}
