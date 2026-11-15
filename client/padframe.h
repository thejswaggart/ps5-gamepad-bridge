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
 *  18    4   rsvd    (byte0=connected 0/1, rest=0)
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

/*
 * rsvd (offset 18, u32): byte 0 = gamepad link state, bytes 1-3 = 0.
 *   1 = a physical gamepad is connected to the phone
 *   0 = no gamepad on the phone
 * The PS5 payload mirrors it into ScePadData.connected so the system
 * shows the controller connected/disconnected accordingly.
 */
#define PADFRAME_RSVD_OFF          18u
#define PADFRAME_RSVD_CONNECTED(rsvd) (((rsvd) & 0xFFu) == 1u)

/* Center value for the 0-255 stick/trigger encoding. */
#define PADFRAME_CENTER 128

/*
 * RumbleFrame — 8 bytes, little-endian, sent the OTHER way: PS5 -> phone,
 * on the same TCP connection. The phone vibrates the controller (or itself).
 *
 *   off  sz  field
 *   0    4   magic   = RUMBLEFRAME_MAGIC
 *   4    1   large   (low-frequency / heavy motor, 0-255)
 *   5    1   small   (high-frequency / light motor, 0-255)
 *   6    2   ttl_ms  (stop after this many ms unless refreshed; 0 = stop now)
 *
 * Send one whenever the game's motor values change, and repeat it about
 * every ttl/2 while they are non-zero. If the link drops, the phone stops
 * on its own when ttl runs out. Older phone builds ignore these bytes.
 */
#define RUMBLEFRAME_MAGIC  0x4C424D52u   /* "RMBL" read as a little-endian u32 */
#define RUMBLEFRAME_SIZE   8u
#define RUMBLE_TTL_DEFAULT_MS 500u

typedef struct __attribute__((packed)) {
    uint32_t magic;
    uint8_t  large;
    uint8_t  small;
    uint16_t ttl_ms;
} RumbleFrame;

_Static_assert(sizeof(RumbleFrame) == RUMBLEFRAME_SIZE,
               "RumbleFrame must be exactly 8 bytes");

/*
 * HelloFrame — 92 bytes, little-endian, sent PS4/PS5 -> phone ONCE right
 * after the payload connects, on the same TCP connection, before (and
 * alongside) the PadFrame / RumbleFrame streams. It gives the console a
 * durable identity (its Wi-Fi MAC) so the phone keeps recognising it across
 * DHCP address changes, plus display fields (type / firmware / name).
 *
 * Fixed size so the phone's reader can frame it unambiguously against the
 * 8-byte RumbleFrame on the same inbound stream (it dispatches on magic).
 * Older phone builds that don't know this magic skip it one byte at a time.
 *
 *   off  sz  field
 *   0    4   magic    = HELLOFRAME_MAGIC
 *   4    1   version  = HELLOFRAME_VERSION
 *   5    1   type     1 = PS4, 2 = PS5 (matches Console.TYPE_PS4/PS5)
 *   6    6   mac      Wi-Fi MAC, 6 bytes; all-zero if unavailable
 *  12   16  firmware ASCII, NUL-padded/terminated; empty if unavailable
 *  28   64  name     ASCII console name (Settings > System name),
 *                    NUL-padded/terminated
 *   -> total 92
 */
#define HELLOFRAME_MAGIC   0x49485047u   /* "GPHI" read as a little-endian u32 */
#define HELLOFRAME_SIZE    92u
#define HELLOFRAME_VERSION 1u
#define HELLO_TYPE_PS4     1u
#define HELLO_TYPE_PS5     2u
#define HELLO_FW_LEN       16u
#define HELLO_NAME_LEN     64u

typedef struct __attribute__((packed)) {
    uint32_t magic;
    uint8_t  version;
    uint8_t  type;
    uint8_t  mac[6];
    char     firmware[HELLO_FW_LEN];
    char     name[HELLO_NAME_LEN];
} HelloFrame;

_Static_assert(sizeof(HelloFrame) == HELLOFRAME_SIZE,
               "HelloFrame must be exactly 92 bytes");
