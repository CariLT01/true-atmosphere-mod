#!/usr/bin/env python3
"""Check that the compatibility mixins still have something to apply to.

The Better Clouds mixins target a class by name, which is what keeps the mod a
dev-only dependency - but it also means a release that renames or moves that
class leaves the integration silently doing nothing. This reads the jars
`scripts/fetch_compat.py` downloaded and reports, per Minecraft version and
loader, which of the two target locations is present and whether it still
carries a `getEffectTint` method.

    scripts/fetch_compat.py          # once, to fill .compat/
    scripts/check_compat.py          # then this; exits non-zero if a jar drifted

A version with no Better Clouds build is reported as such and does not fail the
run: that version simply has nothing to integrate with.
"""

import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JARS = os.path.join(ROOT, ".compat", "betterclouds")

TARGETS = (
    ("legacy", "com/qendolin/betterclouds/clouds/EffectTintProvider.class"),
    ("modern", "com/qendolin/betterclouds/mixin/provider/EffectTintProvider.class"),
)
METHOD = b"getEffectTint"


def inspect(path):
    """('legacy'|'modern'|None, has_method) for one jar."""
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        for label, entry in TARGETS:
            if entry in names:
                return label, METHOD in jar.read(entry)
    return None, False


def rows():
    found = []
    for name in sorted(os.listdir(JARS)) if os.path.isdir(JARS) else []:
        if not name.endswith(".jar"):
            continue
        version, loader, _ = name[:-len(".jar")].rsplit("-", 2)
        found.append((version, loader, os.path.join(JARS, name)))
    return found


def main():
    if not os.path.isdir(JARS):
        print("Nothing fetched yet: run scripts/fetch_compat.py first")
        return 2

    failures = []
    print("%-9s %-9s %-8s %-8s %s" % ("minecraft", "loader", "target", "method", "jar"))
    for version, loader, path in rows():
        target, method = inspect(path)
        if target is None:
            failures.append("%s/%s: no EffectTintProvider in either package" % (version, loader))
        elif not method:
            failures.append("%s/%s: %s has no getEffectTint" % (version, loader, target))
        print("%-9s %-9s %-8s %-8s %s" % (version, loader, target or "-", "yes" if method else "NO",
                                          os.path.basename(path)))

    print()
    if failures:
        print("The mixins would not apply on:")
        for failure in failures:
            print("  " + failure)
        return 1
    print("Every fetched Better Clouds build has a target for its mixin.")

    mixin_config = os.path.join(ROOT, "src/main/resources/trueatmosphere.compat.mixins.json")
    with open(mixin_config, encoding="utf-8") as handle:
        listed = [line for line in handle if "Mixin" in line and "package" not in line]
    print("Compat mixin config lists %d classes." % len(listed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
