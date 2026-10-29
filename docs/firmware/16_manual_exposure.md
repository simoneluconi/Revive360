# Manual exposure and long exposures (C200, AQK1)

Measured on the camera on 2026-10-29 (shots 360_0394–0403, indoors, camera still). Nothing here is in
g360rd or the app, and a manual-exposure feature is not planned: this records what was found, should
it ever be wanted.

## How a 360 still is exposed

A dual-lens still is not an NX1-style mechanical-shutter capture: the sensor (XO3L6900 driver) runs
in its full-resolution video mode and one frame is read out. The RTOS log shows it:

```
[SVC_NX] [VideoSetSensorMode] Video STill Exp time = 20263     # µs: the photo's exposure
[SVC_NX] updateBurstGain : Burst Sens. Value = 0x60
[SENSOR_DRV_LOG] [SetVideoGain] d = 0, a = 1464                 # analog gain, 0 = base ISO
```

VideoSetSensorMode (`FUN_80223ac8`, part05) passes the front-end object's `+0x240` (µs) to the sensor
driver. The EXIF ExposureTime is the same value.

## Switching to manual (M)

All through the RTOS capture shell (`st cap …`, run as root on Linux; g360rd's
`/api/v1/debug/st` or a debug script):

```
st cap capdtm setusr SHOOTINGMODE MANUAL     # value names without their prefix
st cap capdtm setvar TV <tv> 4               # VARIABLE_TV (0x101), 4 bytes
st cap capdtm setusr SHOOTINGMODE PROGRAM    # back to automatic
```

`capdtm usrlist` / `varlist` / `getusr X` / `getvar X` read the current values. `setvar` is exactly
what Samsung's own `CAttributeHandler::setShutterSpeed` sends (`SetVariableDataMCB(0x101, v, 4)` in
libcapture-fw-prod.so), so the plugin could also send it in-process.

**TV encoding: APEX in sixteenths**, `tv = round(16 × log2(1 / seconds))`:

| tv | exposure measured |
|---|---|
| 0 | 1 000 000 µs (1 s) |
| 16 | 500 000 µs (1/2 s) |
| 90 | 20 263 µs (1/49 s) |
| 159 | 1 019 µs (1/1000 s) |

**Limit: about 1 s.** `capdtm` parses with strtol and refuses negative numbers (`Invalid argument`),
a too-large hex value saturates to 0x7fffffff, and that gave 1 044 273 µs: the longest the driver
allows in this mode (about 65535 lines × 16 µs, the frame-length register). Longer exposures would
need another sensor mode; "bulb" (`SHOOTINGMODE BULB`, `VARIABLE_BULBTIME`, `st cap capt bulb`) is
NX1 code for the mechanical shutter and was not tried.

**Hazard:** a tv the driver turns into 0 µs (tv = 0x300 = 768) hangs the capture: no file, g360rd's
`capture_timeout` after 30 s. A reboot (`POST /api/v1/power {"action":"reboot"}`) clears it. A
real setting must keep tv in 0 … ~210 (1 s … 1/8000 s).

## ISO in manual

- In M the camera uses **ISO 100 (gain 0) whatever the light**: ISO stays automatic in name, but in
  M it never rises (1/1000 s indoors came out almost black at ISO 100).
- `capdtm setusr ISO <n>` is **refused** by the RTOS (`UserData is not set -1179648`) with any
  spelling: no fixed ISO on this camera.
- `setusr ISOAUTOMAX ISO200` (the stock `iso_limit`) is accepted, but only matters in P.
- The AE debug commands (`st cap iq ae tv|sv|fix <hex>`, IQ_DebugIF_NX1_Ae: Tv/Sv in 1/256 APEX)
  are accepted but the still's AE overrides them, in P and in M.

## RAW

Every manual shot (1/1000 s … 1 s, ISO 100) saved both lens dumps: M at base ISO stays on
`CMaterial_NX1_Still_IPC_Main`. So M is the way to RAW in low light: up to 1 s at ISO 100, or several
1 s RAW frames stacked on the phone for more. (A P-mode shot at ISO 400 also kept its RAW, so the
High-ISO switch of docs/firmware/14 sits above ISO 400.)

## Leftovers to clean up after experiments

`capdtm` changes live in RTOS RAM: a power cycle (or the reboot above) restores everything. By
hand: `setvar TV 0 4`, `setusr ISOAUTOMAX ISO1600` (the stock value), `setusr SHOOTINGMODE PROGRAM`.
