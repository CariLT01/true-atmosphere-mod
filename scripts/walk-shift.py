#!/usr/bin/env python3
"""Check that the sky is fixed in the world while the player really walks.

`ATMO_SELFTEST_WALK=<frames>` puts the player on a barrier platform in the
void, clears the clouds, points the camera at the sunrise sun, and saves
`<tag>_walk<i>.png` with the walk phase of the frame in the log.

The bob is a camera rotation, so a sky that is fixed in the world must change
pixels between two frames at the same position but different bob phases. A
sky that is fixed to the screen does not. The sun is the clean landmark: it is
drawn at the same distance as the stars, and its screen position in one frame
versus the other only changes if the sky followed the bob.

    scripts/walk-shift.py <screenshots-dir> <tag> [--log <log>]

Exit status is 0 when the sky moved, 2 when it did not (that is the bug).
"""
import argparse
import math
import os
import re
import sys

from PIL import Image, ImageChops, ImageStat

WALK = re.compile(r"walk (\d+): x (-?[\d.]+) .*? phase (-?[\d.]+)")


def frames(directory, tag, log):
    found = {}
    for name in os.listdir(directory):
        match = re.fullmatch(re.escape(tag) + r"_walk(\d+)\.png", name)
        if match:
            found[int(match.group(1))] = os.path.join(directory, name)

    poses = {}
    if log:
        text = open(log, errors="replace").read()
        for match in WALK.finditer(text):
            poses[int(match.group(1))] = (float(match.group(2)), float(match.group(3)))

    usable = [index for index in sorted(found) if index in poses]
    return found, poses, usable


def separation(poses, a, b, period=2.0):
    delta = abs(poses[a][1] - poses[b][1]) % period
    return min(delta, period - delta)


def sun_centre(image):
    """Centroid of the sun: the only near-white blob in the frame."""
    width, height = image.size
    crop = image.convert("RGB").crop((0, 0, width, int(height * 0.6)))
    pixels = crop.load()
    sx = sy = count = 0
    for y in range(crop.height):
        for x in range(crop.width):
            r, g, b = pixels[x, y]
            if r > 235 and g > 235 and b > 225:
                sx += x
                sy += y
                count += 1
    if not count:
        return None
    return (sx / count, sy / count, count)


def difference(a, b):
    diff = ImageChops.difference(a.convert("L"), b.convert("L"))
    histogram = diff.histogram()
    total = sum(histogram)
    return ImageStat.Stat(diff).mean[0], sum(histogram[9:]) * 100.0 / max(1, total)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory")
    parser.add_argument("tag")
    parser.add_argument("--log", default=None)
    args = parser.parse_args()

    found, poses, usable = frames(args.directory, args.tag, args.log)
    if len(usable) < 2:
        print("no walk frames with poses for {}".format(args.tag))
        return 1

    same = [(a, b) for i, a in enumerate(usable) for b in usable[i + 1:]
            if abs(poses[a][0] - poses[b][0]) <= 0.05]
    if not same:
        print("no two frames at the same position for {}".format(args.tag))
        return 1

    widest = max(((separation(poses, a, b), a, b) for a, b in same), key=lambda p: p[0])
    narrowest = min(((separation(poses, a, b), a, b) for a, b in same), key=lambda p: p[0])

    result = {}
    for label, (angle, a, b) in (("bob", widest), ("same", narrowest)):
        one = Image.open(found[a])
        two = Image.open(found[b])
        mean, changed = difference(one, two)
        centre_a = sun_centre(one)
        centre_b = sun_centre(two)
        move = None
        if centre_a and centre_b:
            move = math.hypot(centre_a[0] - centre_b[0], centre_a[1] - centre_b[1])
        print("{:>4} {:>7.2f}  mean {:>6.3f}  changed {:>6.2f}%  sun {}".format(
            "{}->{}".format(a, b), angle, mean, changed,
            "{:.2f} px".format(move) if move is not None else "-"))
        print("     x {} vs {}".format(poses[a][0], poses[b][0]))
        result[label] = (move or 0.0, changed)

    move, changed = result["bob"]
    control_move, control_changed = result.get("same", (0.0, 0.0))
    moving = move >= 0.5 or changed >= 0.1
    print()
    if moving and control_move < 0.5 and control_changed < 0.1:
        print("WORLD-FIXED: sky moves {:.2f} px / {:.2f}% with the bob; same-pose control {:.2f} px".format(
            move, changed, control_move))
        return 0
    print("SCREEN-FIXED: sky moves only {:.2f} px / {:.2f}% with the bob; same-pose control {:.2f} px".format(
        move, changed, control_move))
    return 2


if __name__ == "__main__":
    sys.exit(main())
