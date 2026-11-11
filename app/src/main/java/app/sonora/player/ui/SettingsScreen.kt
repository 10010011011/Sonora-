package app.sonora.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonora.player.Diag
import app.sonora.player.Engine
import app.sonora.player.Importer
import app.sonora.player.S
import app.sonora.player.Settings
import app.sonora.player.fmtBytes
import app.sonora.player.plural
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private class ThemeInfo(val id: String, val name: String, val badge: String, val desc: String)

private val THEMES = listOf(
    ThemeInfo("dark", "Nocturne", "DARK", "Warm obsidian & lime-chartreuse"),
    ThemeInfo("light", "Daylight", "LIGHT", "Soft paper & botanical olive"),
    ThemeInfo("system", "System", "AUTO", "Follows device appearance"),
    ThemeInfo("miku", "Miku", "RIBBON", "Soft waveform grid with ribbon-like accents"),
    ThemeInfo("teto", "Teto", "SPIRAL", "Twin spiral details in rose and warm gold"),
    ThemeInfo("neru", "Neru", "LCD", "Compact LCD-style dots and amber highlights"),
    ThemeInfo("vinyl", "Vinyl", "GLASS", "Frosted glass, cover-art glow and a spinning record"),
)

/** Adds the current value to a dropdown when it is not one of the presets (set with a slider elsewhere). */
private fun withCurrent(options: List<Pair<Double, String>>, cur: Double, label: (Double) -> String) =
    if (options.any { it.first == cur }) options else (options + (cur to label(cur))).sortedBy { it.first }

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPal.current
    Text(title.uppercase(), color = p.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
    Panel(pad = 14.dp, content = content)
}

@Composable
fun SettingsScreen() {
    val p = LocalPal.current
    val actions = LocalActions.current
    val s by S.settings.collectAsState()
    val ps by S.player.collectAsState()
    val tracks by S.tracks.collectAsState()
    val folders by S.folders.collectAsState()
    val set = { f: (Settings) -> Settings -> S.set(f) }
    val stats = remember(tracks) { Importer.stats() }
    val sleepMin = ps.sleepAt?.let { max(1, ceil((it - System.currentTimeMillis()) / 60000.0).toInt()) } ?: 0
    val sleepValue = if (ps.sleepEndOfTrack) -1 else if (ps.sleepTracksRemaining > 0) -100 - ps.sleepTracksRemaining else if (sleepMin == 0) 0 else listOf(15, 30, 45, 60, 90).firstOrNull { it >= sleepMin } ?: 90

    Box(Modifier.fillMaxSize().background(p.bg).pointerInput(Unit) { detectTapGestures { } }) {
        ThemedBackground(null)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MAKE IT YOURS", color = p.muted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                    Text("Preferences", fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, color = p.text, letterSpacing = (-0.6).sp)
                }
                IconBtn(Ic.Close, "Close", tint = p.text, size = 24.dp, onClick = { Ui.settings.value = false })
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 24.dp)) {
                Section("Library") {
                    SettingRow(
                        "${plural(stats.first, "track")} on this device",
                        "${fmtBytes(stats.second)} of copied songs · ${fmtBytes(stats.third)} free. A picked file is copied once and recognised if you add it again, even renamed.",
                    ) {}
                    SettingRow("Folders", "Songs in a folder stay where they are (no copy). Rescan finds new, moved and deleted files without re-reading unchanged ones.") {}
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("Add folder") { actions.addFolder() }
                        Btn("Rescan all", accent = false, enabled = folders.isNotEmpty()) { Importer.rescanFolders() }
                    }
                    folders.forEach { f ->
                        val count = tracks.count { it.treeUri == f.uri }
                        SettingRow(f.name, "${plural(count, "track")} · scanned ${if (f.lastScanAt > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(f.lastScanAt)) else "never"}" + (if (Importer.folderAccessible(f.uri)) "" else " · access was revoked: add the folder again")) {
                            Btn("Remove", accent = false) { Ui.askConfirm("Remove folder", "“${f.name}” is removed from Sonora. Your music files are not touched.", "Remove") { Importer.removeFolder(f.uri) } }
                        }
                    }
                    SettingRow("Diagnostics", "Copies background-playback timing and events for the device test checklist") { Btn("Copy report", accent = false) { actions.copy(Diag.report(), "Diagnostics copied") } }
                }
                Section("Behaviour & backup") {
                    ToggleRow("Simple mode", "Hides the Sound lab and the analyzer. Everything else stays.", s.uiMode == "simple") { on -> set { it.copy(uiMode = if (on) "simple" else "pro") } }
                    SettingRow("Remember position", "Tracks at least this long reopen where you stopped. The last song always does.") {
                        Select(s.resumeMinSec, listOf(300.0 to "5 min", 600.0 to "10 min", 1200.0 to "20 min", 3600.0 to "60 min", 999999.0 to "Never")) { v -> set { it.copy(resumeMinSec = v) } }
                    }
                    SettingRow("Backup", "Saves liked songs, playlists, bookmarks, lyrics edits, tag edits, play counts and settings. No audio. To restore on a new install, add your music first, then restore. Backups from Sonora 1.2 restore too.") {}
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("Export") { actions.saveText("sonora-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json", S.buildBackup()) }
                        Btn("Restore", accent = false) {
                            actions.pickText { _, text ->
                                runCatching { S.restoreBackup(text) }.onSuccess { r ->
                                    Engine.apply()
                                    S.toast("Restored: ${r.matched} songs matched" + (if (r.unmatched > 0) ", ${r.unmatched} not in this library yet" else "") + (if (r.playlists > 0) " · ${r.playlists} playlists" else ""))
                                }.onFailure { S.toast(it.message ?: "Could not restore") }
                            }
                        }
                    }
                }
                Section("Appearance") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                        THEMES.forEach { t ->
                            val chosen = s.theme == t.id
                            Row(
                                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (chosen) p.accentSoft else p.input).border(1.dp, if (chosen) p.accent else p.border, RoundedCornerShape(14.dp))
                                    .clickable { set { it.copy(theme = t.id, colormap = if (t.id == "miku" || t.id == "teto" || t.id == "neru") t.id else it.colormap) } }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(30.dp).clip(CircleShape).background(swatch(t.id)).border(1.dp, p.border, CircleShape))
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(t.name, color = p.text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                    Text(t.badge, color = p.muted, fontSize = 9.5.sp, fontFamily = Mono, letterSpacing = 1.sp)
                                }
                                if (chosen) Icon(Ic.Check, "Selected", tint = p.accent, modifier = Modifier.size(16.dp))
                            }
                        }
                        Box(Modifier.weight(1f))
                    }
                    Text(THEMES.firstOrNull { it.id == s.theme }?.desc.orEmpty(), color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, start = 2.dp))
                    ToggleRow("Sync launcher icon with theme", "When on, the launcher icon follows the selected theme. Turn it off to restore Sonora's default waveform icon. Your launcher may take a moment to update.", s.themeIcon) { on -> set { it.copy(themeIcon = on) } }
                }
                Section("Playback & Transitions") {
                    ToggleRow("Gapless playback", "Pre-buffers the next track for zero-gap transitions", s.gapless) { on -> set { it.copy(gapless = on) } }
                    SettingRow("Smooth play / pause fade", "Gently ramp volume when starting or pausing to avoid clicks") {
                        Select(s.playPauseFadeMs, withCurrent(listOf(0.0 to "Instant (Off)", 150.0 to "150 ms", 220.0 to "220 ms", 350.0 to "350 ms", 500.0 to "500 ms"), s.playPauseFadeMs) { "${it.roundToInt()} ms" }) { v -> set { it.copy(playPauseFadeMs = v) } }
                    }
                    SettingRow("Crossfade tracks", "Smoothly blend the ending and starting tracks") {
                        Select(s.crossfade, withCurrent(listOf(0.0 to "Off", 2.0 to "2 sec", 4.0 to "4 sec", 6.0 to "6 sec", 8.0 to "8 sec", 12.0 to "12 sec"), s.crossfade) { "$it sec" }) { v -> set { it.copy(crossfade = v) } }
                    }
                    SettingRow("Repeat each track N times", "Play a song multiple times before advancing to the next track") {
                        Select(s.repeatCountTarget, listOf(1 to "1× (Normal)", 2 to "2× per track", 3 to "3× per track", 5 to "5× per track")) { n ->
                            set { it.copy(repeatCountTarget = n) }
                            S.player.value = S.player.value.copy(repeatRemaining = max(0, n - 1))
                            Engine.apply()
                        }
                    }
                    SettingRow("Playback speed", "Adjust how fast the track plays") {
                        Select(s.speed, withCurrent(listOf(0.5, 0.75, 0.85, 0.9, 1.0, 1.1, 1.25, 1.5, 2.0).map { it to "${it}x" }, s.speed) { "${"%.2f".format(it)}x" }) { v -> set { it.copy(speed = v) } }
                    }
                    ToggleRow("Preserve pitch", "Keep the musical key unchanged when changing speed", s.keepPitch) { on -> set { it.copy(keepPitch = on) } }
                    ToggleRow("Waveform seekbar", "Show each song's real waveform and bookmark pins in Now Playing. Measured once in the background the first time a song plays.", s.waveformSeekbar) { on -> set { it.copy(waveformSeekbar = on) } }
                    ToggleRow("Pause when volume reaches 0", "Automatically pause playback when the volume slider is dragged to zero", s.pauseOnVolume0) { on -> set { it.copy(pauseOnVolume0 = on) } }
                    SettingRow("Seek step", "How far the keyboard arrows and headset rewind / fast-forward jump") {
                        Select(s.seekStepSec, listOf(5.0 to "5 sec", 10.0 to "10 sec", 15.0 to "15 sec", 30.0 to "30 sec")) { v -> set { it.copy(seekStepSec = v) } }
                    }
                    SettingRow(
                        "Sleep timer",
                        if (ps.sleepEndOfTrack) "Stops at the end of current track" else if (ps.sleepTracksRemaining > 0) "Stops after ${plural(ps.sleepTracksRemaining, "more track")}"
                        else if (ps.sleepAt != null) "Stops in about $sleepMin min (with 20s fade-out)" else "Stop playing after minutes or a number of tracks",
                    ) {
                        Select(sleepValue, listOf(0 to "Off", 15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "60 min", 90 to "90 min", -1 to "End of current track", -102 to "After 2 tracks", -103 to "After 3 tracks", -105 to "After 5 tracks")) { n ->
                            if (n == -1) Engine.setSleep(endOfTrack = true) else if (n < -100) Engine.setSleep(tracks = -(n + 100)) else Engine.setSleep(minutes = n)
                        }
                    }
                }
                Section("Lyrics & Devices") {
                    SettingRow("Tap reaction offset", "Subtract your response delay from every tap-sync stamp") {
                        IconBtn(Ic.Minus, "Decrease tap offset", tint = p.text, size = 15.dp, onClick = { set { it.copy(tapLatency = max(0.0, it.tapLatency - 25)) } })
                        Text("${s.tapLatency.roundToInt()} ms", color = p.accent, fontSize = 12.5.sp, fontFamily = Mono)
                        IconBtn(Ic.Plus, "Increase tap offset", tint = p.text, size = 15.dp, onClick = { set { it.copy(tapLatency = minOf(600.0, it.tapLatency + 25)) } })
                    }
                    Btn("Calibrate", accent = false) { Ui.calibrate.value = true }
                    ToggleRow("Pause when headphones unplug", "Immediately pause playback if an audio output disconnects", s.pauseOnDisconnect) { on -> set { it.copy(pauseOnDisconnect = on) } }
                    ToggleRow("Resume on output reconnect", "Automatically resume when your headset reconnects", s.autoResume) { on -> set { it.copy(autoResume = on) } }
                    SettingRow("Notification access", "Needed for the lock-screen and notification controls (play, pause, skip). Opens Android settings if the prompt was dismissed for good.") { Btn("Check access", accent = false) { actions.askNotifications(true) } }
                    SettingRow("Headset device names", "Shows your headset's real name (and applies its EQ profile). Asks for Android's “Nearby devices” permission. The microphone is never used.") { Btn("Allow", accent = false) { actions.askBluetooth() } }
                }
                Text("Your settings, liked songs, and imported music are saved on this device.", color = p.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp, start = 4.dp))
                Text("Sonora ${app.sonora.player.BuildConfig.VERSION_NAME} · native Android", color = p.faint, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
            }
        }
    }
}
