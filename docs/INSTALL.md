# Installing Revive360

Five steps. In each one a script does the work on your computer and on the SD card, then tells
you exactly what to do on the camera.

## Before you start

**You need:**

- a **Gear 360 (2016, SM-C200)**, best with firmware **C200GLU0AQK1**. To see yours, look at
  the details of any photo it took: "Software" (on Windows: Properties, Details, "Program name")
  is **0.85** for C200GLU0AQK1. You do not have to check by hand: `./install.sh flash` reads it
  from the photos on the card and stops if the camera is not a supported one;
- a **microSD card** and a card reader for the computer (the photos already on it stay). **Take
  at least one photo with the camera on this card** before you start: the script reads the
  camera's model and firmware from it;
- a **Windows or Linux computer** with [Docker Desktop](https://www.docker.com/products/docker-desktop/)
  running and [Git](https://git-scm.com/downloads) installed (on Windows, the commands below go
  in **Git Bash**);
- an **Android phone** with Android 10 or later, of any brand (it does not have to be a Samsung);
- **Samsung's original firmware file** `C200GLU0AQK1_171121_1257_REV00_user.bin`: download it from
  the [XDA thread](https://xdaforums.com/t/mod-port-samsung-gear-360-manager-for-all-devices-updated-10-apr-24-v1-5-00-1-4.3400383/page-93)
  and put it in the `firmware/stock` folder of this project. We cannot share it ourselves: it is
  Samsung's.

**Good to know:**

- You install it at your own risk. Nothing is lost for good: [step 5](#5-going-back-to-samsungs-firmware)
  puts Samsung's firmware back.
- With our firmware the camera takes about **30 seconds to start, with nothing on the screen**.
  It is not broken: wait, and do not press power again.
- If anything goes wrong: hold power until the camera is off, **take the card out**, and only then
  switch it on again. Do not keep switching it on and off with the card in.

All commands are typed in a terminal opened in the project folder.

## 1. Build

```sh
./install.sh build
```

The first time it takes a while (it downloads its tools). It ends with `Built.`

## 2. Install the firmware on the camera

Put the SD card in the camera and take **one photo** (if it has none from this camera yet). Then
put the card in the computer and run:

```sh
./install.sh flash
```

Then on the camera, as the script tells you:

1. battery charged to near full, USB cable unplugged, card in, camera off;
2. **hold the Shutter (OK) button and press the power button**;
3. **"FW update"** appears: press **OK**, then do not touch anything;
4. it shows a progress bar, **"Updated"**, and restarts by itself (the 3 red LEDs are normal).

## 3. Activate it

Camera off, card back in the computer:

```sh
./install.sh activate
```

Then put the card in the camera and switch it on normally. The screen shows a **factory screen**
with a few codes and the battery level (for example `QK1  38%`): that is expected for this step.
**Do not press any button while it is shown.** Wait for **1 beep** (started), then **2 beeps**
(done). Switch it off and on again, and wait the 30 seconds.

## 4. Get it ready for everyday use

Camera off, card back in the computer:

```sh
./install.sh sd
```

It prints the camera's **Wi-Fi password**: write it down, the app asks for it once. Put the card
in the camera and switch it on: after the 30 seconds the LEDs go around the camera and **1 beep**
says its Wi-Fi is on. Open the app on the phone and connect (the camera's network is called
`G360R-...`).

That's it. To update our software later, run `./install.sh build` and `./install.sh sd` again.

## 5. Going back to Samsung's firmware

```sh
./install.sh stock
```

Then the same buttons as in step 2. The camera ends up exactly as it came from Samsung.

## If something goes wrong

Put the card in the computer and run:

```sh
./install.sh status
```

It says what the card is set up for and shows the camera's latest log lines.

- **3 beeps:** an error. Switch off and run `./install.sh status`.
- **No "FW update" screen:** the USB cable is plugged in, or the battery is not charged enough.
- **Nothing happens after switching on:** wait a full minute (the 30 seconds start). Still
  nothing: hold power, take the card out, switch on without it; the camera starts as Samsung's.
- **A factory screen with codes and the battery level:** expected during step 3 only, while the
  activation runs; never press buttons on it. If it shows up at any other time, switch off, take
  the card out and run `./install.sh status`: the card still has the activation files on it.

The details behind every step (what the scripts do, the firmware build, the camera's start-up)
are in the [developer notes](DEVELOPMENT.md) and [camera/fw/README.md](../camera/fw/README.md).
