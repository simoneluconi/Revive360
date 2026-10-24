# Gear 360 (SM-C200) – camera-side Bluetooth control protocol

Sources:
- Firmware `C200GLU0APE4` (FW 0.70/0.83, Tizen 2.2.0). `libdi-network-bt-app.so.0.2.32` decompiled with Ghidra 12.1.4 headless.
- A real camera log, `CFN003330DC2D53_20160731_120624_a9_dlog.info` from the ultramango/gear360reveng repo (Galaxy S7 + APE4).

## Transport

- Samsung Accessory Protocol (SAP), profile `/system/DI_360_2D`, agent name `DI_360_2DApp`.
- UUIDs found in the binary:
  - `E23B00BC-217C-453B-9C08-FC44577136FE`
  - `a49eb41e-cb06-495c-9f4f-aa80a90cdf4a` (SAP SPP)
- The payload is plain-text JSON, handled with json-c. The parser first looks for `"title"` in the buffer. If it's missing, the camera logs `packet format error` and drops the message.

## Message format

Every message, in both directions, is shaped like a JSON schema:

```json
{
  "title": "Command request Message",
  "description": "Message structure in JSON for Command request",
  "type": "object",
  "properties": {
    "msgId": "cmd-req",
    "action": { "enum": "execute", "description": "liveview" }
  }
}
```

- `properties.msgId` is a **plain string**.
- Every other field is an object `{ "type": "string|number|array", "description": <value> }`. Some fields carry `enum` (a string) instead of, or alongside, `description`.

## msgIds the camera accepts (phone → camera)

`bt_get_msgId()` maps the msgId string to an internal id, and `bt_handle_received_data()` acts on it:

| msgId | id | Fields read (under `properties`) | Effect |
|---|---|---|---|
| `widget-info-req` | 1 | – | UI event 0x12; camera answers with `widget-info-rsp` |
| `widget-info-rsp` | 2 | `list.items.result.enum`, `list.items.gps.{enum,description}` | phone GPS is written into EXIF |
| `cmd-req` | 4 | `action.enum` (`execute`/`dismiss`), `action.description` (command name), `action.items.<Key>.description` (only for `config`; only the **first** key of `items` is read) | runs the command |
| `cmd-rsp` | 5 | `result.enum` (`success`), `result.description` (`timeout`), `result.value` | |
| `q-autoshare-rsp` | 7 | `result.enum`, `result.description` | |
| `info` | 8 | see below | handshake |
| `date-time-rsp` | 9 | `date.description`, `time.description`, `region.description` (a `summer` field also exists) | sets the RTC; joined as `"%s-%s-%s"`, e.g. `2016/07/31-12:04:20-+12:00` |
| `remote-shot-req` | 12 | `shot.description` | remote shutter (see table below) |
| `firmware-version-info` | 14 | `server-version.description`, `download-url.description` | |
| `camera-status-info-rsp` | 16 | `result.enum` | |
| `fw-server-info` | 17 | `server-url.description` | |
| `install-req` / `install-rjt` | 19 / 20 | – | firmware update |
| `shot-req` | 23 | – | |
| `camera-info-req` | 25 | – | camera answers with `camera-info-rsp` |

### `info` (id 8): the handshake

There are two variants, told apart by `title`.

**1. `title` = `Phone Device information Message`.** The phone introduces itself:
- `properties.device-name.description`: phone name (e.g. `Samsung Galaxy S7`)
- `properties.wifi-mac-address.description`: **the phone's Wi-Fi MAC** (e.g. `AA:BB:CC:DD:EE:FF`)
- `properties.wifi-direct.enum`: `"true"` if the phone supports Wi-Fi Direct

The camera sets `gSapConnected=1` and fires UI event 9 with (support_wifidirect, mac). The camera app keeps that MAC as `allowed_mac` for Wi-Fi Direct (see 02_camera_wifi.md).

**2. `title` = `Camera Device info response Message`.** The phone acknowledges, and the camera logs `3-way success!`.

### `cmd-req` commands (`bt_get_cmdId`)

The camera matches `action.description` with `strstr(<command name>, description)`. Note the order of the arguments: it checks whether *description occurs inside the name*, so an empty description matches `autoshare`.

| command | execute | dismiss |
|---|---|---|
| autoshare | 3 | 4 |
| rvf | 5 | 6 |
| mobilelink | 7 | 8 |
| selectivepush | 9 | 10 |
| as-activate | 11 | 12 |
| p2p-restart | 13 | 13 |
| as-select | 14 | 15 |
| remote-shot | 16 | 17 |
| pro-suggest | 18 | 19 |
| **liveview** | **20** | 21 |
| config | 22 (uses `action.items.<Key>.description`, first key only) | – |
| fw-download | 23 | 24 |
| format | 25 | 26 |
| reset-device | 27 | 28 |
| reset-connection | 29 | 30 |
| reset-all | 31 | 32 |
| power-save-off | 33 | 34 |

Confirmed by the log: `cmd = 20` → `[EXE_LIVEVIEW]` → `process_start [RVF], use_wifidirect(1)` → Wi-Fi Direct is activated.

**Only a few of these are implemented on the C200.** `CUINETFuncBluetooth::handle_bt_app_receive_command` in `di-camera-app` handles:

| id | command | action |
|---|---|---|
| 7 | mobilelink execute | start DLNA function 2 (media browse/transfer) |
| 20 | liveview execute | start DLNA function 1 (RVF, remote viewfinder) |
| 23 | fw-download execute | start DLNA function 3 |
| 8 / 21 / 24 | mobilelink / liveview / fw-download dismiss | stop DLNA, reply with an exit item |
| 22 | config | `set_menu_by_bluetooth` (one setting per message) |
| 33 / 34 | power-save-off execute / dismiss | stop / restart the auto power-off timer |

Every other command logs `[WARN] Not Implemented`: autoshare, rvf, selectivepush, as-*, p2p-restart, remote-shot, pro-suggest, and format/reset sent as standalone commands.

The DLNA functions bring up the network. They use Wi-Fi Direct if the phone declared `wifi-direct = "true"` in `info`, and the camera softAP otherwise (see 02_camera_wifi.md).

### Remote shutter over BT is not functional on the C200 (APE4)

- `remote-shot-req` is parsed by the BT library and raised as `EVT_BT_APP_REMOTE_SHOT_REQ` (0x14). However, `CUINETFuncBluetooth::handle_bt_app_remote_shot_req` is an empty stub, and 0x14 is missing from the dispatch switch.
- `shot-req` (id 23) reads no fields and raises UI event 0x60, which nothing in `di-camera-app` handles.
- `UI_RR_BT_Callback` does handle push/release/record ids 0xcb–0xd1, but nothing calls it directly. It appears to serve the BT HID remote controller (`EVT_BT_RC_CONNECTION`), not SAP.
- Conclusion: shutter and record must go over Wi-Fi UPnP SOAP (`Shot`, `StartRecord`, `StopRecord`). A BT shutter would need a camera-side helper (see 03_sd_card_hook.md).

### `remote-shot-req`: values of `shot.description`

| value | id |
|---|---|
| `Single shot` | 200 |
| `Continue shot start` | 201 |
| `Continue shot end` | 202 |
| `normal-push` | 203 |
| `normal-release` | 204 |
| `Press-Lock-start` | 205 |
| `Press-Lock-end` / `record-start` | 206 |
| `record-end` | 207 |
| `Press-Lock-AF` | 209 |

## msgIds the camera sends (camera → phone)

Built by `bt_generate_msg()`. `title` and `description` are fixed per message type.

| msgId | title | properties |
|---|---|---|
| `widget-info-req` | Widget info request Message | – |
| `widget-info-rsp` / `widget-info-update` | Widget info response/update Message | `list` (array) whose `items` are `result`, `battery` (number), `battery-state`, `total-memory`, `used-memory`, `free-memory` (numbers), `record-state`, `capture-state` (`true`/`false`), `auto-poweroff` |
| `cmd-req` | Command request Message | `action.{enum,description}` |
| `cmd-rsp` | Command response Message | `result.{enum,description}`, `r-code` (number) |
| `q-autoshare-req` | q-Autoshare request Message | `url`, `photo`, `shot`, `count` |
| `info` | Camera Device information Message | `model-name`, `model-version`, `channel` (number), `wifi-direct-ssid`, `softap-ssid`, `softap-psword`, `softap-security`, `board-revision`, `serial-number`, `wifi-mac`, `bt-mac`, `bt-fota-test-url`, `fw-type`. All are `{type, description}` and only sent when set. |
| `date-time-req` | Date-Time request Message | – |
| `alive` | bt alive Message | – |
| `remote-shot-rsp` | Remote shot response Message | `result`, `r-code` |
| `config-info` / `camera-info-rsp` | Camera Config information / Camera information response Message | `functions` (array with `count` and `items` keyed `"1"`, `"2"`, …; each item is `{sub-title, count, value:"a,b,c", default}`) |
| `camera-status-info` | Camera Status information Message | `record-state`, `capture-state` (`TRUE`/`FALSE`) |
| `fw-download-complete-info` | Camera fw download complete information Message | `action.enum` |
| `device-desc-url` | Device Description URL Message | `url.description`, e.g. `http://192.168.49.10:7676/smp_2_` |
| `notify` | Notification Message | `notify.description` (`heat-warning`, `heat-turnoff`, …) |
| `shot-rsp` | Shot response Message | `result`, `r-code` |

Example `config-info` entries from the log: `Beep` (Mid), `Led indicator` (On), `Auto Power Off` (3min), `Video out` (NTSC), `Format` (No), `Reset` (All).

## Observed sequence (real log, Galaxy S7)

```
P→C  info  (Phone Device information: device-name, wifi-mac-address, wifi-direct=true)
P→C  widget-info-req
C→P  date-time-req
C→P  widget-info-req
C→P  info  (Camera Device information: model SM-C200, C200GLU0APE4_0.70, wifi-direct-ssid "Gear 360(65:EF:1E)", ...)
C→P  config-info
P→C  cmd-req  action{enum:execute, description:liveview}   -> cmd 20
P→C  date-time-rsp  2016/07/31-12:04:20-+12:00
P→C  widget-info-rsp  (GPS)
P→C  info  (Camera Device info response -> "3-way success!")
C→P  widget-info-rsp
P→C  config-info
     ... camera activates Wi-Fi Direct (GO intent 1), phone connects with PBC ...
C→P  device-desc-url  http://192.168.49.10:7676/smp_2_
```
