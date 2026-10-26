# RAW tools (PC)

For the raw dumps the camera saves with the `raw` setting on (`DCIM/RAW/ssif_front_…raw` and
`ssif_rear_…raw`, one pair per photo). Python 3 with `numpy` (and `Pillow` for `ccmfit.py`):

```
pip install numpy pillow
```

| Script | What |
|---|---|
| `ssif2dng.py OUT.dng FRONT.raw [REAR.raw]` | A DNG any raw editor opens: both lenses side by side like the camera's JPEG (or one lens) |
| `rawcheck.py FILE.raw` | Facts about one dump (bit depth, Bayer pattern) and a preview PNG |
| `ccmfit.py JPG FRONT.raw REAR.raw [...]` | Fits the colour matrix again from the camera's JPEG of the same shots (`SSIF_CM=ccm_cm.npy ssif2dng.py …` uses it) |

Take the front and rear files of the same photo (same number before `_10bit`, same time). Format and
background: [docs/firmware/14_raw_capture.md](../../docs/firmware/14_raw_capture.md).
