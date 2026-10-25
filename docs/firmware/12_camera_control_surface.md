# Gear 360 (SM-C200 / AQK1) – full camera control surface
> **Context:** interoperability / repair of the owner's own abandoned Samsung Gear 360. Catalog
> of the control commands the camera firmware exposes, so our controller (g360rd) can drive them
> over our own BLE/Wi-Fi channel. Includes functions the official Samsung app never surfaced.

How g360rd drives these: the camera services the **`st` message queue** (key 0x8828) in normal
mode and the **dfms** command path; g360rd runs the matching `st app …` or dfms command and reads
state back. All verified to be present in the AQK1 di-camera-app / libfactory-mode; argument
tokens below are from the on-device help strings. **Risk labels: [SAFE] / [TEST] (fault/log
injection, not real control) / [DESTRUCTIVE] (erases data) / [HAZARD] (can brick/calibration or
reset the SoC — never expose casually).** "Verify on HW" = token exists but not yet confirmed to
act on the C200 (like RAW), so confirm before exposing.

## 1. Capture (stock-app-equivalent) — [SAFE]
- Shutter / still: `dfms "capture still"` (works in normal mode; JPEG). `st app nx capture run count <n>` also exists (wrote nothing in normal-mode test — use the dfms path).
- Quality: `st app nx capture quality [sfine|fine|norm|raw|raw+sfine|raw+fine|raw+norm]` — the st/dfms path does NOT reach RAW on the 360 (ignores the quality menu). RAW is instead reached from the in-process shim by setting the RTOS USERDATA directly via the exported `SetUserDataMCB`: `RAW_SAVE`=0x7b (1=on), `SSIF_RAW`=0x7c (1=on), `RAW_BIT`=0x7d (0=14/1=12), `RAW_PACK`=0x7e, `RAWQUALITY`=0x7a, `IMAGEQUALITY`=0x04 (3=RAW,4-6=RAW+JPEG). See doc 08 "RESOLVED: RAW is reachable". Per-shot JPG/RAW/RAW+JPG.
- Mode: `st app mode_360 [photo|video|time_lapse|burst|looping_video|slow_motion]` — the **mode change** the user wants.
- Lens: `st app lens_mode [360|180R|180L]` — 360 / front-only / rear-only.
- Self-timer / autoscatto: `st app set [timer] [on|off]` + the capture drive-timer (dfms drive_timer; self_shot path). The countdown value: see `st app set [time_laps]` style; confirm the exact self-timer seconds token on HW.
- Video: `st app nx record run [mode]`, `st app nx record resolution [show|name]`, or
  `st app mov [video|looping][360|180R|180L][3840_1920_30|2560_1280_30|1920_960_30|…][0|5|30|60|max]`.
- Time-lapse: `st app time_laps [360|180R|180L][<mov size>][0.5|1|2|5|10|20|30|60]`.
- Movie size: `st app set [mov_size] [3840_1920_30|2560_1280_30|1920_960_30|2880_1440_30|2560_1280_60]`.
- Still size: `st app set [size] [dual_l|dual_s|sin_l|sin_w]`.

## 2. Settings (stock-app-equivalent) — [SAFE]
- EV / exposure comp: `st app set [ev] [-3 … +3 in 0.3 steps]`.
- Intelligent zoom: `st app set [izoom] [1.0|1.2|1.4|1.7|2.0]`.
- Sharpness: `st app set [sharpness] [normal|vivid]`.
- WB: `st app nx capture wb [option]`.
- AF mode: `st app nx menu afmode [single|caf|auto|manual]` / `st app nx capture af-mode […]`.
- Flash: `st app nx menu flash [off|auto|fill-in|fill-in-red|curtain-1|curtain-2|smart]` (the C200 has no flash — likely a no-op, verify).
- Power-save / auto-off: `st app set [power_timer] [off|0.5|1|3|5|10|30]`, `st app nx menu powersave [on,off]`, `st app set [disp_timer] [off|0.5|1|3|5|10]`.
- Sounds: `st app set [mov_sound] [on|off]`, `[voice] [on|off]`, `[shot] [on|off]`; beep level via `st app 360 [sound] …` tokens.
- Video out: `st app set [video_out] [ntsc|pal]`.
- Loop time: `st app set [loop_time] [5|10|20|60|120|max]`, `[time_laps] [0.5…60]`.
- RTC: `st app rtc [set|get]` (plus the device_set_property RTC path g360rd already uses).

## 3. Status / info — [SAFE]
- `st app show [fw_info|batt_info]`, `st app isfull` (card full?), `st app data [save]`.
- Direct (preferred, no app): battery `/sys/class/power_supply/*`, storage `statvfs` on `/opt/storage/sdcard`, state from the capture engine. (See docs 08/09.)

## 4. EXTRA / HIDDEN — present in firmware, the official app did NOT expose these
- **Special color filters:** `st app set [sf] [off|vig|min|war|neg|red|gr|bl|yel]` — vignette, miniature, warm, negative, red/green/blue/yellow tints. Not in the stock app UI. [SAFE, verify]
- **EFS / electronic features:** `st app set [efs] [on|off]`. [verify]
- **AF debug/engineering:** `st app nx menu af-box set <w> <h>`, `af-box`, `af-box-cluster [on,off]`, `full-af-box [on,off]`, `afmode manual/caf`. [SAFE for read; manual AF is a real extra control]
- **LCD/OLED direct text/icons:** `st app lcd [card][full|err|no]`, `[mov_time][init|start][min|hour1|hour2]`, `[off]` — drive the status screen content directly. [SAFE]
- **LED direct:** `st app led [blink|off] [duration]`, `st app 360 [led][status|front|rear][r|g|b][on|off]` — full LED control (see doc 09). [SAFE]
- **USB mode switch:** `st app usb [mass|ptp|on|off]` — force mass-storage/PTP. [SAFE]
- **Wi-Fi Direct:** `st app wifidirect start|stop` (we use our own hostapd instead). [SAFE]
- **Volume:** `st app volum [off|low|mid|high]`.
- **Perf / test tooling:** `st app nx timing start|save` (timing traces), `st app nx run rand-move` (automated random-input UI test — do NOT expose to users), `st app nx menu test-mode`, `s2-trace`, `capture-end-trace`, `st app subtest [rear|front]`, `st app test [en|fr|cn|o|t]` (language/screen test). [TEST]
- **Sensor / imaging engineering (dfms):** `exposure` (manual exposure/gain), `sys_param` / `image` (sensor & image-pipe parameters), `scam_param` + `scam_capt` (direct sensor-cam capture / factory picture modes). Powerful manual control the app never offered. [verify; some write sensor regs]
- **Factory picture-inspection modes (libfactory-mode, PIM):** `dfms_cmd_mot/dust/noise` → SetPIMode(1/2/3) streaming + raw frame grab to /dev/d5_sma (doc 08). The route to live sensor frames / raw in factory mode. [engineering, compiled helper]
- **Gyro self-test:** `dfms … gyroscope` (`/dev/accel`, gyro enable) — a real IMU read we could surface (orientation/level). [SAFE-ish, read]

## 5. DESTRUCTIVE / HAZARD — gate hard, never expose casually
- **Format card:** `st app nx menu format` / `st app foramt` / dfms `file_format`. [DESTRUCTIVE — erases the SD] Expose only behind an explicit confirm.
- **Reset:** `st app 360 [reset]`, dfms `factory_reset`, `rstverif`. [DESTRUCTIVE — settings/factory reset]
- **Calibration / adjustment writes:** `dfms afi_adj_set`, CIS calibration (`/opt/share/dfms/CIS.bin`), `proc_mark`, `serial`/`shipment` writes. [HAZARD — corrupting these degrades image alignment or identity; do not expose]
- **Battery/fault INJECTION (simulation, not control):** `st app 360 [batt][3|2|1|0|off]`, `[batt_full]`, `[batt_health][hot|normal|cold|cool]`, `[batt_remove]`, `[batt_temp][warning|off|disable]`, `[card][error|normal]`, `[lens][error|normal]`, `[mov][low]`. These SIMULATE fault states for testing the UI — they are NOT real battery/card control, and some battery paths are dangerous. [TEST/HAZARD — never expose; see the sec-battery SoC-reset hazard in doc 07/09]

## 6. What to put in v1 vs later
- **v1 (safe, high-value):** shutter, mode change (mode_360), lens mode, self-timer, time-lapse, video start/stop + resolution, quality (JPEG), EV, zoom, sharpness, WB, AF mode, power-timer/auto-off, sounds, video-out, status (battery/storage/state), **format card (behind confirm)**, Wi-Fi AP on/off, time sync.
- **Nice extras the official app lacked:** special color filters (`sf`), manual AF, direct LCD/LED, USB mode, IMU/level readout.
- **Later / gated:** RAW (ids pinned, shim test pending — see doc 08), live view (PIM), manual exposure/sensor params, factory picture modes.
- **Never user-exposed:** reset/calibration/serial writes, fault-injection, the random-input test.

Each exposed function = a control-plane opcode/endpoint → g360rd runs the `st`/dfms above → reads
state back to confirm. Argument tokens that are "verify on HW" must be tested like RAW (set →
read back → confirm it acted) before shipping, since some are compiled-in from the shared
NX/DRIMe5 codebase but may be gated on the C200.

## In-app control plane (shim-driven) — UI key catalogue (AQK1)

RAW is closed (factory-locked, see doc 08). The control surface is driven from the in-process shim
by calling di-camera-app's own EXPORTED UI functions on its ecore main loop (proven on HW:
`st app lens_mode`, and UI_Get_Value / UI_Set_Value / UI_Send_Message_Changed_Attributes). Prefer
the exported wrappers / `UI_Change_Menu_Value` (they wrap the "exceptional value" + propagation).

Exported setters/getters (dlsym by mangled name; AQK1 addresses):
- `UI_Set_Value(item,int)` @0x1eb1c8 · `UI_Get_Value(item)` @0x1eb054
- `UI_Change_Menu_Value(item,int,bool)` @0x26287c — the app's own "change list menu + propagate"
- `UI_Send_Message_Changed_Attributes(item,int)` @0x2603d0 · `UI_Send_Message_Changed_All_Attributes()` @0x2603a0 (mode needs the "All" form)
- `UI_Get_Cap_Run_State(state)` @0x25deac
- `ui_set_360_lens_mode(int)` @0x1f9a98 (0=360,1=180R,2=180L)
- `UI_Set_Attr_Movie_Size(int)` @0x159138 · `UI_Set_Power_Save_Timer(timer,int)` @0x10f370

| Feature | UI key | Values | How to set |
|---|---|---|---|
| Shooting mode | 0x21c | video=0, photo=1, time_lapse=2, looping=3, burst=4, slow=7 | Set_Value + User_Spec_Exceptional_Value_On_Item_Change(0x21c) + **Send_Message_Changed_All_Attributes()** (timelapse also sets 0x14a=1,0x16c=1). Try UI_Change_Menu_Value(0x21c,v,1). |
| Lens mode | 0x21a | 0=360,1=180R,2=180L | exported `ui_set_360_lens_mode(v)` |
| Photo size | 0xcf | dual_l=0, dual_s=1, sig_l(single-large)=3, sig_w(single-wide)=5 | `UI_Change_Menu_Value(0xcf,v,1)` |
| Movie size | 0x223 | 0=3840x1920@30,1=2560x1280@30,2=1920x960@30,3=2880x1440@30,4=2560x1280@60,5=2560x1440@30,6=1920x1080@30,7=1280x720@30,8=1920x1080@60 (selectable 0–8; idx9=2560x1280@24 in fps map only) | read `UI_Get_Value(0x223)`; set `UI_Change_Menu_Value(0x223,n,1)` or exported `UI_Set_Attr_Movie_Size(n)` |
| Special color filter (sf) | 0xd9 | token table | `UI_Change_Menu_Value(0xd9,v,1)` |
| Loop time | 0x21b | 5=0, 20=1, 60=2, max=3 (only 4 options) | `UI_Change_Menu_Value(0x21b,v,1)` |
| Time-lapse interval | 0x224 | 0.5=0,1=1,2=2,5=3,10=4,20=5,30=6,60=7 | `UI_Change_Menu_Value(0x224,v,1)` |
| Self-timer | 0x225 | 4 values; 0=off; 1/2/3=presets (likely 2s/5s/10s — confirm order on HW) | `UI_Change_Menu_Value(0x225,v,1)` |
| Image quality | 0xd0 | 0=SFINE,1=FINE,2=NORM,3=RAW,4-6=RAW+JPEG (RAW inert on 360 — factory-locked) | Set_Value + Send_Message_Changed_Attributes |

Recording (video mode) — read state, don't guess:
- `MOVIE_REC_STATE` = `UI_Get_Value(0x2bc)` → eUS_MEDIA_STATE: 0=INVALID,1=STANDBY,2=REC,3=STOP,4=PAUSE (==2 recording).
- `REC_ELAPSED_TIME` = `UI_Get_Value(0x2bd)` seconds (written by __ui_cb_recording_status each tick).
- Start/stop: a capture in video mode toggles — from STANDBY/STOP it starts REC, from REC it stops. Read 0x2bc first to decide. (Confirm toggle on HW.)
- Busy / capture-in-progress key = `0x27b` (app sets =2 at capture start). g360rd should refuse a new still while `UI_Get_Value(0x27b)==2` or `UI_Get_Value(0x2bc)==2`.

### Mode switch must run the state-machine transition (not just set 0x21c)

HW result: settings + video record work; but setting mode key 0x21c alone does NOT switch the live
screen/pipeline — the camera only follows at the next shutter. Reason: 0x21c is the 360 "dial
selection"; the ACTIVE engine mode is UI value **0x7d9** (different enum: 360 sub-modes 0x21/0x26/0x27…,
normally derived from the hardware dial via User_Spec_Convert_Mode_By_Dial — the C200 has no real dial).
After setting 0x21c, run the app's own transition chain (all exported, main loop):
`UI_Load_Variable_Mode_Change(mode,false)` @0x181274 (sets 0x7d9 + loads the mode's variable set) →
`UI_Manage_Capture_Mode_By_Modechange(mode)` @0x11ddb4 (capture sub-state + still/movie pipeline) →
`UI_Update_Liveview_Ae_Info(1,1,0,0)` @0x163bcc → `UI_Liveview_State_Constructor()` @0x1457d8 (restart
live view) → `UI_Display_Manage(100,1,0,0,0)` @0x17aee0 (standby redraw). `mode` = the 0x7d9 enum, NOT
the 0x21c value — pin the 0x21c→0x7d9 map empirically (set 0x21c, shutter-switch, read UI_Get_Value(0x7d9))
or statically via User_Spec_Convert_Mode_By_Dial. Pass `false` (true reads the hardware dial). Gate the
next shot on `Ui_Get_Common_Run_State()&0x200`==0 (mode-change-in-progress clears) + UI_Get_Value(0x7d9)==target
+ UI_Get_Value(0x27b)!=2. No dedicated Mode hardware key on the C200 (Menu 0x49/0x7f, Shutter 0x7e only),
so uinput key-injection is not an option for mode.

### Mode/size take effect only via the state-transition entries (not by setting the UI value)

HW finding: setting 0x21c (mode) or 0x223 (movie size) updates the value but does NOT re-realize the
capture/record engine — the camera keeps the old mode/size until the next capture rebuilds it, and
time_lapse/looping never record via the generic dfms shutter. Note 0x7d9 stays 1 on the 360 (NX
still-engine mode, NOT the photo/video selector — do not gate mode on it). Use the app's own
transition entries (exported, main loop, in standby):
- to PHOTO: set 0x21c=1 + Changed_All → `UI_Liveview_State_Constructor()` @0x1457d8
- to VIDEO/TIMELAPSE/LOOPING: set 0x21c=v + Changed_All → `UI_Manage_Movie_Standby()` @0x15c0d8
  (sets 0x2bc=1 standby, 0x27b=6, calls UI_Mm_Camcorder_Record_Stby to realize the camcorder for the mode)
- movie size: set 0x223=n → `UI_Manage_Movie_Standby()`; if it keeps the old size, force
  `UI_Mm_Camcorder_Unrealize()` @0x1a0784 → `UI_Set_Attribute_For_RVF_Stream(1)` @0x159324 →
  `UI_Mm_Camcorder_Realize()` @0x1a0598 → `UI_Manage_Movie_Standby()`.
Recording (use mode-aware entries, not the dfms shutter): start `UI_Manage_Movie_Rec(true)` @0x15bed0
(video/looping) or `UI_Manage_TimeLapse_Rec()` @0x159654 (time-lapse); stop `UI_Manage_Movie_Stop(0)`
@0x159890. Gate readiness on UI_Get_Value(0x2bc) (1=standby,2=recording) and 0x27b (don't start while 2).
