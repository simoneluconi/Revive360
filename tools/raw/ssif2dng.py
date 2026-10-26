"""Convert the C200 RTOS SSIF dumps (ssif_front/rear_<A>x<B>x<C>_<n>_10bit(16bit).raw) to a DNG.

Facts measured on the first real dump (2026-10-26, AQK1, normal mode):
  - 16-bit little-endian words holding 12-bit values shifted left by 4 (low 4 bits always 0)
  - rows 0..~19 are optical black (~128), rows 20..39 a brighter band; the image is the 3888x3888
    square from row 40
  - CFA RGGB, black ~128, white 4095 (12-bit)

Usage:
  ssif2dng.py OUT.dng FRONT.raw [REAR.raw]
One file -> one-lens DNG; two files -> one DNG with front and rear side by side (as the JPEG).

ColorMatrix1 was fitted (ccmfit.py) against the camera's own JPEG of a colourful indoor shot
(360_0382, 2026-10-26): chromaticities of raw vs JPEG near each lens centre, white kept, so a raw
developer gives colours close to the camera's JPEG. Env SSIF_CM=file.npy overrides it. White balance is
the grey-world estimate of the picture, written as AsShotNeutral (editable in any raw editor).
"""
import os
import re
import struct
import sys

import numpy as np

OB_ROWS = 40          # rows above the image in each lens readout
BLACK_ROWS = 16       # of those, the first ~20 are optical black (~128); rows 20..39 are not black
SHIFT = 4             # 12-bit values left-aligned in 16 bits
WHITE = 4095

# XYZ(D65) -> linear sRGB (ccmfit.py builds ColorMatrix1 from it)
XYZ_TO_SRGB = [[3.2406, -1.5372, -0.4986],
               [-0.9689, 1.8758, 0.0415],
               [0.0557, -0.2040, 1.0570]]


# ColorMatrix1 (XYZ -> camera), fitted by ccmfit.py on 360_0382; env SSIF_CM = another .npy 3x3
FITTED_CM = [
                [1.5444, -0.6246, -0.1031],
                [0.1190, 0.7907, 0.0884],
                [0.2728, -0.0814, 0.2876]]
COLOR_MATRIX = np.load(os.environ['SSIF_CM']).tolist() if os.environ.get('SSIF_CM') else FITTED_CM


def load_lens(path):
    m = re.search(r'_(\d+)x(\d+)x(\d+)_\d+', os.path.basename(path))
    if not m:
        sys.exit(f'{path}: no AxBxC in the name')
    rowbytes, width, rows = map(int, m.groups())
    a = np.fromfile(path, dtype='<u2')
    if a.size != rowbytes // 2 * rows:
        sys.exit(f'{path}: {a.size * 2} bytes, expected {rowbytes * rows}')
    a = a.reshape(rows, rowbytes // 2)[:, :width] >> SHIFT
    black = a[:BLACK_ROWS].reshape(BLACK_ROWS // 2, 2, width // 2, 2).mean(axis=(0, 2))   # per CFA site
    img = a[OB_ROWS:OB_ROWS + width]        # square active area
    return img.astype(np.uint16), black


def grey_world(img, black):
    h, w = img.shape
    # the lens circle only: a centred square of half the side avoids the black corners
    y0, x0 = h // 4 & ~1, (w // 4) & ~1
    c = img[y0:y0 + h // 2, x0:x0 + w // 2].astype(np.float64)
    r = c[0::2, 0::2] - black[0, 0]
    g = (c[0::2, 1::2] - black[0, 1] + c[1::2, 0::2] - black[1, 0]) / 2
    b = c[1::2, 1::2] - black[1, 1]
    m = np.array([r.mean(), g.mean(), b.mean()])
    return m / m[1]


def ifd_entry(tag, typ, count, value_bytes):
    return (tag, typ, count, value_bytes)


def write_dng(path, img, black, neutral, model):
    h, w = img.shape
    entries = []
    extra = bytearray()

    def add(tag, typ, values):
        fmt = {1: 'B', 2: 's', 3: 'H', 4: 'I', 5: 'II', 10: 'ii'}[typ]
        size = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 10: 8}[typ]
        if typ == 2:
            data = values.encode() + b'\0'
            count = len(data)
        else:
            flat = []
            for v in values:
                flat.extend(v if isinstance(v, (tuple, list)) else [v])
            data = struct.pack('<' + fmt[-1] * len(flat), *flat)
            count = len(data) // size
        entries.append([tag, typ, count, data])

    def rat(x, den=1000000):
        return (int(round(x * den)), den)

    add(254, 4, [0])                      # NewSubFileType: main image
    add(256, 4, [w])
    add(257, 4, [h])
    add(258, 3, [16])
    add(259, 3, [1])                      # uncompressed
    add(262, 3, [32803])                  # CFA
    add(271, 2, 'Samsung')
    add(272, 2, model)
    add(273, 4, [0])                      # StripOffsets, patched below
    add(274, 3, [1])
    add(277, 3, [1])
    add(278, 4, [h])
    add(279, 4, [w * h * 2])
    add(284, 3, [1])
    add(305, 2, 'Gear360Revived ssif2dng')
    add(33421, 3, [2, 2])                 # CFARepeatPatternDim
    add(33422, 1, [0, 1, 1, 2])           # CFAPattern RGGB
    add(50706, 1, [1, 4, 0, 0])           # DNGVersion
    add(50707, 1, [1, 1, 0, 0])           # DNGBackwardVersion
    add(50708, 2, 'Samsung Gear 360 SM-C200')
    add(50713, 3, [2, 2])                 # BlackLevelRepeatDim
    add(50714, 5, [rat(v, 100) for v in black.ravel()])   # BlackLevel per CFA site
    add(50717, 4, [WHITE])
    add(50721, 10, [rat(v, 10000) for row in COLOR_MATRIX for v in row])  # ColorMatrix1
    add(50728, 5, [rat(v) for v in neutral])                             # AsShotNeutral
    add(50778, 3, [21])                   # CalibrationIlluminant1 = D65

    entries.sort(key=lambda e: e[0])
    n = len(entries)
    ifd_off = 8
    ifd_size = 2 + n * 12 + 4
    data_off = ifd_off + ifd_size
    for e in entries:                     # out-of-line values after the IFD
        if len(e[3]) > 4:
            e.append(data_off + len(extra))
            extra += e[3]
            if len(extra) % 2:
                extra += b'\0'
        else:
            e.append(None)
    strip_off = data_off + len(extra)
    strip_off += (-strip_off) % 16
    for e in entries:
        if e[0] == 273:
            e[3] = struct.pack('<I', strip_off)
    with open(path, 'wb') as f:
        f.write(b'II*\0' + struct.pack('<I', ifd_off))
        f.write(struct.pack('<H', n))
        for tag, typ, count, data, off in entries:
            f.write(struct.pack('<HHI', tag, typ, count))
            f.write(struct.pack('<I', off) if off is not None else data.ljust(4, b'\0'))
        f.write(struct.pack('<I', 0))
        f.write(extra)
        f.write(b'\0' * (strip_off - f.tell()))
        f.write(img.astype('<u2').tobytes())


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    out = sys.argv[1]
    lenses = [load_lens(p) for p in sys.argv[2:4]]
    img = np.hstack([l[0] for l in lenses])
    black = np.mean([l[1] for l in lenses], axis=0)
    neutral = grey_world(img, black)
    write_dng(out, img, black, neutral, 'Gear 360 (SM-C200)')
    print(f'{out}: {img.shape[1]}x{img.shape[0]}, black {black.round(1).tolist()}, '
          f'AsShotNeutral {neutral.round(3).tolist()}')


if __name__ == '__main__':
    main()
