#!/usr/bin/env python3
"""Fetch the optional mods the development runs are tested against.

The compatibility mixins under `src/main/.../mixin/compat` are written against a
class name, not a compile-time dependency, so the build never needs these jars.
The dev client does: start it without Better Clouds and the mixin has nothing to
apply to. This downloads the matching build for one Minecraft version per loader
into `.compat/` (not committed), and the version scripts add whatever is there to
the run classpath.

Better Clouds pulls `yet_another_config_lib_v3` in as a hard dependency, so that
is fetched too - without it the dev client refuses to start at all, and every
run would fail on a missing-dependency screen instead of a sky.

Every jar is named `<mc>-<loader>-<mod>.jar`. The mod name in the file name is
not decoration: Loom keys the remapped copy of a `files(...)` dependency by it, so
two mods that both downloaded as `1.21.1-fabric.jar` collapse into one entry and
only the first of them ever reaches the client.

Usage:
    scripts/fetch_compat.py                  # every version in versions.json
    scripts/fetch_compat.py 1.21.1 26.3      # just these
    scripts/fetch_compat.py --loader fabric 1.21.5
    scripts/fetch_compat.py --force          # re-download what is already there

Modrinth's Maven is referenced in the docs but does not resolve from every
network, so the jars come from the same v2 API the site uses and are pinned by
the version id that API returns.
"""

import argparse
import json
import os
import urllib.parse
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, ".compat")

PROJECTS = (
    ("5srFLIaK", "betterclouds"),
    ("1eAoo2KR", "yacl"),
)

API = "https://api.modrinth.com/v2/project/%s/version"


def wanted(loader):
    with open(os.path.join(ROOT, "versions.json"), encoding="utf-8") as handle:
        return [entry["mc"] for entry in json.load(handle)["versions"]]


def newest(project, mc, loader):
    query = ("%s?game_versions=%s&loaders=%s"
             % (API % project, urllib.parse.quote(json.dumps([mc])), urllib.parse.quote(json.dumps([loader]))))
    request = urllib.request.Request(query, headers={"User-Agent": "trueatmosphere dev env"})
    versions = json.load(urllib.request.urlopen(request, timeout=30))
    if not versions:
        return None
    return versions[0]


def unpack_nested(target, name, mc, loader):
    """Put a mod's bundled jars beside it, named so they cannot collide.

    Better Clouds bundles MixinSquared in `META-INF/jars`, and a production
    launcher unpacks that for it. A Fabric development run does not: the bundle
    stays inside the jar, the loader cannot find the class, and the client dies
    before the main menu with `ClassNotFoundException: ...MixinSquaredBootstrap`.
    Extracting the bundle gives the dev client exactly what a player's instance
    would have.

    NeoForge needs no help here and must not be helped: its JarJar unpacks the
    same bundle into a module of its own, so a second copy on the classpath fails
    module resolution with two modules exporting one package. Fabric only.
    """
    inner_dir = os.path.join(OUT, name, "nested")
    prefix = "%s-%s-%s-" % (mc, loader, name)
    if loader != "fabric":
        if os.path.isdir(inner_dir):
            for stale in os.listdir(inner_dir):
                if stale.startswith(prefix):
                    os.remove(os.path.join(inner_dir, stale))
        return
    os.makedirs(inner_dir, exist_ok=True)
    for stale in os.listdir(inner_dir):
        if stale.startswith(prefix):
            os.remove(os.path.join(inner_dir, stale))
    with zipfile.ZipFile(target) as jar:
        for entry in jar.namelist():
            if entry.startswith("META-INF/jars/") and entry.endswith(".jar"):
                inner = os.path.join(inner_dir, prefix + os.path.basename(entry))
                with jar.open(entry) as source, open(inner, "wb") as handle:
                    handle.write(source.read())


def fetch(project, name, mc, loader, force):
    target = os.path.join(OUT, name, "%s-%s-%s.jar" % (mc, loader, name))
    if os.path.isfile(target) and not force:
        print("  %-8s %-8s %-24s (cached)" % (mc, loader, name))
        unpack_nested(target, name, mc, loader)
        return True
    version = newest(project, mc, loader)
    if version is None:
        print("  %-8s %-8s no build published" % (mc, loader))
        return False
    files = [f for f in version["files"] if f.get("primary")] or version["files"]
    os.makedirs(os.path.dirname(target), exist_ok=True)
    request = urllib.request.Request(files[0]["url"], headers={"User-Agent": "trueatmosphere dev env"})
    with urllib.request.urlopen(request, timeout=120) as response, open(target, "wb") as handle:
        handle.write(response.read())
    print("  %-8s %-8s %-16s %s" % (mc, loader, version["version_number"], os.path.basename(target)))
    unpack_nested(target, name, mc, loader)
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("versions", nargs="*", help="Minecraft versions (default: all in versions.json)")
    parser.add_argument("--loader", choices=("fabric", "neoforge", "both"), default="both")
    parser.add_argument("--force", action="store_true", help="re-download jars that already exist")
    args = parser.parse_args()

    loaders = ("fabric", "neoforge") if args.loader == "both" else (args.loader,)
    versions = args.versions or wanted(loaders)
    print("Fetching into %s" % OUT)
    missing = []
    for mc in versions:
        for loader in loaders:
            parent, parent_name = PROJECTS[0]
            if not fetch(parent, parent_name, mc, loader, args.force):
                missing.append("%s/%s/%s" % (mc, loader, parent_name))
                continue
            for project, name in PROJECTS[1:]:
                if not fetch(project, name, mc, loader, args.force):
                    missing.append("%s/%s/%s" % (mc, loader, name))
    if missing:
        print("\nNo build for: %s" % ", ".join(missing))

    print("\nRestart the dev client's Gradle daemon (or re-run the run task) after fetching.")


if __name__ == "__main__":
    main()
