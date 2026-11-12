package com.aipc.gamepadbridge;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** "Test controller": see what the PS5 receives, and set up rumble. */
public class TestActivity extends PadActivity {

    private Switch swRumble;
    private Switch swPhone;
    private TextView tvRumble;
    private Button btnTest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView hint = new TextView(this);
        hint.setText("Press buttons and move the sticks. This shows what your PS5 receives, "
                + "after your button mapping.");
        hint.setTextColor(getColor(R.color.text_secondary));
        hint.setTextSize(14);
        root.addView(hint);

        PadView pad = new PadView(this, app.getMapper());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(16);
        root.addView(pad, lp);

        root.addView(buildRumbleCard());
        setContentView(root);
    }

    private View buildRumbleCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(16), dp(8), dp(16), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(16);
        card.setLayoutParams(clp);

        swRumble = new Switch(this);
        swRumble.setText("Rumble");
        swRumble.setTextSize(16);
        swRumble.setMinHeight(dp(48));
        swRumble.setOnCheckedChangeListener((b, on) -> {
            if (on != app.getRumbler().isEnabled()) app.setRumbleEnabled(on);
        });
        card.addView(swRumble);

        tvRumble = new TextView(this);
        tvRumble.setTextColor(getColor(R.color.text_secondary));
        tvRumble.setTextSize(13);
        card.addView(tvRumble);

        swPhone = new Switch(this);
        swPhone.setText("Vibrate the phone instead");
        swPhone.setTextSize(15);
        swPhone.setMinHeight(dp(48));
        swPhone.setOnCheckedChangeListener((b, on) -> {
            if (on != app.getRumbler().isPhoneFallback()) app.setRumblePhoneFallback(on);
        });
        card.addView(swPhone);

        btnTest = new Button(this, null, 0, R.style.Button_Text);
        btnTest.setText("Test rumble");
        btnTest.setOnClickListener(v -> app.getRumbler().test());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(4);
        card.addView(btnTest, blp);
        return card;
    }

    @Override
    protected void onStatusChanged() {
        String pad = app.getControllerName();
        if (getActionBar() != null) {
            getActionBar().setSubtitle(pad != null ? pad : "No controller connected");
        }
        if (swRumble == null) return;

        Rumbler r = app.getRumbler();
        boolean on = r.isEnabled();
        boolean canPad = r.controllerCanRumble();
        swRumble.setChecked(on);
        swPhone.setChecked(r.isPhoneFallback());
        // The phone option only matters when the controller has no motor.
        swPhone.setVisibility(on && pad != null && !canPad ? View.VISIBLE : View.GONE);

        String msg;
        if (!on) {
            msg = "Off. Games won't vibrate anything.";
        } else if (pad == null) {
            msg = "Connect a controller to set up rumble.";
        } else if (canPad) {
            msg = app.getRumbleControllerName() + " vibrates when the game rumbles.";
        } else if (r.getTargetKind() == Rumbler.TARGET_PHONE) {
            msg = "This phone can't make your controller rumble, so the phone vibrates instead.";
        } else {
            msg = "This phone can't make your controller rumble over this connection. "
                    + "A USB cable may work, or the phone can vibrate instead.";
        }
        tvRumble.setText(msg);
        btnTest.setVisibility(on && r.getTargetKind() != Rumbler.TARGET_NONE ? View.VISIBLE : View.GONE);
    }
}
