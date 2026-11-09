package com.aipc.gamepadbridge;

/**
 * One snapshot of the mapped pad state, as sent in a PadFrame.
 * Sticks/triggers are u8 0-255 (128 = stick center); buttons are PS5 canonical bits.
 */
public final class PadState {
    public int buttons;
    public int lx, ly, rx, ry;
    public int l2, r2;

    public PadState() {
        // Sticks start at center so the PS5 reads them as centered at rest
        // (before/without any axis event). 128 == PADFRAME_CENTER.
        lx = ly = rx = ry = 128;
    }

    /** Thread-safe copy of another state (locks on the source). */
    public void copyFrom(PadState src) {
        synchronized (src) {
            buttons = src.buttons;
            lx = src.lx;
            ly = src.ly;
            rx = src.rx;
            ry = src.ry;
            l2 = src.l2;
            r2 = src.r2;
        }
    }
}
