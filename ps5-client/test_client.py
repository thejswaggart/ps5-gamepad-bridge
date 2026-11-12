#!/usr/bin/env python3
"""PC-side test client for the gamepad-bridge PadFrame protocol.

Connects to the phone's TCP server (default 192.168.1.131:8471), reads
32-byte little-endian frames, and decodes them. Used to verify the phone
side independently of the PS5 payload.

Usage:
  python3 test_client.py [host] [port] [maxframes] [rumble]
    maxframes: 0 (default) = run until killed; N = exit after N frames.
    rumble:    send RumbleFrames back like the PS5 payload will: pulses of
               light, heavy and full rumble with pauses between, repeated.
Prints one line per *changed* state (deduped) so the log is readable.
"""
import socket, struct, sys, threading, time

MAGIC = 0x43444150
RUMBLE_MAGIC = 0x4C424D52  # "RMBL", 8 bytes: magic, large, small, ttl_ms
RUMBLE_TTL = 500

def rumble_frame(large, small, ttl=RUMBLE_TTL):
    return struct.pack("<IBBH", RUMBLE_MAGIC, large, small, ttl)

def rumble_loop(s):
    """Cycle: light, pause, heavy, pause, full, pause. Refresh every ttl/2."""
    steps = [("light", 0, 90, 1.0), ("off", 0, 0, 1.0),
             ("heavy", 200, 0, 1.0), ("off", 0, 0, 1.0),
             ("full", 255, 255, 1.0), ("off", 0, 0, 2.0)]
    try:
        while True:
            for name, large, small, secs in steps:
                print(f"rumble -> {name} (large={large} small={small})", flush=True)
                end = time.time() + secs
                if large == 0 and small == 0:
                    s.sendall(rumble_frame(0, 0, 0))
                    time.sleep(secs)
                    continue
                while time.time() < end:
                    s.sendall(rumble_frame(large, small))
                    time.sleep(RUMBLE_TTL / 2000.0)
    except OSError:
        pass

def decode(buf):
    magic, seq, buttons = struct.unpack_from("<III", buf, 0)
    lx, ly, rx, ry, l2, r2 = struct.unpack_from("<6B", buf, 12)
    return magic, seq, buttons, lx, ly, rx, ry, l2, r2

def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.131"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8471
    maxframes = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    rumble = len(sys.argv) > 4 and sys.argv[4] == "rumble"
    s = socket.create_connection((host, port), timeout=5)
    s.settimeout(5)
    print(f"connected to {host}:{port}", flush=True)
    if rumble:
        threading.Thread(target=rumble_loop, args=(s,), daemon=True).start()
    buf = b""
    n = 0
    bad = 0
    last_sig = None
    while True:
        if maxframes and n >= maxframes:
            break
        try:
            chunk = s.recv(4096)
        except socket.timeout:
            print("(recv timeout)", flush=True)
            continue
        if not chunk:
            break
        buf += chunk
        while len(buf) >= 32:
            frame = buf[:32]
            buf = buf[32:]
            magic, seq, buttons, lx, ly, rx, ry, l2, r2 = decode(frame)
            n += 1
            if magic != MAGIC:
                bad += 1
                print(f"[{n:5d}] BAD MAGIC 0x{magic:08x}", flush=True)
                continue
            sig = (buttons, lx, ly, rx, ry, l2, r2)
            if sig != last_sig:
                last_sig = sig
                print(f"[{n:5d}] seq={seq:8d} btn=0x{buttons:06x} L=({lx:3d},{ly:3d}) R=({rx:3d},{ry:3d}) l2={l2:3d} r2={r2:3d}", flush=True)
    print(f"--- {n} frames, {bad} bad magic ---", flush=True)

if __name__ == "__main__":
    main()
