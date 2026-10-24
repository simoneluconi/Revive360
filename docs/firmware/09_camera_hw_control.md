# Camera hardware control from a factory-mode root helper (C200 / AQK1)

Building blocks for the "controller" approach: a root process started via the SD `info.tg` hook drives the camera's hardware directly, with the phone as the remote UI. All of this works in factory script mode, as root, with no injection and no app restart. Device/firmware evidence is from the AQK1 extract.

## 1. Networking (bring up our own softAP)
Binaries present (all real ELF, armel softfp, glibc 2.13, interp `/lib/ld-linux.so.3` → `ld-2.13.so`; NOT busybox applets): `/usr/sbin/hostapd` (8 NEEDED libs), `/usr/sbin/hostapd_cli`, `/usr/sbin/wpa_supplicant`, `/usr/sbin/udhcpd` (4 libs), `/usr/bin/dnsmasq` (4 libs), `/usr/bin/mobileap-agent`.
Plan: configure `wlan0` (Broadcom `dhd`) as an AP, our own SSID/pass, independent of di-camera-app. Phone joins with `WifiNetworkSpecifier`.

**Build-critical — NO shell server on the device.** The stock rootfs has **no** `nc`/`netcat`/`ncat`, `httpd`/`lighttpd`/`thttpd`/`mini_httpd`, `python`/`perl`/`socat`, and **no busybox** (only `toybox`, minimal). `/bin/sh` is **bash** (can `/dev/tcp` connect, but cannot `listen()`/`accept()`). So the camera-side control + file server for v1 **must be a small cross-compiled ARM binary** (armel softfp, glibc 2.13, libc-only, no EFL). A shell/toybox HTTP server is not an option.

**softAP mechanism caveat (needs confirming in the build):** `mobileap-agent` does not clearly use the standard hostapd+nl80211 path. Its strings include a Broadcom `dhd` private command `ASCII_CMD=AP_CFG,SSID_LEN=%d,SSID=%s,SEC=%s,KEY_LEN=%d,KEY=%s,CHANNEL=%d,PREAMBLE=0,MAX_SCB=%d,HIDE=%d,END` plus `__mh_core_softap_firmware_start/stop`, alongside `/tmp/dnsmasq.conf` and a hostapd-style conf. So the C200 likely brings the AP up via the Broadcom firmware/driver private command (wext) + `udhcpd`/`dnsmasq`, with a possible AP-mode firmware/nvram load — not plain hostapd. This is the single biggest build unknown for networking and warrants its own RE pass of `mobileap-agent`.

## 2. Imaging (capture / RAW / live view)
Via the dfms/PIM command protocol (firmware session owns the exact sequence). Tokens seen in dfmsd: `dfms_pim_do_video_capture`, `dfms_pim_do_aframe_capture`, `dfms_pim_cmd_movie`, `dfms_pim_liveview_data_receiver`, `dfms_pim_capture_data_receiver`, `RawSave`, `GetCaptureData`. Files pulled over HTTP :9001 (or our own server).

## 3. Status OLED (SSD1309)
Driven by `libmicom-node.so` over `/dev/ssd1309-ctrl` (and `/dev/micom-ctrl`).
Exports: `stlcd_initialize`, `stlcd_start`, `stlcd_stop`, `stlcd_set_property`, `stlcd_has_device`, `stlcd_finalize`, `dot_matrix_display_on`, `dot_matrix_draw`, `set_statusLCD_production`, `get_micom_status`, `get_micom_version`, `micom_has_device`. (`set_oled_color` is also a di-camera-app export.)
di-camera-app draws it through a `Service/StatusLCD/CSLCDDraw*Man` layer (`UI_Status_Lcd_Start/Stop`). dfmsd also writes the status LCD via `set_statusLCD_production` and uses `/dev/fb0`.
**RESOLVED (probe 4, AQK1):** `/dev/ssd1309-ctrl` opened for both READ and WRITE from a second root process — **not exclusive**, so our own OLED UI is viable (concurrent writer, last-writer-wins; full draw needs the compiled `libmicom-node` helper). `/dev/micom-ctrl` does **not** exist on this build; `/dev/fb0` does.

## 4. LEDs
C200 LED set: a tricolor **status** LED (R/G/B, blink-capable), a **front** LED, a **rear** LED, plus the **charge** LED. (di-camera-app enums: `E_DEVELOPE_ITEM_STATUS_LED_R/G/B`, `E_DEVELOPE_ITEM_FRONT_LED`, `E_DEVELOPE_ITEM_REAR_LED`; "Status LED : R/G/B", "R :Low Blink".)

Three ways to drive them as root, easiest first:
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
`st app 360` also exposes: `batt [3|2|1|0|off]`, `batt_full [on|off]`, `batt_health [hot|normal|cold|cool]`, `batt_remove [on|off]`, `batt_temp [warning|off|disable]`, `card [error|normal]`, `lens [error|normal]`, `mov [low]`, `reset`. Mostly fault-injection for the factory line; `led` is the directly useful one.

## 7. Safety
Factory mode disables low-battery auto-power-off (`memory/sysman/battery_skip_low_batt_power_off=1`). Our daemon should reset it to 0 (we are root) so the camera still powers off on a dead battery. Nothing here writes to internal flash; all state is volatile and cleared on reboot.
