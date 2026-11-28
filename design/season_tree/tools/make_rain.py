#!/usr/bin/env python3
"""Step 3: draw the rain layers in code (no art needed).  usage: python3 tools/make_rain.py

processed/fx_rain_far.png    512x512  thin pale streaks, 10 deg to the right, tiles seamlessly (both axes)
processed/fx_rain_near.png   512x512  thicker, brighter streaks, fewer, same tilt, seamless
processed/fx_splash.png      384x32   6 frames of 64x32: dot -> small crown -> fade (drop hitting the ground)
processed/fx_ripple.png      768x32   6 frames of 128x32: thin elliptical ring expanding and fading
processed/ground_puddles.png 1080x240 scattered shallow puddles reflecting a soft grey sky (overlay on any ground)
"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "processed")
SS = 3                                  # supersampling


def rain_tile(path, count, length, width, alpha, seed):
    rnd = random.Random(seed)
    size = 512
    big = size * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    dx = math.sin(math.radians(10))         # tilted 10 degrees to the right
    dy = math.cos(math.radians(10))
    for _ in range(count):
        x = rnd.uniform(0, big)
        y = rnd.uniform(0, big)
        ln = rnd.uniform(0.7, 1.3) * length * SS
        a = int(alpha * rnd.uniform(0.6, 1.0))
        w = max(1, round(width * SS * rnd.uniform(0.8, 1.2)))
        steps = 8                             # fade in/out along the streak
        for s in range(steps):
            t0, t1 = s / steps, (s + 1) / steps
            fade = math.sin(math.pi * (t0 + t1) / 2)
            col = (225, 238, 255, int(a * fade))
            for ox in (-big, 0, big):          # wrap around so the tile repeats without a seam
                for oy in (-big, 0, big):
                    d.line([(x + ox + dx * ln * t0, y + oy + dy * ln * t0),
                            (x + ox + dx * ln * t1, y + oy + dy * ln * t1)], fill=col, width=w)
    img.resize((size, size), Image.LANCZOS).save(path, optimize=True)


def splash(path):
    cw, ch, frames = 64, 32, 6
    sheet = Image.new("RGBA", (cw * frames, ch), (0, 0, 0, 0))
    radius = [1, 5, 10, 15, 19, 23]
    height = [2, 9, 12, 10, 6, 3]
    alpha = [255, 255, 235, 190, 120, 50]
    for k in range(frames):
        big = Image.new("RGBA", (cw * SS, ch * SS), (0, 0, 0, 0))
        d = ImageDraw.Draw(big)
        gx, gy = cw * SS // 2, (ch - 5) * SS
        col = (225, 238, 255, alpha[k])
        r = radius[k] * SS
        # flat ring on the ground
        d.ellipse([gx - r, gy - r // 4, gx + r, gy + r // 4], outline=col, width=max(1, SS))
        # little crown: droplets flying up and out
        for i in range(-3, 4):
            if k == 0:
                break
            fx = i / 3.0
            px = gx + fx * r * 0.8
            py = gy - height[k] * SS * (1 - fx * fx) - SS
            dr = max(1, round(SS * (1.6 if k < 4 else 1.0)))
            d.ellipse([px - dr, py - dr, px + dr, py + dr], fill=col)
        if k == 0:
            d.ellipse([gx - 2 * SS, gy - 2 * SS, gx + 2 * SS, gy + 2 * SS], fill=col)
        sheet.paste(big.resize((cw, ch), Image.LANCZOS), (k * cw, 0))
    sheet.save(path, optimize=True)


def ripple(path):
    cw, ch, frames = 128, 32, 6
    sheet = Image.new("RGBA", (cw * frames, ch), (0, 0, 0, 0))
    for k in range(frames):
        t = (k + 1) / frames
        big = Image.new("RGBA", (cw * SS, ch * SS), (0, 0, 0, 0))
        d = ImageDraw.Draw(big)
        rx, ry = (8 + 54 * t) * SS, (2.5 + 11 * t) * SS
        cx, cy = cw * SS / 2, ch * SS / 2
        a = int(255 * (1 - t) ** 1.2 * 0.9 + 20)
        d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], outline=(225, 238, 255, a), width=max(1, round(SS * (1.8 - t))))
        if k >= 2:                              # fainter second ring trailing behind
            r2x, r2y = rx * 0.62, ry * 0.62
            d.ellipse([cx - r2x, cy - r2y, cx + r2x, cy + r2y], outline=(225, 238, 255, a // 2), width=SS)
        sheet.paste(big.resize((cw, ch), Image.LANCZOS), (k * cw, 0))
    sheet.save(path, optimize=True)


def puddles(path, seed=7):
    rnd = random.Random(seed)
    W, H = 1080, 240
    big = Image.new("RGBA", (W * 2, H * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    spots = []
    for _ in range(40):                          # shallow, wide ellipses spread over the lower 70% of the strip
        rx = rnd.uniform(40, 150)
        ry = rx * rnd.uniform(0.10, 0.17)
        x = rnd.uniform(rx, W - rx)
        y = rnd.uniform(H * 0.38, H - ry - 6)
        if any(abs(x - sx) < (rx + srx) * 0.8 and abs(y - sy) < (ry + sry) * 2.2 for sx, sy, srx, sry in spots):
            continue
        spots.append((x, y, rx, ry))
        if len(spots) >= 9:
            break
    for x, y, rx, ry in spots:
        box = [(x - rx) * 2, (y - ry) * 2, (x + rx) * 2, (y + ry) * 2]
        d.ellipse(box, fill=(150, 165, 185, 105))                      # grey-blue water
        inner = [(x - rx * 0.86) * 2, (y - ry * 0.55) * 2, (x + rx * 0.86) * 2, (y + ry * 0.2) * 2]
        d.ellipse(inner, fill=(205, 218, 235, 110))                     # reflected soft grey sky
        d.arc(box, 190, 350, fill=(235, 242, 255, 150), width=3)        # bright rim on the far edge
    big = big.filter(ImageFilter.GaussianBlur(2.0))
    big.resize((W, H), Image.LANCZOS).save(path, optimize=True)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    rain_tile(os.path.join(OUT, "fx_rain_far.png"), count=150, length=60, width=1.0, alpha=95, seed=11)
    rain_tile(os.path.join(OUT, "fx_rain_near.png"), count=55, length=110, width=2.2, alpha=170, seed=23)
    splash(os.path.join(OUT, "fx_splash.png"))
    ripple(os.path.join(OUT, "fx_ripple.png"))
    puddles(os.path.join(OUT, "ground_puddles.png"))
    print("rain layers written")
