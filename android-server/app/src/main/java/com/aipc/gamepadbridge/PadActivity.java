package com.aipc.gamepadbridge;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.WindowManager;

/**
 * Base for every screen. Android only sends gamepad events to the focused
 * window, so each screen forwards pad input to the shared mapper; that way
 * the PS5 keeps receiving input whichever screen is open.
 *
 * Only gamepad events are consumed. The phone's own keys (Back, volume)
 * reach the system as usual, so the user can always leave a screen.
 */
public abstract class PadActivity extends Activity {

    protected GamepadBridgeApp app;
    private final Runnable statusCallback = this::onStatusChanged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (GamepadBridgeApp) getApplication();
        // Input only arrives while the screen is on and this app is in front.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.addStatusListener(statusCallback);
        onStatusChanged();
    }

    @Override
    protected void onPause() {
        app.removeStatusListener(statusCallback);
        super.onPause();
    }

    /** Called on the main thread when controller / PS5 / send status changes. */
    protected void onStatusChanged() {}

    /**
     * Screens that capture a pad key (the mapping screen) override this.
     * Return true to keep the event from reaching the mapper.
     */
    protected boolean onPadKeyCaptured(KeyEvent event) {
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (app.isPadKey(event)) {
            if (!onPadKeyCaptured(event)) app.onPadKey(event);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (app.isPadMotion(event)) {
            app.onPadMotion(event);
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    protected int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
