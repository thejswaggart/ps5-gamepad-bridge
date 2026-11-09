package com.aipc.gamepadbridge;

/**
 * 32-byte little-endian PadFrame wire format.
 * Mirrors ghostpad-net/padframe.h — the shared contract between this app
 * (TCP server, produces frames) and the PS5 payload (TCP client).
 *
 *   off  sz  field
 *   0    4   magic   = 0x43444150 ("PADC" LE)
 *   4    4   seq     u32 sequence
 *   8    4   buttons u32 (PS5 canonical bits)
 *  12    1   lx      u8 0-255, 128=center
 *  13    1   ly
 *  14    1   rx
 *  15    1   ry
 *  16    1   l2      u8 0-255
 *  17    1   r2
 *  18    4   rsvd    =0
 *  22    8   ts      u64 ms
 *  30    2   pad     =0
 */
public final class PadFrame {
    public static final int MAGIC = 0x43444150; // "PADC"
    public static final int SIZE = 32;

    // PS5 canonical button bits (mirror of padframe.h PAD_BTN_*,
    // which mirrors gc_types.h SCE_PAD_BUTTON_*).
    public static final int BTN_L3       = 0x0002;
    public static final int BTN_R3       = 0x0004;
    public static final int BTN_OPTIONS  = 0x0008;
    public static final int BTN_UP       = 0x0010;
    public static final int BTN_RIGHT    = 0x0020;
    public static final int BTN_DOWN     = 0x0040;
    public static final int BTN_LEFT     = 0x0080;
    public static final int BTN_L2       = 0x0100;
    public static final int BTN_R2       = 0x0200;
    public static final int BTN_L1       = 0x0400;
    public static final int BTN_R1       = 0x0800;
    public static final int BTN_TRIANGLE = 0x1000;
    public static final int BTN_CIRCLE   = 0x2000;
    public static final int BTN_CROSS    = 0x4000;
    public static final int BTN_SQUARE   = 0x8000;
    public static final int BTN_CREATE   = 0x10000; // PS button
    public static final int BTN_SHARE    = 0x0001;
    public static final int BTN_TOUCHPAD = 0x100000;

    public int magic;
    public int seq;
    public int buttons;
    public int lx, ly, rx, ry;
    public int l2, r2;
    public int rsvd;
    public long ts;
    public int pad;

    /** Encode this frame into buf at off (little-endian). */
    public void encode(byte[] buf, int off) {
        putU32(buf, off, magic);
        putU32(buf, off + 4, seq);
        putU32(buf, off + 8, buttons);
        buf[off + 12] = (byte) lx;
        buf[off + 13] = (byte) ly;
        buf[off + 14] = (byte) rx;
        buf[off + 15] = (byte) ry;
        buf[off + 16] = (byte) l2;
        buf[off + 17] = (byte) r2;
        putU32(buf, off + 18, rsvd);
        putU64(buf, off + 22, ts);
        putU16(buf, off + 30, pad);
    }

    /** Decode a frame from buf at off (little-endian). */
    public static PadFrame decode(byte[] buf, int off) {
        PadFrame f = new PadFrame();
        f.magic = getU32(buf, off);
        f.seq = getU32(buf, off + 4);
        f.buttons = getU32(buf, off + 8);
        f.lx = buf[off + 12] & 0xFF;
        f.ly = buf[off + 13] & 0xFF;
        f.rx = buf[off + 14] & 0xFF;
        f.ry = buf[off + 15] & 0xFF;
        f.l2 = buf[off + 16] & 0xFF;
        f.r2 = buf[off + 17] & 0xFF;
        f.rsvd = getU32(buf, off + 18);
        f.ts = getU64(buf, off + 22);
        f.pad = getU16(buf, off + 30);
        return f;
    }

    static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
    }

    static void putU32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
        b[off + 2] = (byte) ((v >>> 16) & 0xFF);
        b[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    static void putU64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) {
            b[off + i] = (byte) ((v >>> (8 * i)) & 0xFF);
        }
    }

    static int getU16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    static int getU32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    static long getU64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xFF);
        }
        return v;
    }
}
