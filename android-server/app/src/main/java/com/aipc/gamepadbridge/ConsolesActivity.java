package com.aipc.gamepadbridge;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Scan the LAN for consoles, show them all, and let the user pick which one
 * is active. Only the active console is deployed to and streamed to; the
 * server refuses every other dialer (see {@link PadServer}).
 */
public class ConsolesActivity extends PadActivity {

    private Button btnScan;
    private TextView tvScanStatus;
    private LinearLayout list;

    private volatile boolean scanning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_consoles);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        btnScan = findViewById(R.id.btn_scan);
        tvScanStatus = findViewById(R.id.tv_scan_status);
        list = findViewById(R.id.console_list);

        btnScan.setOnClickListener(v -> scan());
        findViewById(R.id.btn_add).setOnClickListener(v -> addByAddressDialog());
    }

    @Override
    protected void onStatusChanged() {
        render();
    }

    // ---- discovery ----

    private void scan() {
        if (scanning) return;
        String lan = NetUtil.lanIp();
        if (lan == null) {
            tvScanStatus.setText("This phone isn't on Wi-Fi. Join the same network as your console.");
            return;
        }
        final String base = NetUtil.subnetBase(lan);
        final int port = app.getLoaderPort();
        scanning = true;
        btnScan.setEnabled(false);
        tvScanStatus.setText("Scanning " + base + ".1-254 for ELF loaders on port " + port + "…");
        new Thread(() -> {
            final List<String> hits = NetUtil.findAllOpen(base, port);
            String myIp = NetUtil.lanIp();
            int added = 0;
            for (String ip : hits) {
                if (ip.equals(myIp)) continue; // don't list the phone itself
                Console c = new Console(ip, port);
                c.hostname = NetUtil.hostnameOf(ip);
                c.guessTypeFromHostname();
                c.lastSeenMs = System.currentTimeMillis();
                app.getConsoleStore().addOrUpdate(c);
                added++;
            }
            final int found = added;
            runOnUiThread(() -> {
                scanning = false;
                btnScan.setEnabled(true);
                tvScanStatus.setText(found == 0
                        ? "No consoles found. Open the ELF loader on your PS4/PS5, then scan again."
                        : "Found " + found + " console" + (found == 1 ? "" : "s") + ".");
                render();
            });
        }, "ConsoleScan").start();
    }

    // ---- list ----

    private void render() {
        list.removeAllViews();
        List<Console> all = app.getConsoleStore().all();
        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No consoles yet. Tap Scan network, or add one by address.");
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(14);
            int p = dp(16);
            empty.setPadding(p, p, p, p);
            list.addView(empty);
            return;
        }
        String activeAddr = app.getConsoleStore().getActiveAddress();
        boolean connected = app.getServer().isClientConnected();
        String connectedIp = app.getServer().getClientIp();
        int sendState = app.getSendState();
        String sendIp = app.getSendIp();
        LayoutInflater inf = LayoutInflater.from(this);
        for (Console c : all) {
            View row = inf.inflate(R.layout.console_row, list, false);
            boolean isActive = c.address.equals(activeAddr);
            bindRow(row, c, isActive, connected, connectedIp, sendState, sendIp);
            list.addView(row);
        }
    }

    private void bindRow(View row, Console c, boolean isActive, boolean connected,
                         String connectedIp, int sendState, String sendIp) {
        TextView title = row.findViewById(R.id.console_title);
        TextView detail = row.findViewById(R.id.console_detail);
        Button action = row.findViewById(R.id.console_action);

        title.setText(c.displayName());

        StringBuilder sb = new StringBuilder();
        sb.append(c.typeLabel()).append(" · ").append(c.address);
        if (c.firmware != null && !c.firmware.isEmpty()) {
            sb.append(" · fw ").append(c.firmware);
        }
        if (isActive) {
            boolean sending = sendState == GamepadBridgeApp.SEND_SENDING
                    && c.address.equals(sendIp);
            if (sending) sb.append(" · sending…");
            else if (connected && c.address.equals(connectedIp)) sb.append(" · connected");
            else sb.append(" · active");
        }
        detail.setText(sb.toString());

        if (isActive) {
            action.setText("Active");
            action.setEnabled(false);
        } else {
            action.setText("Use");
            action.setEnabled(true);
            action.setOnClickListener(v -> use(c));
        }
        row.setOnClickListener(v -> consoleMenu(c));
    }

    // ---- actions ----

    /** Make a console active, asking for its type first if unknown. */
    private void use(final Console c) {
        if (c.type == Console.TYPE_UNKNOWN) {
            chooseType(c, () -> use(c));
            return;
        }
        app.setActiveConsole(c);
        render();
    }

    private void chooseType(final Console c, final Runnable then) {
        new AlertDialog.Builder(this)
                .setTitle("Which console is " + c.displayName() + "?")
                .setItems(new CharSequence[]{"PlayStation 5", "PlayStation 4"}, (d, w) -> {
                    app.getConsoleStore().setType(c.address,
                            w == 0 ? Console.TYPE_PS5 : Console.TYPE_PS4);
                    c.type = w == 0 ? Console.TYPE_PS5 : Console.TYPE_PS4;
                    if (then != null) then.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void consoleMenu(final Console c) {
        boolean active = c.address.equals(app.getConsoleStore().getActiveAddress());
        CharSequence[] items = active
                ? new CharSequence[]{"Resend payload", "Rename", "Set type", "Forget"}
                : new CharSequence[]{"Use this console", "Rename", "Set type", "Forget"};
        new AlertDialog.Builder(this)
                .setTitle(c.displayName())
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0:
                            if (active) app.sendPayloadTo(c);
                            else use(c);
                            break;
                        case 1: renameDialog(c); break;
                        case 2: chooseType(c, this::render); break;
                        case 3: forget(c); break;
                    }
                    render();
                })
                .show();
    }

    private void renameDialog(final Console c) {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        in.setText(c.nickname == null ? "" : c.nickname);
        in.setHint(c.hostname == null ? c.address : c.hostname);
        new AlertDialog.Builder(this)
                .setTitle("Name this console")
                .setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    app.getConsoleStore().setNickname(c.address, in.getText().toString().trim());
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void forget(final Console c) {
        new AlertDialog.Builder(this)
                .setTitle("Forget " + c.displayName() + "?")
                .setMessage("It's removed from this list. A scan will find it again.")
                .setPositiveButton("Forget", (d, w) -> {
                    app.getConsoleStore().remove(c.address);
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addByAddressDialog() {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        in.setHint("192.168.1.42");
        new AlertDialog.Builder(this)
                .setTitle("Add a console by address")
                .setView(in)
                .setPositiveButton("Add", (d, w) -> {
                    String ip = in.getText().toString().trim();
                    if (ip.isEmpty()) return;
                    Console c = new Console(ip, app.getLoaderPort());
                    Console stored = app.getConsoleStore().addOrUpdate(c);
                    render();
                    chooseType(stored, this::render);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
