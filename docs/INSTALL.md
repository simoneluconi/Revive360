# Installing Revive360

The same steps for the Gear 360 2016 (SM-C200) and the Gear 360 2017 (SM-R210). You type one
command on the computer, and it tells you what to do on the camera. About half an hour, once.

## What you need

1. **The camera**, updated to Samsung's last firmware. Only that one is supported:
   **C200GLU0AQK1** on the 2016 and **R210GLU0ARB2** on the 2017. Not sure? Just go ahead: the
   script checks and tells you. If yours is older, see [Going back to Samsung's firmware](#going-back-to-samsungs-firmware):
   the same command updates it.
2. **Its microSD card**, with **one photo taken with this camera** on it (the script reads the
   camera's model from it), and a card reader. The photos on the card stay.
3. **A Windows or Linux computer** with [Docker Desktop](https://www.docker.com/products/docker-desktop/)
   (running) and [Git](https://git-scm.com/downloads). On Windows, type the commands in **Git Bash**.
4. **This project**, downloaded: `git clone https://github.com/simoneluconi/Revive360`, then open a
   terminal in the `Revive360` folder.
5. **Samsung's firmware file** for your camera: click its name below to download it from the
   [XDA thread](https://xdaforums.com/t/mod-port-samsung-gear-360-manager-for-all-devices-updated-10-apr-24-v1-5-00-1-4.3400383/page-93)
   (XDA may ask you to log in), and put it in the `firmware/stock` folder:

   | Camera | File | SHA-256 |
   |---|---|---|
   | Gear 360 2016 | [`C200GLU0AQK1_171121_1257_REV00_user.bin`](https://xdaforums.com/attachments/c200glu0aqk1_171121_1257_rev00_user-bin.5193031/) | `150bc48362555a4812e8871ff581c693f50f80e3e31e4647d2b563e9072c48db` |
   | Gear 360 2017 | [`R210GLU0ARB2_180201_1739_REV00_user.bin`](https://xdaforums.com/attachments/r210glu0arb2_180201_1739_rev00_user-bin.5193015/) | `81765495ee2dfa3f67f0543e8f99f0df2e5310e3f0b10f8dabddbf64015d8324` |

   The SHA-256 is the file's fingerprint: `./install.sh build` checks it and refuses any other
   file, so a wrong or damaged download never reaches the camera.

6. **The app** on an Android phone (Android 10 or later, any brand): from
   [Google Play](https://play.google.com/store/apps/details?id=com.simoneluconi.revive360) or the
   APK in the [latest release](https://github.com/simoneluconi/Revive360/releases/latest).

## Step 1: build

```sh
./install.sh build
```

The first time takes a while. It ends with `Built.`

## Step 2: update the camera

Card in the computer:

```sh
./install.sh flash
```

Then card in the camera, and:

1. battery charged, **USB cable unplugged**, camera off;
2. **hold the Shutter button and press power**;
3. the update starts by itself: **don't touch anything** until the camera has restarted
   (a few minutes; lit LEDs are normal).

## Step 3: switch it on

Camera off, card in the computer:

```sh
./install.sh activate
```

Card in the camera, switch it on, and **don't press any button**. Wait for **1 beep**, then
**2 beeps**. Switch the camera off and on again.

On the 2016 a screen with codes and the battery level shows meanwhile: that is expected.

## Step 4: ready (Gear 360 2016 only)

The 2017 skips this step: its card is ready already.

Camera off, card in the computer:

```sh
./install.sh sd
```

Card back in the camera, switch it on.

## Pair the phone

Switch the camera on and wait: it now takes **about 30 seconds, with nothing on the screen**, then
the LEDs go around it and it beeps once. Within 3 minutes, open the app and tap **Find the
camera**. It finds it by itself; Android asks once to confirm the camera's network
(`G360R-...`). Nothing to type.

Done. The app's home lists your cameras: tap one to connect to it. The camera's Wi-Fi switches on
only while the app needs it.

To update our software later: `./install.sh build`, then `./install.sh sd` with the card.

## Going back to Samsung's firmware

```sh
./install.sh stock
```

Then the same buttons as in step 2. The camera is back as it came from Samsung. This is also how
a camera with an older firmware is updated to the last one before step 1.

## If something goes wrong

- **Nothing on the screen after switching on:** normal, wait a full minute.
- **3 beeps:** an error. Camera off, card in the computer, run `./install.sh status`: it says what
  the card is set up for and shows the camera's log.
- **The update doesn't start (the camera starts as usual):** unplug the USB cable and charge the
  battery.
- **Stuck:** hold power until the camera is off, **take the card out**, switch it on: without the
  card it starts as Samsung's.
- **Wrong camera:** the script stops if the card's photos come from another model.

The script finds the camera model by itself. To choose it, put `c200` or `r210` right after
`./install.sh` (for example `./install.sh r210 flash`).

Changing the camera's software is at your own risk; `./install.sh stock` always puts Samsung's
back. The details behind every step are in the [developer notes](DEVELOPMENT.md) and
[camera/fw/README.md](../camera/fw/README.md).
