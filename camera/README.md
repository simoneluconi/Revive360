# camera/ — Revive360 camera-side controller

`g360rd` is a small static ARM binary that runs on the camera as root, started from the SD
card through the factory hook (`info.tg` → `cmd.tg` → `shell script`). It implements
[docs/protocol.md](../docs/protocol.md) v1: info, status, capture (photo / RAW / RAW+photo),
file list, thumbnails, downloads with Range, delete, LEDs, and the debug log. Everything is
volatile. Pull the card and power-cycle, and the camera is stock again.

## Layout

```
camera/
  build.ps1 / build.sh      one-command build (Docker toolchain)
  Makefile                  used inside the container
  docker/Dockerfile         Debian bookworm + gcc-arm-linux-gnueabi + host gcc + curl
  src/                      C sources (libc + pthreads only)
    main.c                  subcommands (serve, detect, ledtest, thumb, net helpers)
    http.c api.c            HTTP/1.1 subset, routing, info, log
    files.c thumb.c         paths, list, download/Range, delete, EXIF/TIFF thumbnails
    status.c capture.c      battery/storage, synchronous capture with DCIM polling
    leds.c                  manual LED control + automatic feedback
    netif.c                 interface ioctls (MAC, flags, address) for the network script
    adapter*.c              per-model adapters: c200, r210 (stub), generic, sim
  sd/                       SD card bootstrap (LF line endings, enforced by .gitattributes)
    info.tg cmd.tg          trigger files (card root)
    g360r/start.sh          detach, leave di-camera-app's cgroup, copy payload to /tmp/g360r
    g360r/launcher.sh       vconf reset, LEDs, diagnostics, network, server, supervision
    g360r/g360r.conf.example
  tools/
    smoke_test.sh           sim smoke test (curl), run by `build -Smoke`
    prepare_sd.ps1 / .sh    copy everything to a card, generate the passphrase
  out/                      build output (gitignored): g360rd, g360rd-host, sd/
```

## Build

Docker Desktop must be running. From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File camera\build.ps1          # build
powershell -ExecutionPolicy Bypass -File camera\build.ps1 -Smoke   # build + sim smoke test
```

(or `sh camera/build.sh [smoke]` from bash). The first run builds the `g360r-build:1` image.

Outputs in `camera/out/`:

| File | What |
|---|---|
| `g360rd` | camera binary: `ELF 32-bit LSB executable, ARM, EABI5, statically linked, for GNU/Linux 3.2.0` (the camera runs 3.5.0). Static, so it does not depend on the camera's glibc 2.13 and also suits APE4/R210. |
| `g360rd-host` | x86-64 build for sim mode and the smoke test |
| `sd/` | ready-to-copy SD tree |

The build ends with `make check` (file/readelf: EABI5, ARM, no interpreter, ABI tag 3.2.0).

## Sim mode (no camera)

`g360rd serve --sim <dir>` runs the full server against a fake camera rooted in `<dir>`:
a seeded `sdcard/DCIM/100PHOTO/`, fake battery files under `sys/class/power_supply/`, five
fake LEDs. Captures write real (tiny, decodable) JPEGs with an EXIF thumbnail and SRW-like
TIFF files with a preview, slowly, like the camera's asynchronous writer. Edit
`<dir>/sys/class/power_supply/battery/{capacity,status}` while it runs to exercise the app.
Create `<dir>/SIM_CAPTURE_TIMEOUT` to force `504`. Rename `<dir>/sdcard` to get `503`.

To point the Android app (or a browser) at it:

```powershell
docker run --rm -p 8360:8360 -v "${PWD}\camera:/src" -w /src g360r-build:1 `
    out/g360rd-host serve --sim /tmp/sim --port 8360
```

Then use `http://<PC LAN IP>:8360/` from a phone, or `http://10.0.2.2:8360/` from the Android
emulator.

## SD card

Prepare a card (FAT/exFAT, inserted in the PC) after building:

```powershell
powershell -ExecutionPolicy Bypass -File camera\tools\prepare_sd.ps1 -Drive E: [-LedTest on|off] [-SelfTest on|off] [-NewPsk]
```

It prints the Wi-Fi passphrase (random 8 digits, created once and kept on later runs). The
user enters it once in the app.

```
<card root>/
  info.tg                 "cmd.tg"
  cmd.tg                  "shell script /sdcard/g360r/start.sh"
  g360r/
    start.sh launcher.sh  bootstrap (copied to /tmp/g360r at boot)
    g360r.conf            passphrase, channel, LED roles, test switches
    bin/g360rd            the binary
    log/                  everything we log (see below)
  DCIM/...                the camera's own photos (unchanged)
```

Logs written to `g360r/log/`:

| File | Content |
|---|---|
| `start.log` | the bootstrap: cgroup before/after, payload copy |
| `g360r.log` | launcher and server, timestamped (previous boot: `g360r.prev.log`) |
| `state.txt` | the last milestone reached (handy if the camera was powered off mid-run) |
| `selftest/` | raw HTTP responses of the on-camera self-test |
| `g360rd.stderr` | crash output of the server, if any |

The controller is detected as adapter `c200` with `adapter_status: "experimental"`. Switch it to
`supported` in `src/adapter_c200.c` once the tests below pass.

## Network bring-up (softAP): NOT INCLUDED YET

The launcher sources `g360r/netup.sh` if it exists. That script must define `netup()` (bring up
the protocol §1 softAP and DHCP, then set `AP_SSID AP_IF AP_IP AP_STRATEGY`) and `dhcp_check()`.
**This delivery does not contain `netup.sh`.** Without it the server still starts, in an error
LED state, and everything except the phone connection can be tested on the camera (Test 1).
`g360rd` already provides the primitives such a script needs: `mac`, `ssid`, `ifflags`, `ifup`,
`ifdown`, `ifaddr`, `waitif`.

## Hardware tests (SM-C200)

### Safety, read before every test

- **Abort at any time:** hold the power button until the camera turns off, then remove the
  card. Without the card the camera boots stock.
- While our code runs, the camera shows the factory **"self test"** screen. **Do not press any
  camera button** while it is shown: factory menus can reach format/reset functions.
- Start each test with a charged battery. Our launcher re-enables the low-battery auto
  power-off, but don't leave the camera running unattended.
- Never "fix" a hang by repeated power cycles with the card in. Remove the card first.

### Test 1: boot, LED mapping, on-camera API self-test (no Wi-Fi needed)

Prepare the card with both diagnostics on:

```powershell
powershell -ExecutionPolicy Bypass -File camera\tools\prepare_sd.ps1 -Drive E: -LedTest on -SelfTest on
```

Have paper ready to note what the LEDs do. Times are from the moment you power on.

| Time | What happens | What you do |
|---|---|---|
| 0:00 | Power on with the card inserted. Normal boot. | Wait. Don't touch the buttons. |
| ~1:00 | Factory "self test" screen. **1 beep** = our launcher started. The LEDs start a slow blink (1 s on / 1 s off). | Start watching the LEDs. |
| +5 s | **Marker:** a beep, then **all LEDs on for 3 s**, then off. | Get ready: the 5 steps follow. |
| +10 s … +45 s | **5 steps.** Each step: a beep, then **one LED on for 5 s**, off for 2 s. | For each step 1–5, write down which physical LED lit, and its colour (e.g. "step 2: top status LED, green"). |
| +47 s | **Marker:** two short all-on flashes. | Get ready for 5 more steps. |
| +50 s … +90 s | **5 more steps**, driven by the firmware's own LED command (status red, green, blue, front, rear). Each step: a beep, then a LED for 5 s. | Write down what lit for steps 6–10. Something not lighting is a valid result: write "nothing". |
| ~+1:35 | **3 "cancel" beeps** (a different sound), then the LEDs blink in the error pattern (250 ms). This is **expected in Test 1**: there is no Wi-Fi script yet. The server is running anyway. | Wait. |
| next ~1–2 min | **Self-test.** The camera takes **3 real shots** (photo, RAW+photo, RAW). You may hear the shutter. | Keep the camera still. Don't press anything. |
| end | **1 beep** = self-test finished. | Wait 10 s, then power off (hold power). |

If you hear no beeps at all after 3 minutes, power off. That is a result too: send what is on
the card.

**Send back:**
1. Your LED notes for steps 1–10.
2. The whole `g360r/log/` folder from the card (zip it).
3. A listing of the newest files in `DCIM/100PHOTO/` (names and sizes). Keep the 3–4 new
   files: the `.SRW` and `.JPG` let us check the RAW and thumbnail handling.

**What we learn:** that the hook-to-launcher-to-server chain works from tmpfs; the `led.0..4`
mapping (and whether `st app 360 led` drives the same GPIOs: `g360r.log` contains
`LEDTEST readback` lines); the battery sysfs attributes (`power_supply ...` lines); whether
`st app nx capture` works in factory mode and which files it writes (`selftest/04..06`);
real EXIF/SRW thumbnail extraction (`selftest/09_*`).

After the test, fill in `led_roles=` in `g360r/g360r.conf` (or tell us the mapping) and set
`ledtest=0`.

### Test 2: phone connection (blocked: needs `netup.sh`)

Runs once the softAP script is added. Prepare the card without diagnostics:
`prepare_sd.ps1 -Drive E: -LedTest off -SelfTest off`.

| Time | What happens | What you do |
|---|---|---|
| 0:00 | Power on with the card. | Wait. |
| ~1:00 | 1 beep, LEDs slow blink. | Wait. |
| ~1:30 | **2 beeps**, LEDs steady = Wi-Fi up and server ready. **3 "cancel" beeps** + 250 ms error blink = Wi-Fi failed. Power off and send the logs. | On the phone, open Wi-Fi settings. |
| | Network `G360R-XXXXXX` appears. | Join it with the passphrase that `prepare_sd` printed. Android will say "no internet": keep the connection. |
| | | In the phone's browser, open `http://192.168.107.1:8360/api/v1/info`. Expected: a JSON page with `"protocol":1` and `"adapter":"c200"`. The LEDs do a short double blink while the phone talks to the camera. |
| | | Open `http://192.168.107.1:8360/api/v1/status` (battery and card) and `http://192.168.107.1:8360/api/v1/files`. |
| | | From a laptop on the same network: `curl -X POST -d "{\"mode\":\"photo\"}" http://192.168.107.1:8360/api/v1/capture` (the LEDs blink fast while it works), then `curl -X POST -d "{\"mode\":\"raw+photo\"}" ...`, then download one file: `curl -O http://192.168.107.1:8360/files/DCIM/100PHOTO/360_XXXX.JPG`. |
| end | | Power off (hold power). |

**Send back:** whether the network appeared and whether it asked for WPA2, the phone's IP on
that network (Wi-Fi details), screenshots or copies of the three JSON pages, the curl output,
and the `g360r/log/` folder.

### LED behaviour (LEDs in `auto`)

| Pattern | Meaning |
|---|---|
| slow blink 1 s / 1 s | controller starting |
| steady on | Wi-Fi up, server listening |
| short double blink every 2 s | a client request in the last 10 s |
| fast blink 100 ms | capture in progress |
| 250 ms blink on the error LED(s) | error (e.g. Wi-Fi bring-up failed) |

Until the mapping is known, no LED shows the feedback (`led_feedback=none`, the default),
so the table above does not apply yet. Instead the launcher drives the logical status LED
through the firmware (`st app 360 led status <r|g|b> <on|off>`, which maps to the right GPIO
itself): **blue** = starting, **green** = Wi-Fi up and server ready, **red** = error.
`led_feedback=all` only covers LEDs with a known role in `led_roles=`: an unidentified `led.N`
is never driven automatically, because one of them may be a GPIO that is not an LED.

The Test 1 reset loop (reset ~27.5 s after boot) was the hardware watchdog, not the LEDs:
the stock app pets it, factory mode does not. `start.sh` and `launcher.sh` now write
`0 > /sys/devices/platform/jack/set_watchdog` first thing, and the supervisor re-writes it
every 5 s. `start.log` and `g360r.log` record the `watchdog:` line.

## Command reference

```
g360rd serve [--sim DIR] [--conf FILE] [--log FILE] [--port 8360] [--bind 0.0.0.0]
             [--adapter c200|r210|generic] [--net-error] [--quiet]
g360rd detect [--sim DIR] [--conf FILE]      adapter + capabilities JSON
g360rd ledtest [--log FILE] [--count 5] [--on-ms 5000] [--gap-ms 2000] [--no-st] [--beep WAV]
g360rd thumb <image> <out.jpg>               thumbnail extraction check
g360rd mac|ssid|ifflags|ifup|ifdown <if>     interface helpers (no ifconfig on PATH)
g360rd ifaddr <if> <ip> <mask>    g360rd waitif <if> <seconds>
g360rd version
```
