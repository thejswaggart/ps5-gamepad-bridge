package com.aipc.gamepadbridge;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Process-wide event log shown on the Diagnostics screen. Holds only
 * significant events (controller / PS5 / payload), plus button presses
 * when the user turns that on. Every line is mirrored to logcat.
 */
public final class AppLog {
    private static final String TAG = "GamepadBridge";
    private static final int MAX_LINES = 300;

    public interface Listener {
        void onLogChanged();
    }

    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile Listener listener;

    private AppLog() {}

    public static void i(String msg) {
        Log.i(TAG, msg);
        synchronized (lines) {
            lines.addLast(FMT.format(new Date()) + "  " + msg);
            while (lines.size() > MAX_LINES) lines.removeFirst();
        }
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onLogChanged();
        });
    }

    public static String text() {
        StringBuilder sb = new StringBuilder();
        synchronized (lines) {
            for (String s : lines) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (lines) {
            lines.clear();
        }
        Listener l = listener;
        if (l != null) main.post(l::onLogChanged);
    }

    public static void setListener(Listener l) {
        listener = l;
    }
}
