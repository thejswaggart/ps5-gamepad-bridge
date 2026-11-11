package com.aipc.gamepadbridge;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The button mapping screen: one row per PS5 button, each showing the
 * gamepad key(s) that currently drive it.
 *
 * Capture flow: tap a row (arms it), then press any button on the pad --
 * the next key event from an external gamepad is bound to that button.
 * Long-press a row to unmap it. ESC (or the CANCEL button, or the armed
 * row itself) cancels an armed capture.
 *
 * Like the main screen, this activity consumes every key event (the app
 * never exits mid-test), so navigation is in-screen: the MAIN button
 * goes back.
 */
public class MappingActivity extends Activity {

    private static final int KEY_ESC = 111; // Linux input ESC

    private KeyMap keyMap;
    private final TextView[] rows = new TextView[KeyMap.PS5_BITS.length];
    private LinearLayout rowsBox;
    private CheckBox cbTrig;
    private Button btnCancel;
    private Button btnMain;
    private Button btnReset;

    private int armedBit = 0;
    private TextView armedRow = null;

    public MappingActivity() {
        super();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mapping);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        keyMap = ((GamepadBridgeApp) getApplication()).getKeyMap();

        rowsBox = findViewById(R.id.rows);
        cbTrig = findViewById(R.id.cb_trig);
        btnCancel = findViewById(R.id.btn_cancel);
        btnMain = findViewById(R.id.btn_main);
        btnReset = findViewById(R.id.btn_reset);

        btnMain.setOnClickListener(v -> finish());
        btnReset.setOnClickListener(v -> {
            keyMap.reset();
            keyMap.save(this);
            cbTrig.setChecked(false);
            refreshAll();
            toast("RESET TO STOCK XBOX MAP");
        });
        btnCancel.setOnClickListener(v -> disarm());

        cbTrig.setChecked(keyMap.isTriggersAsButtons());
        cbTrig.setOnCheckedChangeListener((b, c) -> {
            keyMap.setTriggersAsButtons(c);
            keyMap.save(this);
            refreshAll();
        });

        buildRows();
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (keyMap != null) refreshAll();
    }

    private void buildRows() {
        for (int i = 0; i < KeyMap.PS5_BITS.length; i++) {
            final int bit = KeyMap.PS5_BITS[i];
            TextView row = new TextView(this);
            row.setTextSize(14);
            row.setTypeface(android.graphics.Typeface.MONOSPACE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(8, 2, 8, 2);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> {
                if (armedRow == row) {
                    disarm();
                } else {
                    arm(bit, row);
                }
            });
            row.setOnLongClickListener(v -> {
                keyMap.unbindBit(bit);
                keyMap.save(this);
                if (armedBit == bit) disarm();
                refreshAll();
                toast("UNMAPPED " + KeyMap.buttonName(bit));
                return true;
            });
            rows[i] = row;
            rowsBox.addView(row);
        }
    }

    private String formatRow(int bit, boolean armed) {
        String name = KeyMap.buttonName(bit);
        String rest;
        if (armed) {
            rest = "<- PRESS ANY BUTTON... (ESC cancels)";
        } else if ((bit == PadFrame.BTN_L2 || bit == PadFrame.BTN_R2)
                && !keyMap.isTriggersAsButtons()) {
            rest = "PRESSURE (analog)";
        } else {
            List<Integer> keys = keyMap.keysForBit(bit);
            if (keys.isEmpty()) {
                rest = "-";
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < keys.size(); i++) {
                    if (i > 0) sb.append(", ");
                    int k = keys.get(i);
                    sb.append(KeyEvent.keyCodeToString(k)).append(" (").append(k).append(")");
                }
                rest = sb.toString();
            }
        }
        return String.format(java.util.Locale.US, "%-12s  %s", name, rest);
    }

    private void arm(int bit, TextView row) {
        if (armedRow != null) disarm();
        if ((bit == PadFrame.BTN_L2 || bit == PadFrame.BTN_R2)
                && !keyMap.isTriggersAsButtons()) {
            toast("L2/R2 ARE PRESSURE -- CHECK THE BOX FIRST");
            return;
        }
        armedBit = bit;
        armedRow = row;
        row.setBackgroundColor(0xFFFFF59D);
        row.setText(formatRow(bit, true));
    }

    private void disarm() {
        if (armedRow != null) {
            armedRow.setBackgroundColor(0);
            armedRow.setText(formatRow(armedBit, false));
        }
        armedBit = 0;
        armedRow = null;
    }

    private void refreshAll() {
        for (int i = 0; i < KeyMap.PS5_BITS.length; i++) {
            if (rows[i] == armedRow) continue; // armed row keeps its text
            rows[i].setText(formatRow(KeyMap.PS5_BITS[i], false));
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KEY_ESC) {
                if (armedBit != 0) {
                    disarm();
                    refreshAll();
                    toast("CANCELLED");
                } else {
                    finish(); // ESC with nothing armed = back to main
                }
                return true;
            }
            if (armedBit != 0 && DeviceProbe.isGamepadDevice(event.getDeviceId())) {
                keyMap.bind(event.getKeyCode(), armedBit);
                keyMap.save(this);
                String who = KeyEvent.keyCodeToString(event.getKeyCode());
                toast("MAPPED " + who + " -> " + KeyMap.buttonName(armedBit));
                disarm();
                refreshAll();
                return true;
            }
        }
        return true; // consume everything (the app never exits mid-test)
    }
}
