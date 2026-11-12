package com.aipc.gamepadbridge;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * Home screen: a three-step setup checklist (controller, payload on PS5,
 * PS5 connected), then a "Ready to play" banner once everything is up.
 */
public class MainActivity extends PadActivity {

    private static final int MENU_TEST = 1;
    private static final int MENU_DIAGNOSTICS = 2;

    private static final int ST_PENDING = 0;
    private static final int ST_WORKING = 1;
    private static final int ST_DONE = 2;
    private static final int ST_ERROR = 3;

    private View stepController;
    private View stepPayload;
    private View stepLink;
    private View banner;
    private View advancedBox;
    private TextView tvAdvancedToggle;
    private TextView tvPhoneAddr;
    private EditText etIp;
    private EditText etPort;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        stepController = findViewById(R.id.step_controller);
        stepPayload = findViewById(R.id.step_payload);
        stepLink = findViewById(R.id.step_link);
        banner = findViewById(R.id.banner);
        advancedBox = findViewById(R.id.advanced);
        tvAdvancedToggle = findViewById(R.id.tv_advanced_toggle);
        tvPhoneAddr = findViewById(R.id.tv_phone_addr);
        etIp = findViewById(R.id.et_ip);
        etPort = findViewById(R.id.et_port);

        findViewById(R.id.btn_play).setOnClickListener(v -> open(PlayModeActivity.class));
        setupNavRow(R.id.btn_mapping, "Button mapping",
                "Choose which controller button does what", MappingActivity.class);
        setupNavRow(R.id.btn_test, "Test controller",
                "See what your PS5 receives", TestActivity.class);
        // Keep the row ripples inside the card's rounded corners.
        findViewById(R.id.nav_card).setClipToOutline(true);
        tvAdvancedToggle.setOnClickListener(v -> toggleAdvanced());

        etIp.setText(app.getManualIp());
        etPort.setText(String.valueOf(app.getLoaderPort()));
        etIp.addTextChangedListener(new Watcher(s -> app.setManualIp(s.trim())));
        etPort.addTextChangedListener(new Watcher(s -> {
            int p = parsePort(s);
            if (p > 0) {
                app.setLoaderPort(p);
                etPort.setError(null);
            } else {
                etPort.setError("Use a number from 1 to 65535");
            }
        }));
        // Open Advanced if the user already set something there.
        if (!app.getManualIp().isEmpty()
                || app.getLoaderPort() != GamepadBridgeApp.DEFAULT_LOADER_PORT) {
            toggleAdvanced();
        }
    }

    @Override
    protected void onStatusChanged() {
        render();
    }

    private void render() {
        String pad = app.getControllerName();
        PadServer server = app.getServer();
        boolean linked = server.isClientConnected();
        int send = app.getSendState();
        String sendIp = app.getSendIp();

        // Step 1: controller
        if (pad != null) {
            setStep(stepController, ST_DONE, "1", "Controller connected", pad,
                    "Test", v -> open(TestActivity.class));
        } else {
            setStep(stepController, ST_PENDING, "1", "Connect a controller",
                    "Plug it into this phone by USB, or pair it over Bluetooth.",
                    "Bluetooth", v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        }

        // Step 2: payload on the PS5
        switch (send) {
            case GamepadBridgeApp.SEND_SEARCHING:
                setStep(stepPayload, ST_WORKING, "2", "Looking for your PS5…",
                        "Searching your network for the ELF loader.", null, null);
                break;
            case GamepadBridgeApp.SEND_SENDING:
                setStep(stepPayload, ST_WORKING, "2", "Sending GhostPad to your PS5…",
                        "PS5 at " + sendIp, null, null);
                break;
            case GamepadBridgeApp.SEND_FAILED:
                setStep(stepPayload, ST_ERROR, "2", "Couldn't send to your PS5",
                        app.getSendDetail(), "Try again", v -> confirmSend());
                break;
            case GamepadBridgeApp.SEND_DONE:
                setStep(stepPayload, ST_DONE, "2", "GhostPad sent to your PS5",
                        "PS5 at " + sendIp, "Resend", v -> confirmSend());
                break;
            default:
                if (linked) {
                    setStep(stepPayload, ST_DONE, "2", "GhostPad is running on your PS5",
                            "It was already running.", "Resend", v -> confirmSend());
                } else {
                    setStep(stepPayload, ST_PENDING, "2", "Send GhostPad to your PS5",
                            "Open the ELF loader on your PS5 first.", "Send", v -> confirmSend());
                }
        }

        // Step 3: PS5 connects back to the phone
        if (!server.isRunning()) {
            setStep(stepLink, ST_ERROR, "3", "This phone can't accept the PS5",
                    "Port " + PadServer.PORT + " is in use. Force-stop the app and open it again.",
                    null, null);
        } else if (linked) {
            setStep(stepLink, ST_DONE, "3", "PS5 connected",
                    "Sending controller input to " + server.getClientIp(), null, null);
        } else if (send == GamepadBridgeApp.SEND_DONE) {
            setStep(stepLink, ST_WORKING, "3", "Waiting for the PS5 to connect…",
                    "This usually takes a few seconds.", null, null);
        } else {
            setStep(stepLink, ST_PENDING, "3", "PS5 connects on its own",
                    "Once GhostPad is running, the PS5 finds this phone automatically.", null, null);
        }

        banner.setVisibility(linked && pad != null ? View.VISIBLE : View.GONE);

        String lan = NetUtil.lanIp();
        tvPhoneAddr.setText("This phone: " + (lan == null ? "not on Wi-Fi" : lan)
                + ", port " + PadServer.PORT);
    }

    private void setStep(View row, int state, String number, String title, String detail,
                         String action, View.OnClickListener onAction) {
        TextView badge = row.findViewById(R.id.step_badge);
        TextView tvTitle = row.findViewById(R.id.step_title);
        TextView tvDetail = row.findViewById(R.id.step_detail);
        Button btn = row.findViewById(R.id.step_action);

        switch (state) {
            case ST_DONE:
                badge.setBackgroundResource(R.drawable.badge_done);
                badge.setText("✓");
                badge.setTextColor(getColor(R.color.bg));
                break;
            case ST_WORKING:
                badge.setBackgroundResource(R.drawable.badge_working);
                badge.setText("…");
                badge.setTextColor(getColor(R.color.bg));
                break;
            case ST_ERROR:
                badge.setBackgroundResource(R.drawable.badge_error);
                badge.setText("!");
                badge.setTextColor(getColor(R.color.bg));
                break;
            default:
                badge.setBackgroundResource(R.drawable.badge_pending);
                badge.setText(number);
                badge.setTextColor(getColor(R.color.text_secondary));
        }
        tvTitle.setText(title);
        tvDetail.setText(detail);
        tvDetail.setVisibility(detail == null || detail.isEmpty() ? View.GONE : View.VISIBLE);
        if (action == null) {
            btn.setVisibility(View.GONE);
        } else {
            btn.setVisibility(View.VISIBLE);
            btn.setText(action);
            btn.setOnClickListener(onAction);
        }
    }

    private void confirmSend() {
        new AlertDialog.Builder(this)
                .setTitle("Send GhostPad to your PS5?")
                .setMessage("This replaces any payload that's running on your PS5 right now. "
                        + "Make sure the ELF loader is open on the PS5 first.")
                .setPositiveButton("Send", (d, w) -> app.sendPayload())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toggleAdvanced() {
        boolean show = advancedBox.getVisibility() != View.VISIBLE;
        advancedBox.setVisibility(show ? View.VISIBLE : View.GONE);
        tvAdvancedToggle.setText((show ? "▾" : "▸") + "  Advanced");
    }

    private static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            return (p >= 1 && p <= 65535) ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void setupNavRow(int id, String title, String detail, Class<?> target) {
        View row = findViewById(id);
        ((TextView) row.findViewById(R.id.nav_title)).setText(title);
        ((TextView) row.findViewById(R.id.nav_detail)).setText(detail);
        row.setOnClickListener(v -> open(target));
    }

    private void open(Class<?> cls) {
        startActivity(new Intent(this, cls));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_TEST, 0, "Test controller");
        menu.add(0, MENU_DIAGNOSTICS, 1, "Diagnostics");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_TEST) {
            open(TestActivity.class);
            return true;
        }
        if (item.getItemId() == MENU_DIAGNOSTICS) {
            open(DiagnosticsActivity.class);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** TextWatcher that only cares about the final text. */
    private static final class Watcher implements TextWatcher {
        private final Consumer<String> onChange;

        Watcher(Consumer<String> onChange) {
            this.onChange = onChange;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

        @Override
        public void onTextChanged(CharSequence s, int st, int b, int c) {}

        @Override
        public void afterTextChanged(Editable s) {
            onChange.accept(s.toString());
        }
    }
}
