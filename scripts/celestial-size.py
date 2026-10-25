#!/usr/bin/env python3
"""Measures the sun, moon and star sizes in self-test screenshots.

    scripts/celestial-size.py shot.png [shot.png ...]

Reads the shots the self-test takes with

    SELFTEST_SHOTS="6000@0:-89:250,18000@0:-88:250,18000@90:-30:250"

(a sun overhead in daylight, the full moon overhead at midnight, and a star field) and prints one
line per image: the sun's or moon's width in pixels and as a fraction of the frame height, plus the
star speck count and median area for the star shot. Comparing eras means comparing those fractions,
not the raw pixel counts, because the window size is not the same on every version.

The disc measurement is a thresholded blob, so it reports the *visible* corona as well as the
sprite: the sun texture is a soft square that ends in a bright core, and two renderers that draw
the same quad with different blending will not produce the same blob. It is a regression check, not
a photometer - the target is that every era lands within a couple of percent of vanilla's geometry
(sun half-size 30 at distance 100, moon 20, stars 0.15 + rand * 0.1), which is what the 1.21.1
build draws.
"""
import math
import subprocess
import sys
from collections import deque

FOV_DEGREES = 70.0

EDGE_FRACTION = 0.15

def read(path):
    w, h = (int(v) for v in subprocess.run(
        ["magick", "identify", "-format", "%w %h", path],
        capture_output=True, text=True, check=True).stdout.split())
    raw = subprocess.run(["magick", path, "-depth", "8", "rgb:-"],
                         capture_output=True, check=True).stdout
    return w, h, raw

def luminance(raw, i):
    return (raw[3 * i] + raw[3 * i + 1] + raw[3 * i + 2]) / 3.0

def largest_blob(w, h, mask):
    seen = bytearray(w * h)
    best = (0, 0, 0, 0, 0)
    for start in range(w * h):
        if not mask[start] or seen[start]:
            continue
        queue = deque([start])
        seen[start] = 1
        n = 0
        x0 = x1 = start % w
        y0 = y1 = start // w
        while queue:
            p = queue.popleft()
            n += 1
            x, y = p % w, p // w
            x0, x1 = min(x0, x), max(x1, x)
            y0, y1 = min(y0, y), max(y1, y)
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h:
                    q = ny * w + nx
                    if mask[q] and not seen[q]:
                        seen[q] = 1
                        queue.append(q)
        if n > best[0]:
            best = (n, x0, y0, x1, y1)
    return best

def disc(path):
    """Width of the bright disc at half of its peak above the sky, in pixels, plus the frame height."""
    w, h, raw = read(path)
    centre = (h // 2) * w + w // 2

    corner = [luminance(raw, i) for i in (0, w - 1, (h - 1) * w, h * w - 1)]
    sky = sum(corner) / len(corner)

    peak = max(luminance(raw, centre), 0.0)

    edge = sky + (peak - sky) * EDGE_FRACTION

    mask = bytearray(w * h)
    for y in range(h):
        row = y * w
        for x in range(w):
            if luminance(raw, row + x) >= edge:
                mask[row + x] = 1
    n, x0, y0, x1, y1 = largest_blob(w, h, mask)
    return w, h, x1 - x0 + 1, y1 - y0 + 1, n, peak, sky

def stars(path):
    """Star specks: blobs of 2..400 px above a luminance floor, ignoring the large sky features."""
    w, h, raw = read(path)
    mask = bytearray(w * h)
    for y in range(int(h * 0.05), int(h * 0.95)):
        row = y * w
        for x in range(int(w * 0.05), int(w * 0.95)):
            if luminance(raw, row + x) >= 45.0:
                mask[row + x] = 1

    seen = bytearray(w * h)
    sizes = []
    for start in range(w * h):
        if not mask[start] or seen[start]:
            continue
        queue = deque([start])
        seen[start] = 1
        n = 0
        while queue:
            p = queue.popleft()
            n += 1
            x, y = p % w, p // w
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h:
                    q = ny * w + nx
                    if mask[q] and not seen[q]:
                        seen[q] = 1
                        queue.append(q)
        if 2 <= n <= 400:
            sizes.append(n)

    sizes.sort()
    median = sizes[len(sizes) // 2] if sizes else 0
    return w, h, len(sizes), median, (sum(sizes) / len(sizes)) if sizes else 0.0

def main(paths):
    print("%-42s %9s %7s %8s %7s  %s"
          % ("shot", "frame", "disc px", "% height", "deg", "detail"))
    for path in paths:
        name = path.rsplit("/", 1)[-1]
        low = name.lower()
        frame = "%dx%d" % (read(path)[0], read(path)[1])
        if "_18000_yaw90_" in low:
            _, _, count, median, mean = stars(path)
            print("%-42s %9s %7s %8s %7s  specks %d, median %.0f px, mean %.1f px"
                  % (name, frame, "-", "-", "-", count, median, mean))
            continue
        w, h, bw, bh, n, peak, sky = disc(path)
        focal = (h / 2.0) / math.tan(math.radians(FOV_DEGREES / 2.0))
        degrees = math.degrees(2.0 * math.atan(max(bw, bh) / 2.0 / focal))
        print("%-42s %9s %7d %8.2f %7.2f  blob %d px, peak %.0f, sky %.0f"
              % (name, frame, max(bw, bh), 100.0 * max(bw, bh) / h, degrees, n, peak, sky))

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__.strip())
        raise SystemExit(2)
    main(sys.argv[1:])
