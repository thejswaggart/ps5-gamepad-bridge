/*
 * rumble_out.h — send a game's rumble back to the phone.
 * Reads the two motor levels from scePadVirtualDeviceGetRemoteSetting on our
 * virtual device and streams RumbleFrames to the phone. See rumble_out.c.
 */
#pragma once
#include <stdint.h>

/* Start the background rumble thread once. */
void rumble_out_start(void);

/* Current virtual-device handle, or -1 when there is none. */
void rumble_out_set_handle(int32_t handle);

/* The connected phone socket to send RumbleFrames on, or -1 when down. */
void rumble_out_set_sock(int sock);
