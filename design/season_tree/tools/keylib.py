"""Green-screen keying helpers for the season-tree art.

The generated images sit on a green background that is NOT exactly #00FF00 and differs from image to
image (e.g. (49,213,39) on tree_winter_2), so the background colour is estimated per image from the
strongly green pixels along the border. alpha comes from the distance to that colour (the darker,
duller moss / leaf greens of the tree are far from it and are kept), the colour of the soft edge is
un-mixed from the background, and a 2 px band along the cut-out has its green dominance clamped so no
green or white halo is left on the player screen.
"""
import numpy as np
from PIL import Image, ImageFilter


def _smoothstep(x, lo, hi):
    t = np.clip((x - lo) / float(hi - lo), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def estimate_bg(rgb):
    h, w, _ = rgb.shape
    m = max(4, int(min(h, w) * 0.04))
    border = np.concatenate([rgb[:m].reshape(-1, 3), rgb[-m:].reshape(-1, 3),
                             rgb[:, :m].reshape(-1, 3), rgb[:, -m:].reshape(-1, 3)])
    green = border[(border[:, 1] - np.maximum(border[:, 0], border[:, 2])) > 100]
    if len(green) < 200:
        flat = rgb.reshape(-1, 3)
        green = flat[(flat[:, 1] - np.maximum(flat[:, 0], flat[:, 2])) > 100]
    return np.median(green, axis=0).astype(np.float32)


def key_green(img, lo=45.0, hi=115.0, halo_px=2, halo_extra=10.0, unmix=True, choke=0, clamp=True):
    rgb = np.asarray(img.convert("RGB")).astype(np.float32)
    bg = estimate_bg(rgb)
    dist = np.sqrt(((rgb - bg) ** 2).sum(axis=-1))
    alpha = _smoothstep(dist, lo, hi)

    # colour of the semi-transparent edge: remove the background contribution
    a = np.clip(alpha, 1e-3, 1.0)[..., None]
    fg = np.clip((rgb - (1.0 - a) * bg) / a, 0, 255)
    edge = (alpha < 0.999)[..., None]
    out = np.where(edge, fg, rgb) if unmix else rgb.copy()

    if choke > 0:
        # eat the feathered, background-tinted rim: erode the matte by `choke` px, then soften it a little
        m = Image.fromarray(((alpha >= 0.5) * 255).astype(np.uint8))
        m = m.filter(ImageFilter.MinFilter(choke * 2 + 1)).filter(ImageFilter.GaussianBlur(1.2))
        alpha = alpha * (np.asarray(m).astype(np.float32) / 255.0)

    # choke 1 px and clamp green dominance in a thin band along the cut-out edge (halo removal)
    a_img = Image.fromarray((alpha * 255).astype(np.uint8))
    transparent = np.asarray(a_img.point(lambda v: 255 if v < 128 else 0))
    k = halo_px * 2 + 1
    near = np.asarray(Image.fromarray(transparent).filter(ImageFilter.MaxFilter(k))) > 0
    r, g, b = out[..., 0], out[..., 1], out[..., 2]
    limit = np.maximum(r, b) + halo_extra
    g2 = np.where(near & clamp, np.minimum(g, limit), g)
    out = np.stack([r, g2, b], axis=-1)
    if unmix:
        alpha = np.clip((alpha - 0.12) / 0.88, 0, 1)

    res = np.dstack([np.clip(out, 0, 255), alpha * 255.0]).astype(np.uint8)
    return Image.fromarray(res, "RGBA")


def alpha_bbox(img, thr=128):
    a = np.asarray(img.split()[-1])
    ys, xs = np.where(a >= thr)
    if len(xs) == 0:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1
