# Sonora native: feature list

126 features of the web / Capacitor app (`sonora-v2`) and how the native app delivers each one, plus 20 newer additions.

- **Same**: 114
- **Variant** (adapted for native Android): 10
- **Not on Android**: 2
- **New**: 20

Unit-tested (52 tests) and lint-clean; the emulator test runs on GitHub. Use the last column as your own device checklist.


## Library & import

| Level | Feature | In the native app | Status | Verified |
|---|---|---|---|---|
| Major | Import audio files with the system picker (multi-select) | Storage Access Framework picker; each file is copied once into Sonora's storage | Same | [ ] |
| Minor | Duplicate detection by content fingerprint (size + SHA-1 of first/last 64 KB), even when renamed | Same fingerprint scheme as the Capacitor build | Same | [ ] |
| Major | Add a folder in place (songs stay where they are, no copy) | Persisted folder permission; enabled by default (it sat behind the IN_PLACE_FOLDERS flag before) | Same | [ ] |
| Minor | Rescan folders: finds new, changed and deleted files, skips unchanged ones | Compares size + modified time per document | Same | [ ] |
| Minor | Remove a folder (files untouched) and revoked-access notice | Settings > Library > Folders | Same | [ ] |
| Major | Tag reading: title, artist, album, duration, embedded cover art | MediaMetadataRetriever; cover saved at max 1024 px plus a 160 px list thumbnail | Same | [ ] |
| Minor | Manually set or replace a song thumbnail | Track menu > Set thumbnail…; the image is copied to private artwork storage and retained across folder rescans | Same | [ ] |
| Minor | ReplayGain tags and embedded lyrics from ID3v2, FLAC/Ogg and MP4 | Media3 MetadataRetriever at import, and again when a track starts | Same | [ ] |
| Minor | File-name fallback when a file has no tags | "Artist - Title.ext" pattern; untagged albums are "Local audio" as before; folder cover.jpg / folder.jpg used when no embedded art | Same | [ ] |
| Minor | Sidecar .lrc / .srt / .vtt files paired automatically with the song of the same name | Works for picked files and for folders; lyric files picked alone attach to existing songs | Same | [ ] |
| Minor | Import progress sheet (N of M, file name, keeps going in the background, Cancel) | Bottom sheet; "Scanning…" shown in the top bar when hidden | Same | [ ] |
| Minor | Import summary with failed files, Retry N and Copy error report | Bottom sheet | Same | [ ] |
| Minor | "Open with Sonora" from other apps (audio/*, application/ogg), plays at once | VIEW intent filter; file is imported once then played without disturbing the queue | Same | [ ] |
| Minor | Free-storage check before copying | Fails that file with a clear reason | Same | [ ] |
| Major | Moves your library over from the previous version | Same app id + signing key: installs over 1.2.x and adopts its songs, playlists, lyrics and settings in place. The reader is unit-tested against files shaped like the old ones | Variant | [ ] |
| Minor | Remove from library (Sonora's copy deleted; files in your folders never touched) | Track menu, with confirmation | Same | [ ] |
| Minor | Edit title, artist, album (saved in Sonora only, audio file never changed) | Track menu > Edit details | Same | [ ] |
| Minor | Library stats: tracks on device, size of copied songs, free space | Settings > Library | Same | [ ] |

## Browse & search

| Level | Feature | In the native app | Status | Verified |
|---|---|---|---|---|
| Major | Library tabs: Songs, Albums, Artists, Folders, Playlists | Pill tabs; tapping a group opens its detail list (Back returns) | Same | [ ] |
| Minor | Featured / joint artists separated ("A feat. B", "A & B", "Song (ft. C)") | Port of extractArtists | Same | [ ] |
| Minor | Albums show "Various artists", group covers and track counts | Port of buildGroups | Same | [ ] |
| Major | Search across title, artist, album, file name and lyrics, with typo tolerance | Port of fuzzyMatch (one edit per word) | Same | [ ] |
| Minor | Filters: All, Liked (n), Most played, Rediscover, Lyrics | Chips | Same | [ ] |
| Minor | Sort: Title, Artist, Album, Recently added, Most played, Longest, Manual | Dropdown; saved | Same | [ ] |
| Major | Manual order: press and hold a song, then drag (Home and Library) | Haptic pick-up, rows slide aside, list auto-scrolls at the edges | Same | [ ] |
| Minor | Song row: cover, title, artist · album · LRC · RG · play count, duration, live "playing" indicator | 66 dp rows | Same | [ ] |
| Minor | Tapping the song that is already playing pauses / resumes it | Does not restart the queue | Same | [ ] |
| Minor | Group actions: Play, Shuffle, Play next, Add to queue, Add to playlist | Buttons above the group's list | Same | [ ] |
| Minor | Empty states for every filter and list | Same wording | Same | [ ] |

## Home

| Level | Feature | In the native app | Status | Verified |
|---|---|---|---|---|
| Major | Home hero card: cover, PLAYING / PAUSED, title, artist, play, like; tap opens the player | Top of Home | Same | [ ] |
| Minor | Quick mixes: Shuffle all, Most played (top 25), Rediscover (25 least recently played), Liked mix (n) | Chips | Same | [ ] |
| Minor | All songs list with count, in the chosen sort order | Below the mixes | Same | [ ] |
| Minor | "No songs yet" card with Add music | Shown for an empty library | Same | [ ] |

## Playlists

| Level | Feature | In the native app | Status | Verified |
|---|---|---|---|---|
| Major | Create, rename and delete playlists | Library > Playlists; in-app name / confirm dialogs | Same | [ ] |
| Major | Add songs picker: search, multi-select, Select all (results), Clear, "In playlist" marks | Bottom sheet, first 150 matches shown | Same | [ ] |
| Minor | A new playlist opens the song picker straight away | Same flow | Same | [ ] |
| Minor | Add to playlist from any song's menu or a whole group, creating one inline | Add to playlist sheet | Same | [ ] |
| Minor | Copy to another playlist | Playlist detail | Same | [ ] |
| Minor | Reorder by drag, Move up / down in playlist, Remove from this playlist | Drag on the list; menu items in the track sheet | Same | [ ] |
| Minor | Export M3U8 and Import M3U (matched by path, file name, "Artist - Title" or title) | System save / open dialogs | Same | [ ] |
| Minor | Save the queue as a playlist | Queue sheet | Same | [ ] |

## Queue

| Level | Feature | In the native app | Status | Verified |
|---|---|---|---|---|
| Major | Queue sheet with count and Shuffled / In order | Bottom sheet, opens at the current song | Same | [ ] |
| Minor | Play next (several in sequence stay in order), Add to queue | Port of queue.ts, by track id | Same | [ ] |
| Minor | Drag-handle reorder, remove, tap to play | Grip on each row | Same | [ ] |
| Minor | Jump to playing, Clear up next, Search in queue | Search appears for queues over 5 songs | Same | [ ] |
| Minor | Queue and current song restored at launch | state.json, same shape as before | Same | [ ] |

## Playback

| Level | Feature | In the native app | Status | Verified |
|---|---|---|---|---|
| Major | Background playback with the screen off | Media3 MediaSessionService (foreground service, mediaPlayback type) | Same | [ ] |
| Major | Notification and lock-screen controls: previous, play / pause, next, seek, artwork, Close | Media3 media notification with Sonora's icon and a Close button | Same | [ ] |
| Minor | Headset / Bluetooth buttons; rewind and fast-forward by the seek step | Media session; the step follows the Seek step setting immediately | Same | [ ] |
| Minor | Paused session kept 30 minutes, then released (no dead notification) | Idle timer in the service | Same | [ ] |
| Minor | Swiping the app away | Music keeps playing; if paused, the notification and service go away | Variant | [ ] |
| Major | Gapless playback | The whole queue is loaded as one playlist, so transitions are native and gapless; exact seeks (SeekParameters.EXACT) and a MIME hint per file | Same | [ ] |
| Major | Crossfade 0 to 12 s with a true overlap | The next song starts on a second player while the current one still plays, with equal-power ramps — as in the original dual-deck engine | Same | [ ] |
| Minor | Smooth play / pause fade (0 to 600 ms) | Volume ramp | Same | [ ] |
| Major | Shuffle: only the queue is shuffled; turning it off restores the list order | Port of setShuffle / orderBase | Same | [ ] |
| Major | Repeat: off, all, one (with badge) | Transport | Same | [ ] |
| Minor | Repeat each track N times (1, 2, 3, 5) with a live counter | Native gapless loops via repeat-one mode until the count is used up; live counter in the transport | Same | [ ] |
| Major | Playback speed 0.5x to 2x with Preserve pitch | ExoPlayer speed / pitch (Sonic) | Same | [ ] |
| Minor | Hold the cover for 1.5x speed, release to restore | Starts after a 0.38 s hold so it does not fight double-tap | Same | [ ] |
| Minor | Double-tap the cover to like | Now Playing | Same | [ ] |
| Major | A-B loop: set A, set B, clear; region drawn on the seek bar | Button under the transport in Now Playing | Same | [ ] |
| Major | Sleep timer: 15 / 30 / 45 / 60 / 90 min with 20 s fade-out, end of current track, after 2 / 3 / 5 tracks | Quick sheet with a live countdown from the timer icon in Now Playing; also in Preferences | Same | [ ] |
| Minor | Remembers the last song and position at launch (paused); long tracks remember their own position | Threshold 5 / 10 / 20 / 60 min / Never | Same | [ ] |
| Minor | Play counts (after 30 s or 80% of a short song) and last-played time | Feeds Most played / Rediscover | Same | [ ] |
| Minor | Error recovery: retry once, in place | Each failure is classified (file missing, unsupported format, damaged data, read failure, output problem) with its own message naming the song and format; retry happens in place, then it advances; stops after 3 in a row; never skips silently | Same | [ ] |
| Minor | Duplicate Next / Previous presses within 0.35 s ignored | Debounce | Same | [ ] |
| Minor | Pauses when another app takes audio focus | ExoPlayer audio focus | Same | [ ] |
| Minor | Pause when headphones unplug; Resume on output reconnect | Becoming-noisy handling + audio device callback | Same | [ ] |
| Minor | In-app volume and mute; Pause when volume reaches 0 | Slider in Now Playing | Same | [ ] |
| Minor | Tap the time to switch remaining / total | Seek bar | Same | [ ] |
| Major | Waveform seek bar with bookmark pins (or a plain bar) | Now the song's real waveform, measured once in the background on first play and cached. The original's placeholder pattern shows until then | Variant | [ ] |
| Minor | Previous restarts the song when more than 3 s in | Transport | Same | [ ] |
| Minor | Keyboard: Space, arrows to seek, Shift+arrows to skip | Hardware / Bluetooth keyboards | Same | [ ] |

## Now Playing

| Level | Feature | In the native app | Status | Verified |
|---|---|---|---|---|
| Major | Now Playing screen with Cover / Lyrics tabs | Slides up over the app; landscape puts the controls beside the cover | Same | [ ] |
| Minor | Mini player bar: progress, cover, title, like, play / pause, next | Above the bottom tabs; tap to open the player | Same | [ ] |
| Minor | Quick actions: Lyrics studio, Bookmarks, Queue, Sound | Row at the bottom of Now Playing | Same | [ ] |
| Minor | Song menu: Play next, Add to queue, Add to playlist, Like, Bookmark this moment, Edit, Remove | Bottom sheet | Same | [ ] |
| Major | Bookmarks: add at the current time, jump, rename, delete | Bottom sheet; pins on the seek bar | Same | [ ] |
| Major | Liked songs | Hearts on rows, the mini player, Home and Now Playing | Same | [ ] |

## Lyrics

| Level | Feature | In the native app | Status | Verified |
|---|---|---|---|---|
| Major | Synced lyrics that follow playback; tap a line to jump there | Now Playing > Lyrics | Same | [ ] |
| Major | Word-by-word highlight: exact for enhanced LRC / WebVTT, estimated for plain LRC | Port of estimateWordTimings | Same | [ ] |
| Minor | Auto-scroll waits 3.2 s after you scroll by hand | Same rule | Same | [ ] |
| Major | Lyrics studio: Text editor (paste, Strip times, Use these lyrics) | Lyrics tab | Same | [ ] |
| Major | Tap to sync: stamps on touch-down, Undo, per-line stamp, reaction-delay compensation | Tap pad stays pinned while the lines scroll | Same | [ ] |
| Major | Preview: edit text and timestamps inline, jump to a line | Changes save automatically | Same | [ ] |
| Minor | Import a lyrics file (.lrc, .srt, .vtt, .txt) | System file picker | Same | [ ] |
| Minor | Export LRC (with title / artist tags and word stamps) | System save dialog | Same | [ ] |
| Minor | Shift all timestamps by N seconds, or from the current line | Lyrics studio | Same | [ ] |
| Minor | Live preview offset: nudge ±0.1 / ±0.5 s, Bake, Reset | Lyrics studio | Same | [ ] |
| Minor | Tap-latency calibration (16 clicks, 4 count-in, median) and manual ±25 ms | Native click track (AudioTrack) | Same | [ ] |
| Minor | Parser: repeated LRC tags, [offset:], enhanced LRC, SRT, WebVTT | Kotlin port, covered by unit tests | Same | [ ] |
| Minor | Right-hand live lyrics panel | Shown on wide screens (tablets, foldables, landscape over 840 dp) | Variant | [ ] |

## Sound lab

| Level | Feature | In the native app | Status | Verified |
|---|---|---|---|---|
| Major | Three-band equalizer (Bass, Mids, Treble) with Reset | Custom DSP stage inside the player (Media3 AudioProcessor) | Same | [ ] |
| Minor | Presets: Flat, Deep bass, Clarity, V-shape, Vocaloid pop, Harman-ish | Same curves | Same | [ ] |
| Major | 10-band equalizer with vertical sliders and EQ bypass | Biquad peaking filters, 31 Hz to 16 kHz | Same | [ ] |
| Minor | Live frequency-response curve | Computed from the same filter coefficients | Same | [ ] |
| Minor | AutoEQ / EqualizerAPO / GraphicEQ preset import | Port of parseAutoEqText | Same | [ ] |
| Minor | Pre-amp headroom and Auto headroom | DSP gain stage | Same | [ ] |
| Major | Safe sound boost (0 to 15 dB) behind a brick-wall limiter, with meter and clipping warning | Peak limiter in the DSP stage. As in the original, it steps in only when something can push peaks past full scale | Same | [ ] |
| Major | Smart normalization: ReplayGain (track or album), pre-amp, background loudness analysis for untagged songs | Gated loudness measurement without K-weighting; gain ramps in smoothly | Variant | [ ] |
| Minor | Per-track sound memory: pin, update, clear | Applied whenever that song plays | Same | [ ] |
| Major | Stage: Stereo, Mono, Vocal focus, Karaoke; stereo width; headphone crossfeed | Mid/side matrix in the DSP stage | Same | [ ] |
| Minor | Room ambience | Schroeder reverb (the web app used a convolution reverb) | Variant | [ ] |
| Minor | 8D orbit with speed | Constant-power auto-pan with depth (the web app used an HRTF panner) | Variant | [ ] |
| Minor | Speed presets: 432Hz Natural, Slowed + Reverb, 1.0x Normal, Nightcore, ±1 semitone | Chips | Same | [ ] |
| Major | Output profiles: save, apply, delete, bind to a headset so it applies when that headset connects | Audio device callback | Same | [ ] |
| Minor | Real headset names (Nearby devices permission); microphone never requested | BLUETOOTH_CONNECT prompt, opens App info if denied | Same | [ ] |
| Minor | Switching the audio output device | Not available: Android routes audio itself (the previous Android build had the same limit) | Not on Android | [ ] |

## Visual analyzer

| Level | Feature | In the native app | Status | Verified |
|---|---|---|---|---|
| Major | Full-track spectrogram: FFT 1024 / 2048 / 4096, linear or log, floor, playhead, tap to seek | Decoded with MediaCodec in one streaming pass | Same | [ ] |
| Minor | Colormaps: Magma, Viridis, Gray, Miku, Teto, Neru | Follows the Vocaloid theme you pick | Same | [ ] |
| Major | Live spectrum, oscilloscope and stereo vectorscope | Fed from the DSP output, so the picture can lead the sound by the output buffer delay | Same | [ ] |

## Themes & app

| Level | Feature | In the native app | Status | Verified |
|---|---|---|---|---|
| Major | Seven themes: Nocturne, Daylight, System, Miku, Teto, Neru, Vinyl | Same colour tokens as the CSS | Same | [ ] |
| Minor | Vocaloid backdrops: Miku grid + ribbon twin-tails "01", Teto drill spirals "0401", Neru LCD dots + signal bars "DEN2" | Redrawn natively on a canvas, close to the CSS patterns rather than pixel-identical | Variant | [ ] |
| Minor | Vinyl: frosted glass, cover-art glow, spinning record | The blurred cover glow needs Android 12+; older versions get the gradient glow | Variant | [ ] |
| Minor | Launcher icon follows the theme (default, general, Miku, Teto, Neru) | Activity aliases with your original icon artwork | Same | [ ] |
| Minor | Manrope + DM Sans typography | Bundled variable fonts | Same | [ ] |
| Major | Simple / Pro mode (Simple hides the Sound lab and analyzer) | Preferences | Same | [ ] |
| Major | Backup export / restore (liked, playlists, bookmarks, lyrics, tag edits, play counts, settings; no audio) | Same JSON format: backups from 1.2.x restore; songs matched by id, fingerprint, or name + size | Same | [ ] |
| Minor | Diagnostics: Copy report | Device, version, settings and recent playback events | Same | [ ] |
| Minor | Notification permission asked at launch until granted; Check access opens settings | Android 13+ prompt | Same | [ ] |
| Minor | Toast messages | Pill above the mini player | Same | [ ] |
| Minor | Back closes one level at a time (sheet, player, analyzer, tab), then leaves the app running | Back handlers | Same | [ ] |
| Minor | Saves are debounced, atomic and on their own thread; a late-loading saved copy is merged, never overwrites | Three JSON files, same shapes as before | Same | [ ] |
| Minor | Seek step setting (5 / 10 / 15 / 30 s) | Now visible in Preferences (it existed without a control); headset buttons follow it immediately | Same | [ ] |
| Minor | Bottom tabs Home / Library / Lyrics / Sound, top bar with Queue, Settings and Import | The desktop sidebar's shortcuts live in the top bar and Library filters | Variant | [ ] |
| Major | Builds on GitHub: push or run the workflow, download the APK artifact; tags attach it to a release | Gradle + GitHub Actions, unit tests run first | Same | [ ] |
| Minor | Web-only plumbing: browser file inputs, "Reopen folder", IndexedDB handles, focus trap, WebAudio resume recovery | Not needed: replaced by Android pickers, persisted folder access and ExoPlayer | Not on Android | [ ] |

## New in 2.1

| Level | Feature | In the native app | Status | Verified |
|---|---|---|---|---|
| Major | Swipe the mini player left / right to skip, or up to open the player | Title slides with your finger; tap still opens the player | New | [ ] |
| Major | Swipe down to close Now Playing; swipe the cover left / right to skip | Works anywhere that is not itself scrolling | New | [ ] |
| Minor | Fast scrollbar on long lists (40+ rows) | Appears while scrolling in Home and Library; drag the thumb to jump | New | [ ] |
| Minor | Tabs remember where you were | Scroll position, search text, open album / playlist and Library tab survive switching tabs | New | [ ] |
| Minor | Screen stays on while you read live lyrics or tap-sync | Only while playing with Lyrics showing in Now Playing, and in Tap to sync | New | [ ] |
| Major | Bit-perfect output when no sound processing is on | Samples pass through untouched; verified by a unit test | New | [ ] |
| Major | Minified release build | 2.9 MB instead of 14 MB, and faster than the debug build; the workflow now produces both | New | [ ] |
| Minor | No network permission | The app cannot reach the network at all | New | [ ] |
| Minor | Themed launcher icon on Android 13+ | The default waveform icon follows the wallpaper colours when themed icons are on | New | [ ] |
| Minor | Smoother scrolling while music plays | The limiter meter no longer redraws song lists; search text is cached instead of rebuilt per keystroke | New | [ ] |
| Minor | Play from a headset button before the library has finished loading | The command waits for the saved library instead of being dropped | New | [ ] |
| Major | 52 unit tests plus 2 emulator tests | Queue rules, upgrade from 1.2, backup restore, sound chain, lyrics parser, formats and error classification, crossfade math — plus an emulator test with 200 random seeks and a clean 60–70% pass. All run on every GitHub build | New | [ ] |

## New in 2.2

| Level | Feature | In the native app | Status | Verified |
|---|---|---|---|---|
| Major | True overlap crossfade | The next song starts on a second player while the current one is still audible; the notification follows the handoff. Restores the original dual-deck behavior | New | [ ] |
| Major | FFmpeg software decoders | Built from the Media3 1.11.1 source with ALAC, Opus, Vorbis, FLAC, MP3, AAC, AC-3, E-AC-3, DTS, TrueHD and AMR; used as fallback after the hardware decoders | New | [ ] |
| Minor | WMA, AIFF, APE, WavPack and DSD get a clear "not supported yet" | Refused at import (not retryable) with the format named; folders skip and count them; they are never silently purged by a rescan | New | [ ] |
| Major | ALAC (.m4a) plays | Through the platform decoder on most devices, through FFmpeg where the device has none | New | [ ] |
| Major | Seek-robustness emulator test | 200 random seeks must all land with the clock moving, and playback must run clean through 60–70% of a song; fails the build otherwise | New | [ ] |
| Minor | Engine owned outside the Activity | Two players live in the Application and PlaybackService; music keeps playing after the app is swiped away; release only when the service is destroyed | New | [ ] |
| Minor | Manual audio-focus with ducking | One focus request for the app (two players cannot share ExoPlayer’s automatic focus); navigation prompts duck the music, calls and other apps pause it | New | [ ] |
| Minor | Toolchain: AGP 9.2.1, Kotlin 2.4.0, Compose BOM 2026.06.00, Media3 1.11.1, SDK 36, JDK 21 | Current releases, verified to build together | New | [ ] |
