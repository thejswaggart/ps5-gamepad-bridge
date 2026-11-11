package com.aipc.gamepadbridge;

import android.app.Application;
import android.content.Context;
import android.os.PowerManager;

/**
 * Process-level owner of the KeyMap + PadMapper + PadServer.
 *
 * Why this exists: the PadServer binds a TCP port for the life of the
 * process. If it were created in Activity.onCreate, an Activity recreation
 * (rotation / config change / relaunch) would build a SECOND PadServer that
 * fails to bind (EADDRINUSE) while the FIRST keeps streaming the OLD
 * PadMapper's state. Symptom: the client sees at-rest forever and the UI
 * shows "SERVER: down". Owning them here (one per process) makes them
 * survive recreation: input always updates the one shared mapper, and the
 * one surviving server streams it.
 */
public class GamepadBridgeApp extends Application {

    private static GamepadBridgeApp instance;

    private KeyMap keyMap;
    private PadMapper mapper;
    private PadServer server;
    private String currentProfileKey = "default";
    private String currentProfileName = "default";

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "gamepad-bridge:pad-server");
        keyMap = new KeyMap();
        mapper = new PadMapper(keyMap);
        server = new PadServer(mapper, wl, null);
        server.start();
    }

    /**
     * Called when a gamepad connects: remember which controller it is and
     * load that controller's saved profile into the live KeyMap.
     */
    public void onGamepadConnected(String name) {
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

    public static GamepadBridgeApp get() {
        return instance;
    }

    public KeyMap getKeyMap() {
        return keyMap;
    }

    public PadMapper getMapper() {
        return mapper;
    }

    public PadServer getServer() {
        return server;
    }
}
