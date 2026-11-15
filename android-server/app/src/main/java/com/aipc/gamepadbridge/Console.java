package com.aipc.gamepadbridge;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A PlayStation on the LAN that the app can deploy a payload to and stream
 * controller input to. Identified by its IPv4 address when that's all we
 * know (phase 1); once the payload sends a {@link HelloFrame} its Wi-Fi MAC
 * becomes the durable key, so a DHCP address change no longer loses the
 * console's identity (phase 2).
 */
public final class Console {

    public static final int TYPE_UNKNOWN = 0;
    public static final int TYPE_PS4 = 1;
    public static final int TYPE_PS5 = 2;

    /** IPv4 address; the stable key only until a MAC is learned. */
    public String address;
    /** Wi-Fi MAC "aa:bb:cc:dd:ee:ff" from the handshake; the durable key
     *  across DHCP address changes once known, or null. */
    public String mac;
    /** Firmware string from the handshake, or null. */
    public String firmware;
    /** Reverse-DNS name ("PS5", "ps4pro"), or null. */
    public String hostname;
    /** PS4 / PS5 / unknown. Decides which payload is deployed. */
    public int type = TYPE_UNKNOWN;
    /** User-chosen label, or null. */
    public String nickname;
    /** ELF loader port to deploy to. */
    public int loaderPort;
    /** When discovery last saw the loader port open (ms since epoch), or 0. */
    public long lastSeenMs;

    public Console(String address) {
        this.address = address;
    }

    public Console(String address, int loaderPort) {
        this.address = address;
        this.loaderPort = loaderPort;
    }

    /** Best label to show: nickname, else hostname, else the address. */
    public String displayName() {
        if (nickname != null && !nickname.isEmpty()) return nickname;
        if (hostname != null && !hostname.isEmpty()) return hostname;
        return address;
    }

    public String typeLabel() {
        switch (type) {
            case TYPE_PS4: return "PS4";
            case TYPE_PS5: return "PS5";
            default: return "Console";
        }
    }

    /** Fill in the type from the hostname when it isn't set yet. */
    public void guessTypeFromHostname() {
        if (type != TYPE_UNKNOWN || hostname == null) return;
        String h = hostname.toLowerCase();
        if (h.contains("ps5")) type = TYPE_PS5;
        else if (h.contains("ps4")) type = TYPE_PS4;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("address", address);
        o.put("mac", mac);             // null drops the key
        o.put("firmware", firmware);   // null drops the key
        o.put("hostname", hostname);   // null drops the key
        o.put("type", type);
        o.put("nickname", nickname);   // null drops the key
        o.put("loaderPort", loaderPort);
        o.put("lastSeenMs", lastSeenMs);
        return o;
    }

    public static Console fromJson(JSONObject o) {
        Console c = new Console(o.optString("address", null));
        c.mac = o.has("mac") ? o.optString("mac", null) : null;
        c.firmware = o.has("firmware") ? o.optString("firmware", null) : null;
        c.hostname = o.has("hostname") ? o.optString("hostname", null) : null;
        c.type = o.optInt("type", TYPE_UNKNOWN);
        c.nickname = o.has("nickname") ? o.optString("nickname", null) : null;
        c.loaderPort = o.optInt("loaderPort", GamepadBridgeApp.DEFAULT_LOADER_PORT);
        c.lastSeenMs = o.optLong("lastSeenMs", 0);
        return c;
    }
}
