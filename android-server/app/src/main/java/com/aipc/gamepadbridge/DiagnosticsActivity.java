package com.aipc.gamepadbridge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Technical details for troubleshooting: addresses, server state, the raw
 * values being sent, and the event log.
 */
public class DiagnosticsActivity extends PadActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PadState readout = new PadState();
    private TextView tvInfo;
    private TextView tvRaw;
    private TextView tvLog;
    private ScrollView logScroll;

    private final Runnable rawTick = new Runnable() {
        @Override
        public void run() {
            app.getMapper().getState(readout);
            tvRaw.setText(String.format(Locale.US,
                    "buttons=0x%06X  L(%03d,%03d)  R(%03d,%03d)  L2=%03d  R2=%03d",
                    readout.buttons, readout.lx, readout.ly, readout.rx, readout.ry,
                    readout.l2, readout.r2));
            handler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        tvInfo = mono(13);
        root.addView(tvInfo);

        TextView rawLabel = new TextView(this);
        rawLabel.setText("Sending to PS5");
        rawLabel.setTextColor(getColor(R.color.text_muted));
        rawLabel.setTextSize(13);
        rawLabel.setPadding(0, dp(12), 0, dp(2));
        root.addView(rawLabel);
        tvRaw = mono(12);
        root.addView(tvRaw);

        Switch swInput = new Switch(this);
        swInput.setText("Log button presses");
        swInput.setTextSize(15);
        swInput.setChecked(app.isLogInput());
        swInput.setOnCheckedChangeListener((b, on) -> app.setLogInput(on));
        swInput.setMinHeight(dp(48));
        root.addView(swInput);

        LinearLayout buttons = new LinearLayout(this);
        Button copy = new Button(this);
        copy.setText("Copy log");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Gamepad Bridge log", infoText() + "\n" + AppLog.text()));
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        });
        Button clear = new Button(this);
        clear.setText("Clear log");
        clear.setOnClickListener(v -> AppLog.clear());
        buttons.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(clear, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttons);

        logScroll = new ScrollView(this);
        tvLog = mono(12);
        tvLog.setTextIsSelectable(true);
        logScroll.addView(tvLog);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private TextView mono(int sp) {
        TextView t = new TextView(this);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(sp);
        t.setTextColor(getColor(R.color.text_primary));
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLog.setListener(this::showLog);
        showLog();
        handler.post(rawTick);
    }

    @Override
    protected void onPause() {
        AppLog.setListener(null);
        handler.removeCallbacks(rawTick);
        super.onPause();
    }

    @Override
    protected void onStatusChanged() {
        tvInfo.setText(infoText());
    }

    private String infoText() {
        PadServer server = app.getServer();
        String lan = NetUtil.lanIp();
        String pad = app.getControllerName();
        return "Phone address   " + (lan == null ? "none" : lan) + ":" + PadServer.PORT + "\n"
                + "Phone server    " + (server.isRunning() ? "listening" : "stopped") + "\n"
                + "PS5             " + (server.isClientConnected() ? "connected from " + server.getClientIp() : "not connected") + "\n"
                + "Controller      " + (pad == null ? "none" : pad) + "\n"
                + "Mapping profile " + app.getCurrentProfileName() + "\n"
                + "ELF loader port " + app.getLoaderPort() + "\n"
                + "Android         " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
    }

    private void showLog() {
        tvLog.setText(AppLog.text());
        logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
