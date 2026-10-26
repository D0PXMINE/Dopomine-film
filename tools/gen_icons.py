"""Generates the launcher icon resources (adaptive vectors + legacy PNGs).

Geometry: a film reel (disc with five round holes) with a play-triangle cut-out,
filled with the brand gradient, on a dark background.
Run: python3 tools/gen_icons.py
"""
import math
import os

from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
GRAD = ["#FF8B5CF6", "#FFFF4D6D", "#FFFF9B42"]  # violet -> pink -> orange
BG = "#FF0E0E17"


def reel_shapes(cx, cy, scale):
    """Returns (disc, holes, triangle) in a coordinate system where the disc radius is 30*scale."""
    disc = (cx, cy, 30 * scale)
    holes = []
    for k in range(5):
        a = math.radians(-90 + k * 72)
        holes.append((cx + 17 * scale * math.cos(a), cy + 17 * scale * math.sin(a), 6.5 * scale))
    r = 7.5 * scale
    tri = [(cx + r, cy), (cx - r / 2, cy + r * 0.866), (cx - r / 2, cy - r * 0.866)]
    return disc, holes, tri


def circle_path(cx, cy, r):
    return (f"M{cx - r:.3f},{cy:.3f}a{r:.3f},{r:.3f} 0 1,0 {2 * r:.3f},0"
            f"a{r:.3f},{r:.3f} 0 1,0 {-2 * r:.3f},0Z")


def reel_path(cx, cy, scale):
    disc, holes, tri = reel_shapes(cx, cy, scale)
    d = circle_path(*disc)
    for h in holes:
        d += circle_path(*h)
    d += "M{:.3f},{:.3f}L{:.3f},{:.3f}L{:.3f},{:.3f}Z".format(*tri[0], *tri[1], *tri[2])
    return d


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def vectors():
    # Adaptive icon: 108x108 canvas, safe zone is the central 66dp circle.
    d = reel_path(54, 54, 32 / 30)
    gradient = f"""
            <aapt:attr name="android:fillColor">
                <gradient
                    android:type="linear"
                    android:startX="22" android:startY="86"
                    android:endX="86" android:endY="22">
                    <item android:offset="0" android:color="{GRAD[0]}" />
                    <item android:offset="0.5" android:color="{GRAD[1]}" />
                    <item android:offset="1" android:color="{GRAD[2]}" />
                </gradient>
            </aapt:attr>"""
    write(os.path.join(ROOT, "drawable", "ic_launcher_foreground.xml"), f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillType="evenOdd"
        android:pathData="{d}">{gradient}
    </path>
</vector>
""")
    write(os.path.join(ROOT, "drawable", "ic_launcher_monochrome.xml"), f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="{d}" />
</vector>
""")
    write(os.path.join(ROOT, "drawable", "ic_launcher_background.xml"), f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="54" android:centerY="40"
                android:gradientRadius="80">
                <item android:offset="0" android:color="#FF1E1A2E" />
                <item android:offset="1" android:color="{BG}" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
""")
    # Splash icon (Android 12 style): the icon sits inside a 2/3 circle mask.
    write(os.path.join(ROOT, "drawable", "ic_splash.xml"), f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillType="evenOdd"
        android:pathData="{reel_path(54, 54, 26 / 30)}">{gradient}
    </path>
</vector>
""")
    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""
    write(os.path.join(ROOT, "mipmap-anydpi-v26", "ic_launcher.xml"), adaptive)
    write(os.path.join(ROOT, "mipmap-anydpi-v26", "ic_launcher_round.xml"), adaptive)


def hex_rgb(h):
    h = h.lstrip("#")[-6:]
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def gradient_image(size):
    """Diagonal violet -> pink -> orange gradient, bottom-left to top-right."""
    img = Image.new("RGB", (size, size))
    px = img.load()
    stops = [hex_rgb(c) for c in GRAD]
    for y in range(size):
        for x in range(size):
            t = (x + (size - y)) / (2 * size)
            if t < 0.5:
                a, b, u = stops[0], stops[1], t / 0.5
            else:
                a, b, u = stops[1], stops[2], (t - 0.5) / 0.5
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * u) for i in range(3))
    return img


def legacy_png(px, round_icon):
    ss = 4
    n = px * ss
    bg = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    mask_bg = Image.new("L", (n, n), 0)
    dm = ImageDraw.Draw(mask_bg)
    if round_icon:
        dm.ellipse([0, 0, n - 1, n - 1], fill=255)
    else:
        dm.rounded_rectangle([0, 0, n - 1, n - 1], radius=int(n * 0.22), fill=255)
    bg.paste(Image.new("RGBA", (n, n), hex_rgb(BG) + (255,)), (0, 0), mask_bg)

    reel = Image.new("L", (n, n), 0)
    dr = ImageDraw.Draw(reel)
    disc, holes, tri = reel_shapes(n / 2, n / 2, (n * 0.36) / 30)
    cx, cy, r = disc
    dr.ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)
    for hx, hy, hr in holes:
        dr.ellipse([hx - hr, hy - hr, hx + hr, hy + hr], fill=0)
    dr.polygon(tri, fill=0)
    grad = gradient_image(n).convert("RGBA")
    bg.paste(grad, (0, 0), reel)
    return bg.resize((px, px), Image.LANCZOS)


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
