# Sonora (native Android)

Sonora rebuilt as a fully native Android app: Kotlin, Jetpack Compose and Media3 (ExoPlayer).
No WebView, no Capacitor, no Node build. It replaces the web/Capacitor app on the `sonora-v2` branch.

It keeps the **same application id (`app.sonora.player`) and signing key** with a higher version code (2.2.0),
so it installs over Sonora 1.2.x and adopts that library, playlists, lyrics and settings on first launch.

See [FEATURES.md](FEATURES.md) for the full feature-by-feature list.

## The reported bugs, and where they went

| Bug in the original | Status in 2.2.0 |
|---|---|
| After seeking, the time bar freezes while the song keeps playing (sometimes both freeze) | Gone by architecture: ExoPlayer, not a WebView. The clock is driven by the player's own seek events plus a ticker, with exact seeks. An emulator test hammers 200 random seeks and fails the build if any lands wrong or the clock stops. |
| A healthy file is skipped at ~⅔ with "cannot read the file" | Failures are classified (file missing, unsupported format, damaged data, read failure, output problem), retried once **in place** (same song, just before the failure point), and only then skipped — always with a message naming the song and format. Stops after 3 in a row instead of skipping through the library. |
| ALAC `.m4a` fails; the importer accepts WMA/AIFF that fail at play | ALAC plays (platform decoder, FFmpeg fallback — see below). WMA, AIFF, APE, WavPack, DSD and friends are refused at import with "not supported yet" and never silently purged by rescans. |
| Playback isn't smooth | The whole queue is one playlist with native gapless transitions; release build is minified; the DSP has a bit-perfect fast path when nothing is switched on. |

## Build on GitHub

1. Put the contents of this folder at the root of a branch (for example `sonora-native`), including `.github/`.
   (Keeping it in a `sonora-native/` sub-folder also works: the workflow finds it.)
2. Open **Actions** and run **Build Sonora Native APK**, or push to the branch. It runs the 52 unit tests,
   builds both APKs, and runs the seek-robustness test on an Android emulator.
3. Download the `sonora-native-apk` artifact. Pushing a tag like `v2.2.0` also attaches the APKs to a release.

## Which APK to install

Both are signed with the same key (either installs over the other, and over Sonora 1.2):

- `Sonora-native-<branch>.apk`: the release build. Minified, small and fast. Install this one.
- `Sonora-native-<branch>-debug.apk`: unminified. Use it only if the release build misbehaves.

## Build locally

Needs JDK 21 and the Android SDK (platform 36). Android Studio opens the folder directly.

```bash
./gradlew testDebugUnitTest          # 52 tests: queue, upgrade from 1.2, backup, sound chain, formats, lyrics
./gradlew assembleRelease            # app/build/outputs/apk/release/app-release.apk (install this one)
./gradlew assembleDebug              # app/build/outputs/apk/debug/app-debug.apk (unminified fallback)
./gradlew connectedDebugAndroidTest  # needs an emulator: 200 seeks + a clean 60–70% pass
```

Toolchain (verified to build together): AGP 9.2.1 (built-in Kotlin, no `kotlin-android` plugin),
Kotlin 2.4.0, Gradle 9.4.1, Compose BOM 2026.06.00, Media3 1.11.1, compile/target SDK 36, JDK 21.

## How playback works (2.2)

- **Two ExoPlayers.** The main one plays; the standby starts the next song early for a **true overlap
  crossfade** (equal-power ramps, each song keeping its own ReplayGain). The media session follows the
  handoff, so the notification is always right. With crossfade off, the second player idles.
- **The whole queue is one playlist.** Gapless transitions are native; repeat-one / repeat-all are native
  loop modes (repeat-N loops natively until its count is used up). Sonora's queue stays the source of
  truth; the player's playlist is re-synced after every edit, non-disruptively when only the tail changed.
- **Exact seeks** (`SeekParameters.EXACT`) with a MIME hint on every media item whose container is certain.
- **Audio focus is managed once for the app** (two players can't share ExoPlayer's automatic focus):
  calls and other apps pause playback, navigation prompts duck it.
- **The engine lives in the Application and the PlaybackService**, never in an Activity. Swiping the app
  away keeps music playing; the service (and players) are released only when destroyed.
- **Sound chain** is a custom `AudioProcessor`: EQ, stage, crossfeed, orbit, room, boost, brick-wall
  limiter — shared by the MediaCodec and FFmpeg renderers, so software-decoded files sound identical.

## Formats and FFmpeg

- Plays: MP3, FLAC, M4A/M4B (AAC and ALAC), AAC, Ogg Vorbis, Opus, WAV, MKA, AMR, 3GP, MP4/WebM audio.
- `app/libs/media3-ffmpeg-decoder.aar` adds software fallbacks (ALAC, Opus, Vorbis, FLAC, MP3, AAC,
  AC-3, E-AC-3, DTS, TrueHD, AMR), built from the Media3 1.11.1 source. It is loaded by reflection:
  the app runs without it and picks it up when present. See `lib-ffmpeg/` for the build script and
  license note (FFmpeg is LGPL 2.1+).
- WMA, AIFF, APE, WavPack, DSD, MIDI and tracker music have no Media3 container support, so they are
  refused with a clear message instead of failing cryptically. See `Formats.kt`.

## Where things are

| File | What it does |
|---|---|
| `Models.kt` | Data model. Field names match the web app's JSON so 1.2.x backups restore. |
| `Store.kt` | App state, saving (debounced, atomic, own thread), playlists, bookmarks, resume, backup, legacy migration. |
| `Engine.kt` | Playback: two players, full-queue playlist, overlap crossfade, shuffle/repeat, sleep timer, A-B loop, fades, ReplayGain, classified error recovery, output profiles. |
| `Queue.kt` | Every queue rule as a pure, unit-tested function. |
| `Formats.kt` | Playable/unsupported sets, MIME hints, error classification and messages. |
| `Ffmpeg.kt` | Reflection hook for the optional FFmpeg decoder library. |
| `Dsp.kt` | The sound chain as a Media3 `AudioProcessor`: EQ, stage, crossfeed, orbit, room, boost, limiter, meters. |
| `PlaybackService.kt` | Media session + notification / lock-screen controls; follows crossfade handoffs. |
| `Importer.kt` | Copy-in import with fingerprint dedupe, in-place folders, tags, covers, sidecar lyrics. |
| `Persist.kt` | Reading the Sonora 1.2 library, and backup export / restore (pure, unit-tested). |
| `Analysis.kt` | FFT, decoder, loudness analysis, spectrogram, waveform bars. |
| `LyricsParser.kt`, `Logic.kt` | Ports of `lyrics.ts`, search, sorting, grouping, AutoEQ, M3U, crossfade math. |
| `ui/` | Compose screens: Home, Library, Now Playing, Lyrics studio, Sound lab, Visual analyzer, Preferences, sheets, themes. |
| `PlaybackRobustnessTest` | Emulator test: 200 random seeks + a clean 60–70% pass. |

## Differences from the web app

- **Room ambience** is a Schroeder reverb and **8D orbit** is an auto-pan with depth (the web app used convolution and HRTF).
- **Loudness analysis** for untagged songs is a gated measurement without K-weighting.
- **Vocaloid backdrops** are redrawn natively; they are close to the CSS patterns, not pixel-identical.
- The vinyl theme's blurred cover glow needs Android 12 or newer.
- Swiping the app away keeps music playing (the WebView build had to stop).
- Minimum Android version is 7.0 (API 24).

## Status

Compiled, linted (no errors), 52 unit tests green. The emulator test is written and compiled here; it
runs on GitHub runners (this workspace has no KVM). As always, go through FEATURES.md on your phone
before relying on it — first the upgrade over 1.2, background playback with the screen off, and the
notification controls.
