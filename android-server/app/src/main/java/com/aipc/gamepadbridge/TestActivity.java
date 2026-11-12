package com.aipc.gamepadbridge;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/** "Test controller": press buttons and see exactly what the PS5 receives. */
public class TestActivity extends PadActivity {

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

        setContentView(root);
    }

    @Override
    protected void onStatusChanged() {
        String pad = app.getControllerName();
        if (getActionBar() != null) {
            getActionBar().setSubtitle(pad != null ? pad : "No controller connected");
        }
    }
}
