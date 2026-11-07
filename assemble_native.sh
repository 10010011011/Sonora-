#!/usr/bin/env bash
# Builds ./native (a normal Gradle project) from the FLAT files in the repo root.
# Why: the repo is edited from a phone (no folders). Naming rules:
#   *.kt                -> app/src/main/java/<package from its 'package' line>/<name>.kt   (names ending Test.kt -> src/test)
#   res__<dir>__<file>  -> app/src/main/res/<dir>/<file>        e.g. res__drawable__ic_x.xml, res__values-night__colors.xml
#   assets__<file>      -> app/src/main/assets/<file>
#   fixed names         -> see the 'place' lines below
set -euo pipefail
OUT=native
rm -rf "$OUT"
mkdir -p "$OUT/gradle" "$OUT/app/src/main/java" "$OUT/app/src/main/res" "$OUT/app/src/main/assets" "$OUT/app/src/test/java"

place() { mkdir -p "$(dirname "$2")"; cp "$1" "$2"; }
need()  { [ -f "$1" ] || { echo "ASSEMBLE ERROR: missing required file $1" >&2; exit 1; }; }

need settings.gradle.kts;   place settings.gradle.kts   "$OUT/settings.gradle.kts"
need build.gradle.kts;      place build.gradle.kts      "$OUT/build.gradle.kts"
need gradle.properties;     place gradle.properties     "$OUT/gradle.properties"
need libs.versions.toml;    place libs.versions.toml    "$OUT/gradle/libs.versions.toml"
need app.build.gradle.kts;  place app.build.gradle.kts  "$OUT/app/build.gradle.kts"
need proguard-rules.pro;    place proguard-rules.pro    "$OUT/app/proguard-rules.pro"
need AndroidManifest.xml;   place AndroidManifest.xml   "$OUT/app/src/main/AndroidManifest.xml"
need sonora-debug.keystore; place sonora-debug.keystore "$OUT/app/sonora-debug.keystore"

kt=0
for f in ./*.kt; do
  [ -e "$f" ] || continue
  pkg=$(grep -m1 -E '^package[[:space:]]+' "$f" | sed -E 's/^package[[:space:]]+([A-Za-z0-9_.]+).*/\1/' || true)
  [ -n "$pkg" ] || { echo "ASSEMBLE ERROR: $f has no 'package' line" >&2; exit 1; }
  case "$f" in *Test.kt) root=test ;; *) root=main ;; esac
  place "$f" "$OUT/app/src/$root/java/$(echo "$pkg" | tr . /)/$(basename "$f")"
  kt=$((kt+1))
done

for f in ./res__*; do
  [ -e "$f" ] || continue
  base=$(basename "$f"); rest=${base#res__}; dir=${rest%%__*}; name=${rest#*__}
  if [ "$dir" = "$rest" ] || [ -z "$dir" ] || [ -z "$name" ]; then echo "ASSEMBLE ERROR: bad resource name $base" >&2; exit 1; fi
  place "$f" "$OUT/app/src/main/res/$dir/$name"
done

for f in ./assets__*; do
  [ -e "$f" ] || continue
  base=$(basename "$f"); place "$f" "$OUT/app/src/main/assets/${base#assets__}"
done

# Launcher icons (supports either assets/launcher-icons/*.webp or flat ./icon_*.webp)
if [ -d assets/launcher-icons ] || [ -f icon_general.webp ]; then
  RES="$OUT/app/src/main/res" bash tools_icons.sh assets/launcher-icons
else
  echo "WARN: launcher icons missing - using no launcher icons"
fi

# Fonts: convert the web app's woff2 files to static TTFs in res/font
python3 tools_fonts.py "$OUT/app/src/main/res/font" || echo "WARN: font conversion failed (UI falls back to system font)"

echo "Assembled $kt Kotlin files into $OUT/"
