# Camera hardware control from a factory-mode root helper (C200 / AQK1)
> **Context:** interoperability with the owner's own Samsung Gear 360 (2016/2017), a camera the manufacturer abandoned. All work is on the owner's device and firmware; no third-party systems, services, or accounts are involved.

Building blocks for the "controller" approach: a root process started via the SD `info.tg` hook drives the camera's hardware directly, with the phone as the remote UI. All of this works in factory script mode, with uid 0, with no in-process helper and no app restart. Device/firmware evidence is from the AQK1 extract.

## 0. CRITICAL: some sec-battery sysfs attributes are ACTIONS — never enumerate them

Hardware testing (a reset loop) was traced, with an fsync'd per-access trace, to this: reading
certain `sec-battery` sysfs attributes is an **action on the driver**, not a passive read, and
it **resets the whole SoC**. The specific trigger was reading
`/sys/class/power_supply/battery/batt_inbat_voltage` (the controller's `log_supplies()` had
enumerated *every* attribute of the sec-battery node). Likely also dangerous:
`fg_reset_cap`, `batt_reset_soc`, `set_stability_test`.

Rule for the controller: **never enumerate the sec-battery sysfs directory.** Read only a known
safe allow-list: `capacity`, `status`, `health`, `online`, `present`, `temp`, `voltage_now`,
`batt_temp` (these are safe — the launcher's own dump read them fine). Reading an unknown
attribute by iterating the directory can brick-reset the camera. (This was the real cause of
the Test-1 loop — not the LEDs, watchdog, vconf, cgroup move, or wlan0. Fixed in the controller
by reading named attributes only.)

Note on wlan0: it is still prudent not to use `wlan0` (MAC/ifflags/privcmd) until `netup` has
loaded the dhd AP/STA firmware and brought it up — but that was NOT the reset cause; the
sec-battery enumeration was.

## 1. Networking (bring up our own softAP)
Binaries present (all real ELF, armel softfp, glibc 2.13, interp `/lib/ld-linux.so.3` → `ld-2.13.so`; NOT busybox applets): `/usr/sbin/hostapd` (8 NEEDED libs), `/usr/sbin/hostapd_cli`, `/usr/sbin/wpa_supplicant`, `/usr/sbin/udhcpd` (4 libs), `/usr/bin/dnsmasq` (4 libs), `/usr/bin/mobileap-agent`.
Plan: configure `wlan0` (Broadcom `dhd`) as an AP, our own SSID/pass, independent of di-camera-app. Phone joins with `WifiNetworkSpecifier`.

**Build-critical — NO shell server on the device.** The stock rootfs has **no** `nc`/`netcat`/`ncat`, `httpd`/`lighttpd`/`thttpd`/`mini_httpd`, `python`/`perl`/`socat`, and **no busybox** (only `toybox`, minimal). `/bin/sh` is **bash** (can `/dev/tcp` connect, but cannot `listen()`/`accept()`). So the camera-side control + file server for v1 **must be a small cross-compiled ARM binary** (armel softfp, glibc 2.13, libc-only, no EFL). A shell/toybox HTTP server is not an option.

Networking helpers that DO exist on disk (not on the hook's PATH — call by full path): `/sbin/ifconfig`, `/usr/sbin/ip`, `/usr/bin/iwpriv`, `/usr/bin/iwconfig`, `/usr/bin/wl` (the Broadcom `wl` tool), plus `/usr/bin/wlan.sh`. `dnsmasq` is 2.57; `udhcpd` is a `toybox` applet.

### softAP bring-up — RE'd from `mobileap-agent` (`_mh_core_enable_softap`)

The C200 does **not** use hostapd/nl80211. It drives the Broadcom `dhd` directly with ASCII
private commands over a wireless-extensions private ioctl. **AP mode DOES require loading the
concurrent AP/STA firmware first** — this happens one layer below `mobileap-agent`, in
`/usr/bin/wlan.sh softap` (invoked by net-config), which is why `_mh_core_enable_softap` alone
doesn't show it (corrected after the build agent read `wlan.sh`). Full sequence a C helper
should replicate:

0. Load the AP/STA firmware: run `/usr/bin/wlan.sh softap`, or replicate it —
   `echo /lib/firmware/wlan_apsta_bcm4354.bin > /sys/module/dhd/parameters/firmware_path`
   (use `wlan_apsta_bcm4339.bin` when `/sys/devices/platform/dw_mmc_sdio.0/board_revision`==0),
   `echo /opt/etc/nvram/nvram_net_bcm4354.txt > /sys/module/dhd/parameters/nvram_path`, then
   `/sbin/ifconfig wlan0 up`. (`wlan.sh` also appends one cosmetic line to
   `/opt/var/log/wlanplug_log` — a persistent write; harmless.)
1. `sk = socket(AF_INET, SOCK_DGRAM, 0)`.
2. Build an `ifreq` with `ifr_name = "wlan0"` and `ifr.ifr_data = &priv`, where
   `struct android_wifi_priv_cmd { char *buf; int used_len; int total_len=0x100; }` (32-bit).
   `priv.buf` holds the ASCII command. Then `ioctl(sk, 0x8B0C, &ifr)` — **`0x8B0C = SIOCSIWPRIV`**
   (wireless-ext private ioctl; the bcmdhd AP ASCII commands ride on it). The wrapper sleeps
   `usleep(250000)` before each ioctl.
3. Command 1: `"ASCII_CMD=AP_CFG,SSID_LEN=<n>,SSID=<ssid>,SEC=<sec>,KEY_LEN=<n>,KEY=<key>,CHANNEL=<ch>,PREAMBLE=0,MAX_SCB=10,HIDE=<0|1>,END"`.
   `SEC` token: **`wpa2-psk`** (confirmed as a literal next to the AP_CFG template in
   `mobileap-agent`), or `open` for none.
4. Command 2: `"ASCII_CMD=AP_BSS_START"`. After this the AP virtual iface **`wl0.1`** appears.
5. Set the gateway IP on **`wl0.1`** via `ioctl SIOCSIFADDR (0x8916)`, then flags up via
   `SIOCGIFFLAGS (0x8913)` | `SIOCSIFFLAGS (0x8914)` with `IFF_UP|IFF_RUNNING`
   (that's exactly what `_mh_core_set_ip_address` does). Stock also sets an IP on `wlan0`.
6. DHCP: stock writes `/tmp/dnsmasq.conf` and `execl("/usr/bin/dnsmasq", …, "/tmp/dnsmasq.conf")`.
   Our helper can exec `/usr/bin/dnsmasq` (real ELF) with its own conf, or `/usr/sbin/udhcpd`.
7. Teardown: `"ASCII_CMD=AP_BSS_STOP"` then `"ASCII_CMD=AP_CFG,...DOWN"` style (see
   `_mh_core_disable_softap`), and `ioctl 0x8B0C`.

`hostapd`/`wpa_supplicant` are present but NOT on this path. `mobileap-agent` itself is a D-Bus
service (not a plain CLI), so replicate the ioctl sequence in C rather than exec'ing it.
Stock defaults (overridable): SSID `AP_<proj>(xx:xx:xx)`, IP `IP_ADDRESS_SOFTAP` (≈192.168.107.1),
channel `MOBILE_AP_WIFI_CHANNEL` — the v1 build sets its own (G360R-XXXXXX / 192.168.107.1 / ch6).

## 2. Imaging (capture / RAW / live view)
Via the dfms/PIM command protocol (firmware session owns the exact sequence). Tokens seen in dfmsd: `dfms_pim_do_video_capture`, `dfms_pim_do_aframe_capture`, `dfms_pim_cmd_movie`, `dfms_pim_liveview_data_receiver`, `dfms_pim_capture_data_receiver`, `RawSave`, `GetCaptureData`. Files pulled over HTTP :9001 (or our own server).

## 3. Status OLED (SSD1309)
Driven by `libmicom-node.so` over `/dev/ssd1309-ctrl` (and `/dev/micom-ctrl`).
Exports: `stlcd_initialize`, `stlcd_start`, `stlcd_stop`, `stlcd_set_property`, `stlcd_has_device`, `stlcd_finalize`, `dot_matrix_display_on`, `dot_matrix_draw`, `set_statusLCD_production`, `get_micom_status`, `get_micom_version`, `micom_has_device`. (`set_oled_color` is also a di-camera-app export.)
di-camera-app draws it through a `Service/StatusLCD/CSLCDDraw*Man` layer (`UI_Status_Lcd_Start/Stop`). dfmsd also writes the status LCD via `set_statusLCD_production` and uses `/dev/fb0`.
**RESOLVED (probe 4, AQK1):** `/dev/ssd1309-ctrl` opened for both READ and WRITE from a second root process — **not exclusive**, so our own OLED UI is viable (concurrent writer, last-writer-wins; full draw needs the compiled `libmicom-node` helper). `/dev/micom-ctrl` does **not** exist on this build; `/dev/fb0` does.

## 4. LEDs
C200 LED set: a tricolor **status** LED (R/G/B, blink-capable), a **front** LED, a **rear** LED, plus the **charge** LED. (di-camera-app enums: `E_DEVELOPE_ITEM_STATUS_LED_R/G/B`, `E_DEVELOPE_ITEM_FRONT_LED`, `E_DEVELOPE_ITEM_REAR_LED`; "Status LED : R/G/B", "R :Low Blink".)

Three ways to drive them with uid 0, easiest first:
1. **Factory st command**: `st app 360 led <status|front|rear> <r|g|b> <on|off>`.
2. **Direct sysfs** (standard Linux leds class, hardware blink via the timer trigger):
   - `/sys/class/leds/led.0` .. `/sys/class/leds/led.7`, each with `brightness`, `trigger`, `delay_on`, `delay_off`. **RESOLVED (probe 4, AQK1):** this build has **5** GPIO LEDs, `led.0`..`led.4` (`leds-gpio`), each with `brightness` + `trigger` (rich trigger list incl. `battery-charging*`, `default-on`, `timer`, `gpio`), all writable by us. There is **no** `/sys/class/sec/led` here.
   - Samsung service-LED combined interface: `/sys/class/sec/led/led_blink`, `/sys/class/sec/led/led_pattern` (maps to `OEM_sys_set_svcled_color`).
   - Flash/torch: `/sys/class/leds/torch-sec1/brightness` (+`max_brightness`), `/sys/class/camera/flash/movie_brightness`.
   (Exact led.N → physical LED mapping to be confirmed on hardware.)
3. **OEM API** in `libslp_devman_plugin.so`: `OEM_sys_set_led_0..7_brightness` / `_delay_on` / `_delay_off`, `OEM_sys_set_svcled_color`, `OEM_sys_set_leds_torch_brightness`, `OEM_sys_set_leds_flash_brightness`, `OEM_sys_set_irled_control`.
Charge LED: `libmicom-node` `set_charge_led`.

## 5. Buttons (physical keys)
Linux input. **RESOLVED (probe 4, AQK1):** `event0` = "Drime5 GPIO Keyboard" (the buttons;
captured 128 events, EV_KEY press/release for codes `0x49`, `0x7E`, `0x7F` = 3 of shutter/Wi-Fi/menu;
power short-tap likely routes via the PMIC, not this node). Sensors: `event1` accel, `event2`
**gyro**, `event3` sig-motion, `event4/5` step, `event6` tilt (LSM6DS3 — gyro useful for horizon
later). We **do** read the buttons in parallel, BUT the running app **also** consumes them, so
exclusive ownership needs `EVIOCGRAB` (ioctl → compiled helper). Plan: the daemon `EVIOCGRAB`s
`event0` and maps presses (shutter→capture, Wi-Fi key→toggle our link).

## 6. Factory `st app 360` simulation surface (for reference)
`st app 360` also exposes: `batt [3|2|1|0|off]`, `batt_full [on|off]`, `batt_health [hot|normal|cold|cool]`, `batt_remove [on|off]`, `batt_temp [warning|off|disable]`, `card [error|normal]`, `lens [error|normal]`, `mov [low]`, `reset`. Mostly fault injection for the factory line; `led` is the directly useful one.

## 7. Safety
Factory mode sets `memory/sysman/battery_skip_low_batt_power_off=1` (disables low-battery auto-power-off). **Do NOT clear it to 0 while in factory script mode** — hardware testing showed that clearing it re-enables a power path that resets/powers-off the camera within ~1-2 s (even at 78% healthy battery), causing a boot/reset loop. Leave it at 1. Instead, the daemon should monitor the battery itself (`/sys/class/power_supply/battery/capacity`, `/health`, `/voltage_now`) and power off gracefully below a safe threshold. Nothing here writes to internal flash; all state is volatile and cleared on reboot.

Battery sysfs (confirmed on AQK1): `/sys/class/power_supply/battery/{status,capacity,online,present,temp,batt_temp,voltage_now,health}` (capacity = %, voltage_now = µV, temp = 0.1 °C); also `sm5703-fuelgauge/{capacity,voltage_now,temp}`, `sm5703-charger/{status,health,present}`, `ac/online`, `usb/online`.

## Status display (SSD1309 dot matrix) — exact driver interface (decompiled libmicom-node.so.0.1)

Hardware (AQK1): `/dev/ssd1309-ctrl` = misc char dev **10,113**, the **visible small status
display**. `/dev/fb0` (29,0 root:video, **64x64 32bpp**, stride 256) is the EFL/X **dummy
framebuffer** (offscreen surface so elementary/evas can run) — NOT a visible panel. `/dev/micom-ctrl`
(the MCU path, ioctl magic 'M'=0x4d) does **not** exist on this build, so the display is driven
purely through `ssd1309-ctrl` by the `dot_matrix_*` calls (ioctl magic 'S'=0x53).

The panel is addressed as a **32 x 72 dot grid, 1 BYTE per dot** (not a 1bpp page buffer).
All calls: `open("/dev/ssd1309-ctrl", O_RDWR)` then one ioctl, then close.

| Call | ioctl request | enc | arg |
|---|---|---|---|
| `dot_matrix_init()` | `0x40045300` | _IOW('S',0x00,4) | int (0) — panel init |
| `dot_matrix_test()` | `0x40045301` | _IOW('S',0x01,4) | int |
| `dot_matrix_draw(a0,a1,b0,b1,buf)` | `0x49105302` | _IOC(WR,'S',0x02,**2320**) | struct below |
| `dot_matrix_set_contrast(v)` | `0x40045303` | _IOW('S',0x03,4) | int contrast |
| `dot_matrix_update(a0,a1,b0,b1)` | `0x40105304` | _IOW('S',0x04,16) | {int a0,a1,b0,b1} refresh rect |
| `dot_matrix_display_on(on)` | `0x40045305` | _IOW('S',0x05,4) | int 1=on/0=off |
| `dot_matrix_dump()` | `0x40045306` | _IOW('S',0x06,4) | int |
| `dot_matrix_command(cmd)` | `0x40045307` | _IOW('S',0x07,4) | int raw controller cmd |
| `dot_matrix_*` (nr 8) | `0x40045308` | _IOW('S',0x08,4) | int |

`dot_matrix_draw` struct (2320 bytes): `{ int32 A_start; int32 A_end; int32 B_start; int32
B_end; uint8 pixels[2304]; }`. `A` is clamped to [0,31] (32 units), `B` to [0,71] (72 units);
bytes written = `(A_end-A_start+1)*(B_end-B_start+1)`, capped at 2304 (=32*72), one byte per
dot. (Confirm A=rows/B=cols orientation with `dot_matrix_test` on hardware.)

**Power-on/draw order:** open → `0x40045300` (init) → `0x40045305` arg=1 (display on) →
optional `0x40045303` (contrast) → `0x49105302` (draw with pixel buffer) → optional
`0x40105304` (update/refresh rect).

**Coexisting with di-camera-app's drawer:** there is **no exclusive hold** (probe 4: a second
root process opened `ssd1309-ctrl` R/W) and **no runtime off-switch** (`UI_Status_Lcd_Stop`
runs only on the shutdown path). libmicom-node's `stlcd_thread` command msgqueue
(`ftok("/tmp", proj 0x02)` → `msgget(key, 0666)`) targets `/dev/micom-ctrl`, which is absent
here, so it is not the live drawer. Practical rule: **last-writer-wins** — our daemon opens
`ssd1309-ctrl` and redraws at a higher cadence than the app's event-driven status refresh.
True exclusivity needs the in-process/firmware route. (Keys, by contrast, CAN be owned
exclusively via `EVIOCGRAB` on `/dev/input/event0` — confirmed on hardware.)

### Button codes (event0 "Drime5 GPIO Keyboard")
Observed evdev codes: **0x7e** = mechanical press-and-hold (120-200 ms) = **Shutter/OK**;
**0x7f** and **0x49** = momentary tap (down+up same timestamp) = **Menu / Mode**. Power key is
not on event0 (PMIC short-tap). The evdev-code→label table is via the xkb keycodes map
(`/etc/X11/xkb/keycodes/tizen_mobile`; X keycode = evdev code + 8); di-camera-app's ecore
handler matches by keyname. For our daemon, `EVIOCGRAB` + these raw codes are sufficient.

## LEDs — st surface, colour/state semantics, and raw sysfs (for our boot animation)

`st app 360 led <status|front|rear> <r|g|b> <on|off>` (serviced in normal mode) →
`UI_Operate_LED_Ctrl_Set_360(type, color, ctrl, ...)` → `UI_Devman_Led_Onoff_Duty_360` (deviced).
- type: 0x08 front, 0x10 rear, **0x20 status (the RGB one)**. front/rear are gated by a UI value
  (0x21e) so may not respond unless the relevant state is active; status is reliable.
- color bitmask: **1=R, 2=G, 4=B, 8=ALL**. ctrl: **0 OFF, 1 ON, 2 BLINK, 3 BLINK_OFF, 4
  LOW_BATT_BLINK**. The `st` command only exposes solid on/off (no blink); the driver supports
  blink but `st` doesn't pass it. LED state is **latched** — it holds until something writes it
  again (hence "stays red").

Stock status-LED meaning (di-camera-app):
- **BLUE** = powered-on / ready / standby (`UI_Thread_Power_On_Led` blinks blue during boot;
  `UI_Manage_Status_Led` sets blue). Blue is the normal "on/ready" colour.
- **GREEN** = charge-complete on the power cable (`UI_Status_Led_On_Power_Cable_And_Low_Battery`).
- **RED** = low battery (`UI_Operate_LED_Ctrl_Set_360(0x20,1,4,...)` = red + LOW_BATT_BLINK) or a
  special boot/charge/FW state (branch gated by UI value 0x83b). **Red is NOT the normal ready
  indication** — ready is blue.

Raw LED class (for a boot animation in boot.sh, before di-camera-app): nodes under
`/sys/class/leds/` are **led.0 … led.4** (the status-RGB + front + rear channels) plus `af`
(AF-assist lamp), `card` (SD activity), `wifi_red` (Wi-Fi status). All are kernel LED-class →
writing `brightness` (0..max_brightness) and `trigger` is **safe** (no raw GPIO masquerades in
the LED class). Blink in hardware: `echo timer > trigger; echo <ms> > delay_on; echo <ms> >
delay_off` (as `save_log_info.sh` does on led.0/3/4). For a pure animation use led.0-4 and avoid
`af` (a bright assist lamp). **The led.N → colour/position map is not pinned statically** (the
deviced LED plugin maps device_set_property(LED,type,color)→led.N but the table isn't in the
rootfs); pin it empirically (~15 s, read-safe): for n in 0..4 and af/card/wifi_red:
`echo none > trigger; echo 1 > brightness; sleep 1; echo 0 > brightness` and record which
physical LED/colour lights. Hand back to the app by cleaning up every node you touched
(`echo none > trigger; echo 0 > brightness`); running the animation `Before=di-camera-app`
means the app then sets its own power-on LED after us, so there is no conflict.
