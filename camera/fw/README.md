# Modified firmware: build and flash (SM-C200, AQK1)

How to put our files into the stock firmware and get them running on the camera.
Background and reverse engineering: [docs/firmware/10_firmware_modification.md](../../docs/firmware/10_firmware_modification.md).

The modified image is the stock `C200GLU0AQK1_171121_1257_REV00_user.bin` with files **added** to
its rootfs (SLP part 6 → eMMC p9). Nothing stock is changed or removed, and every other partition is
copied unchanged. Recovery is always possible: flash the stock `.bin` the same way.

## Contents

| Path | What |
|---|---|
| `overlay-controller/` | The real controller: `g360r.service` + `boot.sh`; the build adds `g360rd`, `launcher.sh`, `netup.sh` |
| `overlay-canary/` | First test overlay: a unit that only logs a heartbeat to the SD (`/sdcard/g360r.on`) |
| `p8_invalidate.sh` | One-shot SD hook that runs once after a flash: `mkswap /dev/mmcblk0p8` (see step 3) |
| `flash_diag.sh` | Read-only SD hook: searches every eMMC partition for a marker, logs the partition table |
| `../tools/fw_build.sh` | Adds an overlay to the stock rootfs and rebuilds the SLP image |
| `../tools/slp_pack.py` | SLP container: `info`, `roundtrip`, `extract`, `replace` (CRC = `zlib.crc32 ^ 0xFFFFFFFF`) |
| `../docker/fwtools.Dockerfile` | Build image `g360r-fwtools:1` (lzop, e2fsprogs, python3) |

Overlay format: the directory mirrors the rootfs (`overlay/usr/bin/x` → `/usr/bin/x`); `MODES` lists
`<mode> <path>` (default 0644), `LINKS` lists `<target> <link path>`. `fw_build.sh` refuses to
overwrite a stock path and reads every added file back from the image to compare it with the source.

## 1. Build (PC, Git Bash, from the repo root)

```sh
# once
MSYS_NO_PATHCONV=1 docker build -t g360r-fwtools:1 -f camera/docker/fwtools.Dockerfile camera/docker

# controller payload (out/sd/g360r/...) - see camera/README.md
cd camera && ./build.sh && cd ..      # or build.ps1

# firmware: stock .bin in <stock firmware dir>, output in a scratch dir mounted at /out
OUT=<scratch dir>
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/w" -v "<stock firmware dir>:/fw:ro" -v "$OUT:/out" \
    g360r-fwtools:1 sh camera/tools/fw_build.sh \
    /fw/C200GLU0AQK1_171121_1257_REV00_user.bin <overlay dir> /out/smc200.bin /out/work
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
2. Battery at 3 bars or more out of 5. **USB cable unplugged**: with USB at boot the camera goes to the
   USB/MTP path and shows no prompt.
3. Power on while holding **Shutter + Menu** (the Bluetooth/connection button). The power button is
   Back: do **not** press it while the prompt is on screen (it cancels).
4. "FW update" appears → press OK → "Check for updates" with a progress bar (3 red LEDs) →
   "Updated" → the camera restarts by itself.

There is no version check: AQK1 → modified AQK1 is accepted.

## 3. Invalidate the snapshot (one-shot SD hook)

After the flash the camera still **resumes the stock hibernation snapshot** from p8 on every boot
(the bootloader does it, kernel `noresume`), so our rootfs is not used yet. The snapshot also holds a
stale page cache: anything read from p9 while resumed comes from the old cache (use
`echo 3 > /proc/sys/vm/drop_caches` before reading).

1. Power off, SD to the PC: rename `smc200.bin` → `smc200.bin.flashed`, `fwupgrade.txt` →
   `fwupgrade.txt.done`.
2. Copy `p8_invalidate.sh` to `<card>/g360r/p8_hook/` and create at the card root (LF line endings,
   UTF-8 without BOM):
   - `info.tg`: `cmd.tg`
   - `cmd.tg`: `shell script /sdcard/g360r/p8_hook/p8_invalidate.sh`
3. Power on normally. The factory hook runs the script: **1 beep** = start, **2 beeps** = done
   (`mkswap /dev/mmcblk0p8`), **3 beeps** = error. It logs to the card and disarms itself
   (`info.tg` → `info.tg.done`).
4. Power off and on again: a cold boot from our rootfs. It is slower than usual (~30 s, no instant-on).

From now on every power-on is a cold boot with our files. Instant-on (a new snapshot with
`/etc/snapshot`) is deferred.

## 4. Run the controller

`overlay-controller` starts `boot.sh` on every boot (systemd, after `di-camera-app`). It runs only when
the card holds `g360r/g360r.conf` (the Wi-Fi passphrase, written by `tools/prepare_sd.ps1 -Normal`)
and has no `/sdcard/g360r.off`. A newer `g360r/bin/g360rd`, `g360r/launcher.sh` or `g360r/netup.sh`
on the card wins over the copy in the firmware: controller updates need no reflash.
The card for normal mode must **not** have `info.tg` (that is the factory-mode hook).

Testing: `sdb.conf` at the card root disables the stock idle auto power-off.

## Recovery

Flash the stock `C200GLU0AQK1_171121_1257_REV00_user.bin` as in step 2. It writes back the stock
rootfs and snapshot, and instant-on returns. Without a card config, `boot.sh` exits at once, so the
modified firmware behaves like stock.
