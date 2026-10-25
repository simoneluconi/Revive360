# Gear 360 (SM-C200) – camera-side Wi-Fi (Wi-Fi Direct and softAP)

Sources:
- Firmware `C200GLU0APE4`. The binaries `di-camera-app`, `libdi-network-bt-app.so` and `libdi-network-dlna-api.so` were decompiled with Ghidra 12.1.4.
- A real camera log, `CFN003330DC2D53_20160731_120624_a9_dlog.info` (ultramango/gear360reveng, Galaxy S7).

Facts marked **[inferred]** are deduced and not proven directly by code or log.

## How the network mode is chosen

1. The phone sends BT `info` (title `Phone Device information Message`) with `wifi-direct.enum` and `wifi-mac-address.description`.
2. `CUINETFuncBluetooth::handle_bt_app_device_info` stores:
   - `support_wifidirect = (wifi-direct == "true")`
   - `allowed_mac = lowercase(wifi-mac-address)` (max 64 chars)
3. The camera's `info` reply (`send_bt_device_info`) contains:
   - always: `wifi-direct-ssid`, e.g. `Gear 360(65:EF:1E)`
   - only if `support_wifidirect == 0`: `softap-ssid` and `softap-psword`
4. Nothing starts at handshake time. The network comes up only on a BT `cmd-req` with `liveview`, `mobilelink` or `fw-download` and `execute`. `CUINETFuncDLNA::start_network()` then calls:
   - `support_wifidirect != 0` → `CUINETDevWIFIDirect::ActivateWIFIDirect()`
   - otherwise → `CUINETDevSoftAP::ActivateSoftAP(false, "107", /*security*/ true)`
5. Once the network is up, `CUINETDlnaMan::StartDlna` starts the UPnP stack. The camera then sends BT `device-desc-url` with the UPnP description URL. This happens in both modes.

## Wi-Fi Direct

- Stack: Tizen `libwifi-direct`, `wfd-manager`, `p2p_supplicant` on `p2p-wlan0-0`.
- `p2p_supp.conf`: `device_name=Tizen`, listen channel 1 (reg class 81), operating channel 48 (reg class 115), `config_methods=push_button`.
- The camera sets **`p2p_go_intent 1`**, so the **phone becomes the group owner** (`192.168.49.1`). In the log the camera got `192.168.49.10` (Samsung `P2P_SET static_ip` extension).
- The camera has its own DHCP config for the case where it is GO (`dhcpd.p2p.conf`: `192.168.49.1/24`, leases `.20`–`.40`), but in practice it is the client.
- SSID (as GO): `Gear 360(<last 3 bytes of the Wi-Fi MAC>)`.
- **The phone initiates** the connection with PBC provisioning (`P2P-PROV-DISC-PBC-REQ` received by the camera).

### MAC whitelist (`CUINETDevWIFIDirect::handle_wifi_direct_callback_connection_req`)

- Always active: flag `this[0x168]` is set to 1 in `process_init`.
- `wfd-manager` supplies three addresses for the requesting peer, `peer_mac_1/2/3`. In the log these were the phone's station MAC, its P2P device address, and its P2P interface address.
- If `strcmp(allowed_mac, peer_mac_N) == 0` for any N, the camera reads the WPS type (PBC) and calls `wifi_direct_accept_connection` (auto-connection mode).
- Otherwise it calls **`wifi_direct_cancel_connection`**: a hard reject with **no fallback prompt**.
- An empty `allowed_mac` or `02:00:00:00:00:00` does **not** avoid the check.

Consequence for modern Android: the MAC the app sends over BT must equal the phone's real P2P device or interface address. Android 10+ hides it from non-privileged apps, which makes Wi-Fi Direct impractical. The softAP path avoids the problem.

## softAP

- Class `CUINETDevSoftAP`, based on Tizen `capi-network-tethering` and `mobileap-agent` (dnsmasq).
- **SSID**: `AP_<ProjectName>(XX:XX:XX)`, built from the camera Wi-Fi MAC in `make_ssid`. An OSC variant, `<ProjectName>_(XX:XX).OSC`, is used only when `for_osc` = 1; the BT path passes 0.
- **Password**: 8 random decimal digits, generated once in `make_passwd` (`srand(clock_gettime)`). It is **persisted** in UI setting `0xc4e`, so it is stable across sessions and reboots until a reset. Still, always read it from BT.
- **Security**: `tethering_wifi_ap_set_security_type(h, 1)` = WPA2-PSK.
- Enable call: `tethering_enable(h, 4, client_ip = "107", 2)`.
- **Camera IP [inferred]**: `192.168.107.1/24`.
  - The `"107"` argument is the third octet; `UI_Set_Value_WiFi_Client_Ip("107")` / `("104")` exist.
  - `libdi-network-dlna-api.so` contains the literal `192.168.104.1:18100`.
  - The DHCP range is built at runtime by `mobileap-agent` (`dhcp-range=192.168.%d…`).
  - The app should read the gateway from `LinkProperties` or use `device-desc-url` instead of hard-coding the IP.
- **Channel [inferred]**: the camera `info` carries `channel` (the log shows 1). `mobileap-agent` reads `/opt/etc/softap_channel.conf`.
- **BSSID**: not visible in code. It may equal the `wifi-mac` sent in `info` or a bit-flipped variant; verify on hardware.

## UPnP / HTTP services (observed in the log, Wi-Fi Direct session)

| Port | Service |
|---|---|
| 7676 | UPnP device description, `http://<cam>:7676/smp_2_`, plus SOAP control |
| 1900 | SSDP (HTTPMU) |
| 24234 | HTTPU |
| 7679 | live stream, `/livestream_high.avi`, `/livestream_middle.avi`, … |
| 9001 | file download, e.g. `/DCIM/100PHOTO/360_0241.MP4` |

- The camera subscribes back to the phone at `http://192.168.49.1:8059/evetSub` [sic].
- SOAP actions seen in the log: `SetOperationState`, `GetInfomation` [sic], `StartRecord`, `StopRecord`, `X_TimerlOperation` [sic], `SetStreamQuality`, `StopStreaming`.
- The DLNA stack opens its servers per network interface, so the same ports are expected on the softAP [inferred].
- `libdi-network-dlna-api.so` contains `HTTP/1.1 401 Unauthorised` and parses `Access-Method` and `User-Agent`, so an access check exists. Details still to be reversed.

## Video and live view

- RVF encoder: `mmf_camcorder_set_attributes(..., "camera-rvf-encoder", 0x1d, ...)`. Default RVF size is 2560x1280. Live fps 24/25/30 maps to Wi-Fi fps 12/13/15.
- Streaming size menu (log): 2560x1280, 1920x1920, 1920x1080, 1280x1280, 1280x720.
- Recording modes (log): 3840x1920 30p, 2560x1280 60p, 2560x1280 30p, 1920x960 30p (all 2:1).
- Capture modes: video, time_lapse, photo, burst, looping_video, slow_motion.

## Station mode (camera joining a phone hotspot)

Not supported by stock firmware. Station-mode code exists only in the factory Wi-Fi test (`CUINETWifiTestMan`), triggered by SD files such as `wft_ap.tg` and `wifi_reconnect.txt`, with a static IP of 192.168.1.111. It would need a camera-side helper (see 03_sd_card_hook.md).

## Working softAP bring-up (hardware-confirmed on AQK1, Test 5) — our controller

The controller brings the camera up as a Wi-Fi access point from the factory-mode SD hook,
end-to-end confirmed: the phone joins `G360R-<last6hex>` on a second STA (keeps internet),
rejoins without a dialog, and the app reads battery/SD/gallery and downloads originals.

Mechanism (see `camera/sd/g360r/netup.sh`, strategy "hostapd"):
1. The stock softAP is **hostapd with `driver=nl80211`** (mobileap-agent
   `_mh_core_enable_softap` backend 2). The older WEXT private-ioctl path (SIOCSIWPRIV
   0x8B0C, `ASCII_CMD=AP_CFG`/`AP_BSS_START`) returns **EOPNOTSUPP** on this cfg80211
   bcmdhd build and is not used.
2. **APSTA firmware must be loaded first**, via the stock `/usr/bin/wlan.sh`:
   `wlan.sh stop` (ifconfig wlan0 down) then `wlan.sh softap`, which writes the APSTA image
   (`wlan_apsta_bcm4354.bin`, `_4339` if `board_revision==0`) to
   `/sys/module/dhd/parameters/firmware_path` + nvram and brings the iface up. The default
   `wlan_net` STA image does NOT support the AP iftype, so hostapd aborts on it. Writing
   `firmware_path` + a plain `ifdown/ifup` does **not** re-download the firmware — only
   `wlan.sh softap` does it correctly. `wlan.sh stop`/`softap` write nothing to `/opt`.
3. Then `/usr/sbin/hostapd -dd <conf>` on wlan0 with the stock-format conf (interface=wlan0,
   driver=nl80211, ctrl_interface, ssid, channel, hw_mode=g, ieee80211n=1, max_num_sta=1,
   ignore_broadcast_ssid, wpa=2, wpa_key_mgmt=WPA-PSK, rsn_pairwise=CCMP, wpa_psk/passphrase).
   Run it foreground-backgrounded (not `-B`) so an early abort's stderr is captured.
4. IP 192.168.107.1/24 on wlan0 + our dnsmasq for DHCP.

Environment notes (AQK1):
- No connman/wpa_supplicant/net-config units were present to stop (nothing owns wlan0 as a
  systemd unit); `wlan.sh stop` is what frees the iface.
- `rfkill list` shows **phy0** and **brcmfmac-wifi**; `rfkill unblock wifi` clears both.
- `/usr/bin/wl` works (`wl ap`, `wl status`, `wl ver`, `wl cap`); `/usr/bin/iwpriv` does not
  (libiw.so.30 missing).

### Phone side (companion app)
On Android 17 the app also needs **`android.permission.ACCESS_LOCAL_NETWORK`**, or LAN TCP to
the camera is silently dropped even after joining the AP.
