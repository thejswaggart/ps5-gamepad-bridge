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
 * combined into one strength. Controller motors on Android 9 can only be
 * switched on or off, so weaker rumble is made by pulsing the motor (the
 * trick on/off-motor consoles use): light and medium are short on/off
 * cycles, strong is continuous. Very weak rumble is dropped.
 */
public final class Rumbler {

    public static final int TARGET_NONE = 0;
    public static final int TARGET_CONTROLLER = 1;
    public static final int TARGET_PHONE = 2;

    /** With on/off-only motors, ignore rumble weaker than this (0-255). */
    // The PS5 motor values rest at 0, so any non-zero is real rumble. Keep
    // this low so subtle effects (e.g. Wave Race's wave bounces, ~20/255)
    // still produce a gentle pulse on an on/off motor, not silence.
    private static final int ON_OFF_THRESHOLD = 5;

    /*
     * Pulse levels for on/off motors: {on ms, off ms} per cycle. Weak motors
     * barely spin up on short bursts (a crash felt like "a couple
     * revolutions"), so the "on" slices are long and the duty cycle high;
     * anything strong runs continuously. Games also use different value
     * ranges (one tops out near 63, another near 255), so the thresholds are
     * low enough that a ~60 rumble still drives the motor hard.
     * Levels: 1 light, 2 medium, 3 strong (continuous).
     */
    private static final int LEVEL_OFF = 0, LEVEL_LIGHT = 1, LEVEL_MEDIUM = 2, LEVEL_STRONG = 3;
    private static final int[][] PULSE = { null, {200, 80}, {260, 40}, null };
    private static final int LIGHT_BELOW = 20, MEDIUM_BELOW = 55;
    /** Android 9's input vibrator accepts at most 100 pattern entries. */
    private static final int MAX_PATTERN = 100;

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

    /** Strengths the test plays, 1.5 s each with short pauses between. */
    private static final int[] TEST_STEPS = {60, 140, 255};
    private static final int TEST_ON_MS = 1500, TEST_GAP_MS = 700;

    /**
     * Play light, medium, then full rumble (about 6 s) on the current
     * target, the same way PS5 rumble at those strengths would feel.
     */
    public synchronized void test() {
        Vibrator v = target;
        if (v == null) return;
        boolean graded = hasAmplitudeControl(v);
        java.util.ArrayList<long[]> steps = new java.util.ArrayList<>(); // {ms, amp}
        for (int i = 0; i < TEST_STEPS.length; i++) {
            if (i > 0) steps.add(new long[]{TEST_GAP_MS, 0});
            int amp = TEST_STEPS[i];
            int[] cycle = graded ? null : PULSE[levelFor(amp)];
            if (cycle == null) {
                steps.add(new long[]{TEST_ON_MS, graded ? amp : 255});
            } else {
                for (int t = 0; t < TEST_ON_MS; t += cycle[0] + cycle[1]) {
                    steps.add(new long[]{cycle[0], 255});
                    steps.add(new long[]{cycle[1], 0});
                }
            }
        }
        play(v, steps, graded);
        issuedAmp = -1; // so stop() cancels it, and the next PS5 frame takes over
    }

    /**
     * Play {ms, amp} steps (amp 0 = off). Android's input vibrator wants
     * strict off/on alternation starting with "off", so merge neighbours.
     */
    @SuppressWarnings("deprecation")
    private static void play(Vibrator v, java.util.List<long[]> steps, boolean graded) {
        java.util.ArrayList<Long> t = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> a = new java.util.ArrayList<>();
        t.add(0L);
        a.add(0);
        for (long[] st : steps) {
            int amp = (int) st[1];
            int last = a.get(a.size() - 1);
            boolean sameKind = (amp == 0) == (last == 0) && (!graded || amp == last);
            if (sameKind) {
                t.set(t.size() - 1, t.get(t.size() - 1) + st[0]);
            } else {
                t.add(st[0]);
                a.add(amp);
            }
        }
        int n = Math.min(t.size(), MAX_PATTERN);
        long[] timings = new long[n];
        int[] amps = new int[n];
        for (int i = 0; i < n; i++) {
            timings[i] = t.get(i);
            amps[i] = a.get(i);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(graded ? VibrationEffect.createWaveform(timings, amps, -1)
                    : VibrationEffect.createWaveform(timings, -1));
        } else {
            v.vibrate(timings, -1);
        }
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
        // For on/off motors, "amp" from here on is a pulse level, not 0-255.
        if (!graded) amp = levelFor(amp);
        if (amp == 0 || ttlMs == 0) {
            if (issuedAmp != 0) v.cancel();
            issuedAmp = 0;
            return;
        }
        long now = SystemClock.uptimeMillis();
        // Re-issue on a change, or before the previous effect runs out.
        if (amp != issuedAmp || now - issuedAt >= ttlMs / 3) {
            if (graded) vibrate(v, ttlMs, amp);
            else pulse(v, ttlMs, amp);
            issuedAmp = amp;
            issuedAt = now;
        }
    }

    static int levelFor(int amp) {
        if (amp < ON_OFF_THRESHOLD) return LEVEL_OFF;
        if (amp < LIGHT_BELOW) return LEVEL_LIGHT;
        if (amp < MEDIUM_BELOW) return LEVEL_MEDIUM;
        return LEVEL_STRONG;
    }

    /** Run an on/off motor at a pulse level for about ms milliseconds. */
    @SuppressWarnings("deprecation")
    private static void pulse(Vibrator v, int ms, int level) {
        int[] cycle = PULSE[level];
        if (cycle == null) {
            vibrate(v, ms, 255);
            return;
        }
        int period = cycle[0] + cycle[1];
        int cycles = Math.max(1, Math.min((ms + period - 1) / period, (MAX_PATTERN - 1) / 2));
        long[] timings = new long[1 + 2 * cycles];
        timings[0] = 0; // start with the motor on
        for (int i = 0; i < cycles; i++) {
            timings[1 + 2 * i] = cycle[0];
            timings[2 + 2 * i] = cycle[1];
        }
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createWaveform(timings, -1));
        } else {
            v.vibrate(timings, -1);
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
