# Revive360: phone ↔ camera protocol (v1)

Status: contract for the v1 build (2026-10-24). Owner: the build coordinator session. The camera
controller (`camera/`) and the Android app (`app/`) both implement exactly this document. To change
it, ask the owner; do not diverge silently.

Design rules:

- **Model-agnostic.** Every model- or firmware-specific fact reaches the phone through `info`
  (model, firmware, capabilities). The app never branches on a model name or firmware string.
- **Additive evolution.** New fields, new capability keys and new endpoints do not change the
  protocol version. Clients ignore unknown fields; servers ignore unknown request fields.
- **Small.** One TCP port, a strict subset of HTTP/1.1, JSON bodies.

## 1. Link layer (camera softAP)

The camera-side controller brings up its own access point on `wlan0`, independent of
`di-camera-app`.

| Item | Value |
|---|---|
| SSID | `G360R-XXXXXX`, where `XXXXXX` is the last 3 bytes of the `wlan0` MAC, uppercase hex, no separators (e.g. `G360R-65EF1E`) |
| SSID prefix (what the app matches) | `G360R-` |
| Security | WPA2-PSK, CCMP |
| Passphrase | Per-camera, from the controller's config file on the SD card. The SD preparation tool generates a random 8-digit code and prints it; the user enters it once in the app. 8–63 printable ASCII characters. |
| Band / channel | 2.4 GHz, channel 6 by default (configurable on the SD) |
| BSSID | Normally the `wlan0` MAC (hostapd default), but the Broadcom driver may use a variant. The app MUST take the BSSID from the Wi-Fi scan result for the SSID, never from `info.camera.mac`, and uses that scanned BSSID for the CompanionDeviceManager association and the pinned `WifiNetworkSpecifier`. |
| Camera IP | `192.168.107.1/24` |
| DHCP | pool `192.168.107.10`–`192.168.107.50`, router option = `192.168.107.1`, no DNS |

The app resolves the camera address from the network's `LinkProperties` (DHCP server or default
gateway) and falls back to `192.168.107.1`.

The network carries no internet. The app binds its sockets to this `Network` only (never the whole
process), so mobile data keeps working for everything else.

## 2. Transport

- TCP port **8360** on the camera IP.
- HTTP/1.1 subset:
  - Request: request line, headers, optional body. A body is sent only with `Content-Length`
    (no chunked request bodies). Header block max 8 KiB; JSON request body max 4 KiB (else `413`).
  - Response: always carries `Content-Length` (never chunked).
  - The server MAY close the connection after every response and sends `Connection: close` when it
    does. Clients MUST NOT rely on keep-alive.
- Methods used: `GET`, `HEAD` (download only), `POST`, `DELETE`.
- The server MUST serve at least **4 concurrent connections**, so a download does not block
  status polling or thumbnails. Captures are serialized (see §5.3).
- JSON responses use `Content-Type: application/json; charset=utf-8`. JSON request bodies use the
  same type; the server does not require the header.
- All text is UTF-8. Integers are JSON numbers; byte counts fit in 53 bits.
- No app-layer authentication in v1: joining the WPA2 network is the authorization.

### Paths

File paths in this protocol are **relative to the storage root** (the SD card), use `/`
separators, and always start with `DCIM/`, e.g. `DCIM/100PHOTO/SAM_0001.JPG`.

- In URLs, the client percent-encodes each path segment (RFC 3986) and keeps the `/` separators.
  The server decodes `%XX`.
- The server rejects (`400 bad_path`) any path that does not start with `DCIM/`, contains a `..` or
  `.` segment, an empty segment, a backslash, or a NUL. Server-side, the path is resolved under the
  model's storage root only.

### Errors

Every non-2xx response with a JSON-capable endpoint has this body:

```json
{ "error": { "code": "busy", "message": "a capture is already in progress" } }
```

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `bad_request` | malformed request or JSON, missing/invalid field |
| 400 | `bad_path` | path rejected (see Paths) |
| 404 | `not_found` | unknown endpoint or file |
| 404 | `no_thumbnail` | the file exists but has no embedded thumbnail |
| 405 | `method_not_allowed` | wrong method for the endpoint |
| 409 | `busy` | a capture or recording is in progress (capture, delete, settings, record) |
| 409 | `wrong_mode` | the request needs another shooting mode (§5.3 needs `photo`, §5.13 `video`/`looping`) |
| 413 | `too_large` | headers or body over the limit |
| 416 | `range_not_satisfiable` | bad `Range` on a download |
| 500 | `internal` | unexpected failure; `message` says what |
| 501 | `unsupported` | the request needs a capability this camera does not report |
| 503 | `no_storage` | the SD card is not mounted or not readable |
| 504 | `capture_timeout` | the camera did not produce the file in time (it may still appear later) |

`message` is for logs and developer display only; the app maps `code` to its own strings.

## 3. Versioning

- `info.protocol` is an integer, **1** for this document. All endpoints live under `/api/v1/`
  (except file downloads under `/files/`).
- A breaking change bumps it to 2 and moves to `/api/v2/`. A server MAY serve both.
- The app refuses to operate (and says so) when `info.protocol` is a version it does not know.

## 4. Capabilities

Returned in `info.capabilities`. The app shows and enables features **only** from these values.
A missing key means "not supported" (`false`, empty list).

```json
{
  "capture": {
    "modes": ["photo", "raw", "raw+photo"],
    "photo_qualities": ["sfine", "fine", "norm"],
    "photo_resolutions": ["7776x3888"],
    "resolution_settable": false
  },
  "video_modes": ["3840x1920@30", "2560x1280@60", "2560x1280@30", "1920x960@30"],
  "record": false,
  "liveview": false,
  "files": { "list": true, "download": true, "range": true, "delete": true, "thumbnails": true },
  "status": { "battery": true, "storage": true, "battery_temperature": true },
  "leds": [
    { "id": "status_r", "label": "Status (red)",   "role": "status_red",   "states": ["off", "on", "blink", "auto"] },
    { "id": "front",    "label": "Front",          "role": "front",        "states": ["off", "on", "blink", "auto"] }
  ],
  "debug_log": true,
  "time": true,
  "settings": [
    { "id": "mode",       "values": ["photo", "video", "timelapse", "looping"] },
    { "id": "lens",       "values": ["dual", "rear", "front"] },
    { "id": "video_size", "values": ["3840x1920@30", "2560x1280@30", "1920x960@30"] }
  ]
}
```

| Key | Type | Meaning |
|---|---|---|
| `capture.modes` | list of `photo` \| `raw` \| `raw+photo` | Accepted values of `mode` in §5.3. Empty or missing = no capture. |
| `capture.photo_qualities` | list of opaque strings | Accepted values of `quality` in §5.3; the first is the default. Empty = not settable. |
| `capture.photo_resolutions` | list of `WxH` | Resolutions the camera produces; the first is the default/current. Informational unless `resolution_settable`. |
| `capture.resolution_settable` | bool | If true, `resolution` in §5.3 accepts a value from `photo_resolutions`. |
| `video_modes` | list of `WxH@fps` | Informational (the settable list is the `video_size` setting). |
| `record` | bool | §5.13 is available (start/stop a video recording). |
| `liveview` | bool | Reserved; always `false` in v1. |
| `files.*` | bool | Gates the file endpoints of §5.4–§5.7. `range` = downloads honour `Range`. |
| `status.battery` / `status.storage` / `status.battery_temperature` | bool | Which parts of §5.2 are filled in. |
| `leds` | list of LED channel objects | One entry per independently controllable LED. `id` is opaque (used in §5.8); `label` is for display; `role` is one of `status_red`, `status_green`, `status_blue`, `front`, `rear`, `charge`, `unknown`; `states` lists what §5.8 accepts for it. Empty = no LED control. |
| `debug_log` | bool | §5.9 is available. |
| `time` | bool | §5.10 is available: the app may set the camera clock. |
| `settings` | list of `{id, values}` | Camera settings of §5.12, in display order. `id` is one of the ids below (an app ignores ids it does not know); `values` are the accepted tokens. Empty = none. |

Setting ids and their tokens (an adapter offers a subset):

| `id` | Tokens | Meaning |
|---|---|---|
| `mode` | `photo`, `video`, `timelapse`, `looping`, `burst`, `slowmo` | Shooting mode. §5.3 needs `photo`, §5.13 `video`/`looping`. |
| `lens` | `dual`, `rear`, `front` | Both lenses (360°) or a single one (180°). |
| `photo_size` | `WxH` or adapter tokens (`dual_large`, `dual_small`, `single_large`, `single_wide`) | Still image size. |
| `video_size` | `WxH@fps` | Movie size and frame rate. |
| `timer` | `off`, `on`, or seconds (`2`, `5`, `10`) | Self-timer before a still. |
| `timelapse_interval` | seconds (`0.5` … `60`) | Interval between time-lapse frames. |
| `loop_time` | minutes (`5` … `120`) or `max` | Length of a looping-video segment. |

Values above are illustrative; each camera adapter reports its own. A not-yet-confirmed hardware
mapping is reported honestly (e.g. `role: "unknown"`, `label: "LED 3"`).

## 5. Endpoints

### 5.1 `GET /api/v1/info` (hello)

The first request after joining the network. Cheap; no hardware access beyond cached values.

```json
{
  "protocol": 1,
  "server": { "name": "g360rd", "version": "0.1.0" },
  "camera": {
    "model": "SM-C200",
    "model_raw": "SMC200",
    "firmware": "C200GLU0AQK1",
    "adapter": "c200",
    "adapter_status": "supported",
    "mac": "a0:b1:c2:65:ef:1e",
    "name": "Gear 360 (65:EF:1E)"
  },
  "capabilities": { "...": "see §4" }
}
```

| Field | Meaning |
|---|---|
| `camera.model` | Marketing model, normalized: `SM-C200`, `SM-R210`, or `model_raw` if unknown. Display only. |
| `camera.model_raw` | `Model=` from `/etc/info.ini`, trailing `;` stripped. |
| `camera.firmware` | `Build=` from `/etc/info.ini`, trailing `;` stripped. |
| `camera.adapter` | Camera-side adapter id that serves this camera (`c200`, `r210`, `generic`). Display/diagnostics only. |
| `camera.adapter_status` | `supported` (tested), `experimental` (built, untested on this firmware), `stub` (detected, features mostly missing — capabilities say what works). |
| `camera.mac` | `wlan0` MAC, lowercase, colon-separated. Stable camera identity for the app. |
| `camera.name` | Human-readable default name. |

### 5.2 `GET /api/v1/status`

Polled by the app (suggested every 5 s while the home screen is visible).

```json
{
  "battery": {
    "percent": 87,
    "charging": false,
    "status": "discharging",
    "external_power": false,
    "temperature_c": 31.5
  },
  "storage": {
    "mounted": true,
    "total_bytes": 127865454592,
    "free_bytes": 120000000000,
    "used_bytes": 7865454592
  },
  "busy": false,
  "uptime_s": 312
}
```

- `battery` is present when `capabilities.status.battery`. `percent` 0–100. `status` is one of
  `charging`, `discharging`, `full`, `not_charging`, `unknown`. `temperature_c` is `null` or absent
  when unknown.
- `storage` is present when `capabilities.status.storage`. When `mounted` is false the byte fields
  are 0. Sizes come from `statvfs` (`used = total - free`, where `free` = space available to
  non-root, i.e. `f_bavail`).
- `busy`: a capture is in progress.
- `recording` (when `capabilities.record`): `{ "active": true, "elapsed_s": 42 }`; `elapsed_s` is
  absent when unknown.
- `uptime_s`: seconds since the controller started (lets the app detect a camera reboot).

### 5.3 `POST /api/v1/capture`

Request:

```json
{ "mode": "photo", "quality": "sfine" }
```

| Field | Required | Values |
|---|---|---|
| `mode` | yes | one of `capabilities.capture.modes` |
| `quality` | no | one of `capabilities.capture.photo_qualities`; ignored for `raw` |
| `resolution` | no | one of `photo_resolutions`, only if `resolution_settable` |

Semantics:
- **Synchronous.** The server triggers the capture and responds once the new file(s) are complete
  on the card (size stable), or after its timeout (30 s) with `504 capture_timeout`. The client uses
  a read timeout of at least 45 s.
- Captures are serialized: a second capture while one runs gets `409 busy`; so does a capture during
  a recording (§5.13).
- If the camera has a `mode` setting (§5.12) and it is not `photo`, the server switches it to `photo`
  first (in another mode the shutter would start a recording, say). The answer of a later
  `GET /api/v1/settings` shows the change.
- `mode` `raw+photo` yields two files (SRW + JPG); the response lists every new file.
- Unsupported `mode`/`quality`/`resolution` → `501 unsupported`. No card → `503 no_storage`.

Response `200`:

```json
{
  "files": [
    { "path": "DCIM/100PHOTO/SAM_0042.JPG", "name": "SAM_0042.JPG", "size": 6123456, "mtime": 1729780000, "kind": "photo" }
  ],
  "duration_ms": 2410
}
```

### 5.4 `GET /api/v1/files`

Lists media under `DCIM/` (recursive, max depth 3, dot-files and dot-directories skipped). Newest
first (by `mtime`, then by `path` descending).

```json
{
  "files": [
    { "path": "DCIM/100PHOTO/SAM_0042.SRW", "name": "SAM_0042.SRW", "size": 41234567, "mtime": 1729780000, "kind": "raw" },
    { "path": "DCIM/100PHOTO/SAM_0042.JPG", "name": "SAM_0042.JPG", "size": 6123456,  "mtime": 1729780000, "kind": "photo" },
    { "path": "DCIM/100VIDEO/360_0007.MP4", "name": "360_0007.MP4", "size": 98765432, "mtime": 1729700000, "kind": "video" }
  ]
}
```

File object:

| Field | Meaning |
|---|---|
| `path` | relative path (see §2 Paths) — the file's identity |
| `name` | last path segment |
| `size` | bytes |
| `mtime` | Unix seconds, camera clock (may be wrong if the camera RTC is unset; on a local-clock camera, §5.10, it is the local wall-clock time counted as if it were UTC) |
| `kind` | `photo` (`.JPG`/`.JPEG`), `raw` (`.SRW`/`.DNG`), `video` (`.MP4`/`.MOV`), `other` — by extension, case-insensitive |

Files sharing a base name (`SAM_0042.JPG` + `SAM_0042.SRW`) are separate entries; the app may
group them for display.

`503 no_storage` when the card is not mounted.

### 5.5 `GET /api/v1/thumb/<path>`

Returns a small JPEG (`Content-Type: image/jpeg`) for a `photo` or `raw` file: the embedded EXIF
thumbnail of a JPEG, or the embedded preview JPEG of a RAW file. A RAW file without an embedded preview
falls back to the EXIF thumbnail of the sibling JPEG with the same base name. The server does not decode
or re-encode images.

- `404 no_thumbnail` when none is embedded (always the case for `video`/`other` in v1). The app shows
  a placeholder.
- The server sends `Cache-Control: max-age=86400`. The app caches by `path` + `size` + `mtime`.

### 5.6 `GET /files/<path>` and `HEAD /files/<path>` (download)

Streams the original file.

- `Content-Type`: `image/jpeg`, `image/x-samsung-srw`, `video/mp4`, else `application/octet-stream`.
- `Content-Length`, `Accept-Ranges: bytes` (when `files.range`), `Last-Modified` (from `mtime`).
- `Range: bytes=<a>-` and `Range: bytes=<a>-<b>` (single range only) → `206` with
  `Content-Range: bytes a-b/size`. Unsatisfiable → `416` with `Content-Range: bytes */size`.
  Multi-range requests are answered as if there were no `Range` header.
- `HEAD` returns the same headers without a body.
- Error bodies on this endpoint are the JSON error object of §2.

### 5.7 `DELETE /files/<path>`

Deletes one file (never a directory).

- `200` `{ "deleted": "DCIM/100PHOTO/SAM_0042.JPG" }`.
- `404 not_found`; `409 busy` while a capture is running; `501 unsupported` if `files.delete` is false.
- Deleting one file of a JPG+SRW pair does not touch the other; the app sends one request per file.

### 5.8 `POST /api/v1/led`

```json
{ "id": "front", "state": "blink", "on_ms": 250, "off_ms": 250 }
```

| Field | Required | Values |
|---|---|---|
| `id` | yes | an `id` from `capabilities.leds` |
| `state` | yes | one of that LED's `states`: `off`, `on`, `blink`, `auto` |
| `on_ms`, `off_ms` | for `blink` | 50–5000, default 500 each |

`auto` hands the LED back to the controller's own feedback (§6). Any other state takes manual
control of that LED until `auto` is sent or the controller restarts.

Response `200` `{ "id": "front", "state": "blink" }`. Unknown `id` or state → `400 bad_request`.

### 5.9 `GET /api/v1/log?lines=N`

Only when `capabilities.debug_log`. Returns the last `N` lines (default 200, max 2000) of the
controller's log as `text/plain; charset=utf-8`. For diagnostics in the app's debug screen.

`source=system` returns the camera's own system log instead (on Tizen `dlogutil -d`: the stock
camera app logs there), optionally filtered with `grep=TEXT` (case-insensitive substring; letters,
digits and `_.:+-` only, else `400 bad_request`). Debugging aid; the app does not depend on it.

### 5.10 `POST /api/v1/time`

Only when `capabilities.time`. Sets the camera's date, time and time zone from the phone. The app
sends it right after `info` on every connection.

```json
{ "utc_ms": 1729780000000, "tz": "Europe/Rome", "tz_offset_min": 120 }
```

| Field | Required | Meaning |
|---|---|---|
| `utc_ms` | yes | Current UTC time, Unix milliseconds. |
| `tz` | no | IANA time zone id of the phone. Informative: the camera may log it. |
| `tz_offset_min` | yes | The phone's current offset from UTC in minutes (DST included), -840..840. |

Response `200`:

```json
{ "utc_ms": 1729780000123, "applied": ["system", "rtc", "timezone"] }
```

`utc_ms` is the camera clock after the change, as UTC; `applied` lists what was set (`system` clock,
battery-backed `rtc`, `timezone`). Missing entries are not errors: the camera did what it can.
`400 bad_request` for a missing or implausible time (before 2020) or offset.

Cameras without a time zone database (the C200: its OSD and EXIF show the RTC plus an offset of its
own, left at 0) keep the **local wall-clock time** in the system clock and RTC: the server sets them
to `utc_ms + tz_offset_min` and reports `timezone` in `applied`. EXIF dates are then local, as on
any camera. The app re-sends the time on every connection, so a DST change or a trip is picked up.

### 5.11 Debug endpoints (development only)

Not used by the app; for exploring the stock camera software from a PC through the phone.
Arguments are limited to letters, digits, space and `+,._-` (`/=` too for st/vconf), else
`400 bad_request`; no shell is involved.

- `POST /api/v1/debug/dfms` `{"script": "capture still", "wait_ms": 5000}`: runs
  `dfmstool --script <script>`, waits, answers `{"rc", "out", "new_files"}` (`ls -la` of files
  newer than the request on the card and the RAM disks).
- `POST /api/v1/debug/st` / `POST /api/v1/debug/vconf` `{"args": "app nx capture quality"}`: runs
  `st` / `vconftool` with those arguments, answers `{"rc", "out", "app_result", "system_log"}`
  (st's `/tmp/app_result` and the last 40 system log lines).

### 5.12 `GET /api/v1/settings`, `POST /api/v1/settings`

Only when `capabilities.settings` is not empty. `GET` answers the current value of every setting:

```json
{ "settings": { "mode": "photo", "lens": "dual", "video_size": "3840x1920@30" } }
```

A value the camera cannot report is `null`. `POST` changes one or more settings and answers like
`GET` with the values after the change:

```json
{ "mode": "video", "video_size": "2560x1280@30" }
```

`400 bad_request` for an unknown id or a token not in its `values` (nothing is changed);
`409 busy` while a capture or a recording runs; `500 internal` if the camera refused a setting
(the others may have been applied: read the answer). The camera's own buttons can change settings
too, so the app re-reads them (on connection and when it shows them).

### 5.13 `POST /api/v1/record`

Only when `capabilities.record`. `{"action": "start"}` starts a video recording in the current
`mode` (`video` or `looping`; `409 wrong_mode` otherwise), `{"action": "stop"}` stops it. Answers
`{ "recording": { "active": true, "elapsed_s": 0 } }` (as in §5.2); stopping answers the new files
too, like §5.3: `{ "recording": { "active": false }, "files": [ ... ] }`. `409 busy` to start while
recording or capturing; stopping when nothing records answers `200` with `active: false`.

## 6. Camera-side behaviour (informative)

Not part of the wire contract, documented so both sides agree on what the user sees:

- **LED feedback** (LEDs in `auto`): controller starting → slow blink; softAP up and server
  listening → steady; a client request seen in the last 10 s → short double blink;
  capture in progress → fast blink; error state → the red/status LED blinks. The exact LED per
  signal is chosen by the camera adapter.
- The controller never restarts `di-camera-app`, writes nothing persistent outside the SD card, and
  resets `memory/sysman/battery_skip_low_batt_power_off` to `0` on start.
- Removing the SD card and power-cycling returns the camera to stock behaviour.

## 7. App flow (informative)

1. Join: `WifiNetworkSpecifier` with SSID prefix `G360R-` (or the remembered exact SSID + BSSID) and
   the stored passphrase; a CompanionDeviceManager association makes later joins dialog-free.
2. `GET /api/v1/info`. Check `protocol`. Store `camera.mac`, model, firmware, capabilities.
3. Poll `GET /api/v1/status`. Build the UI from capabilities.
4. Capture with `POST /api/v1/capture`; refresh the gallery from the response and `GET /api/v1/files`.
5. Thumbnails via `/api/v1/thumb/…`, originals via `/files/…` (resume with `Range`), delete via
   `DELETE /files/…`.

## 8. Reserved for later versions

Not implemented in v1; names reserved so v1 code does not collide: `POST /api/v1/record`,
`GET /api/v1/liveview`, `POST /api/v1/settings`, `GET /api/v1/events`, `POST /api/v1/power`.
