#!/usr/bin/env python3
"""Step 2: build the 8-frame sprite sheets from the keyed single sprites.  usage: python3 tools/make_sheets.py

processed/fx_<name>_keyed.png (256x256)  ->  processed/fx_<name>.png
  petal, leaf, snow : 512x64   (8 frames of 64x64)
  tumbleweed        : 1024x128 (8 frames of 128x128)
Frame k is the sprite rotated 45*k degrees, so frame 8 == frame 0 and the loop is seamless. Petal and leaf
also get a slight squash across the turn (tumbling look); snow and tumbleweed are pure rotations. Every frame
uses the SAME scale (the sprite's diagonal fits the cell), so nothing pulses in size while it spins.
"""
import math
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "processed")

SHEETS = {            # name: (cell size, squash amount)
    "petal": (64, 0.35),
    "leaf": (64, 0.30),
    "snow": (64, 0.0),
    "tumbleweed": (128, 0.0),
}
FRAMES = 8
SS = 4                # supersampling factor while rotating


def trim(im):
    bb = im.split()[-1].point(lambda v: 255 if v > 8 else 0).getbbox()
    return im.crop(bb)


def build(name, cell, squash):
    src = trim(Image.open(os.path.join(OUT, "fx_%s_keyed.png" % name)).convert("RGBA"))
    w, h = src.size
    diag = math.hypot(w, h)
    margin = max(2, cell // 16)
    scale = (cell - 2 * margin) / diag
    big = cell * SS
    base = src.resize((max(1, round(w * scale * SS)), max(1, round(h * scale * SS))), Image.LANCZOS)
    sheet = Image.new("RGBA", (cell * FRAMES, cell), (0, 0, 0, 0))
    for k in range(FRAMES):
        ang = 45.0 * k
        s = base
        if squash:
            f = 1.0 - squash * abs(math.sin(math.radians(ang)))      # narrower while "edge on"
            s = base.resize((max(1, round(base.size[0] * f)), base.size[1]), Image.LANCZOS)
        r = s.rotate(ang, resample=Image.BICUBIC, expand=True)
        frame = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        frame.alpha_composite(r, ((big - r.size[0]) // 2, (big - r.size[1]) // 2))
        frame = frame.resize((cell, cell), Image.LANCZOS)
        sheet.paste(frame, (k * cell, 0))
    sheet.save(os.path.join(OUT, "fx_%s.png" % name), optimize=True)
    print("sheet", name, sheet.size)


if __name__ == "__main__":
    for n, (c, q) in SHEETS.items():
        build(n, c, q)
