"""Generates the launcher icon, splash and in-app logo (vector drawables + legacy PNGs).

The mark is an eight-ray sparkle: four long rays, four shorter diagonals, all thin and
flaring smoothly into a round core (the width follows a hyperbola, measured from the
reference sketch). Black on a white tile, monochrome like the rest of the app.
Run: python3 tools/gen_icons.py
"""
import math
import os

from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

BLACK = "#FF0A0A0A"
WHITE = "#FFFFFFFF"

# Thicker strokes for small renderings, so the thin rays survive at launcher sizes.
WEIGHT_LAUNCHER = 1.25
WEIGHT_SPLASH = 1.15
WEIGHT_LOGO = 1.5

# ---- geometry (unit: length of the long rays) --------------------------------------------

LONG, SHORT = 1.0, 0.71          # ray lengths (long rays = 1)
D0 = 0.16                        # where the flare would blow up (inside the core)
CARD = (0.0171, 0.00503)         # half-width = a + b / (d - D0), measured from the sketch
DIAG = (0.0125, 0.0035)
CORE = 0.2
SMOOTH = 10.0                    # p-norm: rounds the dips where two rays meet


def half_width(d, length, a, b, weight):
    sector = d * math.tan(math.radians(40))      # keeps the flare finite; neighbours overlap and merge
    if d <= D0 + 1e-6:
        return sector
    if d > length:
        return 0.0
    w = (a + b / (d - D0)) * weight
    # the outer part thins out towards the tip, like a brush stroke
    u = (d - 0.55 * length) / (0.45 * length)
    if u > 0:
        w *= 1 - 0.4 * u * u
    w = min(w, sector)
    cap = w if d <= length else 0.0
    cap = a * weight * 0.7                         # rounded tip
    start = length - cap
    if d > start:
        t = (d - start) / cap
        w *= math.sqrt(max(0.0, 1 - t * t))
    return w


def ray_extent(delta, length, a, b, weight):
    """Distance from the centre to the edge of one ray, looking at an angle `delta` off its axis."""
    c, s = math.cos(delta), abs(math.sin(delta))
    if c <= 0:
        return 0.0
    def inside(r):
        return r * s <= half_width(r * c, length, a, b, weight) + 1e-9
    if not inside(D0 + 0.01):
        return 0.0
    lo, hi = D0 + 0.01, length / c
    if inside(hi):
        return hi
    for _ in range(50):
        mid = (lo + hi) / 2
        if inside(mid):
            lo = mid
        else:
            hi = mid
    return lo


def radius(phi, weight):
    total = CORE ** SMOOTH
    for i in range(8):
        axis = -math.pi / 2 + i * math.pi / 4
        delta = (phi - axis + math.pi) % (2 * math.pi) - math.pi
        length, (a, b) = (LONG, CARD) if i % 2 == 0 else (SHORT, DIAG)
        r = ray_extent(delta, length, a, b, weight)
        total += r ** SMOOTH
    return total ** (1 / SMOOTH)


def outline(weight=1.0):
    """Closed outline (unit = long ray length), sampled densely around each thin ray."""
    phis = set()
    for i in range(8):
        axis = -math.pi / 2 + i * math.pi / 4
        for k in range(-300, 301):
            phis.add(axis + math.radians(k * 0.02))          # ±6° fine
    for k in range(1440):
        phis.add(math.radians(k * 0.25))
    pts = []
    for phi in sorted(p % (2 * math.pi) for p in phis):
        r = radius(phi, weight)
        pts.append((r * math.cos(phi), r * math.sin(phi)))
    return simplify(pts, 0.0012)


def simplify(pts, tol):
    """Douglas-Peucker on a closed polyline."""
    def rdp(seg):
        if len(seg) < 3:
            return seg
        (x1, y1), (x2, y2) = seg[0], seg[-1]
        dx, dy = x2 - x1, y2 - y1
        n = math.hypot(dx, dy) or 1e-12
        best, idx = 0.0, 0
        for i in range(1, len(seg) - 1):
            x0, y0 = seg[i]
            dist = abs(dy * x0 - dx * y0 + x2 * y1 - y2 * x1) / n
            if dist > best:
                best, idx = dist, i
        if best <= tol:
            return [seg[0], seg[-1]]
        return rdp(seg[: idx + 1])[:-1] + rdp(seg[idx:])
    # a closed ring has no chord to measure against: simplify it as two halves, tip to tip
    top = min(range(len(pts)), key=lambda i: pts[i][1])
    ring = pts[top:] + pts[:top]
    bottom = max(range(len(ring)), key=lambda i: ring[i][1])
    first = rdp(ring[: bottom + 1])
    second = rdp(ring[bottom:] + [ring[0]])
    return first[:-1] + second[:-1]


# ---- output ---------------------------------------------------------------------------------

def path_data(cx, cy, size, weight):
    pts = outline(weight)
    d = "M{:.2f},{:.2f}".format(cx + pts[0][0] * size, cy + pts[0][1] * size)
    for x, y in pts[1:]:
        d += "L{:.2f},{:.2f}".format(cx + x * size, cy + y * size)
    return d + "Z"


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
    mark = path_data(54, 54, 31, WEIGHT_LAUNCHER)
    write(os.path.join(ROOT, "drawable", "ic_launcher_foreground.xml"), vector(mark, BLACK))
    write(os.path.join(ROOT, "drawable", "ic_launcher_monochrome.xml"), vector(mark, WHITE))
    write(os.path.join(ROOT, "drawable", "ic_launcher_background.xml"), vector("M0,0h108v108h-108z", WHITE))
    # Splash (Android 12 style: the icon sits inside a circle of 2/3 of the canvas).
    splash = path_data(54, 54, 25, WEIGHT_SPLASH)
    write(os.path.join(ROOT, "drawable", "ic_splash.xml"), vector(splash, BLACK))
    write(os.path.join(ROOT, "drawable-night", "ic_splash.xml"), vector(splash, "#FFF1F1F1"))
    # In-app logo: fills its box, tinted from Compose.
    write(os.path.join(ROOT, "drawable", "ic_logo.xml"), vector(path_data(12, 12, 11.6, WEIGHT_LOGO), BLACK, size=24))
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
    size = n * 0.36
    d.polygon([(n / 2 + x * size, n / 2 + y * size) for x, y in outline(WEIGHT_LAUNCHER)], fill=(10, 10, 10, 255))
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
