# 14 — RAW capture: the RTOS SSIF dump (AQK1, normal mode)

The C200 can save each lens's sensor data, in normal mode, with no factory mode: the A7 RTOS has
a debug dump of the SSIF frame (the sensor interface output, still Bayer) for the dual-lens still
pipeline. Proven on the camera on 2026-10-26 (modified firmware, AQK1): both lenses, from a shot
taken with the camera's own shutter button after a cold boot, and a DNG that develops like the
camera's JPEG.

This supersedes the RAW sections of [08](08_control_protocol.md) (`.SRW` through the quality menu,
and the `USERDATA_RAW_SAVE` route, both of which produced nothing) and the "factory-locked" verdict
that only looked at RTOS strings. Addresses below are AQK1 (`C200GLU0AQK1`).

The Gear 360 2017 (SM-R210) has the same dump, made to work with a runtime patch of its RTOS, and
saves **10-bit** data instead of 12: [15_r210_port.md, RAW](15_r210_port.md#5-raw), with a table
of the differences.

## Architecture

The DRIMe5 has three cores: M4 device init, **A7 T-Kernel** (the ISP/capture RTOS, firmware part05,
raw ARM at base 0x80000000), and the A9 running Linux (`mem=382M`). The A7 image is reloaded from
eMMC at every boot, so anything set in its RAM is lost at power-off. Linux reaches the RTOS RAM
through `/dev/mem` (`mmap`, `O_SYNC`; plain `read()` is refused), addresses 1:1.

## Switching the dump on

```
st cap back unitest ssifdump on      # "Back SSIFDump Flag On!!"
st cap back unitest ssifdump off
```

- Linux `/usr/bin/st cap ...` (libshell-command) sends the line to the RTOS over MCB message 0xb6.
- RTOS: `CBackendDebugProcess` ("st cap back [command]") → `unitest` →
  `CBackend_uniTest::uniTest` @0x80200414 on the singleton from FUN_80200330 (object 0x8160f788);
  `ssifdump on/off` sets byte **+0x19 = phys 0x8160f7a1** to 1/0.
- The `back` command is registered by `CCaptureFrameworkFactoryNX1::startBackEnd` at every boot,
  with no factory gate; the same function clears the flag. g360rd reads the byte through
  `/dev/mem` to see when it has been cleared.

## Where the dump happens

`CMaterial_NX1_Still_IPC_Main::execute` (0x80284fac) first calls the dump FUN_80284b88, then always
carries on with the normal processing (the JPEG is still made). The dump runs when:

- the flag is set;
- `OrderSheet+0x225 == 0` (USERDATA_LENS_MODE 0x81 = dual 360);
- the IPC input image has `+0x4c == 1`, set by the 360 still recipe FUN_802ae1e0 (selected by
  FUN_802ac484: dual lens, not time-lapse, OSCMODE != 1). That recipe holds the SSIF buffer for
  the dump: it only releases it early when ssifdump is off, so Samsung built the dump for it.

**Limits:** dual-lens photos only (single lens and time-lapse use other recipes). Low light gave
no dump: the camera then processes the still with `CMaterial_NX1_Still_IPC_Main_HighISO`, which
has no dump call (seen at 1/9 s and high gain). Solved since 2026-10-31 by a runtime patch of the
check that picks it: [Low light](#low-light).

The dump takes image key 0x15000 ("SSIF Out Image") and writes two files, the left and right
halves of the frame:

```
/mnt/mmc/ssif_front_%dx%dx%d_%d_10bit(16bit).raw     rowbytes/2 x width/2 x rows, counter
/mnt/mmc/ssif_rear_%dx%dx%d_%d_10bit(16bit).raw
```

e.g. `ssif_front_7776x3888x3950_0_10bit(16bit).raw`, 30,715,200 bytes. The counter (_DAT_816f2404)
is RTOS RAM: it restarts at 0 at every boot. The rows are written one MCB file request each, so a
dump takes a few seconds and finishes after the JPEG.

## RTOS file I/O, and why the rear file was lost

The RTOS has no file system: `FILE_OPEN` (0xc), `FILE_WRITE` (0xe), `FILE_CLOSE` (0x11),
`FILE_ACCESS` (0x13) go to Linux, where `CNotifyHandler::onFileIORequest` → `RunAdjustFunction`
(libcapture-fw-prod.so, inside **di-camera-app**) maps the name and the data from the RTOS RAM
through `/dev/mem` and calls plain `fopen`/`fwrite`. `/mnt/mmc` is the card
(`/opt/storage/sdcard`).

The RTOS side (FUN_8004e9dc) cleans its data cache only for `strlen(name)` bytes, not the
terminating NUL. Both names are printed into the same stack buffer; the rear name is one byte
shorter than the front one, so Linux read the rear name with the front's last byte still behind
it: `ssif_rear_..._10bit(16bit).raww`. The rear dump was written, under that name. (The
`FILE_OPEN (fd=0x0)` in the Linux log right after it is a different file: the
`active_align_check*.chk` calibration files, absent on a normal camera.)

**Fix — plugin `rawfix`** ([g360r_plugin.c](../../camera/shim/g360r_plugin.c)): libcapture imports
`fopen` through one `R_ARM_JUMP_SLOT` (offset 0xba910, a writable segment with no RELRO). `rawfix`
finds it with `dl_iterate_phdr` and points it at a wrapper that cuts every `/mnt/mmc/` name after
`.raw` and logs the name it got (`rawfix: cut "...raww" -> open` in `/tmp/g360r/shim.log`). The
patch lives in the app's memory: it is applied again after every boot.

## The data

- 16-bit little-endian words; **12-bit** values shifted left by 4 (the low 4 bits are always 0;
  the "10bit" in the name is wrong).
- 3950 rows of 3888 pixels per lens: rows 0 to ~19 are optical black (~128 in 12 bit), rows 20–39 a
  brighter band that is not black, the picture is the **3888×3888 square from row 40**.
- **CFA RGGB**, black level 128, white 4095. Same orientation as the matching half of the JPEG.

## DNG, and the colour matrix

[tools/raw/](../../tools/raw/): `ssif2dng.py OUT.dng FRONT.raw [REAR.raw] [PHOTO.JPG]` writes an
uncompressed 12-bit DNG (both lenses side by side like the JPEG, per-site black level from the black
rows, AsShotNeutral as below). Checked with libraw (rawpy). `rawcheck.py` inspects a dump (bit depth,
Bayer or not, CFA guess, preview). Its "not Bayer" verdict uses a 0.02 margin heuristic and can
fire wrongly on a scene with little colour.

`ColorMatrix1` (XYZ → camera, CalibrationIlluminant D65) was fitted with `ccmfit.py` against the
camera's own JPEG of the same shot:

```
[[ 1.5444, -0.6246, -0.1031],
 [ 0.1190,  0.7907,  0.0884],
 [ 0.2728, -0.0814,  0.2876]]
```

The fit compares chromaticities only (the JPEG tone curve and the lens vignetting change brightness
per pixel), near each lens centre. It solves for white balance gains plus a 3×3 CCM whose rows sum
to 1, with a small ridge towards the identity. Mostly grey scenes leave the matrix
under-determined (it changes a lot with the ridge weight); a colourful scene (360_0382) settles
it. The camera neutral under the room's LED light is about [0.73, 1, 0.49]. The DNG comes out a
little more saturated than the JPEG. Only one illuminant so far: a colourful daylight shot could
add `ColorMatrix2`.

### White balance (AsShotNeutral)

The first DNGs used grey world (the picture's mean colour taken as grey). Outdoors that fails: on
nine daylight shots (360_0385–0393, 2026-10-29) sky and grass pushed it to B/G 0.65–0.78, where the
camera's own white is 0.58–0.64 in raw terms, so the DNGs came out yellowish with a dull sky. The
colour matrix was fine: with the camera's white the developed DNG matches the JPEG's colours.

So the DNG takes the camera's white from the photo's JPEG: on a 320×160 grid (the size of the EXIF
thumbnail), the cells the JPEG shows as neutral grey (linear max 0.15–0.85, (max − min)/max < 0.06)
whose raw pixels did not clip (< 4000); AsShotNeutral is the median raw R/G and B/G over them.
Hundreds to thousands of cells qualify in an ordinary scene; under 16 falls back to grey world. The
app uses the camera's thumbnail (`/api/v1/thumb`, a few KB), the script the full JPEG scaled down:
they agree within about 0.03.

## In g360rd (setting `raw`)

[raw.c](../../camera/src/raw.c), shared with the R210, which only adds its RTOS patch
([adapter_r210.c](../../camera/src/adapter_r210.c)); offered on AQK1 and ARB2 only:

- `raw` `on`/`off` in `/api/v1/settings`, kept on the card as `g360r/raw_save` (it survives a
  power-off). `on` sends `rawfix` and switches the flag on; `off` switches it off.
- A thread every 2 s: reads the flag through `/dev/mem` and switches it on again when it was
  cleared. Every 30 s it sends `rawfix` again, in case the camera app restarted. Every still
  taken through the API does the same first, because the recipe reads the flag when it is built.
- Complete dumps (size = rowbytes × rows, untouched for 2 s) move from the card root to
  `DCIM/RAW/`, named after the shot's JPEG: `360_0383.JPG` → `360_0383_front_7776x3888x3950.raw`
  and `360_0383_rear_…`. The RTOS writes both dumps before the JPEG (front done at :47, rear
  :52, JPEG :53), so the shot's JPEG is the oldest one not older than the dump (within 60 s).
  A dump whose JPEG never comes keeps the RTOS name without the parentheses. A name already
  there gets `-N`.

## In the app

The gallery takes each photo's pair out of the list and marks the photo "RAW" (`RawPairs`); deleting
the photo deletes its pair. The photo's page saves the pair as one DNG in the phone's
`Download/Revive360/` (out of the photo gallery, so a shot is not shown twice): both dumps to the
cache (a broken transfer resumes), then `DngWriter` (the Kotlin port of `ssif2dng.py`, both cameras) streams the DNG
straight into shared storage, with the white balance from the photo's thumbnail. Its output decodes identically to the script's (libraw: same raw
data, black, white balance, developed picture). The dumps stay on the camera.

Many at once: the gallery's RAW button saves the DNG of every photo not saved yet (or of the chosen
ones in a selection), one after the other, with an overall progress card and a stop. A failed one
does not stop the rest; the camera link stays while it runs. The mark turns green once a photo's DNG
is on the phone.

## Open

- Manual exposure (M, ISO 100, up to ~1 s) keeps the RAW too: [16_manual_exposure.md](16_manual_exposure.md).

## Low light

The same on both cameras (C200 addresses, R210 in brackets). The pipeline page builder
`FUN_802bd530` (`FUN_802bbec4`) takes `IPC_Main_HighISO` (0x20400) instead of `IPC_Main` (0x20100)
when the check `FUN_8013e2d8` (`FUN_80133ce0`) answers 1:

```c
if (OS[0x53] == 0 || OS[0x7c] < 0x73) return 0;   /* +0x53: USERDATA_ISONR not off; +0x7c: the gain */
return 1;
```

0x73 is probably about ISO 450, which fits ISO 400 keeping the RAW. The same check is asked by the
order sheet (`FUN_80135bc0`: still type 10), every still recipe and the high-ISO buffer handling,
all during a still. The SSIF material is built in high-ISO shots too.

Three ways were weighed: `st cap capdtm setusr ISONR OFF` (a camera setting changed while RAW is
on, maybe for bright shots too, maybe reset by the app), `iso_limit` 400 (longer shutter, blur),
or the check itself. The check is rewritten in place (19 words of `-O0` code, 15 of them rewritten,
the rest no longer reached), with the dump flag as a third condition:

```
ldrb r3, [r0, #0x53] ; cmp r3, #0 ; beq ret0      noise reduction off: normal
ldr  r3, [r0, #0x7c] ; cmp r3, #0x72 ; ble ret0   low gain: normal
movw/movt r3, flag   ; ldrb r3, [r3] ; cmp r3, #0 ; bne ret0   ssifdump on: normal
mov r0, #1 ; bx lr                                high ISO
ret0: mov r0, #0 ; bx lr
```

With the flag off it answers as before, so a camera with `raw` off is Samsung's. With `raw` on, a
dark still takes the normal pipeline: the dump runs, and the JPEG has the normal pipeline's noise
reduction instead of the high-ISO one. Every caller asks the same function, so they agree. `g360rd`
writes it at start-up with the other words ([raw.c](../../camera/src/raw.c); the tables in
`adapter_c200.c`, `adapter_r210.c`, checked with a disassembler against both RTOS images; every
original word is checked on the camera before any is written). The A7 reads the flag from its own RAM, so a change applies to the next still.

Hardware, 2026-10-31, a dark room, cold boot, then two shots each:

| | `raw` on | `raw` off |
|---|---|---|
| R210 | 360_0252: ISO 1250, 1/20 s, both dumps, 9.4 s | 360_0253: ISO 1600, no dump, 2.4 s |
| C200 | 360_0405: ISO 1000, 1/15 s, both dumps, 15.2 s | 360_0406: ISO 1000, no dump, 5.2 s |

The `raw` on JPEGs are a little blotchier than the high-ISO ones, not much; the DNGs have the grain
of their ISO, for a raw editor's noise reduction. In the dark the white balance taken from the JPEG
leans a little to purple on the R210.
