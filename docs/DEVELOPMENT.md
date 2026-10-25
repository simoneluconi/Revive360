# Revive360: for developers

How the project works and how to build it. The [README](../README.md) is the short version for
users; the [install guide](INSTALL.md) has every step of putting it on a camera.

## What is in it

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

## Supported cameras, in detail

| Camera | Firmware | Status |
|---|---|---|
| Samsung Gear 360 (2016), SM-C200 | C200GLU0AQK1 ("AQK1") | **Supported, tested** (adapter `c200`) |
| Samsung Gear 360 (2016), SM-C200 | other versions (e.g. APE4) | Not yet: the custom firmware is built per firmware version |
| Samsung Gear 360 (2017), SM-R210 | any | Not yet: adapter `r210` is a stub (status and files only, no capture) |
| Any other model | any | Adapter `generic`: status and files only, no capture, no LEDs |

The controller detects the model at start and reports it (`adapter`, `adapter_status`) in
`GET /api/v1/info`. Contributions for other models and firmware versions are welcome: start from
`camera/src/adapter_r210.c` and the notes in [docs/firmware/](firmware/).

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
  and serves the protocol in [docs/protocol.md](protocol.md).
- For anything the stock camera app owns (shots, recording, settings, live view) `g360rd` sends
  requests to `libg360r_shim.so`, which systemd preloads into Samsung's `di-camera-app`. The shim
  runs them on the app's main loop through `libg360r_plugin.so`, which it loads from the SD card,
  so the plugin can change without a reflash.
- The app only speaks the protocol; it does not know which camera model is behind it.

More background: [docs/00_overview.md](00_overview.md) (analysis overview) and the research
notes in [docs/firmware/](firmware/).

## Build

### Android app

Requirements: Android Studio (or its bundled JDK, `jbr`, as `JAVA_HOME`) and the Android SDK.

```sh
./gradlew installDebug      # build and install on the connected phone
./gradlew testDebugUnitTest # unit tests (protocol client against a fake camera)
```

### Camera controller

Docker toolchain, see [camera/README.md](../camera/README.md):

```powershell
powershell -ExecutionPolicy Bypass -File camera\build.ps1          # build
powershell -ExecutionPolicy Bypass -File camera\build.ps1 -Smoke   # build + smoke tests
```

`g360rd-host serve --sim <dir>` runs the whole controller against a simulated camera on the PC, so
the app can be developed without hardware ([Sim mode](../camera/README.md#sim-mode-no-camera)).

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
