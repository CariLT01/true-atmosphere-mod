#!/usr/bin/env bash

set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$root/internal/selftest"

usage() {
    cat <<'USAGE'
Runs the in-game sky self-test (ATMO_SELFTEST) for the Minecraft versions in versions.json.

 scripts/selftest-matrix.sh                       # every version, both loaders
 scripts/selftest-matrix.sh --per-era             # newest version of each era, both loaders
 scripts/selftest-matrix.sh neoforge              # every NeoForge version
 scripts/selftest-matrix.sh fabric 1.21.4,26.3    # just those two Fabric runs
 scripts/selftest-matrix.sh --per-era fabric c,d  # newest 1.21.6+ and 1.21.9+ build, Fabric
 scripts/selftest-matrix.sh --list                # print the versions a run would test
 scripts/selftest-matrix.sh report                # summarise ../internal/selftest

--per-era is the quick gate: renderers are shared across an era's versions, so one build per
era finds the bulk of the breakage for a fraction of the runtime. Run the full matrix before a
release.

Each run needs a world in the client's saves directory. The script creates one by starting the
matching dedicated server once (flat, fixed seed), waiting for "Done (", typing "stop" into it
and copying the save across; later runs reuse it.

Everything is written to internal/selftest/<loader>-<mc>.log, screenshots go to the usual run
directory. The script keeps going after a failing version and prints a PASS/FAIL summary at the
end, so one broken era does not hide the rest.

Java: Gradle itself needs 25 for the 26.x Loom runs (see build-matrix.sh); each version still
compiles against the toolchain in versions.json.
USAGE
}

per_era=""
list_only=""
positional=()
for arg in "$@"; do
    case "$arg" in
        --per-era) per_era=1 ;;
        --list) list_only=1 ;;
        -h|--help|help) usage; exit 0 ;;
        -*) echo "unknown option $arg" >&2; exit 2 ;;
        *) positional+=("$arg") ;;
    esac
done

loader="both"
filters=()
if [ "${#positional[@]}" -gt 0 ]; then
    case "${positional[0]}" in
        neoforge|fabric|both|report)
            loader="${positional[0]}"
            filters=("${positional[@]:1}")
            ;;
        *) filters=("${positional[@]}") ;;
    esac
fi
only="$(IFS=,; echo "${filters[*]+"${filters[*]}"}")"

shots="${SELFTEST_SHOTS:-6000@90:-30:250,23215@270:0:250,12785@0:0:250,18000@90:-30:250,18000@0:-88:250}"
shot_count="$(awk -F, '{print NF}' <<< "$shots")"
server_port="${SELFTEST_PORT:-25599}"
server_timeout="${SELFTEST_SERVER_TIMEOUT:-240}"
client_timeout="${SELFTEST_CLIENT_TIMEOUT:-420}"

if [ -z "${JAVA25_HOME:-}" ]; then
    for candidate in /usr/lib/jvm/java-25-graalvm /usr/lib/jvm/java-25-openjdk /usr/lib/jvm/*25*; do
        [ -x "$candidate/bin/java" ] && JAVA25_HOME="$candidate" && break
    done
fi
gradle_java="${JAVA25_HOME:-${JAVA_HOME:-}}"

mc_list() {
    python3 - "$root/versions.json" "$@" <<'PY'
import json, sys

versions = json.load(open(sys.argv[1]))["versions"]
args = sys.argv[2:]
per_era = "--per-era" in args
wanted = {a for a in args if not a.startswith("--")}

if per_era:

    newest = {}
    for v in versions:
        newest[v["era"]] = v
    picked = [v for era, v in newest.items()
              if not wanted or era in wanted or v["mc"] in wanted]
else:
    picked = [v for v in versions if not wanted or v["mc"] in wanted]

print("\n".join(v["mc"] for v in picked))
PY
}

era_of() {
    python3 - "$root/versions.json" "$1" <<'PY'
import json, sys
for v in json.load(open(sys.argv[1]))["versions"]:
    if v["mc"] == sys.argv[2]:
        print(v["era"])
        break
PY
}

centre_pixel() {

    magick "$1" -resize 320x180\! -crop 1x1+160+90 +repage \
        -format "%[fx:round(255*r)],%[fx:round(255*g)],%[fx:round(255*b)]" info: 2>/dev/null
}

wait_for() {
    local log="$1" pattern="$2" timeout="$3" waited=0
    while [ "$waited" -lt "$timeout" ]; do
        if grep -q -- "$pattern" "$log" 2>/dev/null; then
            return 0
        fi
        if ! kill -0 "$4" 2>/dev/null; then
            return 1
        fi
        sleep 2
        waited=$((waited + 2))
    done
    return 1
}

start_group() {
    local log="$1" dir="$2"
    shift 2
    setsid bash -c 'cd "$1" && shift && exec "$@"' _ "$dir" "$@" >> "$log" 2>&1 &
    group_pid=$!
}

stop_group() {
    local pid="$1" waited=0
    kill -- "-$pid" 2>/dev/null
    while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 30 ]; do
        sleep 1
        waited=$((waited + 1))
    done
    kill -9 -- "-$pid" 2>/dev/null
}

ensure_world() {

    local proj="$1" name="$2" server_dir="$3" log="$4"
    if [ -f "$server_dir/server.properties" ]; then
        return 0
    fi

    printf 'eula=true\n' > "$server_dir/eula.txt"

    cat > "$server_dir/server.properties" <<EOF
level-name=atmo-world
level-seed=1234567
level-type=minecraft:flat
online-mode=false
gamemode=creative
spawn-protection=0
max-players=2
view-distance=8
sync-chunk-writes=false
server-port=${server_port}
EOF
    : > "$log"

    start_group "$log" "$proj" env JAVA_HOME="$gradle_java" ./gradlew ":${name}:runServer" --console=plain
    local pid="$group_pid"

    if ! wait_for "$log" "Done (" "$server_timeout" "$pid"; then
        echo "  server never reached 'Done', see $log" >&2
        stop_group "$pid"
        return 1
    fi

    sleep 5
    stop_group "$pid"

    if [ ! -f "$server_dir/atmo-world/level.dat" ]; then
        echo "  server produced no atmo-world" >&2
        return 1
    fi
    return 0
}

run_one() {
    local loader="$1" name="$2"
    local proj="$root/$loader/$name"
    local client_dir server_dir
    if [ "$loader" = "neoforge" ]; then
        client_dir="$proj/run/client"
        server_dir="$proj/run/server"
    else
        client_dir="$proj/run"
        server_dir="$proj/run"
    fi
    local log="$out/$loader-$name.log"
    local tag="st${loader:0:1}${name//./}"
    local era
    era="$(era_of "$name")"

    mkdir -p "$client_dir/saves" "$client_dir/screenshots"

    if [ ! -d "$client_dir/saves/atmo-world" ]; then
        if [ ! -d "$server_dir/atmo-world" ]; then
            echo "== $loader $name (era $era): generating a world"
            mkdir -p "$server_dir"
            if ! ensure_world "$root/$loader" "$name" "$server_dir" "$out/$loader-$name-server.log"; then

                local borrowed
                borrowed="$(find "$root" -maxdepth 4 -type d -name atmo-world -not -path "*/saves/*" | head -1)"
                if [ -n "$borrowed" ]; then
                    echo "  borrowing $borrowed" >&2
                    cp -r "$borrowed" "$server_dir/"
                else
                    printf '%s\t%s\t%s\t%s\n' "$loader" "$name" "$era" "FAIL world" >> "$out/summary.tsv"
                    return 1
                fi
            fi
        fi
        cp -r "$server_dir/atmo-world" "$client_dir/saves/"
    fi

    if [ -f "$client_dir/options.txt" ]; then
        grep -q '^onboardAccessibility:' "$client_dir/options.txt" \
            || printf 'onboardAccessibility:false\n' >> "$client_dir/options.txt"
    else
        printf 'onboardAccessibility:false\n' > "$client_dir/options.txt"
    fi

    if grep -q '^pauseOnLostFocus:' "$client_dir/options.txt"; then
        sed -i 's/^pauseOnLostFocus:.*/pauseOnLostFocus:false/' "$client_dir/options.txt"
    else
        printf 'pauseOnLostFocus:false\n' >> "$client_dir/options.txt"
    fi

    rm -f "$client_dir/screenshots/${tag}"_*.png
    : > "$log"
    echo "== $loader $name (era $era): self-test, $shot_count shots"

    start_group "$log" "$root/$loader" \
        env ATMO_SELFTEST="$shots" ATMO_SELFTEST_TAG="$tag" \
        DISPLAY="${DISPLAY:-:0}" JAVA_HOME="$gradle_java" \
        ./gradlew ":${name}:runClient" --console=plain \
        -PgameArgs="--quickPlaySingleplayer atmo-world --width 1600 --height 900"
    local pid="$group_pid"

    if ! wait_for "$log" "Sky self-test finished" "$client_timeout" "$pid"; then
        echo "  client did not finish the self-test, see $log" >&2
        sleep 10
        stop_group "$pid"
        printf '%s\t%s\t%s\t%s\n' "$loader" "$name" "$era" "FAIL client" >> "$out/summary.tsv"
        return 1
    fi

    local waited=0
    while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 120 ]; do
        sleep 2
        waited=$((waited + 2))
    done
    stop_group "$pid"

    local taken shots_png tables
    taken="$(grep -c 'shot [0-9]*: requested' "$log")"
    shots_png="$(find "$client_dir/screenshots" -maxdepth 1 -name "${tag}_*.png" | wc -l)"
    tables="$(grep -c 'table srgb' "$log")"
    printf '%s\t%s\t%s\t%s\n' "$loader" "$name" "$era" \
        "$([ "$taken" -eq "$shot_count" ] && [ "$shots_png" -eq "$shot_count" ] && echo PASS || echo "FAIL shots=$taken/$shots_png of $shot_count")" \
        >> "$out/summary.tsv"
}

report() {
    python3 - "$out" "$root" <<'PY'
import glob, os, re, subprocess, sys

out, root = sys.argv[1], sys.argv[2]

def centre(png):
    res = subprocess.run(["magick", png, "-resize", "320x180!", "-crop", "1x1+160+90", "+repage",
                          "-format", "%[fx:round(255*r)],%[fx:round(255*g)],%[fx:round(255*b)]",
                          "info:"], capture_output=True, text=True)
    text = res.stdout.strip()
    return tuple(int(v) for v in text.split(",")) if text else None

def white_pixels(png, size=240):
    """Pixels white in all three channels inside a centred square: the core of the sun or moon."""
    if not png:
        return None
    res = subprocess.run(["magick", png, "-gravity", "center", "-crop", "%dx%d+0+0" % (size, size),
                          "+repage", "-fx", "(r>0.78&&g>0.78&&b>0.78)?1:0",
                          "-format", "%[fx:mean*w*h]", "info:"], capture_output=True, text=True)
    try:
        return int(float(res.stdout))
    except ValueError:
        return None

def star_dots(png):
    """Specks well above their 5x5 median: the stars, against the smooth night-sky gradient."""
    if not png:
        return None
    res = subprocess.run(["magick", png, "(", "+clone", "-statistic", "median", "5x5", ")",
                          "-compose", "difference", "-composite", "-colorspace", "Gray",
                          "-threshold", "5%", "-format", "%[fx:mean*w*h]", "info:"],
                         capture_output=True, text=True)
    try:
        return int(float(res.stdout))
    except ValueError:
        return None

rows = []
for log in sorted(glob.glob(os.path.join(out, "*-*.log"))):
    base = os.path.basename(log)[:-4]
    loader, _, mc = base.partition("-")

    if mc.endswith("-server") or mc.endswith("-run") or mc == "server":
        continue
    text = open(log, errors="replace").read()

    shots = {}
    for m in re.finditer(r"shot (\d+): requested (\d+)", text):
        shots.setdefault(int(m.group(1)), {})["time"] = int(m.group(2))
    for m in re.finditer(r"shot (\d+): table srgb \((\d+), (\d+), (\d+)\)", text):
        shots.setdefault(int(m.group(1)), {})["table"] = tuple(int(m.group(i)) for i in (2, 3, 4))

    tag = "st%s%s" % (loader[0], mc.replace(".", ""))
    pngs = []
    for d in ("run/client/screenshots", "run/screenshots"):
        pngs += glob.glob(os.path.join(root, loader, mc, d, tag + "_*.png"))

    by_time, by_angle = {}, {}
    for png in pngs:
        m = re.search(r"_((\d+)_yaw(-?\d+)_p(-?\d+)_y(\d+))\.png$", png)
        if m:
            by_time.setdefault(int(m.group(2)), []).append(png)

            by_angle[(int(m.group(2)), int(m.group(3)), int(m.group(4)))] = png
    for pool in by_time.values():
        pool.sort()

    cells, worst = [], 0
    for idx in sorted(shots):
        shot = shots[idx]
        pool = by_time.get(shot.get("time"), [])
        count = len(pool)
        png = pool.pop(0) if pool else None
        px = centre(png) if png else None

        label = (re.sub(r"^[A-Za-z]{2,4}\d+_", "", os.path.basename(png)) if png
                 else "shot%d" % idx)
        if px and "table" in shot and count == 1:
            table = shot["table"]
            delta = max(abs(px[i] - table[i]) for i in range(3))
            worst = max(worst, delta)
            cells.append(os.path.splitext(label)[0] + " px(%d,%d,%d) tbl(%d,%d,%d) d%d"
                         % (px + table + (delta,)))
        elif px:
            cells.append(os.path.splitext(label)[0] + " px(%d,%d,%d)" % px)
        else:
            cells.append("%s (no screenshot)" % label)

    sun = white_pixels(by_angle.get((23215, 270, 0)))
    moon = white_pixels(by_angle.get((18000, 0, -88)))
    stars = star_dots(by_angle.get((18000, 90, -30)))
    celestial = "sun %s  moon %s  stars %s" % (
        sun if sun is not None else "-", moon if moon is not None else "-",
        stars if stars is not None else "-")
    rows.append((loader, mc, len(shots), worst, celestial, cells))

order = ["fabric", "neoforge"]
rows.sort(key=lambda r: (order.index(r[0]) if r[0] in order else 9, r[1]))

print("%-9s %-7s %5s %6s  %-28s %s" % ("loader", "mc", "shots", "worst", "celestial (white px, star dots)",
                                       "centre pixel (downsampled) vs table"))
for loader, mc, taken, worst, celestial, cells in rows:
    print("%-9s %-7s %5d %6s  %-28s %s" % (loader, mc, taken, "d%d" % worst if worst else "-",
                                          celestial,
                                          "\n" + " " * 53 + ("\n" + " " * 53).join(cells) if cells else "no shots"))
PY
}

if [ "$loader" = "report" ]; then
    report
    exit 0
fi

case "$loader" in
    neoforge|fabric|both) ;;
    *) echo "usage: $0 [neoforge|fabric|both|report] [mc,era,...] [--per-era] [--list]" >&2; exit 2 ;;
esac

requested="${only//,/ }"
if [ "$per_era" = 1 ]; then
    versions="$(mc_list --per-era $requested)"
else
    versions="$(mc_list $requested)"
fi

if [ -n "$list_only" ]; then
    echo "$versions" | tr ' ' '\n'
    exit 0
fi

if [ -z "$versions" ]; then
    echo "no version matches '$only'" >&2
    exit 2
fi

mkdir -p "$out"

exec 9>"$out/.lock"
if ! flock -n 9; then
    echo "another selftest-matrix.sh is already running" >&2
    exit 3
fi

touch "$out/summary.tsv"

awk -F'\t' -v list="$versions" -v loader="$loader" 'BEGIN {
        n = split(list, v, " ")
        for (i = 1; i <= n; i++) rerun[v[i]] = 1
    }
    !((loader == "both" || $1 == loader) && ($2 in rerun))' "$out/summary.tsv" > "$out/summary.tsv.new"
mv "$out/summary.tsv.new" "$out/summary.tsv"

case "$loader" in
    neoforge|fabric)
        for name in $versions; do
            run_one "$loader" "$name"
        done
        ;;
    both)
        for name in $versions; do
            run_one neoforge "$name"
        done
        for name in $versions; do
            run_one fabric "$name"
        done
        ;;
    *)
        echo "usage: $0 [neoforge|fabric|both|report] [mc,era,...] [--per-era] [--list]" >&2
        exit 2
        ;;
esac

echo
echo "== summary (internal/selftest/summary.tsv)"
awk -F'\t' '{printf "%-9s %-8s era %-3s %s\n", $1, $2, $3, $4}' "$out/summary.tsv"
grep -q "FAIL" "$out/summary.tsv" && exit 1
exit 0
