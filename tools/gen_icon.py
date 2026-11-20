#!/usr/bin/env python3
"""70a launcher icon — generated from the user-supplied logo
(tools/logo_source.jpg: anime character, red spiral mask, black cloak
with red cloud, on a black circular background).

Outputs: square + round launcher icons at all densities, adaptive-icon
foreground (logo centred in the safe zone, black background) and the
in-app logo used on the login screen.
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
SRC = os.path.join(ROOT, "tools", "logo_source.jpg")

SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def centre_square(im):
    """Crop to a centred square (logo is already square; kept for safety)."""
    w, h = im.size
    side = min(w, h)
    left = (w - side) // 2
    top = (h - side) // 2
    return im.crop((left, top, left + side, top + side))


def circle_crop(im):
    """Circular crop of a square image (transparent corners)."""
    im = im.convert("RGBA")
    mask = Image.new("L", im.size, 0)
    d = ImageDraw.Draw(mask)
    d.ellipse([0, 0, im.size[0], im.size[1]], fill=255)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def main():
    src = Image.open(SRC).convert("RGB")
    sq = centre_square(src)

    for folder, size in SIZES.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        sq.resize((size, size), Image.LANCZOS).save(
            os.path.join(d, "ic_launcher.png"))
        circle_crop(sq).resize((size, size), Image.LANCZOS).save(
            os.path.join(d, "ic_launcher_round.png"))
        print(f"  {folder}: {size}px square + round")

    # Adaptive-icon foreground: 432x432, logo scaled to ~72% so the whole
    # circle stays inside the 66dp safe zone; background is solid black.
    fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    logo = sq.resize((311, 311), Image.LANCZOS)
    fg.paste(logo, ((432 - 311) // 2, (432 - 311) // 2))
    fg_path = os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_foreground.png")
    fg.save(fg_path)
    print("  adaptive foreground: 432px")

    # In-app logo for the login screen.
    sq.resize((512, 512), Image.LANCZOS).save(
        os.path.join(RES, "drawable", "logo.png"))
    print("  drawable/logo.png: 512px")


if __name__ == "__main__":
    main()
