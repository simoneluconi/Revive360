# Gear 360 (SM-C200) – imaging control protocol (dfms/PIM) for the factory-mode controller
> **Context:** interoperability with the owner's own Samsung Gear 360 (2016/2017), a camera the manufacturer abandoned. All work is on the owner's device and firmware; no third-party systems, services, or accounts are involved.

Companion to `09_camera_hw_control.md` (networking, OLED, LEDs, keys, `st` surface, safety).
That doc deferred the exact imaging sequence and two open tests to this one. Scope here:
how the controller service captures stills/RAW, drives movie, and (the hard part) gets live-view
frames — plus answers to the two OLED/keys open tests. From `dfmsd`, `libfactory-mode.so`,
`di-camera-app` (APE4 source + AQK1 verified).

## Three control transports in factory mode

1. **dfmsd script commands** (`info.tg → <cmd script>`, handled inside `dfmsd`): `shell script
   <path>` → `system(path)` with uid 0 — our main entry. `snapshot make` here is only an LCD
   screenshot (`make_snapshot.sh`), not a photo.
2. **`st` message queue** — SysV key **`0x8828`** (perms 0666). `st app …` → `di-camera-app`
   shell thread → result in `/tmp/app_result`, posts `sem_open("/sem_app_res")`. **Serviced in
   factory mode** (probe 2: `st app help` returned; only the softAP command blocked, for lack
   of network).
3. **ATD / PIM imaging IPC** — `ftok("/tmp/atd_dfms") → msgget` for commands,
   `sem_open("sem_dfms_app_resp")` for sync, and a **fixed physical SDRAM window mmap'd from
   `/dev/mem`** (~`0x85C60000`, control `0x1000` + data `0xFF000` ≈ 1 MB) for frame/image data.
   `di-camera-app` registers PIM callbacks via `RegCallbackClient("pimode", 0, liveview_cb,
   capture_cb)` (`libfactory-mode.so`; impl in `libmmfmodules` / `libcapture-fw-prod` /
   `libmm-photo`; mediated by `/usr/bin/atd-server`). The default `dfms_pim_liveview_data_receiver`
   in `libfactory-mode` is a logging stub; the real receiver is the app's.

## Still capture (JPEG + RAW SRW) — LOW RISK

```
st app nx capture quality raw      # or sfine | fine | norm | raw+sfine | raw+fine | raw+norm
st app nx capture run count 1      # shoot
```
`cmd_set_photo_quality` accepts the raw variants; `cmd_capture_single` sends the shutter key
sequence (`cmd_key_pressed/timed/released`) into the normal engine. Output: a real `.JPG` /
`.SRW` to **DCIM on the SD**; the controller's file server ships it. A 360 RAW is two fisheye
SRW frames; stitch is ours (calibration in doc 04). **Hardware unknown:** that key-event
capture completes while the factory OSD is up (st reaches the handler; completion through the
factory UI state is unverified). In-memory alternative: PIM `capture_data_receiver` + `RawSave`
/ `GetCaptureData` in the `/dev/mem` window (more code; only if the card round-trip is too slow).

## Movie

`st app nx capture` / `mov` subcommands drive record start/stop via the same key-event engine;
files land in DCIM. `dfms_pim_movie_{set_pos,pause,resume,stop}` exist on the ATD path for
playback.

## Battery / storage / status — read directly, no app

Battery: `/sys/class/power_supply/*` (`sec-battery`). Storage: `statvfs` on
`/opt/storage/sdcard`. (The factory `st app 360 batt …` surface in doc 09 is fault injection,
not a reader.) Avoids the BT widget-info path (doc 01).

## Live view (RVF) — HIGH RISK, the key unknown

No simple `st` command (`st app show liveview` only toggles an OSD overlay). Real RVF frames
come via either `CUINETFuncDLNA` (BT-command-gated — the path we avoid) or the PIM
`liveview_data_receiver`: frames land in the `/dev/mem` physical window, signaled by the ATD
msg-queue + `sem_dfms_app_resp`. The PIM route is free of any in-process helper in principle but low-level
(physical-memory window, ISP/A7 pipeline coordination) and **unproven in script mode**.
**Recommendation:** capture-first v1; live view is a separate milestone gated on a hardware
probe that answers: does the PIM liveview callback fire with frame data in script mode, and
what is the pixel format / stride / resolution in that buffer?

### ATD / PIM internals (for the compiled-helper probe)

How the imaging IPC actually works (from `libfactory-mode.so`):

- **Command transport** (`dfms_send_to_app`): a command record of **0xAB4 bytes** (the
  `dfms_cmd_data_s`, same struct the dfms script parser fills) is `memcpy`'d into the control
  page `mmap`'d from `/dev/mem` at phys **0x85C60000** (len 0x1000). Then `msgsnd(q,
  {mtype=cmdid}, 4)` on the queue `msgget(ftok("/tmp/atd_dfms"))` signals the app, and the
  caller waits on `sem_open("sem_dfms_app_resp")` (`sem_timedwait`, 360 s).
- **Frame delivery**: the registered callback (`dfms_pim_capture_data_receiver` /
  `..._liveview_data_receiver`) receives a pointer to a large descriptor struct. Pixel buffers
  come from **`/dev/d5_sma`** (the DRIMe5 System Memory Allocator), not plain `/dev/mem`. The
  descriptor exposes per-plane/per-lens fields at offsets ~0x90, 0x94, 0x98, 0x9C, 0xE4, 0xE8,
  0x104, 0x118–0x124, 0x16C, 0x170, 0x18C, 0x214 (dimensions / strides / formats / phys addr /
  size — exact semantics still to be decoded).
- **Start**: register with `dfms_pim_register_callback_client(capture_cb, liveview_cb)` →
  `RegCallbackClient("pimode", 0, …)`, then `SetPIMode(1|2|3)` to put the camera into a
  picture-inspection streaming mode (1/2/3 correspond to MOT / Dust / Noise factory tests).
  Single-frame grab: `dfms_pim_do_aframe_capture`; RAW: `RawSave` / `GetRawSaveOnOff`.

**Exported symbols** (so a helper can call them instead of reimplementing ATD):
`SetPIMode`, `dfms_pim_register_callback_client`, `dfms_pim_do_aframe_capture`, `RawSave`,
`GetRawSaveOnOff`, `dfms_start_app`, `dfms_launch_daemon` are GLOBAL in `libfactory-mode.so`.
`RegCallbackClient`/`UnRegCallbackClient` are UND there (resolved from another lib, e.g.
`libgui.so`).

**Heavy caveat:** `libfactory-mode.so` NEEDs the full EFL/X stack (`libX11`, `libelementary`,
`libevas`, `libgui`, …). So a helper that links it is **not** a tiny standalone binary — it
pulls in the vendor graphics stack and must coexist with the running `di-camera-app` pipeline
(the app owns the sensor/ISP; our client would be a second `pimode` consumer). This is why
live view is the hard, uncertain milestone: the realistic compiled-helper probe is to link
`libfactory-mode.so` from the device sysroot, `RegCallbackClient("pimode") + SetPIMode(1)`,
and see whether a frame descriptor arrives and whether `/dev/d5_sma` yields pixels — rather
than reimplementing the ATD protocol by hand.

## Answers to the two open tests from doc 09

**(a) Does the factory StatusLCD drawer hold `/dev/ssd1309-ctrl` exclusively?**
RESOLVED by probe 4 (AQK1): **NOT exclusive.** A second root process opened `/dev/ssd1309-ctrl`
for both READ and WRITE ("second writer allowed"). So our own OLED UI is viable — we can draw
concurrently with the app's drawer (last-writer-wins; override at a higher redraw rate). A full
draw still needs the compiled `libmicom-node` helper (`dot_matrix_draw`); shell can only open it.
Context (static): `di-camera-app` keeps drawing via `UI_Status_Lcd_Start/Stop` (stop only on the
shutdown path — no runtime dfms/vconf stop), and `dfmsd` also writes the status LCD, so we are a
concurrent writer, not the only one. Note `/dev/micom-ctrl` does **not** exist on this build;
`/dev/fb0` does.

**(b) Does the factory UI consume `/dev/input` key events or leave them for us?**
RESOLVED by probe 4 (AQK1): **we DO read the buttons in parallel.** A read-only capture of
`/dev/input/event0` collected 128 events during the key window, decoding to EV_KEY press/release
for 3 buttons (codes 0x49, 0x7E, 0x7F). **But the app also consumes them** (the user saw the
factory UI react to presses). So for *exclusive* ownership our daemon must `EVIOCGRAB` the node —
an ioctl, i.e. compiled code, not shell. The read-only shell path double-handles and is
unpleasant in factory mode, so the exclusive-key test is folded into the compiled-helper phase.
Node map: `event0` = "Drime5 GPIO Keyboard" (the buttons; power short-tap likely via PMIC, not
this node); `event1` accel, `event2` **gyro**, `event3` sig-motion, `event4/5` step, `event6`
tilt (LSM6DS3).

## Bottom line

A capture-focused v1 — stills incl. RAW, movie, battery/storage, file transfer, LEDs, and (if
`EVIOCGRAB` works) keys — is achievable as a factory-mode controller service with `st` + our own
networking/server. Two things need a dedicated hardware probe before committing: **live-view
frame flow via PIM**, and **owning the OLED / grabbing the keys** against the still-running app.

## CORRECTION: RAW/SRW is present but UNPROVEN on the C200 (hardware)

The "Still capture (JPEG + RAW SRW) — LOW RISK" claim above was based on the quality tokens
existing in the binary; hardware testing (modified firmware, normal mode, AQK1) revised it:
- The RAW machinery is real in the C200 build — file types `eUI_FILE_TYPE_SRW`(4)/`PHOTO_SRW`(5),
  quality enum `eUS_IQ_RAW_ONLAY`(3)/`SFINE_RAW`(4)/`FINE_RAW`(5)/`NORMAL_RAW`(6), a raw-quality
  menu, and a "one-touch RAW" flag (UI 0x27f). Inherited from the shared NX/DRIMe5 codebase.
- BUT no `.SRW` has ever been produced on the C200: `st app nx capture quality raw` returns
  app_result=(none) and the following dfms `capture still` writes only a `.JPG`.
- Root cause in the code: **`dfms_app_cmd_capture` "still" does NOT read the quality menu 0xd0** —
  it captures at the camera's current quality (it only reads mode 0x7d9 / ramdisk 0x300 /
  cap-state 0x27b / cap-ready 0x29e). `cmd_set_photo_quality` only sets menu 0xd0; RAW needs 0xd0
  to actually hold a raw value (3-6) at shot time, which the st path did not achieve.
- To settle it: force 0xd0 to a raw value, confirm it stuck (read back), then dfms capture and
  look for `*.SRW`. If 0xd0 rejects raw, or SRW still doesn't appear with 0xd0=raw, RAW is not
  functional on the C200 (never a product feature; JPEG-only). v1 should advertise JPEG only.

## RESOLVED: RAW is reachable — direct RTOS USERDATA via the in-process shim

The CORRECTION above was about the *command surface* (dfms/st). The real lever is the DRIMe5 RTOS
USERDATA state, set directly from the in-process shim (LD_PRELOAD into `di-camera-app`). Pinned
from the RTOS name pointer-array (part05 @0x80797810, base 0x80000000); `SetUserDataMCB`'s id ==
the USERDATA enum value (cross-checked `USERDATA_IMAGEQUALITY`=4 vs `SetUserDataMCB(4,...)` in
`libcapture-fw-prod`):

| USERDATA | id | values |
|---|---|---|
| `USERDATA_RAW_SAVE`   | `0x7b` | 0=OFF, 1=ON — master "also dump Bayer", independent of the quality menu |
| `USERDATA_SSIF_RAW`   | `0x7c` | 0=OFF, 1=ON — sensor-interface raw readout |
| `USERDATA_RAWQUALITY` | `0x7a` | 0=LOSSLESS, 1=LOSSY |
| `USERDATA_RAW_BIT`    | `0x7d` | 0=14BIT, 1=12BIT |
| `USERDATA_RAW_PACK`   | `0x7e` | 0=PACK, 1=UNPACK |
| `USERDATA_IMAGEQUALITY` | `0x04` | 0=SFINE,1=FINE,2=NORM,3=RAW,4=RAW+SFINE,5=RAW+FINE,6=RAW+NORM,7=SRAW |

Mechanism: `mm_camera_set_usr_attributes(h,index,val)` does **not** take the USERDATA id (its 2nd
arg is a category 0..0x5a remapped internally, none mapping to RAW). Call the exported low-level
setters directly instead — all in `libcapture-fw-prod.so`, dlsym-able: `SetUserDataMCB(id,param)`
@0x4b19c, `SetVariableDataMCB(id,void*,size)` @0x4b660, `SetUserSpecDataMCB(item,void*)` @0x4b6f0.
Grab the camera handle by interposing `mm_camera_create(void** out)` @0x28ad4 (= `DAT_004d3500`).
`mm_camera_capture_still` @0x28e6c is fixed-arity (17 args, not variadic) — safe to forward.

Test (post to the ecore main loop, then a normal dfms `capture still`): `SetUserDataMCB(0x7b,1)`
+ `SetUserDataMCB(0x7c,1)` + `SetUserDataMCB(0x7d,0)` + `SetUserDataMCB(0x7e,0)` → expect
`/mnt/mmc/RAW_%04d_%s_%dx%d.raw` beside the JPEG. `RAW_SAVE` does not depend on the quality menu
(which the 360 ignores, using `IMAGESIZE` only), so it works regardless; if nothing appears, also
`SetUserDataMCB(4,3)` (RAW) or `(4,4..6)` (RAW+JPEG).

Safety: USERDATA 0x00–0x84 are RTOS RAM capture-config, re-init'd by `di-camera-app` every boot and
lost on power-off — **not** persisted to NAND/adj (calibration is the separate `CPTPAdjInterface` +
`/opt/share` path, never touched). A bad value self-clears on a power cycle. Safe to sweep.
