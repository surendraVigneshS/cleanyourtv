#!/usr/bin/env python3
"""Make the glass Home's pre-blurred images offline, so the TV never blurs anything at runtime.

Writes:
  themes/mitv-glass/wallpapers/glass.webp           1920x1080 wallpaper: four soft colour blobs (blue, magenta,
                                                    teal, violet) on #0a1326, blurred and saturated like the
                                                    mockup's CSS (blur 34px, saturate 130%)
  launcher/.../res/raw/glass_wallpaper.webp            the same image, the launcher's fallback when no theme is pushed
  launcher/.../res/drawable-tvdpi/tile_shadow.9.png       tile shadow at rest: 0 12px 30px black 28% (SPEC section 2)
  launcher/.../res/drawable-tvdpi/tile_shadow_focus.9.png focused tile shadow: 0 28px 56px black 55%

Shadows are nine-patches drawn at tvdpi (1.33x, the MiTV-AXSO0's density), where one design px of the
1920x1080 spec is 2/3 of a device px. The launcher draws them behind each tile, bounds outset by
GlassTile.SHADOW_PAD_* (keep those in step with the pads printed here).

Usage: tools/venv/bin/python tools/glass_assets.py [--wallpaper OUT.webp]   (needs Pillow: pip install pillow)
"""
import argparse
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "launcher/app/src/main/res"
W, H = 1920, 1080
# Device px per design px on the target TV: 1280x720 panel for a 1920x1080 design.
TVDPI = 1280 / 1920

# The mockup's blobs: centre as a fraction of the screen, colour, radius in its half-size canvas (1020 wide).
BLOBS = [(0.12, 0.20, "#1d4ed8", 360), (0.55, 0.75, "#be185d", 340),
         (0.95, 0.80, "#0ea5a4", 280), (0.30, 0.90, "#7c3aed", 260)]


def hex_rgb(c):
    return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5))


def radial(size, cx, cy, r, rgb, alpha):
    """An RGBA layer: rgb at the centre fading linearly to transparent at radius r (canvas createRadialGradient)."""
    w, h = size
    layer = Image.new("RGBA", size, rgb + (0,))
    mask = Image.new("L", size, 0)
    px = mask.load()
    for y in range(h):
        dy = y - cy
        for x in range(w):
            d = (( x - cx) ** 2 + dy * dy) ** 0.5
            if d < r:
                px[x, y] = int(round(255 * alpha * (1 - d / r)))
    layer.putalpha(mask)
    return layer


def wallpaper():
    # Paint at half size (as the mockup does) for speed, then scale up: the blur hides the resampling.
    hw, hh = W // 2, H // 2
    scale = hw / 1020
    img = Image.new("RGBA", (hw, hh), hex_rgb("#0a1326") + (255,))
    for fx, fy, colour, r in BLOBS:
        img = Image.alpha_composite(img, radial((hw, hh), fx * hw, fy * hh, r * scale, hex_rgb(colour), 0.75))
    img = img.convert("RGB").resize((W, H), Image.LANCZOS)
    img = img.filter(ImageFilter.GaussianBlur(34))
    return ImageEnhance.Color(img).enhance(1.3)


def shadow_ninepatch(blur_px, alpha, radius_px):
    """A nine-patch of a black rounded rectangle blurred by blur_px (CSS blur radius; sigma = blur / 2)."""
    sigma = blur_px / 2
    pad = int(round(sigma * 3))
    core = 2 * radius_px + 2                     # two corners and a 2 px stretchable middle
    size = core + 2 * pad
    shape = Image.new("L", (size, size), 0)
    ImageDraw.Draw(shape).rounded_rectangle([pad, pad, pad + core - 1, pad + core - 1], radius=radius_px,
                                            fill=int(round(255 * alpha)))
    shape = shape.filter(ImageFilter.GaussianBlur(sigma))
    # Under the tile the shadow is never seen (the tile covers it) but would still be blended on every
    # frame through the translucent glass, darkening it: cut the tile's own area out.
    hole = Image.new("L", (size, size), 0)
    ImageDraw.Draw(hole).rounded_rectangle([pad, pad, pad + core - 1, pad + core - 1], radius=radius_px, fill=255)
    shape = ImageChops.subtract(shape, hole)
    rgba = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    rgba.putalpha(shape)
    # Nine-patch frame: 1 px border; top/left mark the stretchable middle, bottom/right the content (= the tile).
    out = Image.new("RGBA", (size + 2, size + 2), (0, 0, 0, 0))
    out.paste(rgba, (1, 1))
    d = ImageDraw.Draw(out)
    mid = 1 + pad + radius_px
    black = (0, 0, 0, 255)
    d.line([(mid, 0), (mid + 1, 0)], fill=black)
    d.line([(0, mid), (0, mid + 1)], fill=black)
    d.line([(1 + pad, size + 1), (pad + core, size + 1)], fill=black)
    d.line([(size + 1, 1 + pad), (size + 1, pad + core)], fill=black)
    return out, pad


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--wallpaper", default=str(ROOT / "themes/mitv-glass/wallpapers/glass.webp"))
    args = ap.parse_args()

    wall = wallpaper()
    out = Path(args.wallpaper)
    out.parent.mkdir(parents=True, exist_ok=True)
    wall.save(out, "WEBP", quality=88, method=6)
    fallback = RES / "raw/glass_wallpaper.webp"
    fallback.parent.mkdir(parents=True, exist_ok=True)
    wall.save(fallback, "WEBP", quality=88, method=6)
    print(f"{out} ({out.stat().st_size} B), {fallback} ({fallback.stat().st_size} B)")

    tvdpi = RES / "drawable-tvdpi"
    tvdpi.mkdir(parents=True, exist_ok=True)
    radius = int(round(22 * TVDPI))
    for name, blur, alpha in (("tile_shadow", 30, 0.28), ("tile_shadow_focus", 56, 0.55)):
        img, pad = shadow_ninepatch(blur * TVDPI, alpha, radius)
        path = tvdpi / f"{name}.9.png"
        img.save(path, optimize=True)
        print(f"{path} ({path.stat().st_size} B) pad {pad} px at tvdpi")


if __name__ == "__main__":
    main()
