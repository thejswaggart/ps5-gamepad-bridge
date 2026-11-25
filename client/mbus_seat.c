/* SPDX-License-Identifier: GPL-3.0-or-later
 * mbus_seat.c — in-process libSceMbus bind for the /dev/hid virtual pad (PS5).
 *
 * Ported from ProsperoSunshine-PS4's profile_seat.c (GPL-3.0-only), measured on
 * FW 10.20. The libSceMbus client has to be set up in-process before any call
 * (dlopen the module, run its init NID, then resolve the bind NID); without it
 * the bind reads a null pointer. sceMbusBindDeviceWithUserId seats the pad in a
 * profile, so the console does not ask "Who's using this controller?".
 *
 * The add ioctl can return before the system bus knows the pad, so the bind
 * gets "no such device" (0x809b0005); the login manager only looks at a new pad
 * ~10 ms after the bus announces it, so the bind retries every millisecond for
 * a while.
 */
#include "mbus_seat.h"
#include <pthread.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#ifdef __PROSPERO__

#include <ps5/kernel.h>

extern int sceKernelLoadStartModule(const char *name, size_t size, const void *data,
                                     uint32_t flags, void *unk5, int *out);

// libSceMbus (/system/common/lib), by NID.
#define MBUS_INIT "wRPXMGtkOq0"
#define MBUS_BIND "Cpo6sQmnZgU"
#define MBUS_INFO "RsKVKhSWt28"
typedef int (*mbus_init_fn)(uint64_t, uint64_t);
typedef int (*mbus_bind_fn)(uint64_t device, int32_t user);
typedef int (*mbus_info_fn)(uint64_t device, void *info, uint32_t size);
static pthread_mutex_t setup_lock = PTHREAD_MUTEX_INITIALIZER;
static mbus_bind_fn mbus_bind;    // set once libSceMbus is up; read under setup_lock
static mbus_info_fn mbus_info;
static int64_t mbus_tried_ms = -1;

static int64_t now_ms(void)
{
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

// libSceMbus, set up until it is up: right after the host restarts in the middle
// of a stream, its setup can fail for a while, and binds must not stay off until
// the next restart. A failed try is repeated at most once a second. Returns the
// bind, or NULL while libSceMbus isn't up.
static mbus_bind_fn setup(void)
{
    pthread_mutex_lock(&setup_lock);
    if (!mbus_bind && (mbus_tried_ms < 0 || now_ms() - mbus_tried_ms >= 1000)) {
        mbus_tried_ms = now_ms();
        uint32_t module = 0;
        int loaded = kernel_dynlib_handle(-1, "libSceMbus.sprx", &module) == 0;
        if (!loaded) {
            const int rc = sceKernelLoadStartModule("/system/common/lib/libSceMbus.sprx",
                                                    0, NULL, 0, NULL, NULL);
            if (rc > 0) {
                module = (uint32_t)rc;
                loaded = 1;
            }
        }
        const mbus_init_fn init = loaded ? (mbus_init_fn)kernel_dynlib_resolve(-1, module, MBUS_INIT) : NULL;
        if (init && init(0, 0) == 0) {
            mbus_info = (mbus_info_fn)kernel_dynlib_resolve(-1, module, MBUS_INFO);
            mbus_bind = (mbus_bind_fn)kernel_dynlib_resolve(-1, module, MBUS_BIND);
        }
    }
    const mbus_bind_fn bind = mbus_bind;
    pthread_mutex_unlock(&setup_lock);
    return bind;
}

// The add ioctl can return before the system bus knows the pad: a bind then gets
// "no such device" (0x809b0005).
#define MBUS_NO_DEVICE ((int)0x809b0005)
#define BIND_TRIES 30

int hidpad_mbus_bind(uint64_t device, uint32_t user)
{
    const mbus_bind_fn bind = setup();
    if (!bind) return -1;
    int rc = MBUS_NO_DEVICE;
    for (int i = 0; i < BIND_TRIES && rc == MBUS_NO_DEVICE; i++) {
        if (i) usleep(1000);
        rc = bind(device, (int32_t)user);
    }
    return rc;
}

#endif /* __PROSPERO__ */
