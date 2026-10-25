#!/usr/bin/env bash

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
loader="${1:-both}"
only="${2:-}"

if [ -z "${JAVA25_HOME:-}" ]; then
    for candidate in /usr/lib/jvm/java-25-graalvm /usr/lib/jvm/java-25-openjdk /usr/lib/jvm/*25*; do
        [ -x "$candidate/bin/java" ] && JAVA25_HOME="$candidate" && break
    done
fi

list_versions() {
    python3 - "$root/versions.json" <<'PY'
import json, sys
for v in json.load(open(sys.argv[1]))["versions"]:
    print(v["mc"])
PY
}

if [ -n "$only" ]; then
    versions="${only//,/ }"
else
    versions="$(list_versions)"
fi

build() {
    local name="$1" dir="$2"
    shift 2
    local tasks=()
    for mc in $versions; do
        tasks+=(":${mc}:build")
    done
    echo "== ${name}: ${tasks[*]}"
    (cd "$dir" && JAVA_HOME="${JAVA25_HOME:-$JAVA_HOME}" ./gradlew "${tasks[@]}" --console=plain)
}

case "$loader" in
    neoforge) build neoForge "$root/neoforge" ;;
    fabric)   build fabric "$root/fabric" ;;
    both)
        build neoForge "$root/neoforge"
        build fabric "$root/fabric"
        ;;
    *)
        echo "usage: $0 [neoforge|fabric|both] [mc,mc,...]" >&2
        exit 2
        ;;
esac
