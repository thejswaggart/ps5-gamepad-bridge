/*
 * padframe.h — 32-byte little-endian PadFrame wire format.
 *
 * Shared contract between:
 *   - the Android app  gamepad-bridge  (TCP server, produces frames)
 *   - the PS5 payload  ghostpad-net    (TCP client, consumes frames)
 *
 * The phone captures ANY gamepad, maps it to PS5 canonical button bits,
 * and streams one PadFrame per tick at ~60 Hz. The PS5 side turns each
 * frame into an ScePadData and injects it via the virtual DualSense.
 *
 * Layout is FIXED and little-endian. Do NOT reorder fields.
 */
#pragma once
#include <stdint.h>

#define PADFRAME_MAGIC  0x43444150u   /* "PADC" read as a little-endian u32 */
#define PADFRAME_SIZE   32u

/*
 * PS5 canonical button bits — mirror of gc_types.h SCE_PAD_BUTTON_*.
 * These are the bits the PS5 payload ORs into ScePadData.buttons.
 */
#define PAD_BTN_L3        0x00000002u
#define PAD_BTN_R3        0x00000004u
#define PAD_BTN_OPTIONS   0x00000008u
#define PAD_BTN_UP        0x00000010u
#define PAD_BTN_RIGHT     0x00000020u
#define PAD_BTN_DOWN     0x00000040u
#define PAD_BTN_LEFT      0x00000080u
#define PAD_BTN_L2        0x00000100u
#define PAD_BTN_R2        0x00000200u
#define PAD_BTN_L1        0x00000400u
#define PAD_BTN_R1        0x00000800u
#define PAD_BTN_TRIANGLE  0x00001000u
#define PAD_BTN_CIRCLE   0x00002000u
#define PAD_BTN_CROSS    0x00004000u
#define PAD_BTN_SQUARE   0x00008000u
#define PAD_BTN_CREATE   0x00010000u  /* = PS button (home screen via VDI) */
#define PAD_BTN_PS       0x00010000u
#define PAD_BTN_SHARE    0x00000001u
#define PAD_BTN_TOUCHPAD 0x00100000u

/*
 * Packed 32-byte frame. Offsets are fixed (see header comment).
 *   off  sz  field
 *   0    4   magic   = PADFRAME_MAGIC
 *   4    4   seq     (u32 sequence)
 *   8    4   buttons (PS5 canonical bits)
 *  12    1   lx      (left stick x, 0-255, 128=center)
 *  13    1   ly
 *  14    1   rx      (right stick x)
 *  15    1   ry
 *  16    1   l2      (L2 trigger 0-255)
 *  17    1   r2      (R2 trigger 0-255)
 *  18    4   rsvd    (=0)
 *  22    8   ts      (u64 timestamp, ms)
 *  30    2   pad     (=0)
 *  -> total 32
 */
typedef struct __attribute__((packed)) {
    uint32_t magic;
    uint32_t seq;
    uint32_t buttons;
    uint8_t  lx;
    uint8_t  ly;
    uint8_t  rx;
    uint8_t  ry;
    uint8_t  l2;
    uint8_t  r2;
    uint32_t rsvd;
    uint64_t ts;
    uint16_t pad;
} PadFrame;

_Static_assert(sizeof(PadFrame) == PADFRAME_SIZE,
               "PadFrame must be exactly 32 bytes");

/* Center value for the 0-255 stick/trigger encoding. */
#define PADFRAME_CENTER 128
