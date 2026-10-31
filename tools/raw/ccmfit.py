"""Constrained colour fit: camera raw -> white balance -> 3x3 CCM (rows sum to 1) -> linear sRGB.

Usage: ccmfit.py JPG FRONT.raw REAR.raw [...]   (samples cached in ./ccm_samples.npz; no arguments = reuse them)
       env LAMBDA = ridge weight towards the identity CCM (default 0.02)

Compares chromaticities only (r,g,b shares), so the JPEG tone curve and the lens vignetting, which
change brightness, do not bend the fit. Levenberg-Marquardt on 8 parameters: WB gains for R and B
(G = 1) and the 6 free off-diagonal CCM terms.
"""
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from ssif2dng import XYZ_TO_SRGB, load_lens  # noqa: E402

GRID = 216          # samples per lens side
RADIUS = 0.6        # fraction of the lens radius used (vignetting and the circle edge stay out)


def box(a, n):
    h, w = a.shape[:2]
    a = a[:h - h % n, :w - w % n]
    return a.reshape(h // n, n, w // n, n, *a.shape[2:]).mean(axis=(1, 3))


def srgb_to_linear(v):
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)


def lens_rgb(path):
    img, black, _ = load_lens(path)
    img = img.astype(np.float64)
    r = img[0::2, 0::2] - black[0, 0]
    g = (img[0::2, 1::2] - black[0, 1] + img[1::2, 0::2] - black[1, 0]) / 2
    b = img[1::2, 1::2] - black[1, 1]
    rgb = np.stack([r, g, b], axis=-1) / (4095 - black.mean())
    clip = (img[0::2, 0::2] > 4000) | (img[0::2, 1::2] > 4000) | (img[1::2, 0::2] > 4000) | (img[1::2, 1::2] > 4000)
    return rgb, clip


def samples(jpg_half, raw_path):
    rgb, clip = lens_rgb(raw_path)                     # 1944 x 1944
    n_raw = rgb.shape[0] // GRID
    cam = box(rgb, n_raw)
    camclip = box(clip.astype(float), n_raw) > 0
    j = srgb_to_linear(np.asarray(jpg_half, dtype=np.float64) / 255.0)
    n_j = j.shape[0] // GRID
    jl = box(j, n_j)
    jclip = box((np.asarray(jpg_half) >= 250).any(axis=-1).astype(float), n_j) > 0
    # alignment: best luminance correlation over small shifts
    best = None
    for dy in range(-3, 4):
        for dx in range(-3, 4):
            a = np.roll(cam[..., 1], (dy, dx), axis=(0, 1))[8:-8, 8:-8].ravel()
            b = jl[..., 1][8:-8, 8:-8].ravel()
            c = np.corrcoef(a, b)[0, 1]
            if best is None or c > best[0]:
                best = (c, dy, dx)
    _, dy, dx = best
    cam = np.roll(cam, (dy, dx), axis=(0, 1))
    camclip = np.roll(camclip, (dy, dx), axis=(0, 1))
    yy, xx = np.mgrid[0:GRID, 0:GRID]
    rr = np.hypot(yy - GRID / 2 + 0.5, xx - GRID / 2 + 0.5) / (GRID / 2)
    lum = jl.mean(axis=-1)
    keep = (rr < RADIUS) & ~camclip & ~jclip & (lum > 0.01) & (cam.min(axis=-1) > 0.002)
    print(f'  {raw_path.rsplit("/", 1)[-1]}: shift ({dy},{dx}) corr {best[0]:.4f}, {keep.sum()} samples')
    return cam[keep], jl[keep]


CACHE = 'ccm_samples.npz'   # in the current directory
LAMBDA = float(os.environ.get('LAMBDA', '0.02'))


def load(args):
    if os.path.exists(CACHE) and not args:
        d = np.load(CACHE)
        return d['C'], d['J']
    C, J = [], []
    for i in range(0, len(args), 3):
        jpg = Image.open(args[i]).convert('RGB')
        w, h = jpg.size
        for half, raw in zip([jpg.crop((0, 0, w // 2, h)), jpg.crop((w // 2, 0, w, h))], args[i + 1:i + 3]):
            c, j = samples(half, raw)
            C.append(c)
            J.append(j)
    C, J = np.vstack(C), np.vstack(J)
    np.savez(CACHE, C=C, J=J)
    return C, J


def ccm_of(p):
    o = p[2:]
    return np.array([[1 - o[0] - o[1], o[0], o[1]],
                     [o[2], 1 - o[2] - o[3], o[3]],
                     [o[4], o[5], 1 - o[4] - o[5]]])


def model(p, C):
    wb = np.array([p[0], 1.0, p[1]])
    out = (C * wb) @ ccm_of(p).T
    return out / out.sum(axis=1, keepdims=True)


def residual(p, C, jc):
    r = (model(p, C) - jc).ravel()
    return np.concatenate([r, np.sqrt(LAMBDA * len(C)) * p[2:] * 0.1])


def fit(C, J):
    jc = J / J.sum(axis=1, keepdims=True)
    g = C[:, 1].mean()
    p = np.array([g / C[:, 0].mean(), g / C[:, 2].mean(), 0, 0, 0, 0, 0, 0], dtype=float)
    mu = 1e-3
    for _ in range(200):
        r = residual(p, C, jc)
        Jm = np.empty((r.size, p.size))
        for k in range(p.size):
            d = np.zeros_like(p)
            d[k] = 1e-6
            Jm[:, k] = (residual(p + d, C, jc) - r) / 1e-6
        A = Jm.T @ Jm
        step = np.linalg.solve(A + mu * np.diag(np.diag(A)), -Jm.T @ r)
        if (residual(p + step, C, jc) ** 2).sum() < (r ** 2).sum():
            p += step
            mu *= 0.3
            if np.abs(step).max() < 1e-9:
                break
        else:
            mu *= 10
    return p, jc


def main():
    C, J = load(sys.argv[1:])
    p, jc = fit(C, J)
    ccm = ccm_of(p)
    wb = np.array([p[0], 1.0, p[1]])
    np.set_printoptions(precision=4, suppress=True)
    ident = (C * wb) / (C * wb).sum(axis=1, keepdims=True)
    print(f'{len(C)} samples, lambda {LAMBDA}')
    print('chroma error  fitted:', np.abs(model(p, C) - jc).mean(axis=0), ' WB only:', np.abs(ident - jc).mean(axis=0))
    print('WB gains (R,G,B):', wb, ' -> camera neutral', 1 / wb)
    print('CCM (white-balanced camera -> linear sRGB):\n', ccm)
    # DNG: camera = diag(1/wb) @ CCM^-1 @ sRGB = diag(1/wb) @ CCM^-1 @ XYZ_TO_SRGB @ XYZ
    cm = np.diag(1 / wb) @ np.linalg.inv(ccm) @ np.array(XYZ_TO_SRGB)
    cm /= (cm @ np.array([0.9505, 1.0, 1.089])).max()
    print('ColorMatrix1:\n', cm)
    np.save('ccm_cm.npy', cm)   # ssif2dng.py takes it with SSIF_CM=ccm_cm.npy


if __name__ == '__main__':
    main()
