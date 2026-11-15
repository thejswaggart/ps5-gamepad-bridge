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

    public synchronized Console byMac(String mac) {
        if (mac == null) return null;
        for (Console c : consoles) if (mac.equalsIgnoreCase(c.mac)) return c;
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

    /**
     * Apply a console's handshake (its {@link HelloFrame}) to the store,
     * keyed on the durable MAC. If an entry already carries this MAC (even at
     * a different, now-stale address) it's the same console that changed IP:
     * its address is updated and any separate IP-only entry for the new
     * address is merged in and dropped. Handshake fields (mac, firmware, and
     * the console-reported type) win over the phase-1 hostname guess; a
     * user-set nickname/type is preserved. Returns the canonical console.
     */
    public synchronized Console applyHello(String address, String mac,
                                           String firmware, String name, int type) {
        Console byAddr = byAddress(address);
        Console byMac = byMac(mac);
        Console target;
        if (byMac != null) {
            target = byMac;
            if (byAddr != null && byAddr != byMac) {
                // Merge the IP-only entry at this address into the MAC entry.
                if (target.nickname == null) target.nickname = byAddr.nickname;
                if (target.hostname == null) target.hostname = byAddr.hostname;
                if (byAddr.loaderPort > 0) target.loaderPort = byAddr.loaderPort;
                if (byAddr.lastSeenMs > target.lastSeenMs)
                    target.lastSeenMs = byAddr.lastSeenMs;
                removeEntry(byAddr);
            }
            target.address = address;
        } else if (byAddr != null) {
            target = byAddr;
        } else {
            target = new Console(address);
            consoles.add(target);
        }

        if (mac != null) target.mac = mac;
        if (firmware != null && !firmware.isEmpty()) target.firmware = firmware;
        if (name != null && !name.isEmpty()
                && (target.hostname == null || target.hostname.isEmpty())) {
            target.hostname = name; // the console's own reported name
        }
        if (type != Console.TYPE_UNKNOWN) target.type = type; // prefer handshake
        if (target.lastSeenMs == 0) target.lastSeenMs = System.currentTimeMillis();
        save();
        return target;
    }

    /** Remove one console object by identity, fixing the active pointer. */
    private synchronized void removeEntry(Console c) {
        consoles.remove(c);
        if (c.address != null && c.address.equals(activeAddress)) activeAddress = null;
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
