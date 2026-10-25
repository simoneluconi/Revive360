# Gear 360 (SM-C200) – SAP security, HTTP access checks, lens calibration

Source: firmware `C200GLU0APE4`. These binaries were decompiled with Ghidra 12.1.4:
- `libsap-api.so.1.0.0` and `sap-server-1.0.0` (sap 0.3.15.23)
- `libdi-network-dlna-api.so`
- `libdi-arc-imagestitch.so`
- `libmmfcore.so`
- `dfmsd`

## 1. SAP: version negotiation and WSM device authentication

### Version

- `ParseSapPeerDescriptionMsg`: the camera's maximum and only accepted SAP version is **`0x201` (513)**.
- If the peer's AP version is not `0x201`, the camera logs `SAP VERSION MISMATCH`, but continues if the message type is 7 (*counter offer*). Otherwise it rejects with `MAX version = 513`.
- "Legacy PD" (`ComposeLegacyPeerDescription`, `ParseLegacyPeerDescriptionMsg`, legacy CapEx query) refers to the **format** of the peer description and CapEx. It is not a security avoid.

### WSM (Samsung white-box crypto) is mandatory

1. On every BT socket connection (`__bt_socket_connection_state_changed_cb`), the WSM flag `DAT_00067630` is set to **1**.
2. In `ProcessPayLoadBuffer`, the first payload is parsed as the peer-description response and then **`initiateWSM()` is always called**, whatever the negotiated version.
   - In role 0 the camera calls `WSMGenerateClientChallenge`: `WSM_Server_init(server_id, client_id)` followed by `WSM_Server_generateClientChallenge`, sent as WSM message type `0x10`.
   - If challenge generation fails, the flag is cleared and the link stays plain. That would only happen if the camera's own WSM init failed.
3. While the flag is 1, every incoming payload goes to `WSMMsgHandler`:
   - `0x10/0` → `WSMGenerateServerChallenge`
   - `0x11/0` → `WSMGenerateClientResponce`
   - `0x12/0` → `WSMCheckClientResponce`
   - sub-type 1 = re-auth variants
4. `WSMCheckClientResponce` → `WSM_Client_checkClientResponse`. Whatever the result, it clears the WSM flag and destroys the WSM context. **`SendConnectionConfirmation` is sent only when the check passes.** It is the only caller of `SendConnectionConfirmation` in the library.

**Conclusion:** a custom SAP client must implement the WSM handshake that `libwsm` performs on the phone side. Negotiating an older version does not skip it.

### Payload CRC

- `compute_crc_16`: table-driven and reflected. The table matches polynomial `0xA001` (reflected `0x8005`), init 0, no final XOR. This is **CRC-16/ARC**.
- The payload CRC covers the payload bytes (excluding the 2 CRC bytes). The length CRC covers the 2 length bytes, big-endian.
- On mismatch the camera drops the packet (payload CRC) or closes the BT connection (length CRC).
- CRC is used only while the WSM flag is 0, i.e. after authentication (`ProcessPayLoadBuffer`, `TransmitBTData`).

### Peer-agent (application) authentication

- `sap-server` implements `HandlePeerAgentAuthRequest`/`Indication`. It answers with its own certificate: `get_cert_info` → `_get_pbkey_from_cert` → base64 DER public key.
- The camera's consumer, `libdi-network-bt-app.so`, **never calls `authenticatePeerAgent`**. The camera does not verify the phone app's X.509 key against any whitelist.

Not yet determined: the default `clMode`/`authMode` values that the camera advertises in its peer description.

## 2. HTTP 401 / User-Agent checks

- The string `SEC_RVF_ML` appears **nowhere** in the firmware. The UPnP RVF/ML path (`UPnPAppCDS`, port 7676 SOAP, 7679 stream, 9001 files) does not validate the User-Agent.
- Where 401 responses come from:
  - `ModeHTTPHandler::httpHandler` (`/mode/change` server): returns 401 when the handler is in a refused state (`param_1 == 1`). This server also parses `User-Agent` (stripping a `SEC_MODE_` prefix) and `Access-Method` (`nfc` → 1, otherwise 0).
  - `SPHTTPHandler::httpSendUserResponseToClient`: returns 401 (`RES_UNAUTHORIZED`) when the user declines on the camera. SP = Selective Push.
  - `OSCHTTPInfo::GenerateHttpErrorPacket`: generic error table of the OSC HTTP server.

## 3. Lens calibration ("optical axis")

### Where it comes from

`ADE_ImageStitch_WriteMeta_OpticalAxis(OPTAXIS_DATA*)` in `libdi-arc-imagestitch.so` (ArcSoft) fills a 160-byte buffer of **40 × int32**.

- Version byte `dfms_get_adjdata(3, …)`:
  - `0` → built-in default table (`DAT_000463b0`, 40 × int64, low 32 bits used)
  - `1` → 20 values from adj ids `0x179, 0x181, …, 0x211` (step 8)
  - `2` → 40 values from adj ids `0x179 … 0x211` and `0x225 … 0x2ad` (step 8)
- `dfmsd` names them `EEPA_OPTICAL_AXIS_01..40` (plus `EEPA_OPTICAL_AXIS_FLAG`) and a second bank `EEPB_OPTICAL_AXIS_01..40`. They are printed as `Axis S64(%02d) = %lld`, so they are stored as signed 64-bit factory EEPROM values.
- `libmm-photo.so` writes the buffer into the JPEG MakerNote (`opaxis_realsize`). `libmmfcore.so` writes it into MP4 `udta` (`cMP4Writer::mp4_write_360_optical_axis` → `opax`/`opai`, and `m360`).

### Default table (firmware fallback, version 0)

```
idx  0.. 7 : -479, -159, -1168, 12946, -194755, 1353512, 265972, 263016
idx  8..20 : 195150436, 888567, 1249776, 6204447, 28500000, 251841, 1532, 47427, -27568, 4468, -8847, -14862, -84
idx 21..33 : -5908224, -8413461, 2212874, 1380566, -28500000, 255126, 362, 45985, -26449, 4248, -21859, -8720, -37
idx 34..39 : 24232904, 55305408, 0, 0, 0, 7
```

Observation: indices 12–20 and 25–33 have the same shape: `+28500000` / `-28500000`, then values around 250k, a small value, then about `46000, -27000, 4300, -9000…-22000, -15000…-8700, -40…-80`. This strongly suggests **one parameter block per lens** (13 values each), plus a header (0..7) and a trailer (34..39). The meaning and fixed-point scale of each field are not visible in the camera code: the camera only copies the values, and the ArcSoft stitcher consumes them.

## 4. Orientation data (`vrot` box)

`cMP4Writer::mp4_write_vrot`:

```
u32 size, "vrot", u32 0 (version/flags), u32 count,
count × { s32 a*10, s32 10, s32 b*10, s32 10, s32 c*10, s32 10 }   // big-endian rationals
```

- Each entry is three float angles (pitch/roll/yaw from the orientation sensor) stored as rationals with denominator 10, so the precision is 0.1°.
- Time-lapse files (`cTimeLapseWriter::GetVROTInfo`) read the samples from `/opt/usr/sensor.dat`, 12 bytes = 3 floats each ("pitch/roll data available").
- `dfmsd` contains `ADJUST 3-AXIS GYROSCOPE DATA` and `ADJUST 3-AXIS CALIBRATION DATA`, so a 3-axis gyro/accelerometer is factory calibrated.

## 5. Video modes (from the real log)

Recording: 3840x1920 30p, 2560x1280 60p, 2560x1280 30p, 1920x960 30p (all 2:1). Live-view sizes and the RVF encoder are in 02_camera_wifi.md.
