# Revive360 – Bluetooth control plane (our design: BLE GATT)
> **Context:** interoperability / repair of the owner's own Samsung Gear 360, abandoned by the
> maker. Design for our own modified firmware (g360rd running as root) talking to our own
> Android app. No Samsung Accessory Service / SAP, no third-party systems.

Companion to `01_camera_bt_protocol.md` (the stock SAP protocol RE). This doc is the DESIGN for
the BT control channel we build ourselves, now that we own the firmware.

## Why not the stock SAP path
From `01_camera_bt_protocol.md`: the stock Bluetooth channel is Samsung Accessory Protocol (SAP)
over RFCOMM with JSON. On the C200 it only negotiates Wi-Fi (liveview/mobilelink via DLNA); the
**remote-shot handler is an empty stub** (`handle_bt_app_remote_shot_req`), so shutter-over-BT
never worked on stock. SAP also needs the Samsung Accessory framework on the phone — exactly what
the project avoids. We own g360rd now, so we build our own channel instead.

## The camera's BT stack supports what we need (verified in the AQK1 rootfs)
- **BlueZ 5.18** (`libbluetooth.so.3.18.3`) with **BLE GATT server + LE advertising** — bluetoothd
  strings: "Start GATT server", "Starting GATT server", "Registered advertisement at path",
  "Set advertising data", "New incoming LE ATT connection", "GATT server sending
  notification/indication".
- **Tizen CAPI BT** (`libcapi-network-bluetooth.so.0.1.84`) exposes the full app-facing API a
  compiled g360rd can link directly:
  - GATT server: `bt_gatt_server_initialize/create/register_service/notify/send_response`,
    `bt_gatt_server_set_value_changed_cb`, `bt_gatt_server_set_read_value_requested_cb`,
    `bt_gatt_service_create/add_characteristic`, `bt_gatt_characteristic_create/add_descriptor/
    set_properties/set_write_type`.
  - LE advertising: `bt_adapter_le_create_advertiser`, `bt_adapter_le_add_advertising_service_uuid`,
    `bt_adapter_le_set_advertising_connectable/device_name/mode`, `bt_adapter_le_start_advertising`.
- `bt-service`/`bluetoothd` run the stack; tools exist for bring-up/validation:
  `bluetooth-advertising-test`, `bluetooth-gatt-test`, `bt_le_manager`, `btgatt-client`, `btmgmt`.

So: **camera = BLE GATT peripheral; our Android app = BLE central.** BLE is low-power (BT can
stay on for instant remote shutter without Wi-Fi), needs no Classic pairing dance, and is clean on
Android 17 (BluetoothGatt central APIs).

## Role split: BLE = control plane, Wi-Fi = data plane
- **BLE** carries small, latency-sensitive control + status: shutter, mode, record start/stop,
  battery/storage/state, time sync, and the **Wi-Fi AP credentials** so the phone can join on
  demand. Always available while the camera is on.
- **Wi-Fi** (our hostapd AP, doc 02) carries bulk: file download, and later live view. Brought up
  only when needed (a BLE command starts it), so the camera isn't burning the radio when idle.

This replaces the whole Samsung SAP Wi-Fi-negotiation + "join the camera's Wi-Fi by hand" flow:
the phone reads the SSID/PSK over BLE and auto-joins (Android WifiNetworkSpecifier/suggestion).

## Proposed GATT service

One primary service, 128-bit UUIDs (placeholder base `g360r`, finalize before coding):

```
Service  G360R Control      6700xxxx-...  (one vendor 128-bit base, 16-bit slots below)
```

| Char | UUID slot | Props | Payload | Meaning |
|---|---|---|---|---|
| CMD | 0x0001 | Write / WriteNoResp | 1-byte opcode + optional args (LE) | control command, see opcodes |
| STATUS | 0x0002 | Read / Notify | packed struct (see below) | battery/storage/state; notify on change |
| INFO | 0x0003 | Read | TLV/JSON | model, fw, serial, capabilities, caps/resolutions |
| WIFI | 0x0004 | Read / Notify | TLV: ssid, psk, ip, state | AP credentials + AP state (set by START_AP) |
| TIME | 0x0005 | Write | u32/u64 LE local-epoch-seconds (+opt tz) | set clock (ties to device_set_property RTC, doc on time) |
| EVENT | 0x0006 | Notify | 1-byte event code + args | async events: capture-done, error, heat-warning, low-batt |

### CMD opcodes (phone → camera)
```
0x01 SHUTTER            single still (JPEG+RAW per current mode)
0x02 AF                 (optional) half-press / focus
0x03 VIDEO_START
0x04 VIDEO_STOP
0x05 CONTINUOUS_START
0x06 CONTINUOUS_END
0x10 MODE_SET  arg: photo|video|timelapse|...  (+ 360/single-lens)
0x11 QUALITY_SET arg: jpeg|raw|raw+jpeg, resolution index
0x20 WIFI_AP_START     -> g360rd runs netup.sh; WIFI char then carries ssid/psk/ip
0x21 WIFI_AP_STOP
0x30 POWER_KEEPAWAKE on|off   (reset/disable the idle power-off timer while controlling)
0x31 POWEROFF
```
g360rd executes each by the capture path we validated (normal-mode `st` / the capture engine; or
the PIM path) and by netup.sh for Wi-Fi. STATUS/EVENT notify the result.

### STATUS payload (camera → phone, Read/Notify)
Packed little-endian, versioned first byte:
`ver(u8), battery_pct(u8), batt_level(u8 0-5), charging(u8), storage_free_mb(u32),
storage_total_mb(u32), state(u8: idle|capturing|recording|busy), mode(u8), error(u8)`.
Sources (doc 09/08): battery `/sys/class/power_supply/*`, storage statvfs on
`/opt/storage/sdcard`, state/mode from the capture engine. Notify on change + a slow heartbeat.

## Security
BLE link: use BlueZ bonding (Just-Works or passkey) so only a paired phone controls the camera;
plus an app-level token on first connect if we want to re-key. For v1, bond + a short pairing
confirmation on the camera OLED (doc 09 draw path) is enough. Never advertise the Wi-Fi PSK in the
clear in the advertisement — it lives only in the WIFI characteristic, readable after bonding.

## Open validation points (hardware, when we get there)
1. Confirm `bluetoothd` + `bt-service` come up in our normal-mode sidecar environment and the HCI
   (Broadcom, via `hciattach`) initialises — `hciconfig hci0 up`, then
   `bluetooth-advertising-test` / `bt_le_manager` to prove LE advertising works on this chip.
2. g360rd as a GATT server: does the Tizen CAPI need the app to be a registered Tizen app
   (appframework) or will it work from a plain root daemon? If the CAPI insists on app context,
   fall back to the **BlueZ D-Bus GATT API** (org.bluez `GattManager1.RegisterApplication`,
   `LEAdvertisingManager1`) directly — bluetoothd supports it (strings confirm), no app context.
3. GATT MTU / max write + notify size on this BlueZ (default 23, negotiate up) — keep payloads
   small or chunk INFO.
4. Coexistence: ensure our advertiser/GATT server doesn't collide with bt-service's own
   advertising; we may run our own advertiser only, or register through bt-service.
5. Does BLE peripheral + our Wi-Fi AP run simultaneously on the shared Broadcom combo chip without
   starving either (BT/Wi-Fi coexistence)? Test AP up + BLE connected together.

## Bottom line
A custom **BLE GATT peripheral in g360rd** is the right BT design: it finally delivers remote
shutter over BT (impossible on stock), hands the phone the Wi-Fi AP credentials to kill the manual
join step, and keeps control low-power with Wi-Fi reserved for bulk — all without Samsung SAP.
The camera's BlueZ 5.18 + Tizen CAPI already provide the GATT-server + advertising APIs; the main
unknowns are hardware bring-up details (points above), not whether the stack can do it.

## FROZEN CONTRACT v1 (agreed with the app/g360rd session)

### UUIDs (final)
Vendor base `d9c7XXXX-5a3b-4e21-9f6d-7c8e0a1b2c3d`, 16-bit slot = XXXX:
```
Service  d9c70001-5a3b-4e21-9f6d-7c8e0a1b2c3d
CMD      d9c70002-...   Write-with-response
STATUS   d9c70003-...   Read / Notify
INFO     d9c70004-...   Read (long read / Read Blob)
WIFI     d9c70005-...   Read / Notify (long read)
TIME     d9c70006-...   Write
EVENT    d9c70007-...   Notify
```
Advertise the Service UUID + local name `G360R-<last6 BT-MAC>` so the app filters/shows it.

### Access control (BlueZ enforces via characteristic flags)
- **CMD, TIME, WIFI**: require an **encrypted, bonded** link (flags: encrypt-authenticated
  read/write). WIFI carries the PSK, so never advertise it; readable only after bonding.
- **INFO, STATUS**: readable without bonding (plain read/notify) so the app can show the camera
  in the scan list and basic state before pairing.

### Bonding + pairing window (camera side, g360rd)
- Default: not bondable; unbonded centrals that touch CMD/TIME/WIFI are rejected by BlueZ (no
  encryption) and new bonds are refused.
- **Long-press Menu** opens a 60 s pairing window: g360rd sets the adapter pairable/bondable,
  registers a Just-Works agent (NoInputNoOutput), and draws "PAIR" on the OLED
  (/dev/ssd1309-ctrl, doc 09). Window closes on first successful bond or after 60 s (pairable
  off). Menu long-press is read from /dev/input/event0 with EVIOCGRAB (doc 09). OLED passkey is
  a later hardening step if Just-Works-in-window proves weak.

### Payload sizes (fit default ATT MTU 23 → ≤20 B for the small ones)
- CMD: `opcode(u8)` + optional args, ≤20 B. Write-with-response: the app gets an ATT error for a
  bad/busy opcode; the actual result arrives on EVENT.
- STATUS: packed LE `ver,battery_pct,batt_level,charging,storage_free_mb(u32),
  storage_total_mb(u32),state,mode,error` (≤20 B). Notify on change, max 1/s, heartbeat 15 s.
- TIME: `int64 utc_ms LE + int16 tz_offset_min LE` (10 B) — identical semantics to HTTP §5.10;
  the camera adapter converts to local (clock_local).
- EVENT: `event_code(u8)` + args, ≤20 B (capture-done, error, heat-warning, low-batt).
- INFO, WIFI: may exceed 20 B; the app uses Read Blob (long reads). INFO = model/fw/serial/caps;
  WIFI = ssid/psk/ip/state TLV.

### CMD opcodes → g360rd action (mapping; g360rd session implements)
Normal-mode capture goes through `st` (the validated path is `st app nx capture run count 1`);
factory mode would use dfms. Mapping:
```
0x01 SHUTTER        -> (ensure quality per current mode) st app nx capture run count 1
0x02 AF             -> optional half-press/focus via the st key path (if wired)
0x03 VIDEO_START    -> st movie/record start (st app nx capture mov ...)
0x04 VIDEO_STOP     -> st movie/record stop
0x05 CONT_START / 0x06 CONT_END -> continuous-shot start/end
0x10 MODE_SET  arg  -> st app mode_360 <photo|video|timelapse> (+360/single)
0x11 QUALITY_SET arg-> st app nx capture quality <raw|fine|sfine|raw+fine|...>
0x20 WIFI_AP_START  -> netup.sh netup(); then populate WIFI char (ssid/psk/ip) + notify
0x21 WIFI_AP_STOP   -> netup teardown
0x30 KEEPAWAKE on|off -> reset/disable the idle power-off timer (sdb.conf or UI_Reset_Power_Timer)
0x31 POWEROFF       -> power-off path
```
Exact `st` tokens for video/continuous come from doc 08/09's `st` surface; g360rd owns the wiring.

### Implementation shape (decided)
bluetoothd here IS built with the standard org.bluez GATT + LE-advertising D-Bus interfaces
(`GattManager1`, `GattService1/Characteristic1/Descriptor1`, `LEAdvertisingManager1`,
`LEAdvertisement1`, `RegisterApplication`/`RegisterAdvertisement` all present). Stock launch is
`bluetoothd -d -C` (NO `-E`); in vanilla 5.18 GATT/LE-adv are experimental and need `-E`, but
Tizen often makes them non-experimental. **Plan: option (a)** — g360rd is a minimal **static
D-Bus client** on the system bus calling `GattManager1.RegisterApplication` +
`LEAdvertisingManager1.RegisterAdvertisement` (no CAPI, no Tizen-lib linking). If hardware
introspection shows the interfaces aren't exported, add `-E` to `bluetooth.service` in our rootfs
overlay (we own the firmware) and they appear. Fallback (b): own ATT over an L2CAP LE socket
(CID 4) + mgmt advertising with bluetoothd stopped — only if (a) can't be made to work.
Hardware probe to pin (read-only): `hciconfig hci0 up`; `dbus-send --system --print-reply
--dest=org.bluez /org/bluez/hci0 org.freedesktop.DBus.Introspectable.Introspect` and look for
GattManager1 + LEAdvertisingManager1 on the adapter node. Absent → relaunch
`bluetoothd -d -C -E` and re-introspect.
