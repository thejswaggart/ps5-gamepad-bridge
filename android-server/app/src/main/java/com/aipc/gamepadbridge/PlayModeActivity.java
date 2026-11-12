package com.aipc.gamepadbridge;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Random;

/**
 * Near-black, minimum-brightness screen for playing. The screen stays on
 * (gamepad input only reaches the app while it does), but uses as little
 * power as possible. Touches are ignored so a palm or pocket can't press
 * anything. Hold the screen for 1.5 s, or press the phone's Back button,
 * to leave.
 */
public class PlayModeActivity extends PadActivity {

    private static final long HOLD_TO_EXIT_MS = 1500;
    private static final long MOVE_TEXT_MS = 20000; // avoid OLED burn-in

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private TextView tvStatus;
    private boolean holding = false;

    private final Runnable exitRunnable = this::finish;
    private final Runnable moveRunnable = new Runnable() {
        @Override
        public void run() {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tvStatus.getLayoutParams();
            lp.topMargin = dp(40 + random.nextInt(200));
            tvStatus.setLayoutParams(lp);
            handler.postDelayed(this, MOVE_TEXT_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        tvStatus = new TextView(this);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setTextSize(15);
        tvStatus.setLineSpacing(0, 1.3f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(120);
        root.addView(tvStatus, lp);
        setContentView(root);

        WindowManager.LayoutParams wlp = getWindow().getAttributes();
        wlp.screenBrightness = 0.01f; // this window only; restored on exit
        getWindow().setAttributes(wlp);

        root.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    holding = true;
                    render();
                    handler.postDelayed(exitRunnable, HOLD_TO_EXIT_MS);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    holding = false;
                    handler.removeCallbacks(exitRunnable);
                    render();
                    break;
                default:
                    break;
            }
            return true; // swallow every touch
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.postDelayed(moveRunnable, MOVE_TEXT_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(moveRunnable);
        handler.removeCallbacks(exitRunnable);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    protected void onStatusChanged() {
        render();
    }

    private void render() {
        if (holding) {
            tvStatus.setTextColor(0xFF9AA3B2);
            tvStatus.setText("Keep holding to exit…");
            return;
        }
        boolean linked = app.getServer().isClientConnected();
        String pad = app.getControllerName();
        if (linked && pad != null) {
            tvStatus.setTextColor(0xFF4A5262);
            tvStatus.setText("Streaming to PS5\nHold the screen to exit");
        } else {
            tvStatus.setTextColor(0xFF8A6A2A);
            tvStatus.setText((pad == null ? "No controller connected" : "PS5 not connected")
                    + "\nHold the screen to exit");
        }
    }
}
