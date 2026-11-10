package com.aipc.gamepadbridge;

import android.util.Log;
import android.view.MotionEvent;

/**
 * Maps Android app-level gamepad keycodes/axes to PS5 canonical bits and
 * 0-255 stick/trigger values. Single source of truth for the wire state;
 * all access is synchronized.
 *
 * Key mapping (Xbox confirmed; app-level keycodes):
 *   A(96)->CIRCLE B(97)->CROSS X(99)->SQUARE Y(100)->TRIANGLE
 *   L1(102)->L1 R1(103)->R1 L3(106)->L3 R3(107)->R3
 *   DPAD 19/20/21/22 -> UP/DOWN/LEFT/RIGHT
 *   START(108)->OPTIONS SELECT(109)->SHARE MODE(110)->CREATE(PS)
 * Axes:
 *   AXIS_X(0)->lx AXIS_Y(1)->ly AXIS_Z(2)->rx AXIS_RZ(5)->ry  (float -1..1, deadzone 0.08)
 *   AXIS_GAS(10)->r2 AXIS_BRAKE(11)->l2  (float 0..1)
 * Xbox L2/R2 are analog only (no digital click) -> no L2/R2 bits from keys.
 */
public final class PadMapper {
    private static final String TAG = "PadMapper";
    private static final float DEADZONE = 0.08f;

    private static final int KEY_A = 96;
    private static final int KEY_B = 97;
    private static final int KEY_X = 99;
    private static final int KEY_Y = 100;
    private static final int KEY_L1 = 102;
    private static final int KEY_R1 = 103;
    private static final int KEY_L2 = 104; // no digital click on Xbox; probe only
    private static final int KEY_R2 = 105; // no digital click on Xbox; probe only
    private static final int KEY_L3 = 106; // BUTTON_THUMBL
    private static final int KEY_R3 = 107; // BUTTON_THUMBR
    private static final int KEY_START = 108;
    private static final int KEY_SELECT = 109;
    private static final int KEY_MODE = 110;
    private static final int KEY_DPAD_UP = 19;
    private static final int KEY_DPAD_DOWN = 20;
    private static final int KEY_DPAD_LEFT = 21;
    private static final int KEY_DPAD_RIGHT = 22;

    private final PadState state = new PadState();

    public synchronized void onKey(int keyCode, boolean pressed) {
        int bit = keyToBit(keyCode);
        if (bit == 0) {
            // Probe: log every unmapped keycode so P4 can confirm START/GUIDE/SELECT.
            Log.i(TAG, "key " + keyCode + " (unmapped) " + (pressed ? "down" : "up"));
            return;
        }
        if (pressed) {
            state.buttons |= bit;
        } else {
            state.buttons &= ~bit;
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
                state.r2 = triggerToU8(value);
                break;
            case MotionEvent.AXIS_BRAKE: // 11 Xbox L2 (analog)
                state.l2 = triggerToU8(value);
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
    }

    private static int keyToBit(int keyCode) {
        switch (keyCode) {
            case KEY_A:          return PadFrame.BTN_CROSS;
            case KEY_B:          return PadFrame.BTN_CIRCLE;
            case KEY_X:          return PadFrame.BTN_SQUARE;
            case KEY_Y:          return PadFrame.BTN_TRIANGLE;
            case KEY_L1:         return PadFrame.BTN_L1;
            case KEY_R1:         return PadFrame.BTN_R1;
            case KEY_L3:         return PadFrame.BTN_L3;
            case KEY_R3:         return PadFrame.BTN_R3;
            case KEY_DPAD_UP:    return PadFrame.BTN_UP;
            case KEY_DPAD_DOWN:  return PadFrame.BTN_DOWN;
            case KEY_DPAD_LEFT:  return PadFrame.BTN_LEFT;
            case KEY_DPAD_RIGHT: return PadFrame.BTN_RIGHT;
            case KEY_START:      return PadFrame.BTN_OPTIONS;
            case KEY_SELECT:     return PadFrame.BTN_SHARE;
            case KEY_MODE:       return PadFrame.BTN_CREATE;
            default:             return 0;
        }
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
