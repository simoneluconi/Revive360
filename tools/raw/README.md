# RAW tools (PC)

For the raw dumps the camera saves with the `raw` setting on: one pair per photo in `DCIM/RAW/`,
named after the photo (`360_0383.JPG` → `360_0383_front_7776x3888x3950.raw` and `…_rear_…`;
`…_5824x2912x2896.raw` on the Gear 360 2017, found by the shape). Python 3 with `numpy` (and `Pillow` for `ccmfit.py` and for the white balance from the JPEG):

```
pip install numpy pillow
```

| Script | What |
|---|---|
| `ssif2dng.py OUT.dng FRONT.raw [REAR.raw] [PHOTO.JPG]` | A DNG any raw editor opens: both lenses side by side like the camera's JPEG (or one lens). With the photo's JPEG the white balance is the camera's; without it, grey world (too blue outdoors: yellowish) |
| `rawcheck.py FILE.raw` | Facts about one dump (bit depth, Bayer pattern) and a preview PNG (made for the C200's: on a 2017 dump it shows the 10 bits, and its Bayer verdict is not to be trusted) |
| `ccmfit.py JPG FRONT.raw REAR.raw [...]` | Fits the colour matrix again from the camera's JPEG of the same shots (`SSIF_CM=ccm_cm.npy ssif2dng.py …` uses it) |

e.g. `python ssif2dng.py 360_0383.dng 360_0383_front_7776x3888x3950.raw 360_0383_rear_7776x3888x3950.raw 360_0383.JPG`.
Format and background: [docs/firmware/14_raw_capture.md](../../docs/firmware/14_raw_capture.md); the 2017's
(10-bit, GRBG, no optical black): [docs/firmware/15_r210_port.md](../../docs/firmware/15_r210_port.md#format-10-bit-against-the-c200s-12).
The colour matrix was fitted on the 2016; the 2017's photos looked right with it, not fitted on their own yet.
