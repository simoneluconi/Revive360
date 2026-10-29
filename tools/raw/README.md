# RAW tools (PC)

For the raw dumps the camera saves with the `raw` setting on: one pair per photo in `DCIM/RAW/`,
named after the photo (`360_0383.JPG` → `360_0383_front_7776x3888x3950.raw` and `…_rear_…`). Python 3 with `numpy` (and `Pillow` for `ccmfit.py` and for the white balance from the JPEG):

```
pip install numpy pillow
```

| Script | What |
|---|---|
| `ssif2dng.py OUT.dng FRONT.raw [REAR.raw] [PHOTO.JPG]` | A DNG any raw editor opens: both lenses side by side like the camera's JPEG (or one lens). With the photo's JPEG the white balance is the camera's; without it, grey world (too blue outdoors: yellowish) |
| `rawcheck.py FILE.raw` | Facts about one dump (bit depth, Bayer pattern) and a preview PNG |
| `ccmfit.py JPG FRONT.raw REAR.raw [...]` | Fits the colour matrix again from the camera's JPEG of the same shots (`SSIF_CM=ccm_cm.npy ssif2dng.py …` uses it) |

e.g. `python ssif2dng.py 360_0383.dng 360_0383_front_7776x3888x3950.raw 360_0383_rear_7776x3888x3950.raw 360_0383.JPG`.
Format and background: [docs/firmware/14_raw_capture.md](../../docs/firmware/14_raw_capture.md).
