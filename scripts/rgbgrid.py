#!/usr/bin/env python3
"""Print a coarse RGB grid (and a few probe pixels) from a PNG.

Reads raw bytes straight from ImageMagick, so no image library is needed:
    scripts/rgbgrid.py shot.png [cols] [rows]
"""
import subprocess
import sys

path = sys.argv[1]
cols = int(sys.argv[2]) if len(sys.argv) > 2 else 16
rows = int(sys.argv[3]) if len(sys.argv) > 3 else 9

info = subprocess.run(["magick", "identify", "-format", "%w %h", path],
                      capture_output=True, text=True, check=True).stdout.split()
width, height = int(info[0]), int(info[1])

raw = subprocess.run(["magick", path, "-depth", "8", "rgb:-"],
                     capture_output=True, check=True).stdout

print("size %dx%d" % (width, height))
for row in range(rows):
    line = []
    for col in range(cols):
        x = col * width // cols
        y = row * height // rows
        i = (y * width + x) * 3
        line.append("%3d,%3d,%3d" % (raw[i], raw[i + 1], raw[i + 2]))
    print("  ".join(line))
