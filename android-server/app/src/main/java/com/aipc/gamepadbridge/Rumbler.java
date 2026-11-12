package com.aipc.gamepadbridge;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.InputDevice;

/**
 * Turns RumbleFrames from the PS5 into vibration: the controller's motor if
 * Android exposes one for it, otherwise (if the user allows it) the phone's.
 *
 * Android 9 gives one vibrator per device, so the PS5's two motors are
 * combined into one strength. Many controller drivers can only switch the
 * motor on or off; then weak rumble below a threshold is dropped.
 */
public final class Rumbler {

    public static final int TARGET_NONE = 0;
    public static final int TARGET_CONTROLLER = 1;
    public static final int TARGET_PHONE = 2;

    /** With on/off-only motors, ignore rumble weaker than this (0-255). */
    private static final int ON_OFF_THRESHOLD = 24;

    private final Vibrator phoneVibrator;

    private volatile boolean enabled = true;
    private volatile boolean phoneFallback = false;
    private volatile boolean controllerCanRumble = false;
    private volatile Vibrator target;
    private volatile int targetKind = TARGET_NONE;

    // Last state the PS5 asked for (for diagnostics) and what we issued.
    private volatile int lastLarge, lastSmall;
    private volatile long lastFrameAt;
    private int issuedAmp;
    private long issuedAt;

    public Rumbler(Context ctx) {
        phoneVibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public void setEnabled(boolean v) {
        enabled = v;
        if (!v) stop();
    }

    public void setPhoneFallback(boolean v) {
        phoneFallback = v;
    }

    /**
     * Pick where rumble goes for this controller (device id, or -1 for
     * none). Call on connect/disconnect and when the fallback switch changes.
     */
    public synchronized void selectFor(int deviceId) {
        stop();
        Vibrator v = null;
        InputDevice d = deviceId >= 0 ? InputDevice.getDevice(deviceId) : null;
        if (d != null) v = d.getVibrator();
        controllerCanRumble = v != null && v.hasVibrator();
        if (controllerCanRumble) {
            target = v;
            targetKind = TARGET_CONTROLLER;
        } else if (phoneFallback && phoneVibrator != null && phoneVibrator.hasVibrator()) {
            target = phoneVibrator;
            targetKind = TARGET_PHONE;
        } else {
            target = null;
            targetKind = TARGET_NONE;
        }
    }

    public int getTargetKind() { return targetKind; }
    public boolean controllerCanRumble() { return controllerCanRumble; }
    public boolean isEnabled() { return enabled; }
    public boolean isPhoneFallback() { return phoneFallback; }
    public int getLastLarge() { return lastLarge; }
    public int getLastSmall() { return lastSmall; }
    /** Milliseconds since the PS5 last sent rumble, or -1 if never. */
    public long msSinceLastFrame() {
        return lastFrameAt == 0 ? -1 : SystemClock.uptimeMillis() - lastFrameAt;
    }

    /** A frame from the PS5 (any thread). ttlMs 0 means stop now. */
    public synchronized void onRumble(int large, int small, int ttlMs) {
        lastLarge = large;
        lastSmall = small;
        lastFrameAt = SystemClock.uptimeMillis();
        if (!enabled) return;
        drive(Math.max(large, small), ttlMs);
    }

    /** A short strong buzz on the current target, ignoring the PS5. */
    public synchronized void test() {
        Vibrator v = target;
        if (v == null) return;
        vibrate(v, 400, 255);
        issuedAmp = 0; // let the next PS5 frame take over at once
    }

    public synchronized void stop() {
        Vibrator v = target;
        if (v != null && issuedAmp != 0) v.cancel();
        issuedAmp = 0;
    }

    private void drive(int amp, int ttlMs) {
        Vibrator v = target;
        if (v == null) return;
        boolean graded = hasAmplitudeControl(v);
        if (!graded) amp = amp >= ON_OFF_THRESHOLD ? 255 : 0;
        if (amp == 0 || ttlMs == 0) {
            if (issuedAmp != 0) v.cancel();
            issuedAmp = 0;
            return;
        }
        long now = SystemClock.uptimeMillis();
        // Re-issue on a change, or before the previous one-shot runs out.
        if (amp != issuedAmp || now - issuedAt >= ttlMs / 3) {
            vibrate(v, ttlMs, amp);
            issuedAmp = amp;
            issuedAt = now;
        }
    }

    private static boolean hasAmplitudeControl(Vibrator v) {
        return Build.VERSION.SDK_INT >= 26 && v.hasAmplitudeControl();
    }

    @SuppressWarnings("deprecation")
    private static void vibrate(Vibrator v, long ms, int amp) {
        if (Build.VERSION.SDK_INT >= 26) {
            int a = hasAmplitudeControl(v) ? Math.max(1, Math.min(255, amp))
                    : VibrationEffect.DEFAULT_AMPLITUDE;
            v.vibrate(VibrationEffect.createOneShot(ms, a));
        } else {
            v.vibrate(ms);
        }
    }
}
