/*
 * hello_out.h — send a one-shot HelloFrame to the phone on connect.
 *
 * The phone is the TCP server and keys a console's identity on its IP, which
 * DHCP can change. Right after the payload connects it sends a HelloFrame
 * (see padframe.h) carrying the console's type, Wi-Fi MAC, firmware and name,
 * so the phone can recognise this console again even after its address moves.
 */
#pragma once

/* Build a HelloFrame for this build target (PS4 / PS5) and send it once on
 * the just-connected socket. Best-effort: fields that can't be read are left
 * zero/empty rather than failing. Safe to call on every (re)connect. */
void hello_send(int sock);
