/*
 * rumble_out.c — send a game's rumble back to the phone.
 *
 * A game calls scePadSetVibration on our virtual DualSense; the system records
 * the two motor levels in the device's "remote setting", which we can read
 * locally with scePadVirtualDeviceGetRemoteSetting. Bytes 6 and 7 of that
 * buffer are the two motors, 0-255, live (found with the Route B probe:
 * Astro Bot drove them across the full range, idle = 0,0).
 *
 * This thread polls them at ~60 Hz and sends an 8-byte RumbleFrame to the
 * phone (on the same TCP socket the phone streams PadFrames over) whenever
 * they change, with a slow keepalive while non-zero so a dropped frame can't
 * leave a motor stuck. The phone stops on its own if the ttl lapses.
 */
#include "rumble_out.h"
#include "padframe.h"

#include <pthread.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <sys/socket.h>

extern void ghostpad_status_log(const char *fmt, ...);
#define ro_log(...) ghostpad_status_log("[rumble] " __VA_ARGS__)

extern int32_t scePadVirtualDeviceGetRemoteSetting(int32_t handle, void *data,
                                                   uint64_t size);

/*
 * Where rumble lands in the GetRemoteSetting buffer varies by game/port:
 * one title drove offset 4 (0-63), another offsets 22/24 (0-255), another
 * 6/7. They all rest at 0 and spike with rumble, while a few bytes are
 * constant descriptors. So instead of a fixed offset we take the max over
 * the rumble region [1..31], skipping the known constant descriptor bytes.
 */
#define RS_BUF      256
#define RUMBLE_LO    1
#define RUMBLE_HI   31
static int is_descriptor(int off)
{
    /* Bytes that hold a constant non-zero value at idle (device type,
     * record headers, small flags) rather than rumble intensity. */
    return off == 9 || off == 10 || off == 12 || off == 21 || off == 23;
}
static int rumble_level(const uint8_t *buf)
{
    int level = 0;
    for (int i = RUMBLE_LO; i <= RUMBLE_HI; i++) {
        if (is_descriptor(i)) continue;
        if (buf[i] > level) level = buf[i];
    }
    return level;
}

#define POLL_US       16000u   /* ~60 Hz */
#define KEEPALIVE_MS    150    /* resend an active non-zero rumble this often */
#define HOLD_MS         350    /* GetRemoteSetting keeps the LAST motor values
                               * after a game stops rumbling, so a value that
                               * hasn't changed for this long is treated as
                               * ended and forced to 0. Active rumble changes
                               * far faster than this, so it's never cut. */

static volatile int32_t g_handle = -1;
static volatile int      g_sock  = -1;
static int g_started = 0;
static pthread_t g_thread;

static uint64_t now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

static void send_frame(int sock, uint8_t large, uint8_t small)
{
    RumbleFrame rf;
    rf.magic  = RUMBLEFRAME_MAGIC;
    rf.large  = large;
    rf.small  = small;
    rf.ttl_ms = (uint16_t)RUMBLE_TTL_DEFAULT_MS;
    (void)send(sock, &rf, sizeof(rf), MSG_NOSIGNAL);
}

static void *rumble_thread(void *arg)
{
    (void)arg;
    int raw_large = -1, raw_small = -1;   /* last values read from the device */
    int sent_large = 0, sent_small = 0;   /* last values sent to the phone */
    int have_sent = 0;
    uint64_t last_change = 0, last_send = 0;
    for (;;) {
        int32_t h = g_handle;
        int sock = g_sock;
        if (h < 0 || sock < 0) {
            have_sent = 0; raw_large = raw_small = -1;
            usleep(POLL_US); continue;
        }

        uint8_t buf[RS_BUF];
        memset(buf, 0, sizeof(buf));
        if (scePadVirtualDeviceGetRemoteSetting(h, buf, sizeof(buf)) != 0) {
            usleep(POLL_US); continue;
        }

        int large = rumble_level(buf);   /* combined level across the region */
        int small = large;               /* phone uses max(large,small) anyway */
        uint64_t t = now_ms();

        /* The device holds the last motor values, so track when they last
         * changed; a value held still past HOLD_MS means the game stopped
         * rumbling and we force it to 0. */
        if (large != raw_large || small != raw_small) {
            raw_large = large; raw_small = small; last_change = t;
        }
        int active = (t - last_change) < HOLD_MS;
        int out_large = active ? large : 0;
        int out_small = active ? small : 0;

        int changed = !have_sent || out_large != sent_large || out_small != sent_small;
        int keepalive = (out_large || out_small) && (t - last_send) >= KEEPALIVE_MS;

        if (changed || keepalive) {
            send_frame(sock, (uint8_t)out_large, (uint8_t)out_small);
            if (changed && (out_large || out_small) && !sent_large && !sent_small)
                ro_log("rumble on  (A=%d B=%d)\n", out_large, out_small);
            else if (changed && !out_large && !out_small && (sent_large || sent_small))
                ro_log("rumble off\n");
            sent_large = out_large; sent_small = out_small;
            last_send = t; have_sent = 1;
        }
        usleep(POLL_US);
    }
    return NULL;
}

void rumble_out_start(void)
{
    if (g_started) return;
    if (pthread_create(&g_thread, NULL, rumble_thread, NULL) == 0) {
        g_started = 1;
        ro_log("started (reading GetRemoteSetting motors, sending RumbleFrames)\n");
    } else {
        ro_log("ERROR: could not start rumble thread\n");
    }
}

void rumble_out_set_handle(int32_t handle) { g_handle = handle; }
void rumble_out_set_sock(int sock)         { g_sock = sock; }
