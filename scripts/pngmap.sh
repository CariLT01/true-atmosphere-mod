#!/usr/bin/env bash

set -euo pipefail
file="$1"; cols="${2:-64}"; rows="${3:-30}"
magick "$file" -resize "${cols}x${rows}!" -depth 8 txt:- \
| awk -F'[,:() ]+' '
  /^#/ { next }
  {
    r = $4; g = $5; b = $6;
    lum = (r + g + b) / 3;
    max = r > g ? (r > b ? r : b) : (g > b ? g : b);
    min = r < g ? (r < b ? r : b) : (g < b ? g : b);
    sat = max - min;
    c = "?";
    if (lum > 235 && sat < 30)      c = "W";   # near-white
    else if (lum > 200 && sat < 40) c = "w";   # pale
    else if (r > b + 25 && r > 110) c = "S";   # warm/sun
    else if (lum < 30)              c = "#";   # black
    else if (lum < 60)              c = "*";   # very dark
    else if (b > r)                 c = lum > 120 ? "b" : (lum > 70 ? "c" : "d");
    else                            c = lum > 120 ? "+" : "-";
    printf "%s", c;
    if (++n % '"$cols"' == 0) printf "\n";
  }'
