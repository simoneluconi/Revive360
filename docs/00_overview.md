# Revive360: analysis overview

## CURRENT STATUS AND DECISION (2026-10-24) — read this first; it supersedes the options discussion below

Analysis and architecture are DONE; the build is starting. The decided design (see memory/gear360-project.md and docs/firmware/08,09):

- **Approach = Route C, validated on real hardware (SM-C200, fw AQK1).** The camera's own factory maintenance service runs a startup script from the SD card (`/sdcard/info.tg`), so our script runs with the camera service's privileges (uid 0) in factory service mode, with di-camera-app still running.
- **The helper is a factory-mode CONTROLLER SERVICE.** No Samsung Accessory Service, no SAP, it does not attach to or modify other running processes, no app restart (a mid-session restart stalls the camera — observed in probe 3), and no firmware reflash. All state lives in volatile memory; pulling the SD card fully restores the stock behaviour.
- **Camera networking is ours:** softAP via on-device hostapd + udhcpd/dnsmasq on wlan0. The phone joins with WifiNetworkSpecifier (+ CompanionDeviceManager) and binds sockets to it.
- **Imaging** via `st app nx capture` (stills + RAW); **status** via sysfs (`/sys/class/power_supply/*`) + statvfs on `/opt/storage/sdcard`; **LEDs** via `/sys/class/leds/led.0..4`; **keys** readable at `/dev/input/event0`.
- **Build-critical:** the stock rootfs has no nc/httpd/python/perl and `/bin/sh` is bash (cannot listen), so the camera-side control/file server is a small **cross-compiled ARM binary** (armel, glibc 2.13, libc-only), not shell.
- **v1 scope (user-locked):** auto-connect + still capture + RAW + battery/storage + file list/thumbnail/download/delete + basic LED feedback. **Live view, exclusive keys (EVIOCGRAB), custom OLED drawing, and firmware update are DEFERRED.**
- **Multi-camera from day one:** structure for SM-C200 (APE4 + AQK1) now and SM-R210 (2017) later, via a per-model adapter on the camera and a capability-driven phone UI (see docs/firmware/06).
- App: Kotlin + Jetpack Compose, stack/design mirroring the user's BtCarKey project; package `com.simoneluconi.revive360`, display name "Revive360".

Everything from "## 1. Architecture of the stock system" onward is the original REVERSE-ENGINEERING background that led here — accurate as analysis of the STOCK app/camera, but it is not the plan. The plan is the box above.

---

This document summarises how the Samsung Gear 360 2016 (SM-C200) talks to its phone app. It combines two sources:

- the decompiled Android app, Gear 360 Manager 1.5.00.1 as patched by Kieron Quinn;
- the camera firmware, C200GLU0APE4, which runs Tizen 2.2.

It also lists the design options that were considered for a clean-room replacement app on modern Android. The target phone is a Galaxy S25 Ultra on Android 17 (SDK 37).

Detailed reports:

| File | Topic |
|---|---|
| [app/01_bt_control_protocol.md](app/01_bt_control_protocol.md) | JSON control messages over SAP, handshake, msgId table |
| [app/02_wifi_data_protocol.md](app/02_wifi_data_protocol.md) | Wi-Fi link setup, UPnP/SOAP, live view, file download, FW update |
| [app/03_media_pipeline.md](app/03_media_pipeline.md) | File formats, lens calibration, stitching, 360 metadata, gallery |
| [app/04_sap_wire_protocol.md](app/04_sap_wire_protocol.md) | Samsung Accessory Protocol framing, handshake, security layers |
| [firmware/](firmware/) | Camera-side findings (separate firmware RE session) |

## 1. Architecture of the stock system

```
Phone app ──binder──> Samsung Accessory Service (com.samsung.accessory)
                          │  SAP over RFCOMM (UUID a49eb41e-…-bb80a90cdf00)
                          ▼
                     Camera sap-server ── di-camera-app
                          │
   BT: JSON control on SAP channel 204 (handshake, status, date/time, Wi-Fi bring-up, config)
   Wi-Fi: camera softAP (or Wi-Fi Direct) → UPnP/SOAP :7676, live view :7679, files :9001
```

Bluetooth is only used for the control channel and to bring up Wi-Fi. Shooting, live view, browsing, downloading and deleting all go over Wi-Fi as UPnP/SOAP plus plain HTTP.

## 2. Key findings

### Bluetooth control (SAP channel 204)
- Every message is a JSON envelope: `{"title","description","type":"object","properties":{"msgId",…}}`.
  - The camera rejects a message that has no `title`.
  - The titles must match byte for byte (listed in app/01 §0.1).
- Handshake:
  1. Phone sends `info` ("Phone Device information Message"), then `widget-info-req`.
  2. Camera sends `date-time-req`, `widget-info-req`, its own `info`, and `config-info`.
  3. Phone answers each of them. The `info` ack ("Camera Device info response Message") makes the camera log "3-way success!".
  4. Phone sends `cmd-req` `liveview` or `mobilelink` with `execute` to bring up Wi-Fi.
  5. Camera sends `device-desc-url`.
- Commands the C200 implements over BT: `mobilelink`, `liveview`, `fw-download`, the matching `dismiss`, `config`, and `power-save-off`. Every other command is "Not Implemented" in the firmware.
- `config` reads only the first item per message, so the app must send one `cmd-req` per setting.
- **The BT remote shutter is dead on the C200 (APE4).**
  - `shot-req` is parsed but has no handler.
  - The `remote-shot-req` handler is an empty stub.
  - Shutter and recording must use UPnP SOAP over Wi-Fi: `Shot`, `StartRecord`, `StopRecord`.

### Wi-Fi link
- The phone picks the mode with `info.properties.wifi-direct.enum`.
- **Wi-Fi Direct** (phone is GO at 192.168.49.1, camera at 192.168.49.10):
  - The camera hard-rejects any peer whose MAC differs from the `wifi-mac-address` the phone sent over BT. There is no prompt fallback.
  - On Android 10+ a non-privileged app only sees 02:00:00:00:00:00 for its own P2P address. Without root, Shizuku or a manually entered MAC this mode is not usable.
- **Camera softAP** (the recommended default):
  - SSID is `AP_<ProjectName>(XX:XX:XX)`.
  - Password is 8 digits, WPA2-PSK, generated once and persisted on the camera.
  - The camera sends both over BT in its `info` reply.
  - The AP comes up only after a `cmd-req` execute (`liveview`, `mobilelink` or `fw-download`).
  - The camera IP is probably 192.168.107.1. Always take it from `device-desc-url` or from the network's LinkProperties.
- The camera cannot join a phone hotspot in stock firmware.
- Plan for automatic link setup:
  - Connect with `WifiNetworkSpecifier` and bind the process or each socket to that network, so mobile data keeps working.
  - Add a CompanionDeviceManager Wi-Fi association so the approval dialog appears only the first time (to verify on device).

### Over IP
- UPnP description at `http://<cam>:7676/smp_2_`. The phone fakes an SSDP NOTIFY.
  - Description GET headers: `User-Agent: SEC_RVF_ML_<mac>` and `Access-Method: manual`.
  - The camera does not validate the User-Agent on 7676/7679/9001. The firmware's 401 paths belong to other servers (mode change, Selective Push, OSC).
- SOAP actions:
  - `SetOperationState` (changeToRVF / changeToML), `GetInfomation` (returns the stream URLs), `Shot` (returns the new file URL), `StartRecord`, `StopRecord`, `Browse`, `X_DeleteFile`.
  - About 60 `X_Set*` settings actions.
- Events: the camera subscribes to an HTTP server on the phone at `:8059/evetSub`.
- Live view:
  - HTTP GET of `livestream_high.avi` / `_middle`.
  - The body is Samsung's "TTTS" container. The phone-side decoder suggests HEVC; to confirm with a capture.
  - Default size 2560x1280.
- Files:
  - Originals are a plain HTTP GET on :9001.
  - Thumbnails and screen-nails come from the DIDL `<res>` URLs.
- Firmware update:
  1. Phone serves the .bin over HTTP on :15401.
  2. Phone sends BT `fw-server-info`.
  3. Camera reports `fw-download-complete-info`.
  4. Phone sends BT `install-req`.

### Media
- The C200 never stitches.
  - Photos: dual-fisheye JPEG, 7776x3888.
  - Video: H.264 MP4 at 3840x1920@30, 2560x1280@60/30, or 1920x960@30.
- Lens calibration is embedded in every file:
  - JPEG: MakerNote 0x0012 holds 40 int32 values; 0x0013 holds the shading-table index.
  - MP4: `udta` boxes `opax`, `opai` and `vrot`.
  - The 40 values are factory EEPROM data (EEPA_OPTICAL_AXIS_01..40), written by ArcSoft's ADE_ImageStitch_WriteMeta_OpticalAxis.
    - Likely layout: a header (0..7), one 13-value block per lens (12..20 and 25..33 share a shape) and a trailer (34..39).
    - Field meaning and scale are not in camera code. They must be inferred from the phone-side ArcSoft usage plus real samples.
  - `vrot` holds per-sample pitch/roll/yaw as big-endian rationals at 0.1° precision. The camera has a 3-axis gyro.
- Stock stitching uses proprietary ArcSoft/Samsung native libraries. The replacement will use its own GLSL dual-fisheye→equirect shader, with MediaCodec decode → GL → encode for video.
- 360 metadata: GPano XMP for photos, Spherical Video V1 for video.
- The stock app deletes the original after stitching. The new app must keep originals.

### Samsung Accessory Protocol (SAP)
- Framing, session multiplexing, the handshake and service connection are fully recovered (app/04). They can be reimplemented on a plain `BluetoothSocket`.
- App payloads are cleartext (no SECUREBYTE).
- **Blocker: framework device authentication (Samsung WSM white-box crypto, libwsm2).** The camera side was confirmed in firmware/04_sap_auth_http_calibration.md:
  - WSM is mandatory regardless of version. The camera only accepts SAP version 0x201.
  - The camera always starts a WSM challenge after the Peer Description.
  - SendConnectionConfirmation is sent only if the client response verifies.
  - The legacy-version fallback (old option A) is therefore not possible.
- The camera does not check the app peer-agent X.509 key (no whitelist).
- CRC16 is CRC-16/ARC (poly 0xA001 reflected, init 0) and is used only after WSM.
- Why the current setup is unreliable:
  - Samsung Accessory Service (targetSdk 24) expects to be a privileged, persistent system app.
  - It lacks the BLUETOOTH_CONNECT and BLUETOOTH_SCAN permissions.
  - Its background service start is illegal on Android 8+.

## 3. Options for the BT control channel

| Option | Description | Pros | Cons |
|---|---|---|---|
| ~~A. Own SAP stack, legacy version~~ | ~~Negotiate version 512 to skip device auth~~ | | **Ruled out: the camera always requires WSM** |
| B. Samsung Accessory Service as transport | Use the SAP SDK like the stock app, with a modern, robust wrapper around it | Stock camera, known to work | Keeps the external dependency that works poorly |
| C. SD-card camera helper | the camera's factory maintenance service runs a script from the SD card (`info.tg`) with its own privileges. `/usr/bin/st` already drives di-camera-app ("st app capture", "st app wifidirect start", "st app mode_360", "st app set") | No SAP and no Samsung code on the phone; BT remote shutter possible | One-time SD preparation; untested on hardware; softAP/DLNA start command not found yet (libshell-command IPC not reversed) |
| D. Own SAP stack + Samsung libwsm2 | Our own SAP framing, with Samsung's WSM library (shipped in SAS) bundled for the auth step | No SAS app | Ships proprietary Samsung code; personal use only |

Wi-Fi side: use camera softAP + `WifiNetworkSpecifier` in options B and D.

Option C status (details in firmware/03_sd_card_hook.md §3):
- **Networking without BT: solved on paper.**
  - The undocumented `st app softap start` brings up the camera softAP with the persisted 8-digit WPA2 password.
  - Alternatively, `st app wifidirect start` starts an autonomous GO. A legacy WPA2 client joining it most likely bypasses the MAC whitelist (inferred).
  - The passphrase can be read or set with `wpa_cli`.
- **Open problem: the UPnP/RVF stack does not start without BT.**
  - `CUINETFuncDLNA::Start` is only reached from the BT `cmd-req` handler; `UI_ModeServerStart` is an empty stub on the C200.
  - Candidates:
    - find an IPC into CUINETFuncDLNA;
    - emulate the BT callback event;
    - replace UPnP with our own on-camera HTTP/file server, using `st app capture` etc. for control.
  - The last one makes live view hard, because it needs the camera's RVF pipeline.

## 4. Experiments needed on hardware

1. BT HCI snoop log of a stock connection (Android developer options). It shows the negotiated SAP version, clMode/authMode and the real CRC usage.
2. Option C test: map the libshell-command IPC, then test the `info.tg` startup file with a harmless script. Also try joining "st app wifidirect start" as a legacy client.
3. SoftAP mode: confirm the camera IP, the ports, whether the BSSID equals the camera `wifi-mac`, and the live-view codec (capture one TTTS stream).
4. A few original C200 JPEG and MP4 files straight from the SD card, to decode the calibration and validate the stitcher.
5. Option C test: a harmless SD-card script, e.g. one that writes a file, to confirm the script runs.
