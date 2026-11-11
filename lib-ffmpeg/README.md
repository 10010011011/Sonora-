# FFmpeg audio decoders for Sonora

`FfmpegAudioRenderer` decodes in software what the device's hardware decoders can't — most
importantly **ALAC** (Apple Lossless in `.m4a`), plus Opus, Vorbis, FLAC, MP3, AAC, AC-3, E-AC-3,
DTS, TrueHD and AMR fallbacks. Media3 does not publish this module on Maven, so Sonora builds it
from source and vendors the AAR at `app/libs/media3-ffmpeg-decoder.aar`.

The app runs fine without the AAR: `Ffmpeg.kt` loads it by reflection, and when it is absent the
app simply uses the device decoders. ALAC already plays on most devices through the platform
decoder; FFmpeg covers the rest.

## What's in the vendored AAR

- Media3 tag `1.11.1` (`libraries/decoder_ffmpeg`), the same version the app uses.
- FFmpeg `release/6.0`, audio decoders only:
  `alac flac opus vorbis mp3 aac ac3 eac3 dca mlp truehd amrnb amrwb`.
- NDK r26b, ABI level 24 (matches `minSdk`), all four ABIs
  (`armeabi-v7a arm64-v8a x86 x86_64` — x86_64 is what the emulator test runs on).

Formats whose *container* Media3 can't parse (WMA, AIFF, APE, WavPack, DSD) are not helped by
audio decoders, so Sonora refuses those with a clear "not supported yet" message instead
(see `Formats.kt`).

## Rebuilding

```bash
./lib-ffmpeg/build-aar.sh /path/to/android-sdk
```

On success the script replaces `app/libs/media3-ffmpeg-decoder.aar`. Rebuild the app; the renderers
factory picks the new library up automatically (a `ffmpeg` line appears in the diagnostics).

## License note

The Media3 code is Apache 2.0. The FFmpeg libraries linked into the AAR are
**LGPL 2.1 or later**. If you redistribute the APK, the FFmpeg sources for the linked version
are at https://github.com/FFmpeg/FFmpeg (`release/6.0`), and this README plus `build-aar.sh`
are the build instructions.
