#!/usr/bin/env bash
set -euo pipefail

RES="android/app/src/main/res"
mkdir -p "$RES/drawable" "$RES/values" "$RES/mipmap-anydpi-v26"

cat > "$RES/values/sonora_icon_colors.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="sonora_icon_background">#080D1B</color>
</resources>
XML

cat > "$RES/drawable/sonora_icon_foreground.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- Stylized Vocaloid-inspired twin-tail silhouette -->
    <path android:fillColor="#48D9E3"
        android:pathData="M12,49 C15,24 34,11 54,13 C75,14 91,31 94,50 C96,65 91,76 83,85 C99,86 104,95 102,103 C87,96 77,99 68,104 C59,109 50,109 41,104 C30,112 16,108 8,101 C18,96 22,88 22,78 C11,73 8,61 12,49 Z"/>
    <path android:fillColor="#48D9E3"
        android:pathData="M17,49 C1,57 0,72 7,84 C14,77 19,68 22,57 Z M86,48 C103,55 108,71 100,85 C93,78 89,68 86,57 Z"/>
    <path android:fillColor="#080D1B"
        android:pathData="M34,40 C42,28 53,25 63,29 C72,32 79,39 82,47 C73,43 65,45 59,52 C52,60 43,63 32,60 C36,53 36,47 34,40 Z"/>
    <path android:fillColor="#F1F4F4"
        android:pathData="M35,58 C44,54 53,55 60,60 C66,65 69,72 70,79 C63,88 52,92 41,90 C32,88 26,81 24,72 C29,70 33,65 35,58 Z"/>
    <path android:strokeColor="#080D1B" android:strokeWidth="2.8"
        android:strokeLineCap="round" android:fillColor="@android:color/transparent"
        android:pathData="M35,69 C40,72 45,72 50,69"/>
    <path android:strokeColor="#FF78A9" android:strokeWidth="4"
        android:strokeLineCap="round" android:strokeLineJoin="round"
        android:fillColor="@android:color/transparent"
        android:pathData="M63,20 L74,11 L83,22"/>
    <path android:fillColor="#101A30" android:strokeColor="#FF78A9" android:strokeWidth="4"
        android:pathData="M70,48 C70,39 77,32 86,32 C95,32 102,39 102,48 L102,67 C102,76 95,83 86,83 C77,83 70,76 70,67 Z"/>
    <path android:strokeColor="#63F2ED" android:strokeWidth="3.2"
        android:strokeLineCap="round" android:fillColor="@android:color/transparent"
        android:pathData="M79,62 L79,58 M86,68 L86,49 M93,62 L93,57"/>
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
