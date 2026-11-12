# GhostPad — PS5 Gamepad Bridge

**Turn an Android phone into a PS5 gamepad over your LAN.**

Plug a controller (e.g. an Xbox pad) into your Android phone, and it drives a
jailbroken PS5 as if it were a real DualSense — no USB cable to the console,
just a TCP connection across your network.

```
  [ phone: external pad ]              [ PS5 (jailbroken) ]
  Gamepad Bridge app                   ghostpad-net.elf payload
  ┌──────────────────────────┐         ┌───────────────────────────────┐
  │ PadMapper (Xbox keycodes) │         │ virtual DualSense (VDI path)  │
  │           │                │         │           ▲                  │
  │           ▼                │         │           │                  │
  │ PadServer  TCP :8471 ──────┼────────▶│ TCP client (auto-discovers)    │
  │  (0.0.0.0:8471, 1 client)   │         │  /24 port-scan → connect      │
  └──────────────────────────┘         └───────────────────────────────┘
               streams PadFrame @~60Hz          injects via
               (32-byte wire)                  scePadVirtualDeviceInsertData
```

## How it works

Two halves talk over a single TCP connection:

- **Phone = the gamepad (TCP *server*).** The `Gamepad Bridge` app reads an
  external gamepad (USB/Bluetooth) via `PadMapper`, and `PadServer` streams a
  32-byte `PadFrame` at ~60 Hz (16 ms) to `0.0.0.0:8471`.
  **The app must stay open with the screen on:** Android only delivers
  gamepad events to the focused window, so input stops if you switch apps,
  lock the phone, or let the screen turn off. (The `PARTIAL_WAKE_LOCK` keeps
  the socket alive, but no input arrives.) Use **Play mode** to dim the
  screen to near-black while you play.
- **PS5 = the controller injector (TCP *client*).** The `ghostpad-net.elf`
  payload creates a **virtual DualSense** and injects the received input with
  `scePadVirtualDeviceInsertData` (the "Ghostpad VDI path"). It
  **auto-discovers** the phone by sweeping the local `/24` for an open
  `:8471`, connects, and self-heals (re-scans) if the link drops.

The phone *is* the gamepad: press buttons on the pad plugged into the phone
and they land on the PS5.

## Wire format — `PadFrame` (32 bytes, little-endian)

| off | sz | field |
|-----|----|-------|
| 0   | 4  | `magic` = `0x43444150` ("PADC") |
| 4   | 4  | `seq` u32 sequence |
| 8   | 4  | `buttons` u32 (PS5 canonical bits) |
| 12  | 1  | `lx` u8 0–255, 128 = center |
| 13  | 1  | `ly` |
| 14  | 1  | `rx` |
| 15  | 1  | `ry` |
| 16  | 1  | `l2` u8 0–255 |
| 17  | 1  | `r2` |
| 18  | 4  | `rsvd` (byte 0 = connected 0/1) |
| 22  | 8  | `ts` u64 ms |
| 30  | 2  | `pad` = 0 |

### `RumbleFrame` (8 bytes, little-endian, PS5 → phone)

The same connection carries rumble the other way. The PS5 sends one when the
game's motor values change, and repeats it about every `ttl/2` while they are
non-zero. If the link drops, the phone stops on its own when `ttl` runs out.

| off | sz | field |
|-----|----|-------|
| 0   | 4  | `magic` = `0x4C424D52` ("RMBL") |
| 4   | 1  | `large` u8 heavy motor 0–255 |
| 5   | 1  | `small` u8 light motor 0–255 |
| 6   | 2  | `ttl_ms` u16 (0 = stop now) |

The phone vibrates the first connected controller that Android reports a
motor for, or (if allowed on the Test controller screen) the phone itself.
Android 9 has one motor per device, so both values become one strength, and
it can only switch a controller's motor on or off. Weaker rumble is made by
pulsing it: light is 60 ms on / 140 ms off, medium 100 ms on / 50 ms off,
strong is steady (strength 24–99 / 100–179 / 180+). **Test rumble** on the
Test controller screen plays all three. On the test phone (Android
9, kernel 4.4) an Xbox Wireless Controller has no usable motor over Bluetooth
or USB; a USB GameCube-style pad rumbles. **The PS5 payload doesn't send
RumbleFrames yet**; `test_client.py <phone-ip> 8471 0 rumble` stands in for it.

Shared contract: `ps5-client/padframe.h` (PS5) ↔
`android-server/.../PadFrame.java` and `RumbleFrame.java` (phone). Keep them
in lockstep.

## Prerequisites

**PS5**
- A **jailbroken PS5** (kernel exploit) running an **ELF loader** (pldmgr)
  listening on **TCP :9021**.
- [ps5-payload-sdk](https://github.com/ps5-payload-dev/sdk) to build the
  payload (Prospero x86-64 toolchain).

**Build host**
- Linux, with the PS5 SDK (`prospero.mk`) and a JDK + Android SDK/Gradle for
  the app (the app builds via the bundled `gradlew`).

**Phone**
- Android phone (tested on Android 9), on the **same `/24` LAN** as the PS5.
- An **external gamepad** (USB or Bluetooth) to plug into the phone — an Xbox
  pad is the confirmed reference.

## Building

From the repo root:

```sh
make            # build the PS5 payload, then the Android app (default)
make ps5        # PS5 payload only  -> ps5-client/ghostpad-net.elf
make android    # payload + bundle ELF into the APK + ./gradlew assembleDebug
make deploy     # build + push the payload to the PS5 (nc-style)
make clean      # clean both
```

- `make android` builds the payload first, copies
  `ps5-client/ghostpad-net.elf` into
  `android-server/app/src/main/assets/`, then runs
  `./gradlew assembleDebug` in `android-server/`.
- The PS5 payload is compiled with the SDK's `prospero.mk`
  (`-D__PROSPERO__ -Wall -Wextra -g -O2 -fPIC -fno-stack-protector`), linking
  `-lScePad -lSceUserService -lpthread -ldl`.
- The SDK path defaults to `/home/aipc/projects/PS5_PayloadSDK`; override with
  `PS5_PAYLOAD_SDK=/other/path make ps5`.
- The Android APK lands in
  `android-server/app/build/outputs/apk/debug/`.

## Deploying the PS5 payload

The payload is delivered exactly the way `nc` does it — open a TCP connection
to the ELF loader, stream the raw ELF bytes, then close (EOF):

```sh
nc -w 8 <ps5-ip> 9021 < ps5-client/ghostpad-net.elf
# or:  make deploy        (PS5_HOST defaults to "PS5")
```

Or from the app: open **Gamepad Bridge** and tap **Send** on step 2. It tries
the address under **Advanced** (if set), then the last PS5 that worked, then
sweeps the `/24` for an open `:9021` (`NetUtil`), and streams the bundled
`ghostpad-net.elf` asset.

> Deploying **replaces** whatever the PS5 is currently running. Confirm what's
> live before you deploy.

## Running (end to end)

1. **PS5:** jailbreak, start the ELF loader (pldmgr) so it's listening on `:9021`.
2. **Build:** `make android` (or just `make ps5`).
3. **Deploy the payload** to the PS5 (app **Send**, `make deploy`, or `nc` above).
4. **Phone:** plug the external gamepad into the phone and open **Gamepad
   Bridge** (it listens on `0.0.0.0:8471` as soon as it starts). Keep the app
   open with the screen on; **Play mode** dims it to save battery.
5. **Auto-connect:** the PS5 payload scans the LAN, finds the phone's `:8471`,
   and connects.
6. **Play:** the phone's pad now drives the PS5.

**Verify it connected**
- Step 3 on the phone's home screen shows **PS5 connected**, and a
  **Ready to play** banner appears. **⋮ → Diagnostics** shows the PS5's IP
  and the event log.
- PS5 log `/data/ghostpad/gc_status.log` shows
  `scan: found phone at <ip>` then `tcp: CONNECTED`.

## Configuration

`/data/ghostpad/config.ini` (created with defaults on first run):

```ini
# phone_ip   : phone server IP, or 'auto' to auto-discover (LAN scan).
# phone_port : TCP port the phone listens on (default 8471).
phone_ip = auto
phone_port = 8471
```

- `phone_ip = auto` (or empty/invalid) → the payload **always** discovers the
  phone by sweeping the `/24` for `:phone_port` (32 threads, ~300 ms timeout,
  ~2–3 s).
- `phone_ip = <dotted-quad>` → that IP is the **preferred** target (tried
  first, instant) but it still **falls back to a scan** if it's stale.
- On **repeated** connect failure (≥5) the payload re-scans — this self-heals
  a DHCP change or a stale pinned IP.

## Logs & state

- `/data/ghostpad/gc_status.log` — runtime log (config load, scan, connect).
- `/data/ghostpad/gc_main.pid` — PID.
- Kernel log (`klog`) mirrors the status log.

## Project layout

```
ps5-gamepad-bridge/
├─ Makefile                          # top-level: android / ps5 / deploy
├─ ps5-client/                      # PS5 payload (x86-64 ELF)
│  ├─ gc_main.c                     #   main: config, LAN scan, TCP client, VDI
│  ├─ shellui_pad.c / .h           #   shell UI / pad glue
│  ├─ gc_types.h                   #   SCE pad types
│  ├─ padframe.h                   #   PadFrame + RumbleFrame wire contract (PS5 side)
│  ├─ fake_phone.py                #   isolated test: fake phone server
│  └─ test_client.py               #   fake PS5: reads frames, can send rumble
└─ android-server/                  # "Gamepad Bridge" Android app
   └─ app/src/main/java/com/aipc/gamepadbridge/
      ├─ GamepadBridgeApp.java      #   Application: server, controller tracking,
      │                             #   input routing, payload sending
      ├─ PadActivity.java           #   base screen: forwards pad input to the mapper
      ├─ MainActivity.java          #   home: 3-step setup checklist
      ├─ MappingActivity.java       #   button mapping (per-controller profiles)
      ├─ TestActivity.java / PadView.java  # live controller test + rumble settings
      ├─ PlayModeActivity.java      #   dimmed, touch-locked play screen
      ├─ DiagnosticsActivity.java   #   addresses, raw values, event log
      ├─ AppLog.java                #   in-app event log
      ├─ PadServer.java             #   TCP server :8471, 60 Hz stream
      ├─ PadMapper.java / KeyMap.java  # keycodes -> PS5 bits, user mapping
      ├─ PadFrame.java              #   32-byte wire format (phone side)
      ├─ RumbleFrame.java / Rumbler.java  # rumble from the PS5 -> pad or phone motor
      ├─ PadState.java              #   canonical pad state
      ├─ NetUtil.java               #   LAN address + /24 scan for :9021
      └─ Ps5Deployer.java           #   nc-style ELF deploy
```

## Credits & acknowledgements

The PS5 payload-side controller code — the **virtual DualSense** and the
`scePadVirtualDeviceInsertData` **"Ghostpad VDI path"** — is built heavily on
**[Ghostcontrol](https://github.com/StonedModder/Ghostcontrol-PS5-USB-Controller-Patcher)**
by **[StonedModder](https://github.com/StonedModder)**, which reads a USB HID
controller and injects it into a virtual DualSense on the PS5. This project
adapts that payload side to receive input over the network instead.

Payload tooling: [ps5-payload-sdk](https://github.com/ps5-payload-dev/sdk).

## Disclaimer

This is homebrew/jailbreak tooling for a **jailbroken PS5**. Use it on your
own hardware, at your own risk, and in line with your region's terms and
laws. Nothing here is affiliated with Sony.
