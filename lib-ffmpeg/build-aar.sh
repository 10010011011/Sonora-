#!/bin/bash
# Builds the FFmpeg audio-decoder AAR for Sonora from the Media3 1.11.1 source and vendors it
# into app/libs/. Run on Linux with git, gcc, make, perl and the Android SDK installed.
#
#   ./lib-ffmpeg/build-aar.sh /path/to/android-sdk
#
# Needs about 6 GB of downloads (Media3 source, FFmpeg source, NDK, CMake) and 20-40 minutes.
# The app builds and runs without the AAR; with it, ALAC and other files the device can't
# decode itself fall back to software decoding.
set -euo pipefail

SDK="${1:?Usage: build-aar.sh /path/to/android-sdk}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK_DIR:-/tmp/sonora-ffmpeg}"
MEDIA_TAG="1.11.1"
FFMPEG_BRANCH="release/6.0"
NDK_VERSION="26.1.10909125"
CMAKE_VERSION="3.22.1"
ANDROID_ABI="24" # must not exceed minSdk (24)
DECODERS=(alac flac opus vorbis mp3 aac ac3 eac3 dca mlp truehd amrnb amrwb)

export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"

echo "==> Installing NDK $NDK_VERSION and CMake $CMAKE_VERSION"
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "ndk;$NDK_VERSION" "cmake;$CMAKE_VERSION" > /dev/null
NDK_PATH="$SDK/ndk/$NDK_VERSION"

echo "==> Cloning Media3 $MEDIA_TAG"
rm -rf "$WORK/media"
git clone --depth 1 --branch "$MEDIA_TAG" https://github.com/androidx/media.git "$WORK/media"

JNI="$WORK/media/libraries/decoder_ffmpeg/src/main/jni"
echo "==> Cloning FFmpeg $FFMPEG_BRANCH"
git clone --depth 1 --branch "$FFMPEG_BRANCH" https://github.com/FFmpeg/FFmpeg.git "$JNI/ffmpeg"

echo "==> Building FFmpeg (${DECODERS[*]}) for armeabi-v7a arm64-v8a x86 x86_64"
cd "$JNI"
./build_ffmpeg.sh "$WORK/media/libraries/decoder_ffmpeg/src/main" "$NDK_PATH" "linux-x86_64" "$ANDROID_ABI" "${DECODERS[@]}"

echo "==> Building the decoder AAR"
export ANDROID_NDK_HOME="$NDK_PATH"
cd "$WORK/media"
./gradlew :lib-decoder-ffmpeg:assembleRelease --no-daemon

AAR="$WORK/media/libraries/decoder_ffmpeg/buildout/output/aar/lib-decoder-ffmpeg-release.aar"
[ -f "$AAR" ] || AAR="$(find "$WORK/media/libraries/decoder_ffmpeg/build" -name "*release.aar" | head -1)"
[ -f "$AAR" ] || { echo "AAR not found"; exit 1; }
mkdir -p "$ROOT/app/libs"
cp "$AAR" "$ROOT/app/libs/media3-ffmpeg-decoder.aar"
echo "==> Vendored: $ROOT/app/libs/media3-ffmpeg-decoder.aar ($(du -h "$ROOT/app/libs/media3-ffmpeg-decoder.aar" | cut -f1))"
echo "Rebuild the app; Ffmpeg.isAvailable() will now return true on device."
