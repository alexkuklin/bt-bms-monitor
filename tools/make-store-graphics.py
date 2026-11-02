#!/usr/bin/env python3
"""Renders the Play Store icon (512x512) and feature graphic (1024x500).

Draws the same shapes as app/src/main/res/drawable/ic_launcher_foreground.xml.
Needs Pillow:  python3 -m venv /tmp/v && /tmp/v/bin/pip install pillow && /tmp/v/bin/python tools/make-store-graphics.py
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

BG, WHITE, GREEN = (0x12, 0x30, 0x3B), (255, 255, 255), (0x4C, 0xD9, 0x64)
SS = 4  # supersampling factor
OUT = Path(__file__).resolve().parent.parent / "docs/play-store/graphics"


def battery(d, cx, cy, scale):
    """Battery + bolt from the 108dp vector; (cx, cy) is where the glyph centre (54, 55) lands."""
    def p(x, y):
        return (cx + (x - 54) * scale, cy + (y - 55) * scale)
    d.rectangle([*p(48, 28), *p(60, 34.5)], fill=WHITE)
    d.rounded_rectangle([*p(36, 34), *p(72, 82)], radius=2 * scale, outline=WHITE, width=round(4 * scale))
    bolt = [(56, 42), (44, 59), (52, 59), (49, 73), (61, 55), (53, 55)]
    d.polygon([p(x, y) for x, y in bolt], fill=GREEN)


def font(size, bold=False):
    for path, idx in (("/System/Library/Fonts/HelveticaNeue.ttc", 1 if bold else 0),
                      ("/System/Library/Fonts/Helvetica.ttc", 1 if bold else 0),
                      ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 0)):
        try:
            return ImageFont.truetype(path, size * SS, index=idx)
        except OSError:
            continue
    return ImageFont.load_default()


def icon():
    w = 512 * SS
    im = Image.new("RGB", (w, w), BG)
    battery(ImageDraw.Draw(im), w / 2, w / 2, 6.4 * SS)
    return im.resize((512, 512), Image.LANCZOS)


def feature():
    w, h = 1024 * SS, 500 * SS
    im = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(im)
    battery(d, 200 * SS, h / 2, 5.0 * SS)
    d.text((380 * SS, 190 * SS), "BT BMS Monitor", font=font(64, True), fill=WHITE, anchor="lm")
    d.text((382 * SS, 275 * SS), "Live battery data over Bluetooth", font=font(34), fill=GREEN, anchor="lm")
    d.text((382 * SS, 335 * SS), "Current · cells · SoC · alerts", font=font(28), fill=(200, 215, 220), anchor="lm")
    return im.resize((1024, 500), Image.LANCZOS)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    icon().save(OUT / "icon-512.png", optimize=True)
    feature().save(OUT / "feature-1024x500.png", optimize=True)
    print("wrote", OUT)
