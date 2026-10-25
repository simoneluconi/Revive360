# Installing Revive360 on your camera

This guide puts the Revive360 firmware on a camera, prepares the SD card and connects the
app, step by step. It follows the developer references [camera/fw/README.md](../camera/fw/README.md)
(firmware build and flash) and [camera/README.md](../camera/README.md) (controller and SD card);
if this guide and those files ever disagree, those files win.

Steps marked **TODO: verify** are not confirmed yet. Do not guess there: ask in the project's
issue tracker first.

## Safety, read before you start

- **This is unofficial and experimental. You modify your camera at your own risk.** It is not
  affiliated with or supported by Samsung.
- Use it **only on a supported camera with the exact firmware version listed below**. The
  modified firmware is built from that version's stock file. Never flash an image built for a
  different model or firmware version.
- The modification only **adds** files to the camera's system partition. The bootloader, the kernel
  and the recovery kernel are never touched and Samsung's camera app is unchanged, so the camera's
  own SD-card update keeps working even if our part fails. You can always
  [go back to stock](#8-go-back-to-stock-recovery).
- Flash only with the battery **charged to near full** and the **USB cable unplugged**.
- **Never power-cycle the camera over and over with the card in to "fix" a hang.** Hold the power
  button until the camera turns off, **remove the card**, and only then power it on again. Without
  the card the camera runs stock.
- **Do not use factory mode.** Step 5 below uses the camera's factory SD hook once, for a single
  automatic step. If the camera shows a factory **"self test"** screen, **do not press any camera
  button**: factory menus can reach format/reset functions. Our normal SD card never contains the
  factory trigger file `info.tg`.
- After the firmware is installed, every power-on is a **cold boot**: for about 30 s **nothing
  happens** (no screen, no LED, no sound). The camera looks dead; it is not. Wait, and do not press
  power again, or you may switch it off again.
- Do not leave the camera running unattended while you test.

## 1. What you need

**A supported camera:**

| Camera | Firmware | |
|---|---|---|
| Samsung Gear 360 (2016), SM-C200 | `C200GLU0AQK1` | supported |

Other models and firmware versions are not supported yet (see the
[README](../README.md#supported-cameras)).

How to check the firmware version on the camera: **TODO: verify** (where the camera or the stock
app shows `C200GLU0AQK1`).

**The stock firmware file** for that version: `C200GLU0AQK1_171121_1257_REV00_user.bin`. It is
Samsung's software, so this project **cannot distribute it, nor any firmware built from it**: you
need your own copy, and you build the modified image yourself (step 3). Put it in
`firmware/stock/` in your clone of this repository (git ignores it); the expected file names and
checksums are in [firmware/README.md](../firmware/README.md). Where to obtain the stock file:
**TODO: verify**.

**On the camera side:**

- the battery charged to near full (see step 4);
- a microSD card formatted FAT or exFAT, and a card reader for your PC. The card keeps the
  camera's photos (`DCIM/`) as they are; our files go in a `g360r/` folder.

**On the PC** (Windows; the scripts also run from a Linux shell):

- [Docker Desktop](https://www.docker.com/products/docker-desktop/), running;
- Git for Windows (Git Bash), and a clone of this repository;
- PowerShell (built into Windows);
- for the app: Android Studio (see [Build](../README.md#build)).

**A phone** with Android 10 or later.

## 2. Build the controller, the shim and the plugin

From the repository root, with Docker running:

```powershell
powershell -ExecutionPolicy Bypass -File camera\build.ps1 -Smoke
```

This builds the camera controller `g360rd` in `camera/out/` and runs the smoke tests on a
simulated camera; they must all pass. The first run builds the Docker toolchain image and takes a
few minutes.

Then, in Git Bash:

```sh
sh camera/build.sh shim      # camera/out/libg360r_shim.so   (goes into the firmware)
sh camera/build.sh plugin    # camera/out/libg360r_plugin.so (goes onto the SD card, step 6)
```

The shim and the plugin are linked against the camera's own system libraries, which must first be
taken from your stock firmware into `camera/.toolchain/aqk1/sysroot` (see `camera/Makefile`). How
to extract that sysroot: **TODO: verify** (not documented yet).

## 3. Build the modified firmware

Put the stock image in `firmware/stock/` (git ignores that folder; see
[firmware/README.md](../firmware/README.md) for the files and their checksums). Then, in Git Bash
from the repository root:

```sh
sh camera/tools/make_firmware.sh
```

It checks that the stock image is the supported one (by its SHA-256), builds the controller and
the shim, stages the overlay and rebuilds the image. `fw_build.sh`, which it runs, refuses to
overwrite any stock file and reads every added file back from the new image to compare it with
the source. The result is `firmware/out/smc200.bin`.

The individual steps, the overlay format and the sanity check (an empty overlay rebuilds a
byte-identical rootfs) are in
[camera/fw/README.md, section 1](../camera/fw/README.md#1-build-pc-git-bash-from-the-repo-root).

## 4. Flash the camera

1. **Prepare the card.** At the root of the SD card put only:
   - `smc200.bin` (from step 3);
   - an **empty** file named `fwupgrade.txt`.

   There must be **no** `info.tg` at the root. Check that the copy is intact: its SHA-256 must
   match the build output (in PowerShell: `Get-FileHash E:\smc200.bin`, and the same for the file
   in `firmware/out/`).
2. **Charge the battery to near full.** The camera checks the battery before updating, and the
   battery icon is not precise: half or low can fail the check.
3. **Unplug the USB cable.** With USB connected at power-on the camera goes into USB mode and shows
   no update prompt.
4. Insert the card. With the camera **off**, press and hold **Shutter** (the red OK button), then
   press the **power** button to switch it on.
5. **"FW update"** appears. Press **OK**. From now on do **not** press the power/Back button: it
   cancels.
6. The camera shows **"Check for updates"** with a progress bar, then **"Updated"**, and restarts
   by itself. The **3 red LEDs** during the update are the normal progress indicator, not an error.
   **Keep the card in** until the restart is over: the camera reads `smc200.bin` from it.

The camera accepts the modified image over the same stock version (there is no version check).

## 5. Activate the new firmware (once after every flash)

After the flash the camera still wakes up from Samsung's saved start-up image (an instant-on
snapshot), so it does not use the new firmware yet. A one-shot script on the card fixes this.

1. Power off, put the card in the PC, and rename on the card:
   - `smc200.bin` → `smc200.bin.flashed`
   - `fwupgrade.txt` → `fwupgrade.txt.done`
2. Copy `camera/fw/p8_invalidate.sh` to `<card>/g360r/p8_hook/p8_invalidate.sh`.
3. Create two text files at the card root, with **LF line endings** and **UTF-8 without BOM**
   (for example in VS Code or Notepad++; not Windows Notepad's default):
   - `info.tg` containing: `cmd.tg`
   - `cmd.tg` containing: `shell script /sdcard/g360r/p8_hook/p8_invalidate.sh`
4. Put the card back and power on normally. The script runs by itself:
   - **1 beep** = started,
   - **2 beeps** = done,
   - **3 beeps** = error: power off and look at `g360r/log/p8_invalidate.log` on the card.

   Do not press any camera button while it runs. When it is done it disarms itself (it renames
   `info.tg` to `info.tg.done`). The log also lists the files of ours that the flash wrote
   (`ls /usr/share/g360r/`). Whether the camera shows the factory "self test" screen during this
   step: **TODO: verify**.
5. Power off and on again. This is the first **cold boot** from the new firmware: nothing happens
   for about 30 s, then the stock start-up (the charge LED blinks blue twice, the two LEDs by the
   lenses turn red) and the camera comes on. Without our SD configuration it behaves exactly like
   stock, only slower to start.

From now on every power-on is a cold boot.

## 6. Prepare the SD card

Put the card in the PC (say it is drive `E:`) and run, from the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File camera\tools\prepare_sd.ps1 -Drive E: -Normal
```

It:

- copies the controller to `<card>\g360r\` (the camera's own `DCIM\` is left alone);
- renames any factory trigger files at the card root (`info.tg`, `cmd.tg`) to `*.off`;
- creates `g360r\g360r.conf` with a random 8-digit **Wi-Fi passphrase** on the first run (later
  runs keep it; `-NewPsk` makes a new one) and **prints it. Write it down**: you enter it once in
  the app;
- gives every file the LF line endings the camera needs.

Then copy `camera\out\libg360r_plugin.so` to `<card>\g360r\libg360r_plugin.so` (the controller
loads the plugin from there). **TODO: verify** whether `prepare_sd` should do this itself.

Eject the card safely before removing it.

From Linux or Git Bash there is `sh camera/tools/prepare_sd.sh <card mount dir> [--new-psk]`, but
it has no normal-mode option yet: it always copies `info.tg` and `cmd.tg` to the card root.
**Delete `info.tg` and `cmd.tg` from the card root** afterwards, or the camera starts in factory
mode.

Options you normally leave alone: `-LedTest on|off` and `-SelfTest on|off` are developer
diagnostics (the self-test takes 3 real shots); `-KeepAwake on` puts `sdb.conf` at the card root,
which disables the camera's idle auto power-off (for tests only).

## 7. First connection

1. Insert the card and power on. Cold boot: about 30 s of nothing, then the stock start-up.
2. A few seconds later our **LED signature** runs (around the camera twice: the charge LED blue,
   one lens LED, the other; then two flashes), and **one beep** means the camera's Wi-Fi is up.
   **Three beeps** mean an error: power off, and look at the logs in `g360r/log/` on the card
   (`g360r.log` first).
3. On the phone, install the app (see [Build](../README.md#build): `./gradlew installDebug`) and
   open it.
4. Pair: the app looks for the camera's network, named `G360R-XXXXXX` (the last 6 hex digits of
   the camera's MAC address), and asks for the passphrase from step 6. You enter it only once.

## How to check that it worked

- The app shows the camera with its battery and storage, and can take a photo.
- Or, with the phone (or a laptop) joined to the `G360R-XXXXXX` network, open
  `http://192.168.107.1:8360/api/v1/info` in a browser: you get a JSON page with `"protocol":1`
  and `"adapter":"c200"`. Android may say the network has "no internet": keep the connection.
- Logs on the card, in `g360r/log/`: `g360r.log` (controller, previous boot in `g360r.prev.log`),
  `state.txt` (last milestone reached), `start.log`, `g360rd.stderr` (crashes, if any).

To switch our controller off without touching the firmware, remove `g360r/g360r.conf` from the card
or create an empty file `g360r.off` at the card root. Without it the modified firmware behaves like
stock.

Controller updates need no new flash: a newer `g360r/bin/g360rd`, `g360r/launcher.sh` or
`g360r/netup.sh` on the card wins over the copy in the firmware. Run `prepare_sd` again after a
build.

## 8. Go back to stock (recovery)

Flash the **stock** `C200GLU0AQK1_171121_1257_REV00_user.bin` exactly as in [step 4](#4-flash-the-camera):
rename it to `smc200.bin`, put it at the card root with an empty `fwupgrade.txt` (no `info.tg`),
battery near full, USB unplugged, then the button combination. **TODO: verify** the button
combination for recovery: [camera/fw/README.md](../camera/fw/README.md#recovery) says "Shutter +
Menu", while its flash section says Shutter + power, with Menu not needed. Also **TODO: verify**
that the stock file has to be renamed to `smc200.bin` (the developer reference does not say).

The stock image writes back the stock system partition and the instant-on snapshot, so the camera
is exactly as it came from Samsung (instant-on included). No step 5 is needed after flashing stock.

Remove the `g360r/` folder from the card if you no longer need it.

## If something goes wrong

- **No beep at all, nothing after several minutes:** hold power until the camera is off, remove
  the card, and power on without it. The camera should start stock. Then check the card's
  `g360r/log/` folder.
- **The camera seems dead after the flash:** it is probably the 30 s cold boot. Wait a full minute
  before doing anything.
- **The update prompt does not appear:** USB cable plugged in, battery not full enough, or the
  files are not at the card root with the exact names `smc200.bin` and `fwupgrade.txt`.
- **Never** power-cycle repeatedly with the card in, and never press buttons on a factory screen.
  When in doubt: power off, remove the card, ask.
