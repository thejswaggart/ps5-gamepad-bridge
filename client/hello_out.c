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

/* Best-effort firmware string. The PS4/PS5 payload SDKs in this tree don't
 * expose a verified system-software-version API, so this is left empty for
 * now rather than risk calling an unknown symbol. The phone treats an empty
 * firmware field as "unknown" and simply doesn't show it.
 * TODO: wire to the SDK's firmware query (e.g. sceKernelGetSystemSwVersion
 * on PS4) once its struct layout is confirmed on hardware. */
static void get_firmware(char *out, size_t n)
{
    if (n) out[0] = '\0';
}

static void get_name(char *out, size_t n)
{
    if (!n) return;
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
