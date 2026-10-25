# Revive360

**Use your Samsung Gear 360 again**, with any Android phone (Android 10 or later, any brand, not
only Samsung), now that Samsung's apps no longer work.

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

You need the camera, a microSD card, an Android phone and a computer with Docker. Download
Samsung's original firmware for the camera (shared on
[XDA](https://xdaforums.com/t/mod-port-samsung-gear-360-manager-for-all-devices-updated-10-apr-24-v1-5-00-1-4.3400383/page-93))
into the `firmware/stock` folder, then:

```sh
./install.sh build      # once
./install.sh flash      # then on the camera: hold Shutter, press power, confirm the update
./install.sh activate   # then switch the camera on: 1 beep, 2 beeps
./install.sh sd         # prints the Wi-Fi password; then connect with the app
```

Each step tells you exactly what to do on the camera. The full guide:
**[docs/INSTALL.md](docs/INSTALL.md)**.

> Installing modified firmware is at your own risk. You can always go back to Samsung's original
> firmware the same way: the guide explains how.

## Status

Early days: it works every day on one camera. Coming before version 1: connecting through
Bluetooth, so that the camera's Wi-Fi only turns on when the phone asks for it.

## License

Free software under the [GNU GPL v3](LICENSE). Not made or endorsed by Samsung, and it contains no
Samsung software: you download Samsung's firmware yourself.
