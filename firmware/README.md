# firmware/

Samsung's firmware images go here. Git ignores everything in this folder except this file: the
images are Samsung's and are never published with the project.

```
firmware/
  stock/        the stock images, as downloaded (*.bin)
  opensource/   Samsung's open source packages for the camera (optional, for reference)
  out/          what camera/tools/make_firmware.sh builds (smc200.bin, smr210.bin)
```

| Camera | Firmware | File | SHA-256 |
|---|---|---|---|
| SM-C200 (Gear 360 2016) | C200GLU0AQK1 | `C200GLU0AQK1_171121_1257_REV00_user.bin` | `150bc48362555a4812e8871ff581c693f50f80e3e31e4647d2b563e9072c48db` |
| SM-C200 (Gear 360 2016) | C200GLU0APE4 (older, not supported: reference only) | `C200GLU0APE4_160519_1848_REV00_user.bin` | `f0962ca7521d82219a3e2418cc26fc046db39d8c40e428f5528287e1c7e7ebea` |
| SM-R210 (Gear 360 2017) | R210GLU0ARB2 | `R210GLU0ARB2_180201_1739_REV00_user.bin` | `81765495ee2dfa3f67f0543e8f99f0df2e5310e3f0b10f8dabddbf64015d8324` |

Only the last firmware of each model is supported (C200GLU0AQK1, R210GLU0ARB2). A camera on an
older one is updated to it first with Samsung's SD update.

Where to get them: the community shares the stock images on XDA, in the
[Gear 360 Manager port thread](https://xdaforums.com/t/mod-port-samsung-gear-360-manager-for-all-devices-updated-10-apr-24-v1-5-00-1-4.3400383/page-93). `make_firmware.sh` refuses an image whose checksum is not a
supported one, so a damaged or different download is caught before it is flashed.
