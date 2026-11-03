#!/usr/bin/env bash
set -euo pipefail

RES="android/app/src/main/res"
ASSETS="${1:-assets/launcher-icons}"
mkdir -p "$RES/drawable" "$RES/values" "$RES/mipmap-anydpi-v26" "$RES/mipmap-nodpi"

# The default icon is intentionally character-free: a simple Sonora waveform on obsidian.
cat > "$RES/values/sonora_icon_colors.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="sonora_icon_background">#151B1C</color>
</resources>
XML

cat > "$RES/drawable/sonora_icon_foreground.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#00000000"
        android:strokeColor="#CBEF82"
        android:strokeWidth="8"
        android:strokeLineCap="round"
        android:pathData="M26,63 L26,51 M43,75 L43,39 M60,82 L60,30 M77,68 L77,44" />
    <path
        android:fillColor="#CBEF82"
        android:pathData="M83,72a4,4 0 1,0 0,-8a4,4 0 1,0 0,8" />
</vector>
XML

for variant in "" "_round"; do
  cat > "$RES/mipmap-anydpi-v26/ic_launcher${variant}.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/sonora_icon_background" />
    <foreground android:drawable="@drawable/sonora_icon_foreground" />
</adaptive-icon>
XML
done

# Install custom artwork as adaptive icons on Android 8+.
# Legacy opaque bitmap icons are auto-normalized by many launchers and can look
# noticeably smaller than adaptive icons. The adaptive wrapper lets the
# launcher apply its own shape mask without shrinking the whole bitmap first.
# Keep the original mipmap name as the pre-26 bitmap fallback; store a second
# bitmap name for the adaptive foreground to avoid a resource-name collision.
for id in general miku teto neru; do
  source_file="$ASSETS/$id.webp"
  if [ ! -s "$source_file" ]; then
    echo "Missing launcher artwork: $source_file" >&2
    exit 1
  fi

  cp "$source_file" "$RES/mipmap-nodpi/ic_launcher_$id.webp"
  cp "$source_file" "$RES/mipmap-nodpi/ic_launcher_${id}_image.webp"

  cat > "$RES/mipmap-anydpi-v26/ic_launcher_${id}.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/sonora_icon_background" />
    <foreground android:drawable="@mipmap/ic_launcher_${id}_image" />
</adaptive-icon>
XML
done

echo "Installed default and adaptive General, Miku, Teto and Neru launcher icons."
