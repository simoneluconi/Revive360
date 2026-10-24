# Gear 360 (SM-C200) – running code from the microSD card

Goal: run our own code on the camera without reflashing, for example a small helper daemon that adds BT remote shutter and settings, or brings up Wi-Fi without SAP.

Status: derived from static analysis of `C200GLU0APE4`. **Not tested on hardware yet.**

## 1. dfmsd "script mode" via `info.tg`

`/usr/bin/dfmsd` is the factory daemon (DFMS). It is launched by `libfactory-mode.so` (`dfms_launch_if_noexist`, which execs `/usr/bin/dfmsd`). That library is linked into `di-camera-app`, which checks `/mnt/mmc/info.tg` when the card is mounted.

Option parsing in `dfmsd` (decompiled):

```
-p|--ptp                 ptp mode
-c|--card [SCRIPT_FILE]  script mode; without a file, use the file named in /sdcard/info.tg
-t|--test                test mode
no arguments:            script mode if /sdcard/info.tg exists,
                         ptp mode if /sdcard/info.tgw exists,
                         mode 6 if /sdcard/info.tgx exists
```

- `info.tg`: the **first line** (read with `fgets`, max 40 bytes, trimmed) is a file name. The script path becomes `/sdcard/<name>`.
- The client thread copies `info.tg` and the script to `/tmp/` and reads the script line by line.

### Script syntax (`dfms_parse_cmd_data`)

- Tokens are separated by whitespace, with at most 11 tokens per line.
- `"double quoted"` tokens may contain spaces.
- A line starting with `//`, or an empty line, is a comment.
- Token 0 is the command name (max 31 chars). Tokens 1..n are arguments (max 255 chars each).

### Command `shell` (`dfms_cmd_shell.c`)

```
shell script <path>
```

The command runs `system(<path>)` if `access(path, R_OK|X_OK) == 0`. Only the path is passed, without arguments, so the target must itself be an executable script or binary.

The microSD is mounted by `deviced` with `uid=0,gid=0,dmask=0022,fmask=0022` (both vfat and exfat). Every file is therefore `rwxr-xr-x` and passes the `X_OK` check.

### Minimal example (untested)

```
/sdcard/info.tg      ->  run.txt
/sdcard/run.txt      ->  shell script /sdcard/hook.sh
/sdcard/hook.sh      ->  #!/bin/sh
                         ... our commands, running as root ...
```

### Confirmed trigger (static analysis)

`di-camera-app` does launch `dfmsd` for `info.tg`, and the gate is simple:

- On startup and on card mount, the app calls `UI_Check_Production_Mode_And_Start_Daemon` / `UI_Check_Cs_Mode_And_Start_Daemon`.
- `dfms_check_factory_mode` (libfactory-mode.so) returns factory mode (value 2 = script mode) when **both**:
  1. `/sdcard/info.tg` exists, and
  2. its first line, trimmed and resolved as `/sdcard/<first-line>`, exists (`access(path, R_OK) == 0`).
- When that holds, the app registers its dfms app callbacks, calls `dfms_start_app(ui_dfms_app_cmd_handler)` and `dfms_launch_daemon(2)`, which execs `/usr/bin/dfmsd` in script mode.
- `dfms_check_cs_mode` is the alternative: it triggers on `/sdcard/nx_cs.adj` (mode 2).
- `info.tgw` → ptp mode (3); `info.tgx` → mode 4.

So there is **no production fuse or e-fuse gate**: two files on the SD (an `info.tg` naming an existing script) are enough. This must still be validated on real hardware.

Open points to verify on hardware:
- boot/mount timing: the check runs when the camera handle is ready (`UI_Mm_Camera_Get_Handle_Camera() > 0`) and on the card-mounted idle callback, so the SD must be present at power-on or inserted while on;
- whether factory script mode changes normal camera behaviour (UI, power-off timer): the app does register the dfms app-command handler, so it enters a factory-ish state;
- whether the vfat/exfat mount is `noexec` — `deviced` mounts it `uid=0,gid=0,dmask=0022,fmask=0022` with no `noexec` seen, so scripts should be executable, but confirm on device.

### The `st` CLI IPC (SysV message queue)

`di-camera-app` runs `shell_thread_func` (from `libshell-command.so`), which:
- creates a SysV message queue with the fixed key **`0x8828`** (`msgget(0x8828, 0x7b6)`, perms 0666),
- loops on `msgrcv(q, buf, 0xd0, 0, 0)`.

Message layout (208 bytes): `long mtype` (= 1), `int argc`, then `argc` × 20-byte argv strings (`strncpy` 20). After handling, the app writes the integer result to `/tmp/app_result` and posts the named semaphore `/sem_app_res`.

`/usr/bin/st` is the ready-made sender (`shell_sndmsg(argc, argv, 0x8828)`). Because the queue is world-writable and keyed by a constant, any process can drive it; a hook script can simply call `st app …`.

**Limitation:** the `st` command table only reaches the registered shell handlers. None of them calls `CUINETFuncDLNA::Start`. `st app show liveview` is just an on-screen info toggle. `st app softap start` and `st app wifidirect start` bring up the network but, as noted above, do **not** start DLNA/UPnP. So `st` can start Wi-Fi but not the UPnP/RVF stack. Starting that without BT needs the in-process injection route (docs/firmware/05_injection_route.md) or our own HTTP/RTSP server.

## 2. Other SD trigger files checked by `di-camera-app`

| File | Purpose (from strings and code) |
|---|---|
| `info.tg`, `info.tgx`, `info.tgw` | factory DFMS modes (above) |
| `wft.tg`, `wft_ap.tg` | factory Wi-Fi test (`CUINETWifiTestMan`): station mode, static IP 192.168.1.111 |
| `bt_cert.sh` | BT certification script, run by the app (`[APP] ( bt_cert.sh )`) |
| `bt_elentec.txt` | BT test |
| `wifi_reconnect.txt` | Wi-Fi test reconnection |
| `fwupgrade.txt` | enables "cs fw" upgrade |
| `mwc_demo.txt` | `UIScriptMan`: `[EXECUTE] Script [%s]` |
| `retailmode_batt.txt`, `ux_test.txt`, `ux_sound_test.txt`, `save_screen_enable.txt`, `fota_test.txt` | test and demo modes |

Kieron Quinn's firmware README also documents an `updater.sh` on the SD root that runs `/usr/sbin/fw_upgrade_start <file>.bin`.

## 3. The `st` test CLI

`/usr/bin/st` (with `libshell-command.so`) drives `di-camera-app`. Relevant commands from the app's help text:

```
st app capture                       start capture
st app wifidirect start|stop         Wi-Fi Direct autonomous GO start / disconnect
st app mode_360 [video|time_lapse|photo|burst|looping_video|slow_motion]
st app mov [video|looping][360|180R|180L][3840_1920_30|2560_1280_30|1920_960_30...][0|5|30|60|max]
st app show [liveview|lock3a|pro_shift|mov|fw_info]
st app set [shot] [on|off]
st app info | rtc | led | usb | volum | ...
```

From a hook script, `st app ...` could trigger captures and mode changes, and start Wi-Fi Direct as autonomous GO, without BT. The IPC between `st` and the app is still to be reversed (`libshell-command.so`).

### Undocumented: `st app softap start|stop`

The command table in `di-camera-app` also contains `softap`, which is not listed in the help text. Its handler (`FUN_000e8214`) does:

```
UI_Set_Value_WiFi_Client_Ip("107");
UI_SoftAP_Manager(2, cb);       // -> UI_WiFi_Soft_Ap_Create()
```

`UI_WiFi_Soft_Ap_Create` (the legacy, non-BT network path):
- SSID: `UI_Get_Value_WiFi_SoftAp_SSID()`.
- If setting `0x3c` == 0, it sets WPA2-PSK with the **same persisted 8-digit password** (setting `0xc4e`, recreated only if missing) that the BT path uses.
- It then calls `tethering_enable(h, 4, client_ip, 5)`.

When tethering is enabled, the callback (`FUN_001692e8`) calls `UI_ModeServerStart(...)`. **On the C200 that function is an empty stub**, so the old NX "mode server" (`/mode/change`, the iOS flow) is not started. **DLNA/UPnP is not started on this path either.** Only the BT path (`CUINETFuncDLNA::Start` from a BT `cmd-req`) starts DLNA.

### Autonomous GO (`st app wifidirect start`) and legacy WPA2 clients

- The handler calls `UI_WifiDirect_Set_SSID()` and posts a thread message to the legacy Wi-Fi Direct manager.
- That manager's connection callback (`FUN_00167428`) has the **same** allowed-MAC `strcmp` → `wifi_direct_accept_connection` / `wifi_direct_cancel_connection` logic for P2P connection requests.
- A *legacy* (non-P2P) WPA2 client joining the GO is reported by the supplicant plugin as `AP-STA-CONNECTED`, not as a P2P connection request. It therefore probably does not go through that check [inferred, needs a hardware test].
- `p2p_supplicant` supports `P2P_GET_PASSPHRASE` and `P2P_GROUP_ADD [persistent] [freq=]`, and `/usr/sbin/wpa_cli` is present. A hook script could read the GO passphrase with `wpa_cli -i <p2p-if> p2p_get_passphrase`, or create its own group, and hand it to the phone.
- As with softAP, DLNA would still have to be started separately.

### RAW still capture (via the `st` / NX factory path)

The sensor and firmware support RAW. The on-screen quality enum is `sfine, fine, norm, raw, raw+sfine, raw+fine, raw+norm` (`CAP_FN_QUALITY_RAW*`), and RAW files are Samsung **SRW** (`eUI_FILE_TYPE_PHOTO_SRW`). The stock app and the BT `config-info` path do **not** expose quality, so RAW is not selectable over stock Bluetooth. It is reachable through the `st` factory capture tree, i.e. from an SD hook script or our helper:

```
st app nx capture quality raw        # or raw+fine, raw+sfine, raw+norm
st app nx capture run count 1        # shoot (cmd_capture_single / cmd_scenario_start)
```

`cmd_set_photo_quality` accepts all the raw variants. The resulting `.SRW` is written to DCIM and can be pulled over the Wi-Fi HTTP file server (port 9001, e.g. `http://<cam>:9001/DCIM/100PHOTO/...`), the same way MP4/JPEG are served.

Caveats (to confirm on hardware):
- This is the NX factory capture path; it may put the camera into a factory capture state rather than the normal 360 UI.
- A 360 RAW frame is really two fisheye SRW images (front + rear sensor). Demosaicing and stitching RAW is our problem on the phone/PC side; the lens calibration from docs/firmware/04 is the input for that.
- Exposure/WB/AF also have `st app nx capture` subcommands (`wb`, `af-mode`, `resolution`).

## 4. Implications

- Shutter, record and mode commands could be offered over BT by a helper daemon that listens on a plain RFCOMM socket and calls `st app ...`. This would fill the gap left by the dead BT remote-shot path (see 01_camera_bt_protocol.md).
- The helper could also bring up Wi-Fi (softAP or autonomous GO) and DLNA without a SAP session.
