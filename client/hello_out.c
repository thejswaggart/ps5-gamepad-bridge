/*
 * hello_out.c — send a one-shot HelloFrame to the phone on connect.
 *
 * See hello_out.h / padframe.h. The console type is compile-time (PLATFORM
 * selects __ORBIS__ = PS4 / __PROSPERO__ = PS5). The Wi-Fi MAC is read from
 * the live interface list (getifaddrs / AF_LINK, the BSD way), the name from
 * gethostname(). Firmware is left empty for now — the payload SDKs don't
 * expose a verified firmware API; see get_firmware() below.
 *
 * Everything is best-effort: any field that can't be read is sent zero/empty
 * so a Hello still goes out and the phone at least learns the console type.
 */
#include "hello_out.h"
#include "padframe.h"

#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>

/* BSD interface enumeration. These live in libc on the PS4/PS5 (FreeBSD-
 * derived) payload runtime; getifaddrs is marked weak so a toolchain that
 * somehow lacks it still links (we then just send an all-zero MAC). */
#include <sys/types.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <net/if_dl.h>

extern int getifaddrs(struct ifaddrs **) __attribute__((weak));
extern void freeifaddrs(struct ifaddrs *) __attribute__((weak));

/* Console name as set in Settings > System > "<console> name". Both the PS4
 * (orbis) and PS5 (prospero) SystemService stubs export this; weak so a
 * toolchain without it still links (we then fall back to gethostname). */
extern int sceSystemServiceParamGetString(int paramId, char *buf,
                                          unsigned long bufSize) __attribute__((weak));
#define SCE_SYSTEM_PARAM_ID_SYSTEM_NAME 6

/* System software version. The struct is { uint64_t size (in); char
 * str[0x1C] (out, e.g. "09.000.000"); uint32_t version (out) }; set size
 * before the call. Weak so the link doesn't fail if a stub is missing. */
extern int sceKernelGetSystemSwVersion(void *v) __attribute__((weak));
#ifdef __PROSPERO__
extern int sceKernelGetProsperoSystemSwVersion(void *v) __attribute__((weak));
#endif
typedef struct { uint64_t size; char str[0x1C]; uint32_t version; } sw_version_t;

extern void ghostpad_status_log(const char *fmt, ...);
#define ho_log(...) ghostpad_status_log("[hello] " __VA_ARGS__)

/* Read the first usable hardware MAC into out[6]. Prefers a non-loopback
 * interface whose 6-byte link address is not all-zero. Returns 1 on success
 * (out filled), 0 otherwise (out left untouched — caller zero-inits it). */
static int get_wifi_mac(uint8_t out[6])
{
    if (!getifaddrs) return 0;
    struct ifaddrs *ifa = NULL;
    if (getifaddrs(&ifa) != 0 || !ifa) return 0;

    int found = 0;
    for (struct ifaddrs *p = ifa; p && !found; p = p->ifa_next) {
        if (!p->ifa_addr || p->ifa_addr->sa_family != AF_LINK) continue;
        struct sockaddr_dl *sdl = (struct sockaddr_dl *)p->ifa_addr;
        if (sdl->sdl_alen != 6) continue;
        const unsigned char *a = (const unsigned char *)LLADDR(sdl);
        if (!(a[0] | a[1] | a[2] | a[3] | a[4] | a[5])) continue; /* all-zero */
        /* Skip loopback by name when we can (keep looking for a real NIC). */
        if (p->ifa_name && (p->ifa_name[0] == 'l' && p->ifa_name[1] == 'o'))
            continue;
        memcpy(out, a, 6);
        found = 1;
    }
    if (freeifaddrs) freeifaddrs(ifa);
    return found;
}

/* True if s looks like a printable version string ("09.000.000"). */
static int printable_ver(const char *s)
{
    return s[0] >= 0x20 && (unsigned char)s[0] < 0x7f;
}

/* Best-effort firmware / system-software version. On PS5 the Prospero query
 * gives the real console version; both platforms also have the PS4-style
 * query as a fallback. Left empty on failure (phone shows nothing). */
static void get_firmware(char *out, size_t n)
{
    if (!n) return;
    out[0] = '\0';
    sw_version_t sw;

#ifdef __PROSPERO__
    if (sceKernelGetProsperoSystemSwVersion) {
        memset(&sw, 0, sizeof(sw));
        sw.size = sizeof(sw);
        sceKernelGetProsperoSystemSwVersion(&sw);
        sw.str[sizeof(sw.str) - 1] = '\0';
        if (printable_ver(sw.str)) { snprintf(out, n, "%s", sw.str); out[n-1]='\0'; return; }
    }
#endif
    if (sceKernelGetSystemSwVersion) {
        memset(&sw, 0, sizeof(sw));
        sw.size = sizeof(sw);
        sceKernelGetSystemSwVersion(&sw);
        sw.str[sizeof(sw.str) - 1] = '\0';
        if (printable_ver(sw.str)) { snprintf(out, n, "%s", sw.str); out[n-1]='\0'; }
    }
}

/* The console's user-facing name (Settings > System name), which lets the
 * phone tell consoles apart even when none has a reverse-DNS hostname. Falls
 * back to gethostname() if the system-param call is missing or fails. */
static void get_name(char *out, size_t n)
{
    if (!n) return;
    out[0] = '\0';
    if (sceSystemServiceParamGetString) {
        char sys[128];
        sys[0] = '\0';
        if (sceSystemServiceParamGetString(SCE_SYSTEM_PARAM_ID_SYSTEM_NAME,
                                           sys, sizeof(sys)) == 0 && sys[0]) {
            snprintf(out, n, "%s", sys);
            out[n - 1] = '\0';
            return;
        }
    }
    if (gethostname(out, n) != 0) out[0] = '\0';
    out[n - 1] = '\0';
}

void hello_send(int sock)
{
    HelloFrame hf;
    memset(&hf, 0, sizeof(hf));
    hf.magic   = HELLOFRAME_MAGIC;
    hf.version = (uint8_t)HELLOFRAME_VERSION;
#ifdef __ORBIS__
    hf.type = (uint8_t)HELLO_TYPE_PS4;
#else
    hf.type = (uint8_t)HELLO_TYPE_PS5;
#endif
    get_wifi_mac(hf.mac);                       /* leaves all-zero on failure */
    get_firmware(hf.firmware, sizeof(hf.firmware));
    get_name(hf.name, sizeof(hf.name));

    ssize_t sent = send(sock, &hf, sizeof(hf), MSG_NOSIGNAL);
    ho_log("sent type=%u mac=%02x:%02x:%02x:%02x:%02x:%02x name='%s' (%d bytes)\n",
           (unsigned)hf.type,
           hf.mac[0], hf.mac[1], hf.mac[2], hf.mac[3], hf.mac[4], hf.mac[5],
           hf.name, (int)sent);
}
