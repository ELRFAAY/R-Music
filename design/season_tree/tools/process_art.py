#!/usr/bin/env python3
"""Cut out and normalise the season-tree art.   usage: python3 tools/process_art.py [trees|grounds|fx|all]

Reads   design/season_tree/generated/*.jpg   (art on a green screen)
Writes  design/season_tree/processed/*.png   (transparent, final sizes)

trees   -> tree_<season>_<n>.png     768x768, aligned by the TRUNK BASE (ground contact + base centre), so the
                                     trunk does not jump when the state changes. The 1024x1024 sources share one
                                     framing (x centre 548); the three portrait 768x1376 sources (autumn 1/3/5)
                                     are drawn smaller and are scaled up by PORTRAIT_SCALE (matched on the mound
                                     width of tree_autumn_2).
grounds -> ground_<season>_<n>.png   1080x240 strip, bottom aligned, 5% cropped off both ends (white glow)
fx      -> fx_<name>_keyed.png       square 256x256 single sprite (input for the sprite-sheet step)
"""
import glob
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from keylib import key_green, alpha_bbox  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "generated")
OUT = os.path.join(ROOT, "processed")

CANVAS = 768
SCALE_1024 = 0.75            # 1024 -> 768
ANCHOR = (411, 726)          # where the trunk base (x centre, ground contact) lands on the 768 canvas
BASE_CX_1024 = 548           # common trunk-base x of the 1024x1024 sources
CX_NUDGE = {"tree_autumn_5": 16}   # the leaf pile is lopsided, the trunk base sits ~15 px right of the pile centre
PORTRAIT_SCALE = 1.22        # portrait sources are drawn this much smaller (mound width 415..431 vs 517)


def base_centre(im):
    x0, y0, x1, y1 = alpha_bbox(im)
    a = np.asarray(im.split()[-1])
    k = int(round(im.size[1] / 1024.0 * 30))
    band = a[y1 - k:y1 - max(1, k // 3), :] >= 128
    cols = np.where(band.any(axis=0))[0]
    return int(cols.mean()), y1


def paste_clipped(canvas, im, ox, oy):
    """alpha-composite im onto canvas at (ox, oy), clipping whatever falls outside."""
    w, h = im.size
    cx0, cy0 = max(0, -ox), max(0, -oy)
    cx1, cy1 = min(w, canvas.size[0] - ox), min(h, canvas.size[1] - oy)
    if cx1 <= cx0 or cy1 <= cy0:
        return
    canvas.alpha_composite(im.crop((cx0, cy0, cx1, cy1)), (ox + cx0, oy + cy0))


def do_trees():
    for f in sorted(glob.glob(os.path.join(SRC, "tree_*.jpg"))):
        name = os.path.basename(f)[:-4]
        if "portrait_alt" in name:
            continue
        im = key_green(Image.open(f))
        cx, y1 = base_centre(im)
        if im.size == (1024, 1024):
            scale, cx = SCALE_1024, BASE_CX_1024
        else:
            scale = SCALE_1024 * PORTRAIT_SCALE
            cx += CX_NUDGE.get(name, 0)
        w, h = im.size
        im2 = im.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)
        canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
        paste_clipped(canvas, im2, round(ANCHOR[0] - cx * scale), round(ANCHOR[1] - y1 * scale))
        canvas.save(os.path.join(OUT, name + ".png"), optimize=True)
        print("tree  ", name, "scale %.3f" % scale, "base cx", cx, "y1", y1)


# Strips whose own colour is green (grass) need the gentle key; sand / snow / leaves are far from green, so a
# harder key + a wider despill band removes the neon fringe the generator leaves on the feathered edge.
GRASS_GROUNDS = ("ground_autumn_1", "ground_autumn_2", "ground_spring_1", "ground_spring_2", "ground_winter_1")
KEY_GRASS = dict(lo=45.0, hi=115.0, unmix=False, choke=5, clamp=False)
KEY_PLAIN = dict(lo=45.0, hi=115.0, unmix=False, choke=6, halo_px=8, halo_extra=0.0)


def do_grounds():
    for f in sorted(glob.glob(os.path.join(SRC, "ground_*.jpg"))):
        name = os.path.basename(f)[:-4]
        if "_alt" in name:
            continue
        im = key_green(Image.open(f), **(KEY_GRASS if name in GRASS_GROUNDS else KEY_PLAIN))
        x0, y0, x1, y1 = alpha_bbox(im, thr=40)
        trim = int((x1 - x0) * 0.05)
        im = im.crop((x0 + trim, y0, x1 - trim, y1))
        w, h = im.size
        new_h = min(240, round(h * 1080.0 / w))
        im = im.resize((1080, new_h), Image.LANCZOS)
        canvas = Image.new("RGBA", (1080, 240), (0, 0, 0, 0))
        canvas.alpha_composite(im, (0, 240 - new_h))
        canvas.save(os.path.join(OUT, name + ".png"), optimize=True)
        print("ground", name, "height", new_h)


def do_fx():
    for f in sorted(glob.glob(os.path.join(SRC, "fx_*.jpg"))):
        name = os.path.basename(f)[:-4]
        im = key_green(Image.open(f))
        x0, y0, x1, y1 = alpha_bbox(im, thr=40)
        im = im.crop((x0, y0, x1, y1))
        s = 232.0 / max(im.size)              # 12 px margin inside a 256 square
        im = im.resize((max(1, round(im.size[0] * s)), max(1, round(im.size[1] * s))), Image.LANCZOS)
        canvas = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
        canvas.alpha_composite(im, ((256 - im.size[0]) // 2, (256 - im.size[1]) // 2))
        canvas.save(os.path.join(OUT, name + "_keyed.png"), optimize=True)
        print("fx    ", name)


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    os.makedirs(OUT, exist_ok=True)
    if what in ("trees", "all"):
        do_trees()
    if what in ("grounds", "all"):
        do_grounds()
    if what in ("fx", "all"):
        do_fx()
