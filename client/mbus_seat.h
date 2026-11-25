/* SPDX-License-Identifier: GPL-3.0-or-later
 * mbus_seat.h — seat a /dev/hid virtual pad in a console profile (PS5 only).
 *
 * In-process libSceMbus bind, ported from ProsperoSunshine-PS4's
 * profile_seat.c (GPL-3.0-only). On PS4 there is no bind: the user id in the
 * pad's add descriptor assigns it (or PS_PAD_NO_USER lets the console ask).
 */
#pragma once
#include <stdint.h>

#ifdef __PROSPERO__
/* Gives the /dev/hid device (ps_pad_device_id) to the profile `user`. 0, or the
 * system's error; -1 while libSceMbus is not up. Retries on "no such device"
 * (the bus announces the pad ~10 ms after the add ioctl). */
int hidpad_mbus_bind(uint64_t device, uint32_t user);
#endif
