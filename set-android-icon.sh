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
    <group
        android:pivotX="54"
        android:pivotY="54"
        android:scaleX="0.86"
        android:scaleY="0.86"
        android:translateX="1"
        android:translateY="1.5">
        <path
            android:fillColor="#CBEF82"
            android:pathData="M31,76a9,9 0 1,0 18,0a9,9 0 1,0 -18,0 M57,70a9,9 0 1,0 18,0a9,9 0 1,0 -18,0 M44,28 L44,76 L49,76 L49,28 Z M70,20 L70,70 L75,70 L75,20 Z M44,28 L44,39 L75,31 L75,20 Z" />
    </group>
</vector>
XML

cat > "$RES/mipmap-anydpi-v26/ic_launcher.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/sonora_icon_background" />
    <foreground android:drawable="@drawable/sonora_icon_foreground" />
    <monochrome android:drawable="@drawable/sonora_icon_foreground" />
</adaptive-icon>
XML

cat > "$RES/mipmap-anydpi-v26/ic_launcher_round.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/sonora_icon_background" />
    <foreground android:drawable="@drawable/sonora_icon_foreground" />
    <monochrome android:drawable="@drawable/sonora_icon_foreground" />
</adaptive-icon>
XML
