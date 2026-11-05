# Sonora 1.2.0 – what changed

Upload every file in this zip to the repo root (replace existing ones). Replace `.github/workflows/android.yml` with the separate `android.yml` file. The `assets/` folder is unchanged. Then run the "Build Android APK" workflow.

## Fixes
1. **Playlists** – new **Add songs** button (opens a searchable multi-select picker). Works on empty new playlists; a playlist opens the picker right after you create it. The old button is now "Copy to playlist".
2. **Headset permission** – manifest now declares *Nearby devices* (BLUETOOTH_CONNECT), so it appears under App info → Permissions and Android can show real headset names. In-app "Allow" buttons request it, and open App info if Android stopped asking. The microphone is never requested. Output selector on Android no longer pretends to switch devices. Device-list churn no longer pauses music (only Android's own "headphones unplugged" signal does). Fixed a broken regex for device names.
3. **Notification controls** – the service stays a foreground service while paused (30 min), so Android no longer kills it and leaves a dead notification. Stale notifications remove themselves. Swiping the app away removes the notification (the WebView that plays audio is destroyed then). Commands are no longer queued and replayed later (a cause of phantom skips). Duplicate skip presses within 0.4 s are ignored. Added a Close button. Notification permission is re-requested each launch until granted, and Settings → Notification access opens system settings if needed.
4. **Skipping / not playing** – one silent retry per failing song, a specific message naming the song and format, and playback stops after 3 failures in a row instead of skipping through your library.
5. **Sound quality** – the limiter is bypassed when nothing can push peaks past full scale; ReplayGain changes ramp smoothly instead of jumping mid-song.
6. **Memory / storage** – notification cover art is decoded at ≤512 px (was full size); loudness analysis limits reduced (24 MB / 6 min) to avoid huge decode buffers; playlist/settings saves have their own thread so imports can't delay them; playlist saves happen within 0.4 s and retry on failure; a late-loading saved copy is merged, never overwrites newer edits.

Version bumped to 1.2.0 (so it installs over your current build).

## Not confirmed
"Roundabout" – the cause can't be determined without the file. If it still fails, the toast now names the format; the diagnostics export (Settings → Copy diagnostics) shows the exact media error code.


## 1.2.1 follow-up

- **Interrupted WebAudio recovery** – watch AudioContext state, retry resume while playback is active, and restore the media element to its last audible position after a suspended/interrupted interval. Crossfade and end transitions wait until the context is running.
- **Duplicate transport commands** – debounce repeated Next/Previous dispatches for 350 ms to prevent Android MediaSession and WebView handlers from double-skipping.
- **Gapless deck safety** – validate both the queued position and the actual track ID before promoting a preloaded deck; stale preloads are rejected and reloaded.
- **Media read recovery** – retry once per actual deck load (including mid-track failures), restore just before the interruption point, then advance only if the retry fails. The existing three-consecutive-failure stop remains in place.
- **Background WebView** – refresh WebView timers on window-focus changes and while either playback or the MediaSession is active.
- **Empty playlists** – add a direct “Add songs to playlist” action in the empty state and distinguish it from “Copy to another playlist”.

Version bumped to 1.2.1 (Android versionCode 10201).
