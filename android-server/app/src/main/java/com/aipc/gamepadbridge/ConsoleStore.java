package com.aipc.gamepadbridge;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The known consoles and which one is active, persisted as JSON in
 * SharedPreferences. Discovery (a background thread) adds/updates entries;
 * the UI thread reads them, so every method is synchronized.
 *
 * "Active" is the single console the app deploys to and accepts input from;
 * {@link PadServer} refuses every other dialer while one is active.
 */
public final class ConsoleStore {

    private static final String KEY_LIST = "consoles";
    private static final String KEY_ACTIVE = "active_console";

    private final SharedPreferences prefs;
    private final List<Console> consoles = new ArrayList<>();
    private String activeAddress;

    public ConsoleStore(SharedPreferences prefs) {
        this.prefs = prefs;
        load();
    }

    private synchronized void load() {
        consoles.clear();
        String json = prefs.getString(KEY_LIST, null);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    Console c = Console.fromJson(arr.getJSONObject(i));
                    if (c.address != null && !c.address.isEmpty()) consoles.add(c);
                }
            } catch (JSONException ignored) {
            }
        }
        activeAddress = prefs.getString(KEY_ACTIVE, null);
    }

    private synchronized void save() {
        JSONArray arr = new JSONArray();
        try {
            for (Console c : consoles) arr.put(c.toJson());
        } catch (JSONException ignored) {
        }
        SharedPreferences.Editor e = prefs.edit();
        e.putString(KEY_LIST, arr.toString());
        if (activeAddress == null) e.remove(KEY_ACTIVE);
        else e.putString(KEY_ACTIVE, activeAddress);
        e.apply();
    }

    public synchronized List<Console> all() {
        return new ArrayList<>(consoles);
    }

    public synchronized boolean isEmpty() {
        return consoles.isEmpty();
    }

    public synchronized Console byAddress(String address) {
        if (address == null) return null;
        for (Console c : consoles) if (address.equals(c.address)) return c;
        return null;
    }

    /**
     * Add a new console, or refresh the one that already has this address.
     * User-set fields (nickname, a type the user chose) are kept; discovery
     * fields (hostname, lastSeen) are refreshed. Returns the stored console.
     */
    public synchronized Console addOrUpdate(Console incoming) {
        Console existing = byAddress(incoming.address);
        if (existing == null) {
            consoles.add(incoming);
            save();
            return incoming;
        }
        if (incoming.hostname != null) existing.hostname = incoming.hostname;
        if (existing.type == Console.TYPE_UNKNOWN) existing.type = incoming.type;
        if (incoming.loaderPort > 0) existing.loaderPort = incoming.loaderPort;
        if (incoming.lastSeenMs > 0) existing.lastSeenMs = incoming.lastSeenMs;
        save();
        return existing;
    }

    public synchronized void remove(String address) {
        for (int i = consoles.size() - 1; i >= 0; i--) {
            if (consoles.get(i).address.equals(address)) consoles.remove(i);
        }
        if (address.equals(activeAddress)) activeAddress = null;
        save();
    }

    public synchronized void setNickname(String address, String nickname) {
        Console c = byAddress(address);
        if (c != null) {
            c.nickname = (nickname == null || nickname.isEmpty()) ? null : nickname;
            save();
        }
    }

    public synchronized void setType(String address, int type) {
        Console c = byAddress(address);
        if (c != null) {
            c.type = type;
            save();
        }
    }

    public synchronized String getActiveAddress() {
        return activeAddress;
    }

    public synchronized Console getActive() {
        return activeAddress == null ? null : byAddress(activeAddress);
    }

    public synchronized void setActiveAddress(String address) {
        activeAddress = address;
        save();
    }
}
