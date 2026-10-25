#!/usr/bin/env python3
"""Coarse character map of a screenshot: reads averaged cells straight from ImageMagick.

    scripts/shotmap.py shot.png [cols] [rows]

Legend: W near-white, w pale, S warm (sun), # black, * very dark, d dark blue,
c mid blue, b bright blue, - mid grey, + bright grey.
"""
import subprocess
import sys

path = sys.argv[1]
cols = int(sys.argv[2]) if len(sys.argv) > 2 else 60
rows = int(sys.argv[3]) if len(sys.argv) > 3 else 24

raw = subprocess.run(["magick", path, "-resize", "%dx%d!" % (cols, rows),
                      "-depth", "8", "rgb:-"], capture_output=True, check=True).stdout

out = []
for cell in range(cols * rows):
    r, g, b = raw[cell * 3], raw[cell * 3 + 1], raw[cell * 3 + 2]
    lum = (r + g + b) / 3
    hi, lo = max(r, g, b), min(r, g, b)
    if lum > 235 and hi - lo < 30:
        ch = "W"
    elif lum > 200 and hi - lo < 40:
        ch = "w"
    elif r > b + 25 and r > 110:
        ch = "S"
    elif lum < 12:
        ch = "#"
    elif lum < 22:
        ch = "*"
    elif lum < 40:
        ch = "d"
    elif lum < 90:
        ch = "c"
    elif b > r:
        ch = "b"
    elif lum > 150:
        ch = "+"
    else:
        ch = "-"
    out.append(ch)
    if (cell + 1) % cols == 0:
        out.append("\n")
print("".join(out), end="")
