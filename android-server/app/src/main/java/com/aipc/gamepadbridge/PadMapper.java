package com.aipc.gamepadbridge;

import android.util.Log;
import android.view.MotionEvent;

/**
 * Maps Android app-level gamepad keycodes/axes to PS5 canonical bits and
 * 0-255 stick/trigger values. Single source of truth for the wire state;
 * all access is synchronized.
 *
 * The key -> button table is the user-editable KeyMap (mapping screen);
 * its fresh-install default is the stock Xbox table:
 *   A(96)->CROSS B(97)->CIRCLE X(99)->SQUARE Y(100)->TRIANGLE
 *   L1(102)->L1 R1(103)->R1 L3(106)->L3 R3(107)->R3
 *   DPAD 19/20/21/22 -> UP/DOWN/LEFT/RIGHT
 *   START(108)->OPTIONS SELECT(109)->SHARE MODE(110)->CREATE(PS)
 *
 * Axes (fixed, not user-mapped):
 *   AXIS_X(0)->lx AXIS_Y(1)->ly AXIS_Z(2)->rx AXIS_RZ(5)->ry  (float -1..1, deadzone 0.08)
 *   AXIS_GAS(10)->r2 AXIS_BRAKE(11)->l2  (float 0..1)  -- pressure mode only
 *   AXIS_HAT_X(3)/AXIS_HAT_Y(4) -> dpad bits (mouse motion)
 */
public final class PadMapper {
    private static final String TAG = "PadMapper";
    private static final float DEADZONE = 0.08f;

    private final KeyMap keyMap;
    private final PadState state = new PadState();
    private volatile boolean padConnected = false;

    public PadMapper(KeyMap keyMap) {
        this.keyMap = keyMap;
    }

    public synchronized void onKey(int keyCode, boolean pressed) {
        int bit = keyMap.bitFor(keyCode);
        if (bit == 0) {
            // Probe: log every unmapped keycode.
            Log.i(TAG, "key " + keyCode + " (unmapped) " + (pressed ? "down" : "up"));
            return;
        }
        if (pressed) {
            state.buttons |= bit;
        } else {
            state.buttons &= ~bit;
        }
        // Button mode: mirror the trigger press into the u8 fields so both
        // wire representations agree (the PS5 payload also derives the
        // bit from the u8 threshold).
        if (keyMap.isTriggersAsButtons()) {
            if (bit == PadFrame.BTN_L2) {
                state.l2 = pressed ? 255 : 0;
            } else if (bit == PadFrame.BTN_R2) {
                state.r2 = pressed ? 255 : 0;
            }
        }
        Log.i(TAG, "key " + keyCode + " -> 0x" + Integer.toHexString(bit)
                + " " + (pressed ? "down" : "up"));
    }

    public synchronized void onAxis(int axis, float value) {
        switch (axis) {
            case MotionEvent.AXIS_X:     // 0 left stick x
                state.lx = stickToU8(value);
                break;
            case MotionEvent.AXIS_Y:     // 1 left stick y
                state.ly = stickToU8(value);
                break;
            case MotionEvent.AXIS_Z:     // 2 right stick x
                state.rx = stickToU8(value);
                break;
            case MotionEvent.AXIS_RZ:    // 5 right stick y
                state.ry = stickToU8(value);
                break;
            case MotionEvent.AXIS_GAS:   // 10 Xbox R2 (analog)
            case MotionEvent.AXIS_BRAKE: // 11 Xbox L2 (analog)
                // Pressure mode: axes drive the u8 values (the PS5 payload
                // derives the L2/R2 bits from the u8 threshold). Button
                // mode: keys drive l2/r2; ignore the axes.
                if (!keyMap.isTriggersAsButtons()) {
                    if (axis == MotionEvent.AXIS_GAS) {
                        state.r2 = triggerToU8(value);
                    } else {
                        state.l2 = triggerToU8(value);
                    }
                }
                break;
            case MotionEvent.AXIS_HAT_X: // 3 D-pad x (arrives as mouse motion; -1=LEFT +1=RIGHT)
                if (value < -0.5f) {
                    state.buttons |= PadFrame.BTN_LEFT;
                } else if (value > 0.5f) {
                    state.buttons |= PadFrame.BTN_RIGHT;
                } else {
                    state.buttons &= ~(PadFrame.BTN_LEFT | PadFrame.BTN_RIGHT);
                }
                break;
            case MotionEvent.AXIS_HAT_Y: // 4 D-pad y (mouse motion; -1=UP +1=DOWN, flip if user reports swap)
                if (value < -0.5f) {
                    state.buttons |= PadFrame.BTN_UP;
                } else if (value > 0.5f) {
                    state.buttons |= PadFrame.BTN_DOWN;
                } else {
                    state.buttons &= ~(PadFrame.BTN_UP | PadFrame.BTN_DOWN);
                }
                break;
            default:
                break;
        }
    }

    /** Thread-safe snapshot of the current state. */
    public synchronized void getState(PadState out) {
        out.copyFrom(state);
        out.connected = padConnected ? 1 : 0;
    }

    /**
     * Set whether a physical gamepad is connected to the phone.
     * On disconnect, all input is cleared so a later reconnect starts clean.
     */
    public synchronized void setPadConnected(boolean connected) {
        if (connected == padConnected) return;
        padConnected = connected;
        Log.i(TAG, "pad link " + (connected ? "CONNECTED" : "DISCONNECTED"));
        if (!connected) {
            state.buttons = 0;
            state.lx = state.ly = state.rx = state.ry = 128;
            state.l2 = state.r2 = 0;
        }
    }

    public synchronized boolean isPadConnected() {
        return padConnected;
    }

    /** Stick float -1..1 (center 0) -> u8 0-255, 128=center, with deadzone. */
    private static int stickToU8(float a) {
        if (a > 1.0f) a = 1.0f;
        if (a < -1.0f) a = -1.0f;
        if (Math.abs(a) < DEADZONE) a = 0.0f;
        return Math.round(a * 127.0f) + 128;
    }

    /** Trigger float 0..1 -> u8 0-255. */
    private static int triggerToU8(float a) {
        if (a > 1.0f) a = 1.0f;
        if (a < 0.0f) a = 0.0f;
        return Math.round(a * 255.0f);
    }
}
