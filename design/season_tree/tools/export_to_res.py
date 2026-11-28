#!/usr/bin/env python3
"""Step 4a: copy the processed art into the app.  usage: python3 tools/export_to_res.py

processed/tree_*, ground_*, fx_rain_*, ground_puddles  -> app/src/main/res/drawable-nodpi/*.webp (lossy q88 + alpha)
processed/fx_<sprite sheets>                           -> app/src/main/res/drawable-nodpi/*.png (tiny, kept lossless)
Only the finished files are copied (not the *_keyed.png helpers).
"""
import glob
import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROC = os.path.join(ROOT, "processed")
RES = os.path.abspath(os.path.join(ROOT, "..", "..", "app", "src", "main", "res", "drawable-nodpi"))
SHEETS = ("fx_petal", "fx_leaf", "fx_snow", "fx_tumbleweed", "fx_splash", "fx_ripple")

os.makedirs(RES, exist_ok=True)
total = 0
for f in sorted(glob.glob(os.path.join(PROC, "*.png"))):
    name = os.path.basename(f)[:-4]
    if name.endswith("_keyed"):
        continue
    if name in SHEETS:
        dst = os.path.join(RES, name + ".png")
        Image.open(f).save(dst, optimize=True)
    else:
        dst = os.path.join(RES, name + ".webp")
        Image.open(f).convert("RGBA").save(dst, "WEBP", quality=88, method=6, alpha_quality=100)
    size = os.path.getsize(dst)
    total += size
    print("%-28s %7.0f KB" % (os.path.basename(dst), size / 1024.0))
print("total %.2f MB" % (total / 1048576.0))
