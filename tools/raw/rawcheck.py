"""Inspect an ssif_front/rear_*.raw dump from the C200 RTOS and say what it is.

The RTOS names the file ssif_<lens>_<A>x<B>x<C>_<n>_10bit(16bit).raw where (from the dump code at
0x80284b88) A = stride/2 = bytes written per row, B = W/2 = pixels per lens row, C = GH = rows.

Checks, each printed with the numbers behind it:
  - value range of the 16-bit little-endian words (max < 1024 -> 10 bit, < 4096 -> 12 bit, ...)
  - whether the four 2x2 sub-planes behave like a Bayer mosaic: two of them (the greens, on a
    diagonal) agree closely with each other, the other two differ; a YCC/YUV buffer has no such
    2x2 structure but strong odd/even byte structure instead
  - the CFA order (RGGB / BGGR / GRBG / GBRG) from which diagonal holds the matching pair
Writes <file>.preview.png: a half-size RGB image built by 2x2 binning in the detected order, so the
picture can be checked by eye.

Usage: python rawcheck.py FILE [--width PIXELS] [--rows ROWS] [--rowbytes BYTES]
"""
import argparse
import os
import re
import sys

import numpy as np


def dims_from_name(path):
    m = re.search(r'_(\d+)x(\d+)x(\d+)', os.path.basename(path))
    if not m:
        return None
    return int(m.group(1)), int(m.group(2)), int(m.group(3))   # rowbytes, width px, rows


def load(path, rowbytes, width, rows):
    data = np.fromfile(path, dtype='<u2')
    words_per_row = rowbytes // 2
    if data.size < words_per_row * rows:
        rows = data.size // words_per_row
        print(f'note: file holds only {rows} full rows')
    img = data[:words_per_row * rows].reshape(rows, words_per_row)
    return img[:, :min(width, words_per_row)]


def planes(img):
    h, w = img.shape[0] & ~1, img.shape[1] & ~1
    img = img[:h, :w].astype(np.float64)
    return {(0, 0): img[0::2, 0::2], (0, 1): img[0::2, 1::2],
            (1, 0): img[1::2, 0::2], (1, 1): img[1::2, 1::2]}


def corr(a, b):
    a = a.ravel() - a.mean()
    b = b.ravel() - b.mean()
    d = np.sqrt((a * a).sum() * (b * b).sum())
    return float((a * b).sum() / d) if d else 0.0


def analyse(img):
    out = {}
    mx = int(img.max())
    out['min'], out['max'], out['mean'] = int(img.min()), mx, float(img.mean())
    out['bits'] = next((b for b in (8, 10, 12, 14, 16) if mx < (1 << b)), 16)
    # values that are always multiples of 2^k mean the data is left-aligned in the word
    nz = img[img > 0]
    tz = 0
    while tz < 8 and nz.size and np.all((nz >> tz) & 1 == 0):
        tz += 1
    out['trailing_zero_bits'] = tz
    p = planes(img)
    means = {k: float(v.mean()) for k, v in p.items()}
    diag = abs(means[(0, 0)] - means[(1, 1)]), abs(means[(0, 1)] - means[(1, 0)])
    c_main = corr(p[(0, 0)], p[(1, 1)])
    c_anti = corr(p[(0, 1)], p[(1, 0)])
    out['plane_means'] = means
    out['corr_00_11'], out['corr_01_10'] = c_main, c_anti
    # greens sit on the diagonal whose two planes match best (closest means, highest correlation)
    greens_on_anti = (diag[1] < diag[0]) if abs(diag[0] - diag[1]) > 1e-9 else (c_anti > c_main)
    out['greens'] = 'anti (01,10)' if greens_on_anti else 'main (00,11)'
    if greens_on_anti:
        r_or_b = (means[(0, 0)], means[(1, 1)])
        order = ('R', 'B') if r_or_b[0] >= r_or_b[1] else ('B', 'R')
        out['cfa_guess'] = f'{order[0]}G G{order[1]}'.replace(' ', '')
    else:
        r_or_b = (means[(0, 1)], means[(1, 0)])
        order = ('R', 'B') if r_or_b[0] >= r_or_b[1] else ('B', 'R')
        out['cfa_guess'] = f'G{order[0]}{order[1]}G'
    # a Bayer mosaic of a real scene: the matching green pair correlates far better with itself
    # than with the other two planes; YCC interleaved data does not show this split
    g = (p[(0, 1)], p[(1, 0)]) if greens_on_anti else (p[(0, 0)], p[(1, 1)])
    o = (p[(0, 0)], p[(1, 1)]) if greens_on_anti else (p[(0, 1)], p[(1, 0)])
    out['corr_green_pair'] = corr(*g)
    out['corr_other_pair'] = corr(*o)
    return out, p


def preview(p, cfa, path):
    from PIL import Image
    pos = {'RGGB': ((0, 0), (0, 1), (1, 0), (1, 1)), 'BGGR': ((1, 1), (0, 1), (1, 0), (0, 0)),
           'GRBG': ((0, 1), (0, 0), (1, 1), (1, 0)), 'GBRG': ((1, 0), (0, 0), (1, 1), (0, 1))}
    r, g1, g2, b = (p[k] for k in pos[cfa])
    rgb = np.dstack([r, (g1 + g2) / 2, b])
    rgb -= np.percentile(rgb, 0.5)
    rgb /= max(np.percentile(rgb, 99.5), 1e-9)
    rgb = np.clip(rgb, 0, 1) ** (1 / 2.2)
    Image.fromarray((rgb * 255).astype(np.uint8)).save(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('file')
    ap.add_argument('--width', type=int)
    ap.add_argument('--rows', type=int)
    ap.add_argument('--rowbytes', type=int)
    a = ap.parse_args()
    d = dims_from_name(a.file) or (None, None, None)
    rowbytes, width, rows = a.rowbytes or d[0], a.width or d[1], a.rows or d[2]
    if not (rowbytes and width and rows):
        sys.exit('dimensions unknown: pass --rowbytes, --width and --rows')
    size = os.path.getsize(a.file)
    print(f'{a.file}: {size} bytes; rowbytes {rowbytes}, width {width} px, rows {rows} '
          f'(expected {rowbytes * rows} bytes)')
    img = load(a.file, rowbytes, width, rows)
    res, p = analyse(img)
    for k, v in res.items():
        print(f'  {k}: {v}')
    bayer = res['corr_green_pair'] > 0.9 and res['corr_green_pair'] > res['corr_other_pair'] + 0.02
    print('VERDICT:', 'looks like a Bayer mosaic' if bayer else 'does NOT look like a Bayer mosaic')
    out = a.file + '.preview.png'
    preview(p, res['cfa_guess'], out)
    print('preview:', out)


if __name__ == '__main__':
    main()
