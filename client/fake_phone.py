#!/usr/bin/env python3
"""Fake gamepad-bridge: TCP server on :8471 emitting 32-byte PadFrames.
Mirrors what the real Android app sends, for testing the PS5 payload's
TCP client + parse path without a phone.
Usage: python3 fake_phone.py [port] [idle|buttons|conn]
  idle    -> all frames buttons=0 (safe: no button presses)
  buttons -> periodic CROSS/STICK changes (DO NOT point at a live PS5 app)
"""
import socket, struct, time, sys

MAGIC  = 0x43444150
CENTER = 128
BTN_CROSS = 0x4000
BTN_TRIANGLE = 0x1000
BTN_UP = 0x10

# '<IIIBBBBBBIQH' -> magic,seq,buttons,lx,ly,rx,ry,l2,r2,rsvd,ts,pad = 32B
FMT = '<IIIBBBBBBIQH'
assert struct.calcsize(FMT) == 32, struct.calcsize(FMT)

def frame(seq, buttons, lx=CENTER, ly=CENTER, rx=CENTER, ry=CENTER, l2=0, r2=0, conn=0):
    ts = int(time.time() * 1000)
    return struct.pack(FMT, MAGIC, seq, buttons, lx, ly, rx, ry, l2, r2, conn, ts, 0)

def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8471
    mode = sys.argv[2] if len(sys.argv) > 2 else 'idle'
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(('0.0.0.0', port)); srv.listen(1)
    print(f"fake-phone: listening on 0.0.0.0:{port} mode={mode}", flush=True)
    while True:
        c, addr = srv.accept()
        print(f"fake-phone: client {addr} connected", flush=True)
        c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        seq = 0
        try:
            while True:
                if mode == 'buttons':
                    # 1s idle, 1s CROSS, 1s stick-left, 1s idle (periodic)
                    ph = seq % 240
                    if   ph < 60:  btn, lx, rx = 0, CENTER, CENTER
                    elif ph < 120: btn, lx, rx = BTN_CROSS, CENTER, CENTER
                    elif ph < 180: btn, lx, rx = 0, 64, CENTER   # stick left
                    else:          btn, lx, rx = 0, CENTER, CENTER
                    data = frame(seq, btn, lx=lx, rx=rx)
                elif mode == 'conn':
                    # 3s disconnected / 3s connected, idle buttons (safe)
                    conn = 1 if (seq // 180) % 2 else 0
                    data = frame(seq, 0, conn=conn)
                else:
                    btn, lx, rx = 0, CENTER, CENTER
                    data = frame(seq, btn, lx=lx, rx=rx)
                c.sendall(data)
                seq += 1
                time.sleep(1.0/60.0)
        except Exception as e:
            print(f"fake-phone: client gone ({e})", flush=True)
        finally:
            c.close()
        print("fake-phone: back to accept", flush=True)

if __name__ == '__main__':
    main()
