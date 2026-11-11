package com.aipc.gamepadbridge;

import android.content.Context;
import android.util.Log;

import java.io.File;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The user-editable button mapping: which gamepad keycodes drive which PS5
 * buttons, plus the L2/R2 trigger mode.
 *
 * Owns the single source of truth for:
 *   - the PS5 button table (bits + display names)
 *   - the key -> bit map (many keys may drive one bit; a key drives at
 *     most one bit)
 *   - the L2/R2 trigger mode (pressure = analog axes, button = key events)
 *   - persistence: one INI profile file per controller, loaded when that
 *     controller connects and saved on every edit in the mapping screen.
 *
 * A fresh install (no profile file) is exactly the stock Xbox mapping the
 * app always had, so behavior is unchanged until the user edits it.
 */
public final class KeyMap {
    private static final String TAG = "KeyMap";

    /** PS5 button table: one row per entry, in the mapping screen. */
    public static final int[] PS5_BITS = {
            PadFrame.BTN_L3, PadFrame.BTN_R3, PadFrame.BTN_OPTIONS,
            PadFrame.BTN_UP, PadFrame.BTN_RIGHT, PadFrame.BTN_DOWN, PadFrame.BTN_LEFT,
            PadFrame.BTN_L2, PadFrame.BTN_R2, PadFrame.BTN_L1, PadFrame.BTN_R1,
            PadFrame.BTN_TRIANGLE, PadFrame.BTN_CIRCLE, PadFrame.BTN_CROSS,
            PadFrame.BTN_SQUARE, PadFrame.BTN_CREATE, PadFrame.BTN_SHARE,
    };

    public static String buttonName(int bit) {
        switch (bit) {
            case PadFrame.BTN_L3:       return "L3";
            case PadFrame.BTN_R3:       return "R3";
            case PadFrame.BTN_OPTIONS:  return "OPTIONS";
            case PadFrame.BTN_UP:       return "UP";
            case PadFrame.BTN_RIGHT:    return "RIGHT";
            case PadFrame.BTN_DOWN:     return "DOWN";
            case PadFrame.BTN_LEFT:     return "LEFT";
            case PadFrame.BTN_L2:       return "L2";
            case PadFrame.BTN_R2:       return "R2";
            case PadFrame.BTN_L1:       return "L1";
            case PadFrame.BTN_R1:       return "R1";
            case PadFrame.BTN_TRIANGLE: return "TRIANGLE";
            case PadFrame.BTN_CIRCLE:   return "CIRCLE";
            case PadFrame.BTN_CROSS:    return "CROSS";
            case PadFrame.BTN_SQUARE:   return "SQUARE";
            case PadFrame.BTN_CREATE:   return "CREATE (PS)";
            case PadFrame.BTN_SHARE:    return "SHARE";
            default:                    return "0x" + Integer.toHexString(bit);
        }
    }

    /** Stock Xbox mapping (the app's original hardcoded table). */
    private static final int[][] STOCK = {
            { 96,  PadFrame.BTN_CROSS },
            { 97,  PadFrame.BTN_CIRCLE },
            { 99,  PadFrame.BTN_SQUARE },
            { 100, PadFrame.BTN_TRIANGLE },
            { 102, PadFrame.BTN_L1 },
            { 103, PadFrame.BTN_R1 },
            { 106, PadFrame.BTN_L3 },
            { 107, PadFrame.BTN_R3 },
            { 19,  PadFrame.BTN_UP },
            { 20,  PadFrame.BTN_DOWN },
            { 21,  PadFrame.BTN_LEFT },
            { 22,  PadFrame.BTN_RIGHT },
            { 108, PadFrame.BTN_OPTIONS },
            { 109, PadFrame.BTN_SHARE },
            { 110, PadFrame.BTN_CREATE },
    };

    private final HashMap<Integer, Integer> keyToBit = new HashMap<>();
    private boolean triggersAsButtons = false;

    public KeyMap() {
        for (int[] e : STOCK) {
            keyToBit.put(e[0], e[1]);
        }
    }

    /** Bit a keycode drives, or 0 if it drives nothing. */
    public int bitFor(int keyCode) {
        Integer b = keyToBit.get(keyCode);
        return b == null ? 0 : b;
    }

    /** All keycodes driving a bit (may be empty). */
    public java.util.List<Integer> keysForBit(int bit) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (Map.Entry<Integer, Integer> e : keyToBit.entrySet()) {
            if (e.getValue() == bit) out.add(e.getKey());
        }
        Collections.sort(out);
        return out;
    }

    /**
     * Point a keycode at a bit. A key drives at most one bit: if it drove
     * another, it is unbound from that one (the old button's field goes
     * dim). Many keys may drive the same bit (user's choice).
     */
    public void bind(int keyCode, int bit) {
        Integer old = keyToBit.get(keyCode);
        if (old != null && old.intValue() != bit) {
            keyToBit.remove(keyCode);
        }
        keyToBit.put(keyCode, bit);
    }

    /** Unbind a whole bit (every key that drove it). */
    public void unbindBit(int bit) {
        java.util.List<Integer> keys = keysForBit(bit);
        for (int k : keys) keyToBit.remove(k);
    }

    public boolean isTriggersAsButtons() { return triggersAsButtons; }
    public void setTriggersAsButtons(boolean v) { triggersAsButtons = v; }

    public void reset() {
        keyToBit.clear();
        for (int[] e : STOCK) keyToBit.put(e[0], e[1]);
        triggersAsButtons = false;
    }

    public int size() { return keyToBit.size(); }

    // ---- persistence: one INI profile file per controller ----
    //
    // The keymap is stored as a small INI file per controller, named by the
    // controller's (sanitized) name, under <files>/profiles/. It is loaded
    // when that controller connects and saved on every edit in the mapping
    // screen. A controller with no file yet starts at the stock map.

    /** The on-disk directory holding the profile files. */
    static File profilesDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(base, "profiles");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Sanitize a controller name into a safe filename component. */
    static String safeKey(String name) {
        if (name == null) name = "";
        String s = name.trim();
        if (s.isEmpty()) return "default";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') sb.append(c);
            else sb.append('_');
        }
        String key = sb.toString();
        if (key.length() > 48) key = key.substring(0, 48);
        return key;
    }

    /**
     * Load the profile for this controller into the in-memory map. A
     * controller with no saved file yet starts at the stock map.
     */
    public void loadProfile(Context ctx, String key) {
        try {
            File f = new File(profilesDir(ctx), key + ".ini");
            if (!f.exists()) {
                reset(); // no file yet: fresh controller = stock map
                return;
            }
            java.util.List<String> lines = new java.util.ArrayList<>();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
            }
            keyToBit.clear();
            triggersAsButtons = false;
            String section = "";
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith(";")) continue;
                if (t.startsWith("[") && t.endsWith("]")) {
                    section = t.substring(1, t.length() - 1).trim();
                    continue;
                }
                if (section.equals("map")) {
                    int eq = t.indexOf('=');
                    if (eq < 0) continue;
                    try {
                        int k = Integer.parseInt(t.substring(0, eq).trim());
                        int b = Integer.parseInt(t.substring(eq + 1).trim());
                        keyToBit.put(k, b);
                    } catch (Exception e) {
                        Log.w(TAG, "loadProfile: bad line " + t);
                    }
                } else if (section.equals("options")) {
                    int eq = t.indexOf('=');
                    if (eq >= 0 && t.substring(0, eq).trim().equals("triggersAsButtons")) {
                        triggersAsButtons = t.substring(eq + 1).trim().equals("1");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "loadProfile: " + e);
        }
    }

    /** Save the in-memory map as the profile for this controller. */
    public void saveProfile(Context ctx, String key, String displayName) {
        try {
            File f = new File(profilesDir(ctx), key + ".ini");
            StringBuilder sb = new StringBuilder();
            sb.append("; GhostPad keymap profile\n");
            sb.append("; controller: ").append(displayName == null ? "default" : displayName).append("\n");
            sb.append("; generated: ").append(new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date())).append("\n");
            sb.append("[map]\n");
            java.util.List<Map.Entry<Integer, Integer>> entries =
                    new java.util.ArrayList<>(keyToBit.entrySet());
            entries.sort((a, b) -> a.getKey() - b.getKey());
            for (Map.Entry<Integer, Integer> e : entries) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append("\n");
            }
            sb.append("[options]\n");
            sb.append("triggersAsButtons=").append(triggersAsButtons ? "1" : "0").append("\n");
            try (java.io.Writer w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(f), "UTF-8")) {
                w.write(sb.toString());
            }
        } catch (Exception e) {
            Log.w(TAG, "saveProfile: " + e);
        }
    }
}
