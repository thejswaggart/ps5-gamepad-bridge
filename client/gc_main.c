/* SPDX-License-Identifier: GPL-3.0-or-later
 * ghostpad-net: PS4/PS5 payload — TCP client for the gamepad-bridge phone app
 *
 * A single virtual DualSense (VDA/VDI) on PS5. Instead of capturing a
 * USB HID controller, this payload connects as a TCP CLIENT to an
 * Android "gamepad-bridge" server (the phone). The phone captures ANY
 * gamepad, maps it to PS5 canonical button bits, and streams one
 * 32-byte little-endian PadFrame per tick at ~60 Hz. This payload parses
 * each frame into an ScePadData and injects it via the virtual DualSense.
 *
 * Flow: TCP client thread (drives the /dev/hid VDA create/delete) -> keep-alive.
 */

#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <pthread.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <poll.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#ifdef __PROSPERO__
#include <ps5/kernel.h>
#include <ps5/klog.h>
#include <ps5/mdbg.h>
#endif

#ifdef __ORBIS__
#include <ps4/kernel.h>
#include <ps4/klog.h>
#include <ps4/mdbg.h>
#endif

#include "hidpad.h"
#include "mbus_seat.h"
#include "rumble_out.h"
#include "hello_out.h"
#include "gc_types.h"
#include "padframe.h"

/* ── Logging ──────────────────────────────────────────────────────────── */
/* TODO(PS4): /data/ghostpad is the PS5 payload home. PS4 homebrew lives
 * under /user/homebrew (PS4_HBROOT); /data may not exist on PS4. */
#define LOG_DIR  "/data/ghostpad"
#define LOG_PATH "/data/ghostpad/gc_status.log"
#define PID_PATH "/data/ghostpad/gc_main.pid"
#define LOG_MAX  480

static pthread_mutex_t g_log_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_log_fd = -1;

void ghostpad_status_log_reset(void) {
    pthread_mutex_lock(&g_log_lock);
    if (g_log_fd >= 0) { close(g_log_fd); g_log_fd = -1; }
    mkdir(LOG_DIR, 0755);
    g_log_fd = open(LOG_PATH, O_WRONLY|O_CREAT|O_TRUNC, 0600);
    pthread_mutex_unlock(&g_log_lock);
}

void ghostpad_status_log(const char *fmt, ...) {
    char buf[LOG_MAX]; va_list ap;
    va_start(ap, fmt); vsnprintf(buf, sizeof(buf)-1, fmt, ap); va_end(ap);
    buf[LOG_MAX-1] = '\0';
    klog_printf("%s", buf);
    pthread_mutex_lock(&g_log_lock);
    if (g_log_fd >= 0) {
        size_t n = strnlen(buf, sizeof(buf));
        write(g_log_fd, buf, n);
        if (n && buf[n-1] != '\n') write(g_log_fd, "\n", 1);
    }
    pthread_mutex_unlock(&g_log_lock);
}
#define gp_log(...) ghostpad_status_log("[GC] " __VA_ARGS__)

/* ── SCE stubs ────────────────────────────────────────────────────────── */
extern int32_t sceUserServiceInitialize(void *params);
extern int32_t sceUserServiceGetInitialUser(int32_t *outUserId);
extern int32_t sceUserServiceGetForegroundUser(int32_t *outUserId);
extern int32_t scePadInit(void);
extern int32_t scePadSetProcessPrivilege(int32_t privilege);
extern int32_t scePadVirtualDeviceDeleteDevice(int32_t handle);
extern int32_t scePadVirtualDeviceInsertData(int32_t handle, const void *padData);
extern int32_t sceKernelSendNotificationRequest(int unk0, void *req, size_t size, int unk1);


/* ── Single controller slot ───────────────────────────────────────────── */
#define MAX_SLOTS 1

typedef struct {
    volatile int32_t handle;        /* VDA handle (== /dev/hid ADD handle), -1 = not ready */
    volatile int     vdi_ready;
    volatile uint32_t inject_count;
    ps_pad         *pad;           /* the /dev/hid pad (ps_pad_open); NULL = none */
} ctrl_slot_t;

static ctrl_slot_t     g_slots[MAX_SLOTS];
static int32_t         g_inject_uid = 0x10000000;

/* ── Notification ─────────────────────────────────────────────────────── */
typedef struct { char _unk[45]; char message[3075]; } NotifyRequest;
static void notify(const char *fmt, ...) {
    NotifyRequest req; va_list ap;
    memset(&req, 0, sizeof(req));
    va_start(ap, fmt); vsnprintf(req.message, sizeof(req.message), fmt, ap); va_end(ap);
    sceKernelSendNotificationRequest(0, &req, sizeof(req), 0);
}

/* ── VDI injection ────────────────────────────────────────────────────── */
static void inject_pad(int slot, const ScePadData *pad) {
    int32_t h = g_slots[slot].handle;
    if (h < 0 || !g_slots[slot].vdi_ready) return;
    ScePadData stamped = *pad;
#ifdef __ORBIS__
    /* PS4: the sample needs a rising timestamp + counter; a constant one
     * is seen by de-duplicating readers as "no new data". (Control4Free.) */
    static uint64_t stamp = 0;
    static uint8_t  count = 0;
    stamp += 33000u;
    count++;
    stamped.timestamp = stamp;
    stamped.count     = count;
#endif
    int vr = scePadVirtualDeviceInsertData(h, &stamped);
    uint32_t n = ++g_slots[slot].inject_count;
    if ((n % 600) == 0)
        gp_log("slot[%d] VDI #%u ret=0x%08x\n", slot, n, (uint32_t)vr);
    static int vdi_err_logged = 0;
    if (vr != 0 && !vdi_err_logged) { gp_log("VDI error 0x%08x\n",(uint32_t)vr); vdi_err_logged=1; }
}

/* Sleep ms while the phone link is down. The virtual device is deleted on
 * link-down, so the PS5 shows no controller; there is nothing to inject. */
static void link_down_sleep(int ms) {
    usleep((useconds_t)(ms * 1000));
}

/* ── Settings: /data/ghostpad/config.ini ─────────────────────────────── */
#define CONFIG_PATH "/data/ghostpad/config.ini"
#define DEFAULT_CONFIG_TEXT \
    "# ghostpad-net settings - edit over FTP, then restart the payload.\n" \
    "#\n" \
    "# phone_ip   : phone server IP, or 'auto' to auto-discover (LAN scan).\n" \
    "# phone_port : TCP port the phone listens on (default 8471).\n" \
    "phone_ip = auto\n" \
    "phone_port = 8471\n"

static char g_ip_pref[64];
static int  g_ip_pref_valid = 0;
static int  g_phone_port  = 8471;

static int parse_dotted_quad(const char *s, uint32_t *out) {
    struct in_addr a;
    if (inet_pton(AF_INET, s, &a) != 1) return -1;
    *out = (uint32_t)ntohl(a.s_addr);
    return 0;
}

static void load_config(void) {
    FILE *f = fopen(CONFIG_PATH, "r");
    if (!f) {
        f = fopen(CONFIG_PATH, "w");
        if (f) { fputs(DEFAULT_CONFIG_TEXT, f); fclose(f); }
        gp_log("config: %s created with defaults (phone_ip=auto phone_port=%d)\n",
               CONFIG_PATH, g_phone_port);
        return;
    }
    char line[128];
    while (fgets(line, sizeof(line), f)) {
        char key[32], val[64];
        char *p = line; while (*p == ' ' || *p == '\t') p++;
        if (*p == '#' || *p == ';' || *p == '\n' || *p == 0) continue;
        if (sscanf(p, " %31[^= \t] = %63s", key, val) != 2) continue;
        if (strcmp(key, "phone_ip") == 0) {
            uint32_t ip;
            if (parse_dotted_quad(val, &ip) == 0) {
                snprintf(g_ip_pref, sizeof(g_ip_pref), "%u.%u.%u.%u",
                         (unsigned)((ip>>24)&0xff), (unsigned)((ip>>16)&0xff),
                         (unsigned)((ip>>8)&0xff), (unsigned)(ip&0xff));
                g_ip_pref_valid = 1;
            } else {
                g_ip_pref_valid = 0;
            }
        } else if (strcmp(key, "phone_port") == 0) {
            int v = atoi(val);
            if (v > 0 && v < 65536) g_phone_port = v;
            else gp_log("config: bad phone_port \"%s\"\n", val);
        }
    }
    fclose(f);
    gp_log("config: phone_ip=%s phone_port=%d\n",
           g_ip_pref_valid ? g_ip_pref : "auto", g_phone_port);
}

/* VDA lifecycle (defined below): the TCP client thread creates the virtual
 * DualSense when the phone reports a pad connected, and deletes it when the
 * pad disconnects or the phone link drops. */
static int32_t create_vda_for_slot(int slot);
static void delete_vda_for_slot(int slot);
/* ── LAN discovery: find the phone's IP by port-scanning our /24 ─────── */
/*
 * The phone's IP can change (DHCP). If no valid phone_ip is pinned in the
 * config, we sweep the /24 that contains our own address and probe each
 * host for the phone's TCP port; the first open port is the phone. A small
 * pool of threads makes the sweep finish in a couple of seconds.
 */

#define SCAN_THREADS 32          /* concurrent probe threads            */
#define SCAN_TIMEOUT_MS 300     /* per-probe connect timeout            */
#define TCP_RESCAN_AFTER_FAILS 5 /* connect fails before we re-scan    */

/* Return our own IPv4 address in HOST byte order, using the UDP connect
 * trick: connect() to 8.8.8.8:80 sends no packet, it only selects a route,
 * so getsockname() reveals the local address even when offline. 0 on fail. */
static uint32_t get_own_ip(void) {
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return 0;
    struct sockaddr_in dst;
    memset(&dst, 0, sizeof(dst));
    dst.sin_family      = AF_INET;
    dst.sin_port        = htons(80);
    dst.sin_addr.s_addr = htonl(0x08080808u); /* 8.8.8.8 */
    if (connect(fd, (struct sockaddr*)&dst, sizeof(dst)) != 0) {
        close(fd);
        return 0;
    }
    struct sockaddr_in local;
    socklen_t len = sizeof(local);
    if (getsockname(fd, (struct sockaddr*)&local, &len) != 0) {
        close(fd);
        return 0;
    }
    close(fd);
    return (uint32_t)ntohl(local.sin_addr.s_addr);
}

/* Try a TCP connect to ip_host (HOST order):port with a timeout in ms.
 * Returns 0 on success, -1 on failure. Always closes the socket. */
static int connect_timeout(uint32_t ip_host, int port, int timeout_ms) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    int fl = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, fl | O_NONBLOCK);
    struct sockaddr_in sin;
    memset(&sin, 0, sizeof(sin));
    sin.sin_family      = AF_INET;
    sin.sin_port        = htons((uint16_t)port);
    sin.sin_addr.s_addr = htonl(ip_host);
    int rc = connect(fd, (struct sockaddr*)&sin, sizeof(sin));
    if (rc == 0) {
        close(fd);
        return 0;
    }
    if (errno != EINPROGRESS && errno != EAGAIN && errno != EWOULDBLOCK) {
        close(fd);
        return -1;
    }
    struct pollfd pfd;
    pfd.fd     = fd;
    pfd.events = POLLOUT;
    int pr = poll(&pfd, 1, timeout_ms);
    int ok = 0;
    if (pr > 0) {
        int soerr = 0;
        socklen_t slen = sizeof(soerr);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &soerr, &slen) == 0 &&
            soerr == 0)
            ok = 1;
    }
    close(fd);
    return ok ? 0 : -1;
}

typedef struct {
    uint32_t lo, hi;        /* inclusive IP range to probe (host order) */
    int port;
    volatile int *found;   /* set when any thread finds an open port    */
    char *out_ip;          /* where to write the dotted-quad on success */
    uint32_t own;          /* our own IP, to skip                       */
} scan_arg_t;

static void *scan_worker(void *arg) {
    scan_arg_t *a = (scan_arg_t*)arg;
    for (uint32_t ip = a->lo; ip <= a->hi; ip++) {
        if (*(a->found)) break;
        if (ip == a->own) continue;
        if (connect_timeout(ip, a->port, SCAN_TIMEOUT_MS) == 0) {
            *(a->found) = 1;
            snprintf(a->out_ip, 64, "%u.%u.%u.%u",
                     (unsigned)((ip >> 24) & 0xff),
                     (unsigned)((ip >> 16) & 0xff),
                     (unsigned)((ip >> 8) & 0xff),
                     (unsigned)(ip & 0xff));
        }
    }
    return NULL;
}

/* Sweep the /24 containing our own IP, probing base.1 .. base.254 for an
 * open `port` (skipping .0/.255 and our own address). On success, writes
 * the dotted-quad into out_ip (>=64 bytes) and returns 0; returns -1 if
 * nothing is found. */
static int scan_for_phone(int port, char *out_ip) {
    uint32_t own = get_own_ip();
    if (own == 0) {
        gp_log("scan: could not determine own IP\n");
        return -1;
    }
    uint32_t base = own & 0xffffff00u;
    gp_log("scan: probing %u.%u.%u.1-254:%d\n",
           (unsigned)((own >> 24) & 0xff),
           (unsigned)((own >> 16) & 0xff),
           (unsigned)((own >> 8) & 0xff),
           (unsigned)(own & 0xff), port);
    volatile int found = 0;
    scan_arg_t args[SCAN_THREADS];
    pthread_t th[SCAN_THREADS];
    int created[SCAN_THREADS];
    int idx = 0;
    int per = 254 / SCAN_THREADS, rem = 254 % SCAN_THREADS;
    for (int t = 0; t < SCAN_THREADS; t++) {
        int count = per + (t < rem ? 1 : 0);
        args[t].lo     = base + 1 + idx;
        args[t].hi     = args[t].lo + count - 1;
        args[t].port   = port;
        args[t].found  = &found;
        args[t].out_ip = out_ip;
        args[t].own    = own;
        idx += count;
    }
    for (int t = 0; t < SCAN_THREADS; t++) {
        if (pthread_create(&th[t], NULL, scan_worker, &args[t]) == 0)
            created[t] = 1;
        else
            scan_worker(&args[t]); /* run this chunk inline */
    }
    for (int t = 0; t < SCAN_THREADS; t++)
        if (created[t]) pthread_join(th[t], NULL);
    if (found) {
        gp_log("scan: found phone at %s\n", out_ip);
        return 0;
    }
    gp_log("scan: no phone found on :%d\n", port);
    return -1;
}

/* ── TCP client: connect to the phone gamepad-bridge server ───────────── */
/* One virtual DualSense (slot 0). Reconnects forever: connect every ~2 s,
 * stream 32-byte frames, parse -> inject, auto-reconnect on EOF/reset. */
static void *tcp_client(void *arg) {
    (void)arg;
    char target[64];
    int fail_count = 0;

    /* Resolve the phone. A valid phone_ip in the config is the preferred
     * target (tried first, instant). Otherwise scan the LAN for the phone
     * port and keep re-scanning until it is found (self-heals DHCP moves
     * and a stale pinned IP). */
    if (g_ip_pref_valid) {
        snprintf(target, sizeof(target), "%s", g_ip_pref);
        gp_log("tcp: preferred target %s:%d\n", target, g_phone_port);
    } else {
        gp_log("tcp: no preferred phone_ip; scanning LAN for :%d\n",
               g_phone_port);
        while (scan_for_phone(g_phone_port, target) != 0)
            link_down_sleep(10000);
    }

    while (1) {
        char ipbuf[80];
        snprintf(ipbuf, sizeof(ipbuf), "%s:%d", target, g_phone_port);

        int sock = socket(AF_INET, SOCK_STREAM, 0);
        if (sock < 0) {
            gp_log("tcp: socket errno=%d (retry 2s)\n", errno);
            link_down_sleep(2000);
            continue;
        }

        struct sockaddr_in sin;
        memset(&sin, 0, sizeof(sin));
        sin.sin_family = AF_INET;
        sin.sin_port   = htons((uint16_t)g_phone_port);
        if (inet_pton(AF_INET, target, &sin.sin_addr) != 1) {
            gp_log("tcp: bad phone_ip \"%s\" (retry 2s)\n", target);
            close(sock);
            link_down_sleep(2000);
            continue;
        }

        if (connect(sock, (struct sockaddr*)&sin, sizeof(sin)) != 0) {
            close(sock);
            fail_count++;
            if (fail_count >= TCP_RESCAN_AFTER_FAILS) {
                gp_log("tcp: %d connect failures to %s - re-scanning LAN\n",
                       fail_count, target);
                fail_count = 0;
                gp_log("tcp: scanning LAN for :%d\n", g_phone_port);
                while (scan_for_phone(g_phone_port, target) != 0)
                    link_down_sleep(10000);
            } else {
                gp_log("tcp: connect errno=%d (retry 2s)\n", errno);
                link_down_sleep(2000);
            }
            continue;
        }

        fail_count = 0;
        gp_log("tcp: CONNECTED to %s\n", ipbuf);
        rumble_out_set_sock(sock);

        /* Announce our identity (type / MAC / name) once, right after
         * connect, so the phone can key this console on its durable MAC and
         * still recognise it after a DHCP address change. Older phones that
         * don't understand the HelloFrame magic simply skip these bytes. */
        hello_send(sock);

        /* Receive exactly PADFRAME_SIZE bytes per frame, then inject. */
        uint8_t buf[PADFRAME_SIZE];
        size_t got = 0;
        static uint32_t magic_warn = 0;
        static int last_conn = -1;
        while (1) {
            ssize_t n = recv(sock, buf + got, PADFRAME_SIZE - got, 0);
            if (n <= 0) {
                if (n < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK))
                    continue;
                gp_log("tcp: EOF/reset (n=%d errno=%d) - reconnecting\n",
                       (int)n, n < 0 ? errno : 0);
                rumble_out_set_sock(-1);
                close(sock);
                if (last_conn != 0) {
                    last_conn = 0;
                    gp_log("pad: DISCONNECTED (phone link down)\n");
                }
                if (g_slots[0].vdi_ready) delete_vda_for_slot(0);
                break;
            }
            got += (size_t)n;
            if (got < PADFRAME_SIZE) continue;

            PadFrame f;
            memcpy(&f, buf, sizeof(f));
            if (f.magic != PADFRAME_MAGIC) {
                if ((++magic_warn % 600) == 1)
                    gp_log("tcp: magic mismatch 0x%08x (expected 0x%08x)\n",
                           f.magic, PADFRAME_MAGIC);
            }

            int connected = PADFRAME_RSVD_CONNECTED(f.rsvd);
            if (connected != last_conn) {
                last_conn = connected;
                gp_log("pad: %s\n", connected ? "CONNECTED" : "DISCONNECTED");
            }

            if (connected) {
                /* Pad connected: make sure the virtual device exists, then
                 * stream its data. Creation is what makes the PS5 show a
                 * controller (the connected field alone is not honored). */
                if (!g_slots[0].vdi_ready) {
                    int32_t h = create_vda_for_slot(0);
                    if (h < 0)
                        gp_log("tcp: /dev/hid ADD failed (retry next frame)\n");
                }
                if (g_slots[0].vdi_ready) {
                    ScePadData pad;
                    memset(&pad, 0, sizeof(pad));
                    pad.buttons          = f.buttons;
                    if (f.l2 > 16u) pad.buttons |= SCE_PAD_BUTTON_L2;
                    if (f.r2 > 16u) pad.buttons |= SCE_PAD_BUTTON_R2;
                    pad.leftStick.x      = f.lx;  pad.leftStick.y      = f.ly;
                    pad.rightStick.x     = f.rx;  pad.rightStick.y     = f.ry;
                    pad.analogButtons.l2 = f.l2;  pad.analogButtons.r2 = f.r2;
                    pad.quat.w = 1.0f;
                    pad.connected = 1;
                    inject_pad(0, &pad);
                }
            } else {
                /* Pad disconnected: remove the virtual device so the PS5
                 * shows no controller. */
                if (g_slots[0].vdi_ready) delete_vda_for_slot(0);
            }

            got = 0;
        }
        link_down_sleep(500); /* brief pause before reconnect (no controller) */
    }
    return NULL;
}

/* ── VDA creation ───────────────────────────────────────────────── ─ */
/* The /dev/hid ADD ioctl hands back the handle synchronously (no klog), so a
 * failed ADD creates no device: it is safe to retry next frame. */
static int32_t create_vda_for_slot(int slot) {
    uint32_t user;
#ifdef __ORBIS__
    user = ps_pad_default_user();
    if (!user) user = PS_PAD_NO_USER;
#else
    user = (uint32_t)g_inject_uid;
#endif
    ps_pad *pad = NULL;
    int rc = ps_pad_open(user, &pad);
    if (rc != 0) {
        gp_log("slot[%d] /dev/hid ADD failed ret=%d\n", slot, rc);
        return -1;
    }
    uint32_t dev_id = ps_pad_device_id(pad);
#ifdef __PROSPERO__
    int br = hidpad_mbus_bind(dev_id, user);
    gp_log("slot[%d] mbus bind(0x%x, 0x%08x) ret=%d\n", slot, dev_id, user, br);
#endif
    g_slots[slot].pad          = pad;
    g_slots[slot].handle       = (int32_t)dev_id;
    g_slots[slot].vdi_ready    = 1;
    g_slots[slot].inject_count = 0;
    rumble_out_set_handle((int32_t)dev_id);
    gp_log("slot[%d] /dev/hid ADD handle=0x%x ready (user=0x%08x)\n",
           slot, dev_id, user);
    return (int32_t)dev_id;
}
/* Delete the virtual device for a slot: the PS5 then shows NO controller.
 * (The PS5 does not honor the injected connected field; device existence
 * is what the system UI tracks. See R1.) */
static void delete_vda_for_slot(int slot) {
    int32_t h = g_slots[slot].handle;
    if (h >= 0) {
        scePadVirtualDeviceDeleteDevice(h);
        gp_log("slot[%d] VDA deleted (handle=0x%x)\n", slot, (uint32_t)h);
    }
    if (g_slots[slot].pad) {
        int rc = ps_pad_close(g_slots[slot].pad);
        g_slots[slot].pad = NULL;
        gp_log("slot[%d] /dev/hid close ret=%d\n", slot, rc);
    }
    g_slots[slot].handle       = -1;
    g_slots[slot].vdi_ready    = 0;
    g_slots[slot].inject_count = 0;
    rumble_out_set_handle(-1);
}

/* ── Credentials ──────────────────────────────────────────────────────── */
static void elevate_credentials(void) {
    pid_t p = getpid();
    uint8_t caps[16]; memset(caps,0xff,sizeof(caps));
#ifdef __PROSPERO__
    kernel_set_ucred_authid(p, 0x3800000000010003l);
#else
    /* TODO(PS4): PS4 authid for SceShellCore. 0x4800000000010003 is the
     * strong candidate (the PS5 value is 0x3800000000010003); the
     * all-0xff caps are a PS5 placeholder, the PS4 caps layout is not
     * verified. */
    kernel_set_ucred_authid(p, 0x4800000000010003l);
#endif
    kernel_set_ucred_caps(p, caps);
}

/* ── main ─────────────────────────────────────────────────────────────── */
int main(void) {
    int32_t userId=-1, fgUser=-1; int ret;

    ghostpad_status_log_reset();
    gp_log("ghostpad-net starting - 1 slot (TCP client)\n");
    notify("ghostpad-net - connect a gamepad to the phone");

    /* Kill previous instance */
    { int pfd=open(PID_PATH,O_RDONLY);
      if(pfd>=0){char pb[16]={0};read(pfd,pb,15);close(pfd);
        pid_t old=(pid_t)atoi(pb);
        if(old>0&&old!=getpid()){gp_log("Killing prev pid=%d\n",old);kill(old,SIGTERM);usleep(600000);}
      }
      int pfd2=open(PID_PATH,O_WRONLY|O_CREAT|O_TRUNC,0600);
      if(pfd2>=0){char pb[16];snprintf(pb,sizeof(pb),"%d",getpid());write(pfd2,pb,strlen(pb));close(pfd2);}
    }

    load_config();

    /* Init slot */
    g_slots[0].handle       = -1;
    g_slots[0].vdi_ready    = 0;
    g_slots[0].inject_count = 0;

    sceUserServiceInitialize(NULL);
    sceUserServiceGetInitialUser(&userId);
    sceUserServiceGetForegroundUser(&fgUser);
    gp_log("userId=0x%08x fgUser=0x%08x\n", (uint32_t)userId, (uint32_t)fgUser);
    /* TODO(PS4): 0x10000000..0x1000000F is the PS5 user-ID range; the PS4
     * user-ID scheme is not verified. */
    g_inject_uid = (fgUser > 0) ? fgUser : userId;
    if ((uint32_t)userId<0x10000000u||(uint32_t)userId>0x1000000Fu) userId=0x10000000;
    gp_log("inject_uid=0x%08x\n", (uint32_t)g_inject_uid);

    elevate_credentials();

    ret=scePadInit(); gp_log("scePadInit: 0x%08x\n", ret);
    ret=scePadSetProcessPrivilege(1); gp_log("scePadSetProcessPrivilege: 0x%08x\n", ret);

    rumble_out_start();

    /* Clean up any orphaned VDA devices */
    for (int dh=0; dh<64; dh++) {
        if (scePadVirtualDeviceDeleteDevice(dh)==0) gp_log("deleteDevice(%d)\n", dh);
    }


    /* The virtual DualSense is created/deleted dynamically by the TCP
     * client thread from the phone's connected state. It starts DOWN: the
     * PS5 shows no controller until the phone reports a pad connected. */

    /* Start TCP client thread (connect to the phone) */
    pthread_t tcp_tid;
    if (pthread_create(&tcp_tid, NULL, tcp_client, NULL)==0) {
        pthread_detach(tcp_tid);
        gp_log("tcp client thread started\n");
    }

    /* Keep-alive */
    uint32_t tick = 0;
    while (1) {
        usleep(1000000);
        tick++;
        if (tick % 10 == 0)
            gp_log("alive tick=%u vdi=%d\n", tick, g_slots[0].vdi_ready);
    }
    return 0;
}
