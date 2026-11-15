package com.aipc.gamepadbridge;

import java.nio.charset.StandardCharsets;

/**
 * 60-byte little-endian HelloFrame, sent console -> phone ONCE right after
 * the payload connects, on the PadFrame connection. Mirrors client/padframe.h;
 * keep them in lockstep.
 *
 * It gives a console a durable identity (its Wi-Fi MAC) so the phone keeps
 * recognising it across DHCP address changes, plus display fields.
 *
 *   off  sz  field
 *   0    4   magic    = 0x49485047 ("GPHI" LE)
 *   4    1   version  u8
 *   5    1   type     u8 1=PS4, 2=PS5 (matches Console.TYPE_PS4/PS5)
 *   6    6   mac      Wi-Fi MAC; all-zero if unavailable
 *  12   16  firmware ASCII, NUL-padded; empty if unavailable
 *  28   32  name     ASCII console name, NUL-padded
 */
public final class HelloFrame {
    public static final int MAGIC = 0x49485047; // "GPHI"
    public static final int SIZE = 60;
    public static final int FW_LEN = 16;
    public static final int NAME_LEN = 32;

    public int version;
    public int type;               // 1=PS4, 2=PS5, 0=unknown
    public final byte[] mac = new byte[6];
    public String firmware = "";
    public String name = "";

    /** Decode at off, or return null if the magic doesn't match. */
    public static HelloFrame decode(byte[] buf, int off) {
        if (PadFrame.getU32(buf, off) != MAGIC) return null;
        HelloFrame f = new HelloFrame();
        f.version = buf[off + 4] & 0xFF;
        f.type = buf[off + 5] & 0xFF;
        System.arraycopy(buf, off + 6, f.mac, 0, 6);
        f.firmware = cString(buf, off + 12, FW_LEN);
        f.name = cString(buf, off + 28, NAME_LEN);
        return f;
    }

    /** MAC as "aa:bb:cc:dd:ee:ff" (lowercase), or null if all-zero/unknown. */
    public String macString() {
        return macString(mac);
    }

    /** Shared formatter so the store and the frame agree on MAC spelling. */
    public static String macString(byte[] mac) {
        if (mac == null || mac.length != 6) return null;
        int acc = 0;
        for (byte b : mac) acc |= (b & 0xFF);
        if (acc == 0) return null; // all-zero = console couldn't read its MAC
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02x", mac[i] & 0xFF));
        }
        return sb.toString();
    }

    /** Read a NUL-terminated/padded ASCII string from a fixed-width field. */
    private static String cString(byte[] b, int off, int len) {
        int end = off;
        int max = Math.min(off + len, b.length);
        while (end < max && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }
}
