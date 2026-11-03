#!/usr/bin/env bash
set -euo pipefail

RES="android/app/src/main/res"
mkdir -p "$RES/drawable" "$RES/values" "$RES/mipmap-anydpi-v26"

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

cat > "$RES/mipmap-anydpi-v26/ic_launcher.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/sonora_icon_background" />
    <foreground android:drawable="@drawable/sonora_icon_foreground" />
</adaptive-icon>
XML

cat > "$RES/mipmap-anydpi-v26/ic_launcher_round.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/sonora_icon_background" />
    <foreground android:drawable="@drawable/sonora_icon_foreground" />
</adaptive-icon>
XML

# Placeholder launcher icons, one per theme (used by Settings > "Match app icon to theme").
# Swap in real art later by replacing these files; the default icon above stays as it is.
make_theme_icon() {
  local id="$1" fg="$2"
  cat > "$RES/drawable/sonora_icon_foreground_${id}.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#00000000"
        android:strokeColor="${fg}"
        android:strokeWidth="8"
        android:strokeLineCap="round"
        android:pathData="M26,63 L26,51 M43,75 L43,39 M60,82 L60,30 M77,68 L77,44" />
    <path
        android:fillColor="${fg}"
        android:pathData="M83,72a4,4 0 1,0 0,-8a4,4 0 1,0 0,8" />
</vector>
XML
  cat > "$RES/mipmap-anydpi-v26/ic_launcher_${id}.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/sonora_icon_bg_${id}" />
    <foreground android:drawable="@drawable/sonora_icon_foreground_${id}" />
</adaptive-icon>
XML
}

cat > "$RES/values/sonora_icon_theme_colors.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="sonora_icon_bg_vinyl">#1A1433</color>
    <color name="sonora_icon_bg_miku">#0F1D20</color>
    <color name="sonora_icon_bg_teto">#1E1317</color>
    <color name="sonora_icon_bg_neru">#1C1A10</color>
</resources>
XML

make_theme_icon vinyl "#B4A0FF"
make_theme_icon miku "#39C5BB"
make_theme_icon teto "#F0546C"
make_theme_icon neru "#FFD60A"
