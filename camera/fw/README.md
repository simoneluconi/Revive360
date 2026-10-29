# Modified firmware: build and flash (SM-C200, AQK1)

How to put our files into the stock firmware and get them running on the camera.
Background and reverse engineering: [docs/firmware/10_firmware_modification.md](../../docs/firmware/10_firmware_modification.md).

The modified image is the stock `C200GLU0AQK1_171121_1257_REV00_user.bin` with files **added** to
its rootfs (SLP part 6 → eMMC p9). Nothing stock is changed or removed, and every other SLP part is
copied unchanged, including the stock snapshot (SLP part 9 → p8): every flash writes the factory
snapshot back, so step 3 is needed after each one. Recovery is always possible: flash the stock `.bin` the same way (see Recovery).

## Contents

| Path | What |
|---|---|
| `overlay-controller/` | The real controller: `g360r.service` + `boot.sh`; the build adds `g360rd`, `launcher.sh`, `netup.sh`. Plus the shim: `di-camera-app.service.d/g360r-shim.conf` sets `LD_PRELOAD=/usr/lib/libg360r_shim.so` (built with `make shim`) for the stock camera app |
| `p8_invalidate.sh` | One-shot SD hook that runs once after a flash: `mkswap /dev/mmcblk0p8` (see step 3) |
| `../tools/fw_build.sh` | Adds an overlay to the stock rootfs and rebuilds the SLP image |
| `../tools/slp_pack.py` | SLP container: `info`, `roundtrip`, `extract`, `replace` (CRC = `zlib.crc32 ^ 0xFFFFFFFF`) |
| `../docker/fwtools.Dockerfile` | Build image `g360r-fwtools:1` (lzop, e2fsprogs, python3) |

Overlay format: the directory mirrors the rootfs (`overlay/usr/bin/x` → `/usr/bin/x`); `MODES` lists
`<mode> <path>` (default 0644), `LINKS` lists `<target> <link path>`. `fw_build.sh` refuses to
overwrite a stock path and reads every added file back from the image to compare it with the source.

## 1. Build (PC, Git Bash, from the repo root)

With the stock image in `firmware/stock/` (see [firmware/README.md](../../firmware/README.md)), one
command does all of the below and leaves `firmware/out/smc200.bin`:

```sh
sh camera/tools/make_firmware.sh
```

The steps it runs, by hand:

```sh
# once
MSYS_NO_PATHCONV=1 docker build -t g360r-fwtools:1 -f camera/docker/fwtools.Dockerfile camera/docker

# controller payload (out/sd/g360r/...) - see camera/README.md
cd camera && ./build.sh && cd ..      # or build.ps1

# firmware: stock .bin in firmware/stock/ (the repository is mounted at /w)
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/w" -w /w g360r-fwtools:1 sh camera/tools/fw_build.sh \
    firmware/stock/C200GLU0AQK1_171121_1257_REV00_user.bin <overlay dir> firmware/out/smc200.bin /tmp/fwbuild
```

For `overlay-controller` first stage it with `tools/fw_stage_controller.sh` (it copies the built
`g360rd`, `launcher.sh`, `netup.sh` next to `boot.sh`) and pass the staged directory.

Sanity check: with an empty overlay the rebuilt rootfs is byte-identical to stock
(`cmp /out/work/rootfs.img /out/work/rootfs.stock.img`). The rootfs is recompressed with
`lzop -1` from stdin: method 2 / level 1, the same as the stock blob (the camera's lzop 1.03 must
decompress it while flashing).

## 2. Flash (camera)

1. SD card root: `smc200.bin` (the built image) + an **empty** `fwupgrade.txt`. **No** `info.tg`.
   Check the copy with a SHA-256 against the build output.
2. Battery **charged to near full**. The firmware wants internal level ≥ 3 on a 0–5 scale, and the
   battery icon does not map 1:1 to it (half or low can fail the check). **USB cable unplugged**: with
   USB at boot the camera goes to the USB/MTP path and shows no prompt.
3. With the camera off: press and hold **Shutter** (the red OK button), then press the power button
   (**Back**) to switch on. Menu is not needed (confirmed on the hardware, second flash). The update
   starts by itself, there is no prompt to confirm; do **not** press Back again (it cancels).
4. "Check for updates" with a progress bar → "Updated" → the camera
   restarts by itself. The **3 red LEDs** during the update are the normal progress indicator, not an
   error. Keep the SD card in until the restart is over: the flasher reads `smc200.bin` from it.

There is no version check: AQK1 → modified AQK1 is accepted.

## 3. Invalidate the snapshot (one-shot SD hook)

After the flash the camera still **resumes the stock hibernation snapshot** from p8 on every boot
(the bootloader does it, kernel `noresume`), so our rootfs is not used yet. The snapshot also holds a
stale page cache: anything read from p9 while resumed comes from the old cache, files too
(`/etc/info.ini`, `/fw/log` look stock). Before reading: `echo 3 > /proc/sys/vm/drop_caches`, and
`blockdev --flushbufs /dev/mmcblk0p9`. Zeroing the first 512 bytes of p8 would **not** invalidate it:
the swsusp signature is at the end of page 0, hence `mkswap`.

1. Power off, SD to the PC: rename `smc200.bin` → `smc200.bin.flashed`, `fwupgrade.txt` →
   `fwupgrade.txt.done`.
2. Copy `p8_invalidate.sh` to `<card>/g360r/p8_hook/` and create at the card root (LF line endings,
   UTF-8 without BOM):
   - `info.tg`: `cmd.tg`
   - `cmd.tg`: `shell script /sdcard/g360r/p8_hook/p8_invalidate.sh`
3. Power on normally. The factory hook runs the script: **1 beep** = start, **2 beeps** = done
   (`mkswap /dev/mmcblk0p8`), **3 beeps** = error. It logs to the card and disarms itself
   (`info.tg` → `info.tg.done`). It also logs `ls /usr/share/g360r/` after dropping the caches
   (`g360r/log/p8_invalidate.log`): a quick look that the flash wrote our files. A raw `grep` of p9 has
   proved unreliable even after dropping caches. The snapshot is invalidated either way: a rootfs the
   flash did not write just cold-boots stock.
4. Power off and on again: a cold boot from our rootfs. It is slower than usual (~30 s, no instant-on).

**What a cold boot looks like** (every power-on from now on, so tell users): press power and
**nothing happens for a while**: no screen, no LED, no sound, for long enough that the camera
**looks dead or broken. It is not: just wait** (about 30 s), and don't press anything. Then the stock start-up (the charge LED blinks blue twice, the two
LEDs by the lenses turn red) and the camera comes on; a few seconds later, if the card holds
`g360r/g360r.conf`, our **LED signature** (around the camera twice: charge LED blue, one lens LED,
the other; then two flashes) and **one beep** when the Wi-Fi is ready (three beeps = error). This
is normal: without the snapshot the camera loads the kernel and the system from scratch. Don't
press power again while you wait, or you may switch it off again.

From now on every power-on is a cold boot with our files. Instant-on (a new snapshot with
`/etc/snapshot`) is deferred.

## 4. Run the controller

`overlay-controller` starts `boot.sh` on every boot (systemd, after `di-camera-app`). It runs only when
the card holds `g360r/g360r.conf` (the Wi-Fi passphrase, written by `tools/prepare_sd.ps1 -Normal`)
and has no `/sdcard/g360r.off`. A newer `g360r/bin/g360rd`, `g360r/launcher.sh` or `g360r/netup.sh`
on the card wins over the copy in the firmware: controller updates need no reflash.
The card for normal mode must **not** have `info.tg` (that is the factory-mode hook).

Testing: `sdb.conf` at the card root disables the stock idle auto power-off.

## SM-R210 (Gear 360 2017)

Same procedure, driven by `./install.sh r210 build|flash|activate|stock|status`. It is built
from the stock `R210GLU0ARB2_180201_1739_REV00_user.bin` (the only supported R210 build) and
written as `firmware/out/smr210.bin`. Background: [docs/firmware/15_r210_port.md](../../docs/firmware/15_r210_port.md).

- **Shim without interposers**: `make_firmware.sh` stages the R210 overlay with `shim-r210`
  (`make shim-r210`: the same `g360r_shim.c` built with `G360R_SHIM_NO_INTERPOSE`). It keeps the
  socket, the main-loop requests and the plugin loader, but wraps none of the camera app's
  functions. Everything model-specific goes in the plugin on the card. (The first R210 image,
  2026-10-29, had no shim at all: `noshim`.)
- **Flash prepared over Wi-Fi** (no card swap): the image goes to the card with
  `debug/upload` (`UPLOAD_MAX` 400 MB, streamed) and a `debug/script` moves it to the root, writes
  `fwupgrade.txt` and arms the activation hook with `g360r/p8_hook/expect_version` (the new image's
  `/usr/share/g360r/VERSION`). With that file `p8_invalidate.sh` waits on boots before the flash.
  After the flash it invalidates the factory snapshot, or only disarms if the new firmware already
  cold-boots, and removes the image and `fwupgrade.txt`. On the R210, `finishPowerOn` takes the update
  branch before the factory-hook check, so `info.tg` on the card does not get in the flash's way.
- **Card name.** The R210 flashes an image under Samsung's own name (`R210GL…_user.bin`) before
  `smr210.bin`. With such a file on the card the update installs that one instead, so
  `install.sh` refuses to prepare a card that has one.
- **Fewer card swaps.** On the R210 the flash card already gets `g360r/` (controller, Bluetooth
  side, settings with a Wi-Fi passphrase made on the PC and printed for manual pairing). After the
  activation the card stays in: there is no `sd` step.
- **Sounds.** The R210 has `confirm.wav`/`error.wav` instead of `04_N_OK`/`05_N_Cancel`, and
  `paplay` stays silent until the rt5659 `Ext Spk L/R` switches are on. `p8_invalidate.sh` and
  `launcher.sh` pick the sound that exists and switch the speaker on first.
- The C200 LED signature is skipped on the R210, whose 7 LEDs are not mapped yet.

## Recovery

Flash the stock `C200GLU0AQK1_171121_1257_REV00_user.bin` as in step 2, with the same conditions
(near-full battery, USB unplugged, hold Shutter/OK and press power, `fwupgrade.txt`). The board version matches, so it
writes back the stock rootfs **and** the snapshot, and instant-on returns.

Why this always works: the overlay only adds files and leaves `di-camera-app` stock, so the camera
boots its normal UI, and the SD update path with it, even if `boot.sh` fails. We only change the rootfs
(p9): bootloader (boot0), kernel and the recovery kernel (rImage, p12) are never touched. Without a
card config `boot.sh` exits at once, so the modified firmware behaves like stock.
