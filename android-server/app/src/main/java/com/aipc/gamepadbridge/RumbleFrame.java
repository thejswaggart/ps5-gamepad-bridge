package com.aipc.gamepadbridge;

/**
 * 8-byte little-endian RumbleFrame, sent PS5 -> phone on the PadFrame
 * connection. Mirrors ps5-client/padframe.h; keep them in lockstep.
 *
 *   off  sz  field
 *   0    4   magic   = 0x4C424D52 ("RMBL" LE)
 *   4    1   large   u8 heavy motor 0-255
 *   5    1   small   u8 light motor 0-255
 *   6    2   ttl_ms  u16 stop after this long unless refreshed; 0 = stop now
 */
public final class RumbleFrame {
    public static final int MAGIC = 0x4C424D52; // "RMBL"
    public static final int SIZE = 8;

    public int large, small, ttlMs;

    /** Decode at off, or return null if the magic doesn't match. */
    public static RumbleFrame decode(byte[] buf, int off) {
        if (PadFrame.getU32(buf, off) != MAGIC) return null;
        RumbleFrame f = new RumbleFrame();
        f.large = buf[off + 4] & 0xFF;
        f.small = buf[off + 5] & 0xFF;
        f.ttlMs = PadFrame.getU16(buf, off + 6);
        return f;
    }
}
