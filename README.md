# Revive360

A clean-room revival of the Samsung Gear 360 cameras, which Samsung's own apps no longer support
on current phones. A small controller runs on the camera, the camera puts up its own Wi-Fi
network, and an open Android app talks to it over a documented HTTP protocol: no Samsung app, no
Samsung account, no Samsung Accessory Protocol, no manual Wi-Fi joining. The protocol is
camera-neutral; each camera model gets its own adapter in the controller.

> **Status: experimental.** It works end-to-end on one camera model (see
> [Supported cameras](#supported-cameras)). Installing it means flashing a modified firmware.
> Read the [safety notes](#safety-read-first) and the [install guide](docs/INSTALL.md) before you
> start.

## Features

Android app (Kotlin, Jetpack Compose, Android 10+):

- **Pairing over the camera's own Wi-Fi**: the app finds the camera's network and joins it
  itself (Android companion device pairing). You enter the passphrase once.
- **Shooting**: photo, video, time-lapse and looping video, from a capture bar.
- **Camera settings** through the camera's own remote-control handlers, the same path the stock
  phone app used, so the camera handles every change as it would from its buttons.
- **Live view**: the camera's HEVC preview stream.
- **Gallery** of the files on the camera: thumbnails (photos and videos), download, delete,
  multi-select.
- **360 viewer**, and **stitching** of dual-fisheye photos to equirectangular JPEGs with GPano XMP
  metadata on the GPU, so other 360 viewers and sites recognise them.
- **Phone gallery** of the photos and videos already saved on the phone.
- **Camera info and control**: battery and storage, Wi-Fi band/channel/link details, time sync,
  auto power-off setting, reboot and power off over Wi-Fi.

Camera side:

- `g360rd`, a static ARM controller (C, libc + pthreads only) that serves the protocol, brings up
  the softAP, drives the LEDs and lists/serves/deletes files.
- An `LD_PRELOAD` shim and a plugin loaded into Samsung's stock camera app, so that capture,
  recording and settings go through the camera's own code instead of poking the hardware.
- A firmware overlay that only **adds** files to the stock firmware (nothing stock is changed or
  removed), and an SD-card configuration that switches it on.
- A simulator adapter and 139 smoke tests, so the controller and the app can be developed without
  a camera.

## Supported cameras

| Camera | Firmware | Status |
|---|---|---|
| Samsung Gear 360 (2016), SM-C200 | C200GLU0AQK1 ("AQK1") | **Supported, tested** (adapter `c200`) |
| Samsung Gear 360 (2016), SM-C200 | other versions (e.g. APE4) | Not yet: the custom firmware is built per firmware version |
| Samsung Gear 360 (2017), SM-R210 | any | Not yet: adapter `r210` is a stub (status and files only, no capture) |
| Any other model | any | Adapter `generic`: status and files only, no capture, no LEDs |

The controller detects the model at start and reports it (`adapter`, `adapter_status`) in
`GET /api/v1/info`. Contributions for other models and firmware versions are welcome: start from
`camera/src/adapter_r210.c` and the notes in [docs/firmware/](docs/firmware/).

## How it works

```
 Android app                         Gear 360 camera
 ┌──────────────────┐   camera's    ┌──────────────────────────────────────────┐
 │ Compose UI       │   own softAP  │ g360rd  (HTTP :8360, protocol v1)        │
 │ CameraController │◄────Wi-Fi────►│   ├─ files, thumbnails, status, LEDs     │
 │ CameraClient     │   HTTP/JSON   │   ├─ softAP + DHCP (netup.sh, hostapd)   │
 │ stitcher (GLES)  │   + streams   │   └─ adapter_c200 ──► shim + plugin      │
 └──────────────────┘               │          inside di-camera-app (stock UI) │
                                    │          capture, record, settings,      │
                                    │          live view                       │
                                    └──────────────────────────────────────────┘
```

- The modified firmware starts `g360rd` at boot (systemd unit `g360r.service`) when the SD card
  holds a configuration file; without it the camera behaves exactly like stock.
- `g360rd` brings up a WPA2 access point named `G360R-XXXXXX` (last three bytes of the camera's MAC)
  and serves the protocol in [docs/protocol.md](docs/protocol.md).
- For anything the stock camera app owns (shots, recording, settings, live view) `g360rd` sends
  requests to `libg360r_shim.so`, which systemd preloads into Samsung's `di-camera-app`. The shim
  runs them on the app's main loop through `libg360r_plugin.so`, which it loads from the SD card,
  so the plugin can change without a reflash.
- The app only speaks the protocol; it does not know which camera model is behind it.

More background: [docs/00_overview.md](docs/00_overview.md) (analysis overview) and the research
notes in [docs/firmware/](docs/firmware/) and [docs/app/](docs/app/).

## Screenshots

See [docs/screenshots/README.md](docs/screenshots/README.md) for the list of screenshots to add.

| Home and capture bar | Camera settings | Live view |
|---|---|---|
| ![Home](docs/screenshots/home.png) | ![Settings](docs/screenshots/settings.png) | ![Live view](docs/screenshots/liveview.png) |

| Gallery | 360 viewer | Phone gallery |
|---|---|---|
| ![Gallery](docs/screenshots/gallery.png) | ![360 viewer](docs/screenshots/viewer360.png) | ![Phone gallery](docs/screenshots/phone-gallery.png) |

## Install

> **Step-by-step guide: [docs/INSTALL.md](docs/INSTALL.md).** Follow it to install; this section
> is only a summary.

Installing has three parts: build and flash the modified firmware (once per camera), prepare the
SD card, and install the app. The developer reference for each step is
[camera/fw/README.md](camera/fw/README.md) (firmware) and [camera/README.md](camera/README.md)
(controller and SD card).

### Safety, read first

- **This is unofficial and experimental. You flash your camera at your own risk.**
- The modified firmware is built from the **stock firmware file for your exact model and
  firmware version**. Today that is only the SM-C200 with `C200GLU0AQK1_171121_1257_REV00_user.bin`.
  Do not flash an image built for another model or version. The stock firmware is Samsung's and is
  **not** distributed with this project: you need your own copy.
- The overlay only adds files to the rootfs (eMMC p9). The bootloader, kernel and recovery
  kernel are never touched, the stock camera app is unchanged, and the SD update path keeps
  working even if our boot script fails.
- **Recovery** is always possible: flash the stock `.bin` the same way (see
  [Recovery](camera/fw/README.md#recovery)).
- Flash only with the battery **charged to near full** and the **USB cable unplugged**.
- After the flash every power-on is a **cold boot**: nothing happens for about 30 s (no screen,
  no LED, no sound). The camera looks dead; it is not. Wait, and do not press power again.
- In the SD factory-hook mode (only used for one step after flashing, and by the diagnostic
  tests) the camera shows a factory **"self test"** screen: **do not press any camera button**
  while it is shown, as factory menus can reach format/reset functions. To abort, hold power
  until the camera turns off and remove the card.

### 1. Requirements

- Windows with Docker Desktop and Git Bash (the scripts also run from a Linux shell).
- A microSD card (FAT/exFAT) and a card reader.
- Your own copy of the stock firmware file for your camera (see above).
- An Android phone with Android 10 or later.

### 2. Build the controller, the shim and the plugin

From the repository root, with Docker running:

```powershell
powershell -ExecutionPolicy Bypass -File camera\build.ps1 -Smoke   # g360rd + simulator smoke test
```

```sh
sh camera/build.sh shim      # out/libg360r_shim.so   (goes into the firmware)
sh camera/build.sh plugin    # out/libg360r_plugin.so (loaded from the SD card)
```

Outputs go to `camera/out/`. The shim and the plugin link against the camera's own glibc, taken
from your stock firmware into `camera/.toolchain/aqk1/sysroot` (see `camera/Makefile`). Details:
[camera/README.md](camera/README.md#build), [docs/INSTALL.md](docs/INSTALL.md#2-build-the-controller-the-shim-and-the-plugin).

### 3. Build the modified firmware

Build the firmware tools image once, stage the controller overlay with
`camera/tools/fw_stage_controller.sh`, then run `camera/tools/fw_build.sh` on your stock `.bin`.
It produces `smc200.bin`, refuses to overwrite any stock file and verifies every added file. Exact
commands: [camera/fw/README.md, section 1](camera/fw/README.md#1-build-pc-git-bash-from-the-repo-root).

### 4. Flash it

1. Copy `smc200.bin` and an **empty** `fwupgrade.txt` to the root of the SD card (no `info.tg`).
   Check the copy's SHA-256.
2. Battery near full, USB unplugged, camera off.
3. Hold **Shutter**, press **power**. When "FW update" appears press OK, and do not press Back.
4. The three red LEDs are the normal progress indicator. The camera restarts by itself; keep the
   card in until it has.

Details: [camera/fw/README.md, section 2](camera/fw/README.md#2-flash-camera).

### 5. Invalidate the stock snapshot (once after every flash)

After a flash the camera still resumes Samsung's hibernation snapshot, so the new rootfs is not
used yet. A one-shot SD hook (`camera/fw/p8_invalidate.sh`) fixes this: 1 beep = start,
2 beeps = done, 3 beeps = error. Then power off and on again: from now on every power-on is a cold
boot with our files. Exact steps: [camera/fw/README.md, section 3](camera/fw/README.md#3-invalidate-the-snapshot-one-shot-sd-hook).

### 6. Prepare the SD card

```powershell
powershell -ExecutionPolicy Bypass -File camera\tools\prepare_sd.ps1 -Drive E: -Normal
```

It copies the controller to `<card>/g360r/` and creates `g360r/g360r.conf` with a random Wi-Fi
passphrase, which it prints: note it for the app. Copy `camera/out/libg360r_plugin.so` to
`<card>/g360r/` as well. The controller runs only when this file is on
the card; to switch it off remove the file or create `g360r.off` at the card root. A card for
normal use must **not** contain `info.tg` (`-Normal` takes care of it; the shell version
`camera/tools/prepare_sd.sh` has no such option yet, so delete `info.tg` and `cmd.tg` from the card
root after running it). A newer `g360rd`, `launcher.sh` or `netup.sh` on the card wins over the copy in the
firmware, so controller updates need no reflash; [camera/tools/wifi_update.sh](camera/tools/wifi_update.sh)
even updates `g360rd` over Wi-Fi. See [camera/fw/README.md, section 4](camera/fw/README.md#4-run-the-controller).

Power on with the card: after the cold boot the LEDs run a short signature and **one beep** means
the Wi-Fi is ready (three beeps = error; the logs are in `g360r/log/` on the card).

### 7. Install the app and pair

Build and install the app (below), open it, and pair: it finds the `G360R-XXXXXX` network and
asks for the passphrase from step 6 once.

## Build

### Android app

Requirements: Android Studio (or its bundled JDK, `jbr`, as `JAVA_HOME`) and the Android SDK.

```sh
./gradlew installDebug      # build and install on the connected phone
./gradlew testDebugUnitTest # unit tests (protocol client against a fake camera)
```

### Camera controller

Docker toolchain, see [camera/README.md](camera/README.md):

```powershell
powershell -ExecutionPolicy Bypass -File camera\build.ps1          # build
powershell -ExecutionPolicy Bypass -File camera\build.ps1 -Smoke   # build + smoke tests
```

`g360rd-host serve --sim <dir>` runs the whole controller against a simulated camera on the PC, so
the app can be developed without hardware ([Sim mode](camera/README.md#sim-mode-no-camera)).

## Project layout

```
app/                Android app (com.simoneluconi.revive360)
  src/main/java/…   ui/ (Compose screens), link/ (connection, Wi-Fi pairing),
                    protocol/ (HTTP client), media/ (thumbnails, live view, saving),
                    stitch/ (dual-fisheye to equirectangular, GLES)
camera/
  src/              g360rd: HTTP server, protocol API, per-model adapters (adapter_*.c)
  shim/             LD_PRELOAD shim and plugin for Samsung's di-camera-app
  sd/               SD card bootstrap: launcher, softAP bring-up, config example
  fw/               firmware overlays, flash helpers, build/flash procedure (README.md)
  tools/            SD preparation, firmware build, Wi-Fi update, smoke tests
  docker/           build toolchain images
docs/
  INSTALL.md        step-by-step install guide (firmware, SD card, app)
  protocol.md       phone <-> camera protocol (v1)
  00_overview.md    analysis overview and design decisions
  firmware/         camera-side reverse-engineering notes
  app/              notes on the stock phone app's protocols
  screenshots/      README screenshots
tools/slp_unpack.py unpacks Samsung SLP firmware containers (research helper)
```

## Disclaimer

This project is not affiliated with, endorsed by or supported by Samsung. "Samsung" and "Gear 360"
are trademarks of Samsung Electronics. No Samsung firmware, binaries or other proprietary files are
included in this repository; you need your own copy of the stock firmware to build the modified
image.

## License

**TODO: licence not chosen yet.** Until a licence is added, all rights are reserved by the author.
