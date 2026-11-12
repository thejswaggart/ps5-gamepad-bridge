package com.aipc.gamepadbridge;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-level owner of the KeyMap + PadMapper + PadServer, controller
 * tracking, pad input routing, and the "send payload to PS5" job.
 *
 * Why this exists: the PadServer binds a TCP port for the life of the
 * process. If it were created in Activity.onCreate, an Activity recreation
 * (rotation / config change / relaunch) would build a SECOND PadServer that
 * fails to bind (EADDRINUSE) while the FIRST keeps streaming the OLD
 * PadMapper's state. Owning everything here (one per process) makes it
 * survive recreation, and lets every screen share one status model.
 *
 * Note: Android only delivers gamepad events to the focused window, so
 * every screen forwards pad input here (see PadActivity). Input stops
 * when the app leaves the foreground or the screen turns off.
 */
public class GamepadBridgeApp extends Application {

    public static final int SEND_IDLE = 0;
    public static final int SEND_SEARCHING = 1;
    public static final int SEND_SENDING = 2;
    public static final int SEND_DONE = 3;
    public static final int SEND_FAILED = 4;

    public static final int DEFAULT_LOADER_PORT = 9021;

    private static final String PREFS = "bridge";
    private static final String PREF_LAST_IP = "last_ps5_ip";
    private static final String PREF_MANUAL_IP = "manual_ps5_ip";
    private static final String PREF_PORT = "loader_port";

    // Axes fed to the mapper: sticks, D-pad hat, Xbox analog triggers.
    private static final int[] MAP_AXES = {
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
            MotionEvent.AXIS_GAS, MotionEvent.AXIS_BRAKE,
    };

    private static GamepadBridgeApp instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private KeyMap keyMap;
    private PadMapper mapper;
    private PadServer server;
    private String currentProfileKey = "default";
    private String currentProfileName = "default";

    // Connected external gamepads: device id -> name (insertion order).
    private final Map<Integer, String> gamepads = new LinkedHashMap<>();
    private final List<Runnable> statusListeners = new ArrayList<>();

    private boolean lastClientConnected = false;
    private boolean lastServerRunning = true;

    private volatile int sendState = SEND_IDLE;
    private volatile String sendIp = null;
    private volatile String sendDetail = "";

    private volatile boolean logInput = false;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        AppLog.i("Gamepad Bridge started (Android " + Build.VERSION.RELEASE
                + ", API " + Build.VERSION.SDK_INT + ")");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "gamepad-bridge:pad-server");
        keyMap = new KeyMap();
        mapper = new PadMapper(keyMap);
        server = new PadServer(mapper, wl, () -> main.post(this::onServerStatus));
        server.start();

        InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
        im.registerInputDeviceListener(new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int id) {
                onDeviceChanged(id);
            }

            @Override
            public void onInputDeviceChanged(int id) {
                onDeviceChanged(id);
            }

            @Override
            public void onInputDeviceRemoved(int id) {
                String name = gamepads.remove(id);
                if (name != null) {
                    AppLog.i("Controller disconnected: " + name);
                    mapper.setPadConnected(!gamepads.isEmpty());
                    notifyStatus();
                }
            }
        }, main);

        // Pads already connected at launch.
        int[] ids = InputDevice.getDeviceIds();
        if (ids != null) {
            for (int id : ids) onDeviceChanged(id);
        }
    }

    public static GamepadBridgeApp get() {
        return instance;
    }

    // ---- controller tracking ----

    private void onDeviceChanged(int id) {
        if (DeviceProbe.isGamepadDevice(id)) {
            if (gamepads.containsKey(id)) return;
            InputDevice d = InputDevice.getDevice(id);
            String name = d != null ? d.getName() : "Controller";
            gamepads.put(id, name);
            AppLog.i("Controller connected: " + name);
            loadProfileFor(name);
            mapper.setPadConnected(true);
            notifyStatus();
        } else if (gamepads.containsKey(id)) {
            String name = gamepads.remove(id);
            AppLog.i("Controller disconnected: " + name);
            mapper.setPadConnected(!gamepads.isEmpty());
            notifyStatus();
        }
    }

    /** Name of the first connected controller, or null if none. */
    public String getControllerName() {
        for (String n : gamepads.values()) return n;
        return null;
    }

    // ---- profiles ----

    /** Load a controller's saved profile into the live KeyMap. */
    private void loadProfileFor(String name) {
        currentProfileKey = KeyMap.safeKey(name);
        currentProfileName = (name == null || name.isEmpty()) ? "default" : name;
        keyMap.loadProfile(this, currentProfileKey);
    }

    /** Persist the current KeyMap as the active controller's profile file. */
    public void saveCurrent() {
        keyMap.saveProfile(this, currentProfileKey, currentProfileName);
    }

    public String getCurrentProfileKey() { return currentProfileKey; }
    public String getCurrentProfileName() { return currentProfileName; }

    // ---- pad input routing ----

    /** True if this key event comes from a gamepad (not the phone's own keys). */
    public boolean isPadKey(KeyEvent e) {
        return e.isFromSource(InputDevice.SOURCE_GAMEPAD)
                || e.isFromSource(InputDevice.SOURCE_JOYSTICK)
                || DeviceProbe.isGamepadDevice(e.getDeviceId());
    }

    public boolean isPadMotion(MotionEvent e) {
        return e.isFromSource(InputDevice.SOURCE_JOYSTICK)
                || e.isFromSource(InputDevice.SOURCE_GAMEPAD)
                || DeviceProbe.isGamepadDevice(e.getDeviceId());
    }

    public void onPadKey(KeyEvent e) {
        int action = e.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return;
        boolean down = action == KeyEvent.ACTION_DOWN;
        mapper.onKey(e.getKeyCode(), down);
        if (logInput && down && e.getRepeatCount() == 0) {
            int bit = keyMap.bitFor(e.getKeyCode());
            AppLog.i("Pressed " + KeyMap.keyLabel(e.getKeyCode())
                    + " (key " + e.getKeyCode() + ") -> "
                    + (bit == 0 ? "not mapped" : KeyMap.buttonLabel(bit)));
        }
    }

    public void onPadMotion(MotionEvent e) {
        for (int axis : MAP_AXES) {
            mapper.onAxis(axis, e.getAxisValue(axis)); // 0.0 is a valid release
        }
    }

    public void setLogInput(boolean v) { logInput = v; }
    public boolean isLogInput() { return logInput; }

    // ---- status ----

    private void onServerStatus() {
        boolean c = server.isClientConnected();
        if (c != lastClientConnected) {
            lastClientConnected = c;
            AppLog.i(c ? "PS5 connected from " + server.getClientIp() : "PS5 disconnected");
        }
        boolean r = server.isRunning();
        if (r != lastServerRunning) {
            lastServerRunning = r;
            if (!r) AppLog.i("Phone server stopped (port " + PadServer.PORT + " unavailable)");
        }
        notifyStatus();
    }

    public void addStatusListener(Runnable r) { statusListeners.add(r); }
    public void removeStatusListener(Runnable r) { statusListeners.remove(r); }

    private void notifyStatus() {
        main.post(() -> {
            for (Runnable r : new ArrayList<>(statusListeners)) r.run();
        });
    }

    // ---- settings ----

    public String getManualIp() { return prefs.getString(PREF_MANUAL_IP, ""); }
    public void setManualIp(String ip) { prefs.edit().putString(PREF_MANUAL_IP, ip).apply(); }
    public int getLoaderPort() { return prefs.getInt(PREF_PORT, DEFAULT_LOADER_PORT); }
    public void setLoaderPort(int p) { prefs.edit().putInt(PREF_PORT, p).apply(); }

    // ---- sending the payload to the PS5 ----

    public int getSendState() { return sendState; }
    public String getSendIp() { return sendIp; }
    public String getSendDetail() { return sendDetail; }

    private void setSend(int state, String ip, String detail) {
        sendState = state;
        sendIp = ip;
        sendDetail = detail;
        notifyStatus();
    }

    /**
     * Find the PS5's ELF loader and stream the bundled payload to it.
     * Order: the address under Advanced (if set), else the last PS5 that
     * worked, else a sweep of the phone's /24.
     */
    public void sendPayload() {
        if (sendState == SEND_SEARCHING || sendState == SEND_SENDING) return;
        final String manual = getManualIp().trim();
        final int port = getLoaderPort();
        setSend(SEND_SEARCHING, null, "");
        new Thread(() -> {
            String target;
            if (!manual.isEmpty()) {
                target = manual;
            } else {
                String lan = NetUtil.lanIp();
                if (lan == null) {
                    fail(null, "This phone isn't on a network. Connect it to the same Wi-Fi as your PS5.");
                    return;
                }
                String last = prefs.getString(PREF_LAST_IP, null);
                if (last != null && NetUtil.sameSubnet(last, lan) && NetUtil.isOpen(last, port, 800)) {
                    target = last;
                } else {
                    String base = NetUtil.subnetBase(lan);
                    AppLog.i("Searching " + base + ".1-254 for the ELF loader on port " + port);
                    target = NetUtil.findFirstOpen(base, port);
                    if (target == null) {
                        fail(null, "No PS5 found. Start the ELF loader on your PS5 and try again, "
                                + "or enter its address under Advanced.");
                        return;
                    }
                }
            }
            setSend(SEND_SENDING, target, "");
            AppLog.i("Sending payload to " + target + ":" + port);
            try {
                byte[] payload = Ps5Deployer.loadPayload(this);
                Ps5Deployer.deploy(target, port, payload);
                prefs.edit().putString(PREF_LAST_IP, target).apply();
                AppLog.i("Payload sent (" + payload.length + " bytes)");
                setSend(SEND_DONE, target, "");
            } catch (Exception e) {
                AppLog.i("Send failed: " + e);
                fail(target, friendlyError(e, target, port));
            }
        }, "PayloadSender").start();
    }

    private void fail(String ip, String msg) {
        if (ip == null) AppLog.i("Send failed: " + msg);
        setSend(SEND_FAILED, ip, msg);
    }

    private static String friendlyError(Exception e, String ip, int port) {
        if (e instanceof FileNotFoundException) {
            return "The PS5 payload is missing from this build. Rebuild the app with 'make android'.";
        }
        if (e instanceof SocketTimeoutException || e instanceof ConnectException
                || e instanceof NoRouteToHostException) {
            return "Couldn't reach " + ip + " on port " + port
                    + ". Check that the ELF loader is open on your PS5.";
        }
        String m = e.getMessage();
        return "Sending failed" + (m != null ? ": " + m : ".");
    }

    // ---- accessors ----

    public KeyMap getKeyMap() { return keyMap; }
    public PadMapper getMapper() { return mapper; }
    public PadServer getServer() { return server; }
}
