# Gear 360 (SM-C200) – factory script mode: gating, persistence, and the handoff to normal mode

Source: Ghidra analysis of `di-camera-app`, `dfmsd`, `libfactory-mode.so` (C200GLU0APE4,
confirmed byte-identical `dfmsd`/`libfactory-mode.so` on AQK1). Hardware-confirmed on the
user's AQK1 camera by probes 1 and 2.

This is the basis for the SD-card helper's design (see 03_sd_card_hook.md and
05_injection_route.md): how to enter our code, and how to hand control back so the camera
runs in its normal UI with our payload loaded.

## 1. When factory mode is decided (the latch)

The factory/script-mode decision runs exactly **once per process**, behind a latch:

- `FUN_0012ef1c`: `if (g_app_data+0xcc == 1) return; else { g_app_data+0xcc = 1; UI_Check_Production_Mode_And_Start_Daemon(...); ... }`.
- Both entry paths funnel through it:
  - power-on: `UI_Start_Power_On_Camera → FUN_0013cb80 → FUN_0012ef1c`
  - file-list-init / card-mounted idle callback: `UI_Done_File_List_Init → FUN_0012ef1c`

Consequences:
- Factory mode is evaluated at the first of {power-on init, file-list-init} in a given
  process, and **not re-evaluated** on later card events in the same process.
- `dfms_check_factory_mode` requires **both** `/sdcard/info.tg` and the script it names
  (resolved as `/sdcard/<first line>`) to exist (`access R_OK`). Removing or renaming
  `info.tg` defuses it.
- On a **restart** of `di-camera-app` the process is fresh, so the latch resets and the
  check runs again — this is what the handoff relies on.

## 2. Persistence: what entering factory mode writes

Entering script mode and running a `shell script` line writes **only volatile `memory/`
vconf keys**; nothing persistent:

- `dfmsd` `main()` sets `memory/sysman/battery_skip_low_batt_power_off=1`,
  `memory/dfms/power_off=0`, `memory/dfms/stop_cmd=0`, `memory/dfms/start_cmd`.
- `memory/` vconf = RAM/tmpfs, cleared on reboot. No `db/` (persistent) or `/opt` writes
  occur on entry.

Persistent writers exist but are reached **only by explicit dfms commands**, never by a
`shell script` line:

- CIS calibration blob → `/opt/share/dfms/CIS.bin` (`FUN_000130d8`, CIS adjust command)
- proc-mark values (`dfms_set_proc_mark_value`, `FUN_00017b98`, proc_mark command)
- serial-number / shipment-info handlers
- `__dfms_vconf_mmc_format_cb` (MMC format) and `dfms_cmd_factory_reset` — these are the
  destructive ones, and they are command/vconf-callback driven.

**Caveat (volatile but relevant):** while in factory mode,
`memory/sysman/battery_skip_low_batt_power_off=1` disables low-battery auto-power-off. It
resets on reboot, but do not leave the camera in factory mode unattended on low battery.

**Operational warning:** the factory mode draws a "self test" OSD. Do not press the camera
buttons there — key/touch handling in that UI can reach factory functions (format, reset,
calibration). Our scripts never invoke those, but a human pressing buttons might. Enter,
let the script hand back to normal mode, and otherwise power off.

## 3. What "self test" is

The on-screen "self test" is the `dfmsd` script-mode **factory OSD** (default screen), not
an automatic sensor test. The real gyro/accel self-test
(`dfms_gyroscope_selftest`, `/dev/accel`, `/sys/class/input/input2/gyro/enable`) runs
**only** via an explicit `dfms … gyroscope` command. `dfmsd`'s client thread merely reads
the command script and dispatches; there is no auto-self-test on entry.

## 4. The handoff to normal mode

Goal: enter via the SD hook (root), then get `di-camera-app` running in its **normal** UI
with our payload loaded, leaving no persistent trace and re-arming for the next boot.

Hardware facts that make this work (probe 2, AQK1):
- `systemctl` works from the hook context; `di-camera-app.service` is `loaded active running`.
- `/run/systemd/system` exists and is empty → a runtime drop-in is viable.
- A detached child reparents to PID 1, so it survives `dfmsd` being torn down.
- SMACK is inactive (no smackfs), so `LD_PRELOAD` is not blocked.
- `di-camera-app` env has no `LD_PRELOAD`; libc is `/lib/libc-2.13.so`, EFL `libecore*.so.1.7.99`.

**Cgroup caveat (critical):** `dfmsd` and the hook script are descendants of
`di-camera-app`, so they live in `di-camera-app.service`'s cgroup. systemd here is v208
with the default `KillMode=control-group`, so `systemctl restart di-camera-app` kills the
**whole cgroup** — a plain `setsid`/`nohup` child dies with it. The helper must leave that
cgroup before the restart. Two ways, in order of preference:
1. Re-exec under a transient unit: `systemd-run --unit=<name> /bin/sh <helper> …` (the unit
   gets its own cgroup). `systemd-run` and `setsid` exist on the device; `nohup` does not.
2. Fallback: `setsid` the helper, then `echo $$ > /sys/fs/cgroup/systemd/tasks` to move it
   to the root of the `name=systemd` hierarchy. Log `/proc/self/cgroup` before/after.

Sequence (run detached, outside di-camera-app's cgroup):

1. Copy the payload (and any `.so`) to `/tmp` (tmpfs, RW).
2. `mv /sdcard/info.tg /sdcard/info.tg.g360r` — defuse factory mode for the restart.
3. (Injection variant) write a `/run/systemd/system/di-camera-app.service.d/*.conf` drop-in
   with `Environment=LD_PRELOAD=/tmp/…/libg360r.so`, then `systemctl daemon-reload`.
4. `systemctl restart di-camera-app`.
5. Poll until the new `di-camera-app` PID differs from the old one and has been alive ~20 s.
   The fresh process re-runs the latch; with `info.tg` gone it comes up in **normal** mode.
6. `mv /sdcard/info.tg.g360r /sdcard/info.tg` — re-arm for the next power-on. Safe now: the
   running process already latched and will not re-check.

Because the restart exits factory mode, it also clears the factory OSD and undoes the
volatile low-battery-auto-off change.

Probe 3 (tools/sd_probe3/) tests steps 2, 4, 5, 6 without injection (no drop-in), and then
tries `st app softap start` from normal mode, to confirm the handoff and the softAP path.

## 5. Probe 3 result: the `systemctl restart` handoff FAILS

Run on the AQK1 camera, the handoff did not work:
- The cgroup escape and `mv info.tg` worked; `dfmsd` was gone; `systemctl restart di-camera-app` ran.
- But the new `di-camera-app` sat at ~94% CPU with the UI frozen; `st` returned with no
  `/tmp/app_result`; no BT/Wi-Fi. A hard power-off was needed.

Cause: `di-camera-app` cannot be restarted mid-session by systemd. It is normally brought up
through the Tizen app framework (AUL / launchpad: `launchpad_preloading_preinitializing_daemon`
preloads and forks it), which performs the hardware/ISP/display/hibernation init it expects.
`systemctl restart` re-execs it outside that lifecycle, so early init wedges.

## 6. What is and isn't possible for the handoff (firmware facts)

- **No runtime exit from factory mode to the normal UI.** The only `dfms` teardown
  (`dfms_stop_ipcc`/`dfms_stop_app`/`dfms_terminate_daemon`) lives in
  `UI_Power_Off_Production_Mode` — a shutdown path. Factory mode is latched at boot
  (section 1) and torn down only at power-off. There is no "return to normal" dfms command.
- **The only root-script-exec entry is the `dfmsd` factory path** (`info.tg`; `nx_cs.adj`
  = CS mode, also via `dfmsd`). Other SD files do NOT exec an arbitrary script:
  - `bt_cert.sh`: `sap-server` only checks that it *exists* and then opens a BT-certification
    RFCOMM socket (UUID at .text 0x51238). It is never executed.
  - `UIScriptMan::start_script` (normal-mode, every boot) only reads config files:
    `mwc_demo.txt` (demo mode), `wft_ap.tg` (Wi-Fi factory test), `fota_test.txt` (FOTA URL).
    No exec.
  - `sdb.conf`: checked in `UI_Set_Power_Timer`; its presence only disables the power-save
    / power-off timers (keeps the camera awake). No exec.
- **Proper relaunch is via launchpad, not systemd.** `UI_Manage_App_Relaunch` (used by the
  app for capture-mode/USB/HDMI changes) goes through AUL. On-disk CLIs exist:
  `/usr/bin/launch_app`, `/usr/bin/aul_test`, `/usr/bin/amd`. A launchpad relaunch does the
  correct init, so it is the candidate fix for the broken restart.

### Consequence for the architecture

Returning the app to a working normal UI from the SD hook needs one of:
1. **Relaunch through launchpad** (not systemd): defuse `info.tg`, stop the systemd unit,
   then `launch_app com.samsung.di-camera-app`. To be tested (probe 4).
2. **Inject without restarting**: `di-camera-app` stays alive in factory mode and SMACK is
   off, so LD_PRELOAD/ptrace into the running PID could call the network/capture functions
   directly, avoiding any restart. (See 05_injection_route.md.) Caveat: factory mode may
   gate some normal functions (`st app softap` did not service in factory mode during probe 2).
3. **Own stack**: ignore `di-camera-app`; the root helper brings up wlan0 (wpa/hostapd) and
   serves its own HTTP/RTSP, reading the camera device nodes. Heaviest, most portable.

### Bonus: SDB (dev only)

`memory/sysman/sdb_sel` is a volatile key (probe 2 read it = 0). Setting it to 1 and
connecting USB routes to SDB (`eUI_USB_SEL_SDB`). With root from the hook we can enable SDB
for development iteration, gated behind the user's explicit OK.
