# Third-Party Notices & Clean-Room Implementation Attributions

## 1. AndroidX Media3 & Jetpack Compose (Apache License 2.0)

Sonora Native uses the official AndroidX Media3 (`androidx.media3:media3-exoplayer`, `media3-session`, `media3-ui`, `media3-common`, `media3-extractor` v1.11.1) and AndroidX Jetpack Compose libraries, which are licensed under the **Apache License, Version 2.0**:

- Copyright (C) The Android Open Source Project
- Licensed under the Apache License, Version 2.0 (the "License"); you may not use these files except in compliance with the License. You may obtain a copy of the License at:
  https://www.apache.org/licenses/LICENSE-2.0

Architectural patterns in `PlaybackService.kt`, `SonoraForwardingPlayer.kt`, and `SonoraAudioProcessor.kt` follow the official AndroidX Media3 `MediaSessionService`, `ForwardingPlayer`, and `BaseAudioProcessor` APIs and reference documentation.

## 2. Clean-Room Audio DSP & Parser Implementations

In strict compliance with `HANDOFF_PROMPT.md` §3.8 and `Sonora Native - Master Migration Plan.md` §13:
- **No GPLv3 or copyleft source code** (including Auxio, Metro/RetroMusicPlayer, Gramophone, Booming, or Symphony) was copied or pasted into this repository.
- **Audio DSP (`SonoraAudioProcessor.kt`, `LiveAnalyzer.kt`, `TrackAnalyzer.kt`)**:
  - Robert Bristow-Johnson's public-domain *Audio EQ Cookbook* biquad transfer functions (peaking, low-shelf, high-shelf, low-pass).
  - Woodworth (1938) spherical-head interaural time difference (ITD) formula and equal-power panning for **Orbit 8D**.
  - Jezar at Dreampoint's public-domain *Freeverb* topology (8 parallel lowpass-feedback comb filters + 4 series allpass filters) for **Studio Room Reverb**.
  - ITU-R BS.1770-4 gated loudness integration equations for **Integrated LUFS**.
- **Binary Tag & Lyrics Parsers (`TagReader.kt`, `LyricsParser.kt`)**:
  - Written directly from the legacy Sonora v2 JavaScript specification (`tagreader.js`, `lyrics.js`) and public container format specifications (ID3v2.3/2.4, FLAC format specification, ISO/IEC 14496-12 MP4 atoms, Xiph Vorbis comment header specification, RIFF/WAVE specification).

## 3. Bundled Fonts

- **DM Sans** (`dm_sans_400.ttf` – `dm_sans_700.ttf`) — Copyright Colophon Foundry, licensed under the **SIL Open Font License 1.1**.
- **Manrope** (`manrope_500.ttf` – `manrope_800.ttf`) — Copyright Mikhail Sharanda, licensed under the **SIL Open Font License 1.1**.
