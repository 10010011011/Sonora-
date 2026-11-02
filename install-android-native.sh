#!/usr/bin/env bash
# Installs Sonora's native Android layer into the generated Capacitor project (run after `cap add android`).
# Supports both structured (`native/android/...`) and completely flat root layouts.
set -euo pipefail

JAVA_DIR="android/app/src/main/java/app/sonora/player"
RES_DIR="android/app/src/main/res"
MANIFEST="android/app/src/main/AndroidManifest.xml"
GRADLE="android/app/build.gradle"

if [ -f "native/android/sonora-debug.keystore" ]; then
  KEYSTORE_SRC="native/android/sonora-debug.keystore"
elif [ -f "sonora-debug.keystore" ]; then
  KEYSTORE_SRC="sonora-debug.keystore"
else
  echo "Expected sonora-debug.keystore" >&2; exit 1
fi

for f in "$MANIFEST" "$GRADLE"; do
  [ -f "$f" ] || { echo "Expected $f (run 'npx cap add android' first)" >&2; exit 1; }
done

mkdir -p "$JAVA_DIR" "$RES_DIR/drawable"
if [ -d "native/android/java/app/sonora/player" ]; then
  cp native/android/java/app/sonora/player/*.java "$JAVA_DIR/"
else
  cp ./*.java "$JAVA_DIR/"
fi
rm -f "$JAVA_DIR/SonoraUtilTest.java"   # JVM-only test, not part of the app

if [ -d "native/android/res/drawable" ]; then
  cp native/android/res/drawable/*.xml "$RES_DIR/drawable/"
else
  cp ./ic_*.xml "$RES_DIR/drawable/"
fi

if [ -f "scripts/patch-android-manifest.mjs" ]; then
  node scripts/patch-android-manifest.mjs "$MANIFEST"
else
  node ./patch-android-manifest.mjs "$MANIFEST"
fi

# MediaSessionCompat + MediaStyle notification.
if ! grep -q 'androidx.media:media' "$GRADLE"; then
  sed -i '/dependencies[[:space:]]*{/a\    implementation "androidx.media:media:1.7.0"' "$GRADLE"
fi

cp "$KEYSTORE_SRC" android/app/sonora-debug.keystore
node -e '
const fs = require("fs");
const file = process.argv[1];
const src = fs.readFileSync(file, "utf8");
if (!src.includes("sonora-debug.keystore")) {
  const block = [
    "",
    "    signingConfigs {",
    "        debug {",
    "            storeFile file(\"sonora-debug.keystore\")",
    "            storePassword \"sonora-debug\"",
    "            keyAlias \"sonora\"",
    "            keyPassword \"sonora-debug\"",
    "        }",
    "    }",
  ].join("\n");
  const out = src.replace(/^android \{/m, "android {" + block);
  if (out === src) { console.error("android { block not found in " + file); process.exit(1); }
  fs.writeFileSync(file, out);
}
' "$GRADLE"

VN=$(node -p "require('./package.json').version")
VC=$(node -p "const [a,b,c]=require('./package.json').version.split('.').map(n=>parseInt(n,10)||0); a*10000+b*100+c")
sed -i -E "s/versionCode [0-9]+/versionCode $VC/; s/versionName \"[^\"]*\"/versionName \"$VN\"/" "$GRADLE"

echo "Sonora native layer installed (version $VN, code $VC)."
