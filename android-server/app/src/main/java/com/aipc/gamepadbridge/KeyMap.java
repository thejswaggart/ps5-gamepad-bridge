package com.aipc.gamepadbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
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
 *   - persistence (SharedPreferences JSON)
 *
 * A fresh install (no prefs) is exactly the stock Xbox mapping the app
 * always had, so behavior is unchanged until the user edits it.
 */
public final class KeyMap {
    private static final String TAG = "KeyMap";
    private static final String PREFS = "keymap";
    private static final String KEY_JSON = "map";

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

    // ---- persistence (SharedPreferences, one JSON string) ----

    public void load(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, 0);
            String j = sp.getString(KEY_JSON, null);
            if (j == null) return; // fresh install: stock table
            JSONObject o = new JSONObject(j);
            JSONArray ks = o.optJSONArray("keys");
            if (ks != null) {
                keyToBit.clear();
                for (int i = 0; i < ks.length(); i++) {
                    JSONObject p = ks.optJSONObject(i);
                    if (p == null) continue;
                    keyToBit.put(p.getInt("k"), p.getInt("b"));
                }
            }
            triggersAsButtons = o.optBoolean("trigButtons", false);
        } catch (Exception e) {
            Log.w(TAG, "load: " + e);
        }
    }

    public void save(Context ctx) {
        try {
            JSONObject o = new JSONObject();
            JSONArray ks = new JSONArray();
            for (Map.Entry<Integer, Integer> e : keyToBit.entrySet()) {
                JSONObject p = new JSONObject();
                p.put("k", e.getKey());
                p.put("b", e.getValue());
                ks.put(p);
            }
            o.put("keys", ks);
            o.put("trigButtons", triggersAsButtons);
            ctx.getSharedPreferences(PREFS, 0)
               .edit().putString(KEY_JSON, o.toString()).commit();
        } catch (Exception e) {
            Log.w(TAG, "save: " + e);
        }
    }
}
