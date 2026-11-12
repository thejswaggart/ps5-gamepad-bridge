package com.aipc.gamepadbridge;

import android.app.AlertDialog;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Button mapping: one row per PS5 button, grouped by type, showing which
 * controller button(s) drive it.
 *
 * Capture flow: tap a row (it highlights), then press a button on the
 * controller; that button is bound to the row. Tap the row again, tap
 * Cancel, or press the phone's Back button to stop. Long-press a row to
 * clear it.
 */
public class MappingActivity extends PadActivity {

    private static final int MENU_RESET = 1;

    private static final Object[][] SECTIONS = {
            {"Face buttons", null, new int[]{
                    PadFrame.BTN_CROSS, PadFrame.BTN_CIRCLE,
                    PadFrame.BTN_SQUARE, PadFrame.BTN_TRIANGLE}},
            {"D-pad", "Your controller's D-pad always works. You can add extra buttons here.", new int[]{
                    PadFrame.BTN_UP, PadFrame.BTN_DOWN,
                    PadFrame.BTN_LEFT, PadFrame.BTN_RIGHT}},
            {"Shoulders and triggers", null, new int[]{
                    PadFrame.BTN_L1, PadFrame.BTN_R1,
                    PadFrame.BTN_L2, PadFrame.BTN_R2}},
            {"Sticks", null, new int[]{
                    PadFrame.BTN_L3, PadFrame.BTN_R3}},
            {"System", null, new int[]{
                    PadFrame.BTN_OPTIONS, PadFrame.BTN_SHARE,
                    PadFrame.BTN_CREATE, PadFrame.BTN_TOUCHPAD}},
    };

    private KeyMap keyMap;
    private Switch swTriggers;
    private View captureBar;
    private TextView tvCapture;
    private final Map<Integer, View> rows = new HashMap<>();
    private final Map<Integer, TextView> values = new HashMap<>();
    private int armedBit = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mapping);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        keyMap = app.getKeyMap();
        swTriggers = findViewById(R.id.sw_digital_triggers);
        captureBar = findViewById(R.id.capture_bar);
        tvCapture = findViewById(R.id.tv_capture);
        findViewById(R.id.btn_cancel).setOnClickListener(v -> disarm());

        swTriggers.setChecked(keyMap.isTriggersAsButtons());
        swTriggers.setOnCheckedChangeListener((b, on) -> {
            keyMap.setTriggersAsButtons(on);
            app.saveCurrent();
            if (!on && (armedBit == PadFrame.BTN_L2 || armedBit == PadFrame.BTN_R2)) disarm();
            refreshAll();
        });

        buildRows(findViewById(R.id.rows));
        refreshAll();
    }

    @Override
    protected void onStatusChanged() {
        if (getActionBar() != null) {
            String pad = app.getControllerName();
            getActionBar().setSubtitle(pad != null ? "For " + pad : "No controller connected");
        }
        // A newly connected controller loads its own profile.
        swTriggers.setChecked(keyMap.isTriggersAsButtons());
        refreshAll();
    }

    private void buildRows(LinearLayout box) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);

        for (Object[] sec : SECTIONS) {
            TextView header = new TextView(this);
            header.setText((String) sec[0]);
            header.setTextAppearance(R.style.SectionLabel);
            header.setPadding(0, dp(20), 0, dp(4));
            box.addView(header);
            if (sec[1] != null) {
                TextView note = new TextView(this);
                note.setText((String) sec[1]);
                note.setTextColor(getColor(R.color.text_secondary));
                note.setTextSize(13);
                note.setPadding(0, 0, 0, dp(4));
                box.addView(note);
            }
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.card_bg);
            box.addView(card);

            for (int bit : (int[]) sec[2]) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(52));
                row.setPadding(dp(16), dp(8), dp(16), dp(8));
                row.setBackgroundResource(tv.resourceId);

                TextView name = new TextView(this);
                name.setText(KeyMap.buttonLabel(bit));
                name.setTextSize(16);
                name.setTextColor(getColor(R.color.text_primary));
                row.addView(name, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                TextView value = new TextView(this);
                value.setTextSize(15);
                value.setGravity(Gravity.END);
                row.addView(value, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                row.setOnClickListener(v -> {
                    if (armedBit == bit) disarm();
                    else arm(bit);
                });
                row.setOnLongClickListener(v -> {
                    confirmClear(bit);
                    return true;
                });
                rows.put(bit, row);
                values.put(bit, value);
                card.addView(row);
            }
        }
    }

    private boolean isAnalogTrigger(int bit) {
        return (bit == PadFrame.BTN_L2 || bit == PadFrame.BTN_R2) && !keyMap.isTriggersAsButtons();
    }

    private void refreshAll() {
        for (Map.Entry<Integer, TextView> e : values.entrySet()) {
            int bit = e.getKey();
            TextView v = e.getValue();
            View row = rows.get(bit);
            if (bit == armedBit) {
                row.setBackgroundResource(R.drawable.row_armed);
                v.setText("Press a button…");
                v.setTextColor(getColor(R.color.warning));
                continue;
            }
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setBackgroundResource(tv.resourceId);

            if (isAnalogTrigger(bit)) {
                v.setText(bit == PadFrame.BTN_L2 ? "Left trigger (analog)" : "Right trigger (analog)");
                v.setTextColor(getColor(R.color.text_secondary));
                continue;
            }
            List<Integer> keys = keyMap.keysForBit(bit);
            if (keys.isEmpty()) {
                v.setText("Not set");
                v.setTextColor(getColor(R.color.text_muted));
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < keys.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(KeyMap.keyLabel(keys.get(i)));
                }
                v.setText(sb.toString());
                v.setTextColor(getColor(R.color.text_secondary));
            }
        }
    }

    private void arm(int bit) {
        if (isAnalogTrigger(bit)) {
            toast("Turn on Digital triggers to remap L2 and R2");
            return;
        }
        if (app.getControllerName() == null) {
            toast("Connect a controller first");
            return;
        }
        armedBit = bit;
        tvCapture.setText("Press the controller button to use for "
                + KeyMap.buttonLabel(bit).replaceFirst("^. {2}", ""));
        captureBar.setVisibility(View.VISIBLE);
        refreshAll();
    }

    private void disarm() {
        armedBit = 0;
        captureBar.setVisibility(View.GONE);
        refreshAll();
    }

    private void confirmClear(int bit) {
        if (isAnalogTrigger(bit)) return;
        if (keyMap.keysForBit(bit).isEmpty()) {
            toast("Nothing to clear");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Clear " + KeyMap.buttonLabel(bit) + "?")
                .setMessage("No controller button will press it until you set it again.")
                .setPositiveButton("Clear", (d, w) -> {
                    keyMap.unbindBit(bit);
                    app.saveCurrent();
                    if (armedBit == bit) armedBit = 0;
                    refreshAll();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected boolean onPadKeyCaptured(KeyEvent event) {
        if (armedBit == 0) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) return false;
        int code = event.getKeyCode();
        keyMap.bind(code, armedBit);
        app.saveCurrent();
        toast(KeyMap.keyLabel(code) + " now presses " + KeyMap.buttonLabel(armedBit));
        disarm();
        return true;
    }

    @Override
    public void onBackPressed() {
        if (armedBit != 0) {
            disarm();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_RESET, 0, "Reset to defaults");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_RESET) {
            new AlertDialog.Builder(this)
                    .setTitle("Reset button mapping?")
                    .setMessage("This controller goes back to the standard Xbox-style layout. "
                            + "Your changes for it will be lost.")
                    .setPositiveButton("Reset", (d, w) -> {
                        keyMap.reset();
                        app.saveCurrent();
                        swTriggers.setChecked(false);
                        disarm();
                        toast("Mapping reset");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
