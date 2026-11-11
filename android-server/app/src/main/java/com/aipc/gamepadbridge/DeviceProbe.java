package com.aipc.gamepadbridge;

import android.os.Build;
import android.util.Log;
import android.view.InputDevice;

/**
 * Shared "is this a real external gamepad?" detection.
 * Used by the main screen (device list) and the mapping screen
 * (filtering capture key events).
 */
public final class DeviceProbe {
    private static final String TAG = "DeviceProbe";

    private DeviceProbe() {}

    /** True if this is an external device (USB/Bluetooth), not built-in. */
    public static boolean isExternalDevice(InputDevice d) {
        if (d == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return d.isExternal();
        }
        try {
            java.lang.reflect.Method m = d.getClass().getMethod("isExternal");
            m.setAccessible(true);
            Object r = m.invoke(d);
            return (r instanceof Boolean) ? (Boolean) r : false;
        } catch (Exception e) {
            Log.w(TAG, "isExternal reflect failed", e);
            return false;
        }
    }

    /** True if this (non-virtual) device id is an external gamepad-class device. */
    public static boolean isGamepadDevice(int id) {
        if (id == -1) return false; // -1 == InputDevice virtual device id
        InputDevice d = InputDevice.getDevice(id);
        if (d == null) return false;
        if (!isExternalDevice(d)) return false;
        int src = d.getSources();
        return (src & (InputDevice.SOURCE_GAMEPAD
                | InputDevice.SOURCE_JOYSTICK
                | InputDevice.SOURCE_DPAD)) != 0;
    }
}
