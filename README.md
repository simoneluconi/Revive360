# Revive360

**Use your Samsung Gear 360 again**, with any recent Android phone, now that Samsung's apps no
longer work.

Revive360 is a free app for your phone plus a small add-on for the camera. The phone talks
straight to the camera over the camera's own Wi-Fi: no Samsung app, no account, no internet.

<p align="center">
  <img src="docs/screenshots/home.png" width="19%" alt="Home: the camera, its battery and card, and the shutter">
  <img src="docs/screenshots/settings.png" width="19%" alt="Camera settings">
  <img src="docs/screenshots/liveview.png" width="19%" alt="Live view">
  <img src="docs/screenshots/gallery.png" width="19%" alt="The photos and videos on the camera">
  <img src="docs/screenshots/viewer360.png" width="19%" alt="The 360 viewer, framing a normal photo">
</p>

## What you can do

- **Shoot** photos, videos, time-lapses and looping videos from your phone.
- **Change the camera's settings**: lens, size, self-timer, exposure, white balance and more.
- **See what the camera sees**, live.
- **Browse the camera's card**: download or delete photos and videos, one or many at a time.
- **Turn photos into real 360° photos** that Google Photos and other apps recognise.
- **Look around** a 360° photo, straighten it, and save **normal photos** or **"little planets"**
  out of it.

## Which cameras

| Camera | Works? |
|---|---|
| Gear 360 (2016, SM-C200) with firmware C200GLU0AQK1 | Yes |
| Gear 360 (2016) with other firmware | Not yet |
| Gear 360 (2017, SM-R210) | Not yet |

Want to help with another camera? See the [developer notes](docs/DEVELOPMENT.md).

## How to install it

You need the camera, a microSD card, an Android phone and a computer. It takes about half an
hour. In short:

1. **Download Samsung's original firmware** for your camera (shared on
   [XDA](https://xdaforums.com/t/mod-port-samsung-gear-360-manager-for-all-devices-updated-10-apr-24-v1-5-00-1-4.3400383/page-93)) and put it in the `firmware/stock` folder.
2. **Build our version** of it on your computer, with one command.
3. **Copy it to the SD card and install it on the camera**: hold the shutter button and press the
   power button.
4. **Install the app** on your phone and connect to the camera.

Every step, with what the camera shows along the way: **[the install guide](docs/INSTALL.md)**.

> Installing modified firmware is at your own risk. You can always go back to Samsung's original
> firmware the same way: the guide explains how.

## Status

Early days: it works every day on one camera. Coming before version 1: connecting through
Bluetooth, so that the camera's Wi-Fi only turns on when the phone asks for it.

## License

Free software under the [GNU GPL v3](LICENSE). Not made or endorsed by Samsung, and it contains no
Samsung software: you download Samsung's firmware yourself.
