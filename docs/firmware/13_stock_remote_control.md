# 13 — The stock remote control inside di-camera-app (AQK1)

The Samsung phone app (Gear 360 Manager) drove the camera over Wi-Fi (DLNA/SMP) and Bluetooth. Its
handlers are still in `di-camera-app` and exported, so the shim plugin can call them on the app's
main loop. They are the camera's own remote paths: using them instead of re-creating the menu's
side effects one by one is what makes a setting behave exactly as on the camera.

Addresses are AQK1 (`C200GLU0AQK1`); every name is exported (`nm -D`), the plugin resolves them with
`dlsym(RTLD_DEFAULT, mangled)`.

## Settings: `NETCameraMenuMan`

One table of remote-settable menus, shared by Wi-Fi (`CUINETFuncDLNA::dlna_set_*`) and Bluetooth
(`CUINETFuncBluetooth::SendBTMsgRequestSetConfig(E_CAMERA_MENU_ID)`, `send_bt_config_info`,
`send_bt_response_set_config`). Singleton: `NETCameraMenuMan::Inst()`; members take the instance.

| Function | Use |
|---|---|
| `is_valid_id(E_CAMERA_MENU_ID)` | non-zero for a menu this camera has |
| `ToString_E_CAMERA_MENU_ID(int)` | the enum name |
| `GetTitleName(id)` | the title string the remote protocol uses |
| `GetMaxNumOfItemList(id)` | item count |
| `GetItemValueWithIndex(id, index)` | the item's value (the UI enum) |
| `GetItemStrByValue(id, value)` | the item's string |
| `GetCurrItemIndex(id)` / `GetCurrItemStr(id)` | the current item |
| `SetMenuValue(id, index)` | applies an item (below) |
| `SetMenuValue(const char *title, const char *item)` | the same by strings (the BT protocol) |

Each ID is a 20-byte entry in the instance: title, UI key (`eUI_VALUE_ITEM`), item count, item
strings (fixed length, `GetMaxLengthOfItem`), item values.

`SetMenuValue(id, index)`: invalid ID → logged and ignored; the mode (ID 8) goes to
`set_mode_value` → `UI_Exit_Mode_Set_State` (the camera's mode change); every other ID:
`UI_Set_Value(key, value)`, `User_Spec_Exceptional_Value_On_Item_Change(key)`,
`UI_Send_Message_Changed_Attributes(key, value)`, `UI_Calculate_Remain` for the size menus, plus the
LED, volume and power extras for their IDs.

The Wi-Fi handlers wrap it: `dlna_set_interval(v)` = `SetMenuValue(24, v)` +
`CUINETUtil::RefreshStatusLCD(true)`; `dlna_set_mode(v)` = `process_mode_change(v)`
(`SetMenuValue(8, v)`, `init_dlna_network_menu`, `RefreshStatusLCD(true)`,
`process_mode_change_complete`) + `CUINETUtil::SLCDExitMenu()`. `dlna_set_switch_lens` →
`process_switch_lens(int)`; `dlna_set_dual_movie_resolution`, `dlna_set_movie_resolution`,
`dlna_set_resolution`, `dlna_set_dual_resolution`, `dlna_set_timer`, `dlna_set_recording_time`
(loop), `dlna_set_interval`, `dlna_set_beep`, `dlna_set_led`, `dlna_set_auto_power_off`, … The
status display refresh after a value is part of the stock path.

The table, dumped on AQK1 with the plugin's `netmenu ID` (2026-10-25; `index=value "string"`, the
value is the UI enum written to the key; IDs 33+ invalid):

| ID | Enum | UI key | Items |
|---|---|---|---|
| 0 | PHOTO_SIZE_DUAL | 0xcf | 0 "7776x3888_2:1", 1 "5376x2688_2:1" |
| 1 | PHOTO_SIZE_SINGLE | 0xcf | 5 "3072x1728_16:9" |
| 2 | FLASH | 0x103 | 0 Off |
| 3 | EV | 0xc9 | 0..60 = -3.0 … +3.0 in 0.1 |
| 4 | ISO (limit) | 0x111 | 1 400, 2 800, 3 1600, 4 3200, 5 6400 |
| 5 | WB | 0xd3 | 0 Auto, 3 Daylight, 4 Cloudy, 2 Incandescent, 5 Fluorescent, 11 Aqua |
| 6 | METERING | 0x105 | 0 Multi, 1 Center-Weighted, 2 Spot |
| 7 | DRIVE | 0xf8 | 0 Single |
| 8 | MODE | 0x21c | 0 Video, 1 Photo, 2 Time lapse, 3 Looping Video |
| 9 | MOV_RESOLUTION_DUAL | 0x109 | 0 3840x1920 30p, 3 2880x1440 30p, 4 2560x1280 60p, 1 2560x1280 30p, 2 1920x960 30p |
| 10 | MOV_RESOLUTION_SINGLE | 0x109 | 5 2560x1440 30p, 8 1920x1080 60p, 6 1920x1080 30p, 7 1280x720 30p |
| 11 | FILE_SAVE_OPTION | 0xc33 | 0 Smartphone + Camera, 1 Camera |
| 12 | RATIO_OFFSET | — | 0 2:1, 1 1:1, 2 16:9 |
| 13 | STREAMING_QUALITY | 0xc35 | 0 High, 1 Middle, 2 Low |
| 14 | STREAMING_RESOLUTION_DUAL | 0xc35 | 0 3840x1920, 1 2880x1440, 2 2560x1280 |
| 15 | STREAMING_RESOLUTION_SINGLE | 0xc35 | 0 1920x1920, 1 2560x1440, 2 1920x1080 |
| 16 | BEEP | 0x14 | 3 High, 2 Mid, 1 Low, 0 Off |
| 17 | LED | 0x21e | 1 On, 0 Off |
| 18 | AUTO_POWER_OFF | 0xd | 1 1min, 2 3min, 3 5min, 5 30min |
| 19 | VIEW_TYPE | — | 0 VR, 1 Dual, 2 Panoramic |
| 20 | HDR | 0x106 | 0 Off, 1 On |
| 21 | WIND_CUT | 0x10f | 0 Off, 1 On |
| 22 | SHARPNESS | 0x222 | 0 +1, 1 +2 |
| 23 | SWITCH_LENS | 0x21a | 0 Dual CAM, 1 Single CAM - F, 2 Single CAM - R |
| 24 | INTERVAL | 0x224 | 0 0.5s, 1 1s, 2 2s, 3 5s, 4 10s, 6 30s, 7 60s |
| 25 | RECORDING_TIME (loop) | 0x21b | 0 5min, 1 30min, 2 60min, 3 Max |
| 26 | MOV_VOICE | 0x10e | 0 Off, 1 On |
| 27 | TIMER | 0x225 | 0 Off, 1 2s, 2 5s, 3 10s |
| 28 | VIDEO_OUT | 0x1a | 0 NTSC, 1 PAL |
| 29 | QUICK_RECORDING | 0x21f | 0 OFF, 1 ON |
| 30 | RESET | — | 0 All, 1 Camera, 2 Connection |
| 31 | LOCATION_TAG | — | 0 Off, 1 On |
| 32 | (Format) | — | 0 No, 1 Yes |

"—" is key 0xc8 (200): handled outside the UI values. The size menus come per lens (9/10, 0/1): the
item list of the current lens is what the camera accepts. The time-lapse size (`0x22a`) is not
remote-settable; the camera derives it.

The plugin's `netset ID VALUE` selects the item with that value (index lookup in the camera's list,
so a value the camera does not offer now is refused), then `RefreshStatusLCD(true)`; `netvalues ID`
answers the values in the camera's order. The C200 adapter maps every protocol setting onto these
(the `available` lists of protocol §5.12 come from `netvalues`).

Tested on the hardware (2026-10-25): `netset 8 0` (video) switches the mode and redraws the status
display; video records straight from the live view through `process_movie_rec` (3840x1920 from
`0x109`), `process_movie_rec_stop` saves and returns to the live view; `netset 24 1` changes the
interval and redraws.

## Recording and shots: `CUINETFuncRemoteShot` (Bluetooth remote shot)

`Inst()`, then `process_movie_rec()`: time-lapse mode (`CUINETUtil::IsTimelapseMode`, `0x21c == 2`)
→ `UI_Manage_Start_Capture(2, 0)` (or `UI_Manage_Timer_Start(2)` with the self-timer), else
`UI_Manage_Movie_Rec(0)` when `is_record_possible()`. `process_movie_rec_stop()`: time-lapse →
`UI_Stop_Interval_Captue_360()`, else `UI_Manage_Movie_Stop(IsNoCard ? 6 : 2)`. `process_capture()`
for a still. `CUINETUtil::IsTimelapseCapturing()` = time-lapse mode and cap run state 23.

`is_capture_record_common_possible()` refuses with "Recording..." when `CUINETUtil::IsRecording()`;
seen after our own movie stop + standby (state left by our sequence, not by the camera's).

## What the camera's own menu does (dlog, 2026-10-25)

- Mode to time-lapse from the settings menu: `set_item_value(MODE, 2)` → exceptional handling
  (`__set_360_lens_mode`, `__set_mov_size`, `__set_360_time_laps`), `Set_User_Spec_All_Change`,
  live view stays running, menu → standby screen redraw. No movie standby: the time-lapse is an
  interval capture of stills (`UI_Manage_Start_Capture` with `eUI_CAP_START_TIME_LAPS`, lock3a
  captures each interval, then `Ui_Operate_Timelapse` records them through the camcorder).
- In the movie standby a time-lapse's first capture never completes and its stop
  (`WAIT_INTERVAL_CAPTURE_STOP_FLAG`) waits forever: the app hangs.
- The recorder realizes `0x109` (movie size, `eUS_MOVIE_SIZE`; `0x22a` for the time-lapse), not the
  menu's `0x223`; mode/lens changes reset `0x109` to the lens default.
- `UI_Manage_Movie_Standby` is a no-op while already in standby (flag at struct+0x118);
  `UI_Manage_Movie_Stop` leaves it synchronously when not recording.
- `ui_set_360_lens_mode` holds cap run state `0x33` (LV_DISP_CHANGE_FLAG) until the photo live
  view's display callback; in a video mode it never comes. The settings menu's lens switch
  (`CSLCDDrawModeMenuSetting::set_menu_value_switch_lens`: live view stop, value, exceptional,
  push, remaining, lens LED, live view start) does not use it.
