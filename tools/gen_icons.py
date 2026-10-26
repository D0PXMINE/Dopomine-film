"""Generates the launcher icon resources (adaptive vectors + legacy PNGs).

The mark: black rays coming out of the center, forming a circle (a nod to the
Kinopoisk logo), on a white tile — monochrome, like the rest of the app.
Run: python3 tools/gen_icons.py
"""
import math
import os

from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

RAYS = 24          # number of rays
DUTY = 0.55        # share of each step covered by a ray at the rim
INNER = 0.1        # inner radius as a share of the outer radius (keeps the center clean)
BLACK = "#FF0A0A0A"
WHITE = "#FFFFFFFF"


def ray_polygons(cx, cy, outer):
    """Each ray is a thin wedge from the inner circle to the rim."""
    step = 2 * math.pi / RAYS
    half = step * DUTY / 2
    inner = outer * INNER
    polys = []
    for k in range(RAYS):
        a = -math.pi / 2 + k * step
        polys.append([
            (cx + inner * math.cos(a), cy + inner * math.sin(a)),
            (cx + outer * math.cos(a - half), cy + outer * math.sin(a - half)),
            (cx + outer * math.cos(a + half), cy + outer * math.sin(a + half)),
        ])
    return polys


def rays_path(cx, cy, outer):
    d = ""
    for p in ray_polygons(cx, cy, outer):
        d += "M{:.3f},{:.3f}L{:.3f},{:.3f}L{:.3f},{:.3f}Z".format(*p[0], *p[1], *p[2])
    return d


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def vector(path_data, color, size=108):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size}dp"
    android:height="{size}dp"
    android:viewportWidth="{size}"
    android:viewportHeight="{size}">
    <path
        android:fillColor="{color}"
        android:pathData="{path_data}" />
</vector>
"""


def vectors():
    # Adaptive icon: 108x108 canvas, the safe zone is the central 66dp circle.
    rays = rays_path(54, 54, 30)
    write(os.path.join(ROOT, "drawable", "ic_launcher_foreground.xml"), vector(rays, BLACK))
    write(os.path.join(ROOT, "drawable", "ic_launcher_monochrome.xml"), vector(rays, WHITE))
    write(os.path.join(ROOT, "drawable", "ic_launcher_background.xml"), vector("M0,0h108v108h-108z", WHITE))
    # Splash (Android 12 style: the icon sits inside a circle of 2/3 of the canvas).
    splash = rays_path(54, 54, 24)
    write(os.path.join(ROOT, "drawable", "ic_splash.xml"), vector(splash, BLACK))
    write(os.path.join(ROOT, "drawable-night", "ic_splash.xml"), vector(splash, "#FFF1F1F1"))
    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""
    write(os.path.join(ROOT, "mipmap-anydpi-v26", "ic_launcher.xml"), adaptive)
    write(os.path.join(ROOT, "mipmap-anydpi-v26", "ic_launcher_round.xml"), adaptive)


def legacy_png(px, round_icon):
    ss = 4
    n = px * ss
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    mask = Image.new("L", (n, n), 0)
    dm = ImageDraw.Draw(mask)
    if round_icon:
        dm.ellipse([0, 0, n - 1, n - 1], fill=255)
    else:
        dm.rounded_rectangle([0, 0, n - 1, n - 1], radius=int(n * 0.22), fill=255)
    tile = Image.new("RGBA", (n, n), (255, 255, 255, 255))
    d = ImageDraw.Draw(tile)
    for poly in ray_polygons(n / 2, n / 2, n * 0.34):
        d.polygon(poly, fill=(10, 10, 10, 255))
    # hairline border so the white tile does not dissolve into light wallpapers
    if round_icon:
        d.ellipse([ss, ss, n - 1 - ss, n - 1 - ss], outline=(226, 226, 228, 255), width=ss * 2)
    else:
        d.rounded_rectangle([ss, ss, n - 1 - ss, n - 1 - ss], radius=int(n * 0.22), outline=(226, 226, 228, 255), width=ss * 2)
    img.paste(tile, (0, 0), mask)
    return img.resize((px, px), Image.LANCZOS)


def pngs():
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for density, px in sizes.items():
        folder = os.path.join(ROOT, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        legacy_png(px, False).save(os.path.join(folder, "ic_launcher.png"), optimize=True)
        legacy_png(px, True).save(os.path.join(folder, "ic_launcher_round.png"), optimize=True)


if __name__ == "__main__":
    vectors()
    pngs()
    print("icons generated")
