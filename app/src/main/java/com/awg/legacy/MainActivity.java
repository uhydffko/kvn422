package com.awg.legacy;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Zero-dependency MainActivity for Android 4.2.2 (API 17, Jelly Bean).
 * Uses pure Android Holo UI widgets constructed programmatically so no external layout/drawable XMLs are required.
 */
public class MainActivity extends Activity {

    private static final String PREFS_NAME = "awg_legacy_prefs";
    private static final String KEY_SAVED_CONF = "saved_conf_text";
    private static final int REQUEST_VPN_PERMISSION = 1017;

    private EditText mConfigEdit;
    private Button mToggleBtn;
    private Button mValidateBtn;
    private Button mSampleBtn;
    private TextView mStatusView;
    private TextView mLogView;
    private boolean mConnected = false;

    private static final String DEFAULT_AWG_31_TEMPLATE =
            "[Interface]\n" +
            "PrivateKey = YXdnLWxlZ2FjeS1hbmRyb2lkLTQyMi1hcGkxNy1rZXk=\n" +
            "Address = 10.8.1.2/32\n" +
            "DNS = 1.1.1.1, 1.0.0.1\n" +
            "MTU = 1280\n" +
            "Jc = 5\n" +
            "Jmin = 40\n" +
            "Jmax = 700\n" +
            "S1 = 44\n" +
            "S2 = 92\n" +
            "S3 = 28\n" +
            "S4 = 16\n" +
            "H1 = 10203040-20304050\n" +
            "H2 = 20304051-30405060\n" +
            "H3 = 30405061-40506070\n" +
            "H4 = 40506071-50607080\n" +
            "I1 = <b 0xc700000001><rc 8><t><r 32>\n" +
            "I2 = <b 0x16030100><r 16>\n" +
            "\n" +
            "[Peer]\n" +
            "PublicKey = cGVlci1wdWJsaWMta2V5LWFtbmV6aWF3Zy1hbmRyb2lk=\n" +
            "PresharedKey = cHJlc2hhcmVkLWtleS1hbW5lemlhd2ctYW5kcm9pZDE=\n" +
            "Endpoint = 198.51.100.42:51820\n" +
            "AllowedIPs = 0.0.0.0/0, ::/0\n" +
            "PersistentKeepalive = 25\n";

    private final BroadcastReceiver mStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !AwgVpnService.ACTION_STATUS_BROADCAST.equals(intent.getAction())) {
                return;
            }
            boolean connected = intent.getBooleanExtra(AwgVpnService.EXTRA_CONNECTED, false);
            String message = intent.getStringExtra(AwgVpnService.EXTRA_LOG_MESSAGE);
            updateConnectionUi(connected);
            if (message != null && !message.isEmpty()) {
                appendLog(message);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView rootScroll = new ScrollView(this);
        rootScroll.setFillViewport(true);
        rootScroll.setBackgroundColor(Color.parseColor("#11161D"));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        container.setPadding(pad, pad, pad, pad);

        TextView headerTitle = new TextView(this);
        headerTitle.setText("AmneziaWG Legacy Client (API 17)");
        headerTitle.setTextSize(18f);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitle.setTextColor(Color.parseColor("#F1F5F9"));
        container.addView(headerTitle);

        mStatusView = new TextView(this);
        mStatusView.setText("STATUS: DISCONNECTED (Android 4.2.2+ / AWG 1.5 - 3.1)");
        mStatusView.setTextSize(13f);
        mStatusView.setTextColor(Color.parseColor("#94A3B8"));
        mStatusView.setPadding(0, dp(4), 0, dp(10));
        container.addView(mStatusView);

        LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);

        mToggleBtn = new Button(this);
        mToggleBtn.setText("Connect");
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        mToggleBtn.setLayoutParams(toggleParams);

        mValidateBtn = new Button(this);
        mValidateBtn.setText("Preview UAPI");
        LinearLayout.LayoutParams validateParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        mValidateBtn.setLayoutParams(validateParams);

        mSampleBtn = new Button(this);
        mSampleBtn.setText("AWG 3.1 Sample");
        LinearLayout.LayoutParams sampleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        mSampleBtn.setLayoutParams(sampleParams);

        buttonBar.addView(mToggleBtn);
        buttonBar.addView(mValidateBtn);
        buttonBar.addView(mSampleBtn);
        container.addView(buttonBar);

        TextView confLabel = new TextView(this);
        confLabel.setText("AmneziaWG Configuration (.conf):");
        confLabel.setTextSize(13f);
        confLabel.setTextColor(Color.parseColor("#CBD5E1"));
        confLabel.setPadding(0, dp(10), 0, dp(4));
        container.addView(confLabel);

        mConfigEdit = new EditText(this);
        mConfigEdit.setTypeface(Typeface.MONOSPACE);
        mConfigEdit.setTextSize(12f);
        mConfigEdit.setTextColor(Color.parseColor("#E2E8F0"));
        mConfigEdit.setBackgroundColor(Color.parseColor("#1E293B"));
        mConfigEdit.setGravity(Gravity.TOP | Gravity.START);
        mConfigEdit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mConfigEdit.setMinLines(12);
        int editPad = dp(10);
        mConfigEdit.setPadding(editPad, editPad, editPad, editPad);
        container.addView(mConfigEdit);

        TextView logLabel = new TextView(this);
        logLabel.setText("Status & UAPI Diagnostic Log:");
        logLabel.setTextSize(13f);
        logLabel.setTextColor(Color.parseColor("#CBD5E1"));
        logLabel.setPadding(0, dp(12), 0, dp(4));
        container.addView(logLabel);

        mLogView = new TextView(this);
        mLogView.setTypeface(Typeface.MONOSPACE);
        mLogView.setTextSize(11.5f);
        mLogView.setTextColor(Color.parseColor("#38BDF8"));
        mLogView.setBackgroundColor(Color.parseColor("#0F172A"));
        mLogView.setPadding(editPad, editPad, editPad, editPad);
        mLogView.setMinLines(8);
        container.addView(mLogView);

        rootScroll.addView(container);
        setContentView(rootScroll);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedConf = prefs.getString(KEY_SAVED_CONF, DEFAULT_AWG_31_TEMPLATE);
        mConfigEdit.setText(savedConf);

        mToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveConfigToPrefs();
                if (mConnected) {
                    disconnectVpn();
                } else {
                    prepareAndConnectVpn();
                }
            }
        });

        mValidateBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveConfigToPrefs();
                validateAndPreviewUapi();
            }
        });

        mSampleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mConfigEdit.setText(DEFAULT_AWG_31_TEMPLATE);
                saveConfigToPrefs();
                appendLog("Loaded sample AmneziaWG 3.1 configuration (Jc/Jmin/Jmax, S1-S4, range H1-H4, CPS I1-I2).");
            }
        });

        updateConnectionUi(AwgVpnService.isTunnelRunning());
        appendLog("Ready on Android API " + android.os.Build.VERSION.SDK_INT + ". Supports AWG 1.5, 2.0, 3.0, 3.1.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(AwgVpnService.ACTION_STATUS_BROADCAST);
        registerReceiver(mStatusReceiver, filter);
        updateConnectionUi(AwgVpnService.isTunnelRunning());
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(mStatusReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        saveConfigToPrefs();
        super.onPause();
    }

    private void saveConfigToPrefs() {
        String text = mConfigEdit.getText().toString();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_SAVED_CONF, text)
                .apply();
    }

    private void validateAndPreviewUapi() {
        String raw = mConfigEdit.getText().toString();
        try {
            AwgConfig parsed = AwgConfig.parse(raw);
            appendLog("Config Validated: " + parsed.detectProtocolRevision()
                    + "\n--- Generated UAPI Payload ---\n" + parsed.toUapiString().trim());
        } catch (IllegalArgumentException e) {
            appendLog("Validation Failed: " + e.getMessage());
        }
    }

    private void prepareAndConnectVpn() {
        String raw = mConfigEdit.getText().toString();
        try {
            AwgConfig.parse(raw);
        } catch (IllegalArgumentException e) {
            appendLog("Cannot connect — invalid config: " + e.getMessage());
            return;
        }

        Intent prepareIntent = VpnService.prepare(this);
        if (prepareIntent != null) {
            appendLog("Requesting Android VPN authorization...");
            startActivityForResult(prepareIntent, REQUEST_VPN_PERMISSION);
        } else {
            onActivityResult(REQUEST_VPN_PERMISSION, RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                Intent serviceIntent = new Intent(this, AwgVpnService.class);
                serviceIntent.setAction(AwgVpnService.ACTION_CONNECT);
                serviceIntent.putExtra(AwgVpnService.EXTRA_CONFIG_TEXT, mConfigEdit.getText().toString());
                startService(serviceIntent);
            } else {
                appendLog("VPN authorization declined by user.");
            }
        }
    }

    private void disconnectVpn() {
        Intent serviceIntent = new Intent(this, AwgVpnService.class);
        serviceIntent.setAction(AwgVpnService.ACTION_DISCONNECT);
        startService(serviceIntent);
    }

    private void updateConnectionUi(boolean connected) {
        mConnected = connected;
        if (connected) {
            mToggleBtn.setText("Disconnect");
            mStatusView.setText("STATUS: CONNECTED (TUN awg0 Active)");
            mStatusView.setTextColor(Color.parseColor("#4ADE80"));
        } else {
            mToggleBtn.setText("Connect");
            mStatusView.setText("STATUS: DISCONNECTED (Android 4.2.2+ / AWG 1.5 - 3.1)");
            mStatusView.setTextColor(Color.parseColor("#94A3B8"));
        }
    }

    private void appendLog(String msg) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String existing = mLogView.getText().toString();
        String updated = "[" + timestamp + "] " + msg + (existing.isEmpty() ? "" : "\n\n" + existing);
        if (updated.length() > 6000) {
            updated = updated.substring(0, 6000);
        }
        mLogView.setText(updated);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
