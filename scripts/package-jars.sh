#!/usr/bin/env bash

set -euo pipefail

root="${ATMO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
loader="${1:-both}"
only="${2:-}"

case "$loader" in
    neoforge|fabric|both) ;;
    *) echo "usage: $0 [neoforge|fabric|both] [mc,mc,...]" >&2; exit 2 ;;
esac

if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 is required to rewrite the jar descriptors" >&2
    exit 1
fi

ATMO_ROOT="$root" ATMO_LOADER="$loader" ATMO_ONLY="$only" python3 - <<'PY'
import hashlib
import json
import os
import re
import shutil
import sys
import zipfile

root = os.environ["ATMO_ROOT"]
loader_arg = os.environ["ATMO_LOADER"]
only = [v.strip() for v in os.environ["ATMO_ONLY"].split(",") if v.strip()]

DESCRIPTORS = {"fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/MANIFEST.MF"}

matrix = json.load(open(os.path.join(root, "versions.json")))
mod_version = matrix["mod_version"]
versions = matrix["versions"]

loaders = ["neoforge", "fabric"] if loader_arg == "both" else [loader_arg]

def jar_of(loader, mc):
    return os.path.join(root, loader, mc, "build", "libs",
                        f"trueatmosphere-{mc}-{loader}-{mod_version}.jar")

def payload(archive):
    return {name: hashlib.sha1(archive.read(name)).hexdigest()
            for name in archive.namelist()
            if not name.endswith("/") and name not in DESCRIPTORS}

def numeric(version):
    return tuple(int(part) for part in re.findall(r"\d+", version.split("-")[0]))

def groups(loader):
    """Consecutive versions.json entries that share an era, in matrix order."""
    out = []
    for entry in versions:
        if out and out[-1][-1]["era"] == entry["era"]:
            out[-1].append(entry)
        else:
            out.append([entry])
    return out

def upper_bound(members):
    """The next Minecraft version after the group, the exclusive top of its range."""
    last = versions.index(members[-1])
    return versions[last + 1]["mc"] if last + 1 < len(versions) else None

def fabric_api_floor(members):
    lowest = min((e["fabric_api"].split("+")[0] for e in members), key=numeric)
    return f">={lowest}"

def rewrite_fabric(descriptor, members, top):
    meta = json.loads(descriptor.decode("utf-8"))
    depends = meta["depends"]
    first = members[0]["mc"]
    depends["minecraft"] = f">={first} <{top}" if top else f">={first}"
    depends["fabric-api"] = fabric_api_floor(members)
    return (json.dumps(meta, indent=2) + "\n").encode("utf-8")

def rewrite_neoforge(descriptor, members, top):
    text = descriptor.decode("utf-8")
    first = members[0]["mc"]
    lowest = min((e["neoforge"] for e in members), key=numeric)
    minecraft_range = f"[{first},{top})" if top else f"[{first},)"

    lines = text.splitlines(keepends=True)
    block = None
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("[[dependencies."):
            block = None
        elif stripped.startswith("modId="):
            block = stripped.split("=", 1)[1].strip().strip('"')
        elif stripped.startswith("versionRange=") and block in ("minecraft", "neoforge"):
            wanted = minecraft_range if block == "minecraft" else f"[{lowest},)"
            indent = line[:len(line) - len(line.lstrip())]
            lines[i] = f'{indent}versionRange="{wanted}"\n'
        elif stripped.startswith("[[") and not stripped.startswith("[[dependencies."):
            block = None
    return "".join(lines).encode("utf-8")

def write_merged(source, target, members, top):
    with zipfile.ZipFile(source) as src:
        fabric = src.read("fabric.mod.json") if "fabric.mod.json" in src.namelist() else None
        neoforge = (src.read("META-INF/neoforge.mods.toml")
                    if "META-INF/neoforge.mods.toml" in src.namelist() else None)
        replacements = {}
        if fabric is not None:
            replacements["fabric.mod.json"] = rewrite_fabric(fabric, members, top)
        if neoforge is not None:
            replacements["META-INF/neoforge.mods.toml"] = rewrite_neoforge(neoforge, members, top)

        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as out:
            for info in src.infolist():
                data = replacements.get(info.filename)
                if data is None:
                    data = src.read(info.filename)
                copy = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                copy.compress_type = info.compress_type
                copy.external_attr = info.external_attr
                out.writestr(copy, data)

def main():
    dist = os.path.join(root, "dist")
    os.makedirs(dist, exist_ok=True)

    written = []
    for loader in loaders:
        for members in groups(loader):
            mcs = [e["mc"] for e in members]
            if only and not set(only) & set(mcs):
                continue

            sources = [jar_of(loader, mc) for mc in mcs]
            missing = [p for p in sources if not os.path.exists(p)]
            if missing:
                for path in missing:
                    print(f"missing {os.path.relpath(path, root)}", file=sys.stderr)
                print("run scripts/build-matrix.sh first", file=sys.stderr)
                return 1

            reference = payload(zipfile.ZipFile(sources[0]))
            for mc, path in zip(mcs[1:], sources[1:]):
                other = payload(zipfile.ZipFile(path))
                if other != reference:
                    differing = sorted(set(reference) ^ set(other)) + sorted(
                        k for k in set(reference) & set(other) if reference[k] != other[k])
                    print(f"refusing to merge {mcs[0]} and {mc}: payloads differ "
                          f"({', '.join(differing[:5])}{'...' if len(differing) > 5 else ''})",
                          file=sys.stderr)
                    print("each era must compile to identical classes; check versions.json",
                          file=sys.stderr)
                    return 1

            label = mcs[0] if len(mcs) == 1 else f"{mcs[0]}-{mcs[-1]}"
            target = os.path.join(dist, f"trueatmosphere-{label}-{loader}-{mod_version}.jar")
            if len(mcs) == 1:
                shutil.copyfile(sources[0], target)
            else:
                write_merged(sources[0], target, members, upper_bound(members))

            with zipfile.ZipFile(target) as merged:
                if payload(merged) != reference:
                    print(f"{os.path.basename(target)} does not match its sources", file=sys.stderr)
                    return 1

            size = os.path.getsize(target) / (1024 * 1024)
            written.append((os.path.basename(target), ", ".join(mcs), size))

    for name, mcs, size in written:
        print(f"{name:<52} {mcs:<24} {size:6.1f} MiB")
    print(f"{len(written)} jar(s) in {os.path.relpath(dist, root)}/")
    return 0

sys.exit(main())
PY
