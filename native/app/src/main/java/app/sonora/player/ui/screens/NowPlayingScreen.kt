// Sonora Native — NowPlayingScreen.kt
// Full Now Playing screen (Art / Lyrics / Info sub-tabs, 72-bar waveform seekbar,
// Transport row with 1.5x hold, A-B loop, Repeat-N, Sleep timer, Bookmarks, Queue) + MiniPlayerBar.

package app.sonora.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonora.player.Graph
import app.sonora.player.audio.PlayerController
import app.sonora.player.audio.TrackAnalyzer
import app.sonora.player.data.Settings
import app.sonora.player.data.TrackRecord
import app.sonora.player.data.fmtSec
import app.sonora.player.library.LibraryRepo
import app.sonora.player.library.SonoraUtil
import app.sonora.player.ui.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MiniPlayerBar(
    track: TrackRecord,
    repo: LibraryRepo,
    player: PlayerController,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalTheme.current
    val ui by player.state.collectAsState()
    val posMs by player.positionMs.collectAsState()
    val progress = if (ui.durationMs > 0L) (posMs.toFloat() / ui.durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(theme.bgElevated)
            .border(1.dp, theme.borderStrong, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenNowPlaying),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .background(theme.bgHover),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(theme.accent),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            CoverArt(
                track = track,
                repo = repo,
                size = 38.dp,
                small = true,
                isPlaying = ui.isPlaying,
                vinylSpin = theme.id == "vinyl",
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                SonoraText(
                    text = track.title.ifEmpty { track.fileName },
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = SonoraFonts.Manrope,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SonoraText(
                    text = "${track.artist} • ${fmtTime(posMs)} / ${fmtTime(ui.durationMs)}",
                    color = theme.textSub,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .clickable { player.prev() }
                    .padding(8.dp),
            ) {
                SonoraIcon(SonoraIcons.SkipBack, tint = theme.text, size = 18.dp)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(theme.accent)
                    .clickable { player.toggle() },
            ) {
                SonoraIcon(
                    spec = if (ui.isPlaying) SonoraIcons.Pause else SonoraIcons.Play,
                    tint = theme.accentInk,
                    size = 17.dp,
                )
            }
            Box(
                modifier = Modifier
                    .clickable { player.next() }
                    .padding(8.dp),
            ) {
                SonoraIcon(SonoraIcons.SkipFwd, tint = theme.text, size = 18.dp)
            }
        }
    }
}

@Composable
fun TransportRow(
    player: PlayerController,
    settings: Settings,
    onAskRepeatCount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalTheme.current
    val ui by player.state.collectAsState()
    val scope = rememberCoroutineScope()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = modifier.fillMaxWidth(),
    ) {
        // Shuffle button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (settings.shuffle) theme.accentSoft else Color.Transparent)
                .clickable { player.setShuffle(!settings.shuffle) },
        ) {
            SonoraIcon(
                spec = SonoraIcons.Shuffle,
                tint = if (settings.shuffle) theme.accent else theme.textSub,
                size = 20.dp,
            )
        }

        // Previous button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(theme.bgElevated)
                .border(1.dp, theme.border, CircleShape)
                .clickable { player.prev() },
        ) {
            SonoraIcon(SonoraIcons.SkipBack, tint = theme.text, size = 22.dp)
        }

        // Play / Pause + 320ms Hold for 1.5x Speed Boost
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(theme.accent)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var holdTriggered = false
                        val job: Job = scope.launch {
                            delay(320)
                            holdTriggered = true
                            player.startHoldSpeed()
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                        }
                        job.cancel()
                        if (holdTriggered) {
                            player.endHoldSpeed()
                        } else {
                            player.toggle()
                        }
                    }
                },
        ) {
            SonoraIcon(
                spec = if (ui.isPlaying) SonoraIcons.Pause else SonoraIcons.Play,
                tint = theme.accentInk,
                size = 28.dp,
            )
        }

        // Next button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(theme.bgElevated)
                .border(1.dp, theme.border, CircleShape)
                .clickable { player.next() },
        ) {
            SonoraIcon(SonoraIcons.SkipFwd, tint = theme.text, size = 22.dp)
        }

        // Repeat button (off -> all -> one -> count prompt)
        val repeatActive = settings.repeat != "off" || ui.repeatRemaining > 0
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (repeatActive) theme.accentSoft else Color.Transparent)
                .clickable {
                    if (settings.repeat == "one" && ui.repeatRemaining == 0) {
                        onAskRepeatCount()
                    } else {
                        player.setRepeatRemaining(0)
                        player.cycleRepeat()
                    }
                },
        ) {
            SonoraIcon(
                spec = if (settings.repeat == "one" || ui.repeatRemaining > 0) SonoraIcons.RepeatOne else SonoraIcons.Repeat,
                tint = if (repeatActive) theme.accent else theme.textSub,
                size = 20.dp,
            )
            if (ui.repeatRemaining > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(theme.accent)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    SonoraText("${ui.repeatRemaining}", color = theme.accentInk, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
fun NowPlayingScreen(
    repo: LibraryRepo,
    player: PlayerController,
    sheets: SheetsStore,
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onToast: (String) -> Unit,
) {
    val theme = LocalTheme.current
    val ui by player.state.collectAsState()
    val posMs by player.positionMs.collectAsState()
    val tracks by repo.tracks.flow.collectAsState()
    val userData by repo.userData.flow.collectAsState()
    val lyricsMap by repo.lyrics.flow.collectAsState()

    val currentTrack = remember(ui.mediaId, tracks) { player.currentTrack() }
    var subView by remember { mutableStateOf("art") }
    var showSleepSheet by remember { mutableStateOf(false) }

    if (currentTrack == null) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(24.dp),
        ) {
            SonoraIcon(SonoraIcons.Disc, tint = theme.textMuted, size = 48.dp)
            Spacer(Modifier.height(12.dp))
            SonoraText("No track playing", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, fontFamily = SonoraFonts.Manrope)
            Spacer(Modifier.height(4.dp))
            SonoraText("Pick any song from Home or Library to start listening.", color = theme.textSub, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        return
    }

    val analysis = TrackAnalyzer.getCachedAnalysis(currentTrack.id)
    val bookmarks = remember(userData.bookmarks, currentTrack.id) { repo.bookmarksFor(currentTrack.id) }
    val lyrics = remember(lyricsMap, currentTrack.id) { repo.getLyrics(currentTrack) }
    val posSec = posMs / 1000.0
    val isFav = settings.likedIds.contains(currentTrack.id)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        // Sub-view Switcher: Art | Lyrics | Info + Hold 1.5x badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SonoraChip("Art", selected = subView == "art", onClick = { subView = "art" })
                SonoraChip("Lyrics", selected = subView == "lyrics", onClick = { subView = "lyrics" })
                SonoraChip("Info", selected = subView == "info", onClick = { subView = "info" })
            }
            if (ui.holdSpeedActive) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(theme.warn.copy(alpha = 0.2f))
                        .border(1.dp, theme.warn, RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    SonoraText("1.5x HOLD", color = theme.warn, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.clickable { sheets.openQueue() }.padding(6.dp)) {
                        SonoraIcon(SonoraIcons.Queue, tint = theme.textSub, size = 20.dp)
                    }
                    Box(Modifier.clickable { sheets.openTrackMenu(currentTrack.id) }.padding(6.dp)) {
                        SonoraIcon(SonoraIcons.More, tint = theme.textSub, size = 20.dp)
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (subView) {
                "art" -> {
                    var dragSumX by remember { mutableFloatStateOf(0f) }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(currentTrack.id) {
                                detectHorizontalDragGestures(
                                    onDragStart = { dragSumX = 0f },
                                    onHorizontalDrag = { _, dragAmount -> dragSumX += dragAmount },
                                    onDragEnd = {
                                        if (dragSumX < -90f) player.next()
                                        else if (dragSumX > 90f) player.prev()
                                    },
                                )
                            },
                    ) {
                        CoverArt(
                            track = currentTrack,
                            repo = repo,
                            size = 268.dp,
                            small = false,
                            isPlaying = ui.isPlaying,
                            vinylSpin = theme.id == "vinyl",
                            cornerRadius = 22.dp,
                        )
                    }
                }

                "lyrics" -> {
                    LyricsScroller(
                        lyrics = lyrics,
                        currentTimeSec = posSec,
                        onSeekToLine = { player.seekSeconds(it) },
                        onOpenStudio = { sheets.openLyricsStudio(currentTrack.id) },
                    )
                }

                "info" -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                            .background(theme.bgSurface)
                            .border(1.dp, theme.border, RoundedCornerShape(16.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        SectionKicker("Technical & Loudness Telemetry")
                        InfoGridRow("Format", SonoraUtil.extOf(currentTrack.fileName).uppercase())
                        InfoGridRow("MIME Type", currentTrack.mime.ifEmpty { "audio/*" })
                        InfoGridRow("File Size", "${String.format(java.util.Locale.US, "%.2f", currentTrack.size / (1024.0 * 1024.0))} MB")
                        InfoGridRow("ReplayGain Applied", String.format(java.util.Locale.US, "%+.1f dB", ui.rgApplied))
                        if (analysis != null) {
                            InfoGridRow("Integrated Loudness", String.format(java.util.Locale.US, "%.1f LUFS", analysis.lufs))
                            InfoGridRow("Dynamic Range", String.format(java.util.Locale.US, "DR %.1f dB", analysis.drDb))
                            InfoGridRow("Peak / RMS", String.format(java.util.Locale.US, "%.1f / %.1f dBFS", analysis.peakDb, analysis.rmsDb))
                            InfoGridRow("Crest Factor", String.format(java.util.Locale.US, "%.1f dB", analysis.crestDb))
                            InfoGridRow("Clipped Samples", "${analysis.clipped}")
                        }
                        InfoGridRow("Play Count", "${currentTrack.playCount} plays")
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SonoraText(
                    text = currentTrack.title.ifEmpty { currentTrack.fileName },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = SonoraFonts.Manrope,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                SonoraText(
                    text = "${currentTrack.artist} — ${currentTrack.album}",
                    color = theme.textSub,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .clickable { Graph.toggleLikeTrack(currentTrack.id) }
                    .padding(8.dp),
            ) {
                SonoraIcon(
                    spec = if (isFav) SonoraIcons.HeartFill else SonoraIcons.Heart,
                    tint = if (isFav) theme.accent2 else theme.textSub,
                    size = 22.dp,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        NowPlayingSeekBar(
            track = currentTrack,
            positionMs = posMs,
            durationMs = ui.durationMs,
            waveformOn = settings.waveformSeekbar,
            showRemainingTime = settings.showRemainingTime,
            abA = ui.abA,
            abB = ui.abB,
            bookmarks = bookmarks,
            onSeek = { player.seekTo(it) },
            onToggleRemainingTime = {
                onUpdateSettings { it.copy(showRemainingTime = !it.showRemainingTime) }
            },
        )

        Spacer(Modifier.height(10.dp))

        TransportRow(
            player = player,
            settings = settings,
            onAskRepeatCount = {
                sheets.askPrompt("Repeat Track N Times", initialValue = "3") { raw ->
                    val n = raw.trim().toIntOrNull()?.coerceIn(2, 99) ?: 3
                    onUpdateSettings { it.copy(repeatCountTarget = n.toDouble()) }
                    player.setRepeatRemaining(n - 1)
                    onToast("Repeating track $n times")
                }
            },
        )

        Spacer(Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val abA = ui.abA
            val abB = ui.abB
            val abLabel = when {
                abA != null && abB != null -> "A-B (${fmtSec(abA)}-${fmtSec(abB)})"
                abA != null -> "A: ${fmtSec(abA)} (Set B)"
                else -> "A-B Loop"
            }
            SonoraChip(
                label = abLabel,
                selected = abA != null,
                onClick = { player.toggleAbLoop() },
                modifier = Modifier.weight(1f),
            )
            SonoraChip(
                label = "Pin (${bookmarks.size})",
                selected = bookmarks.isNotEmpty(),
                onClick = { sheets.openBookmarks(currentTrack.id) },
            )
            val sleepAtMs = ui.sleepAt
            val sleepActive = sleepAtMs != null || ui.sleepEndOfTrack || ui.sleepTracksRemaining > 0
            val sleepLabel = when {
                ui.sleepEndOfTrack -> "Sleep: EOT"
                ui.sleepTracksRemaining > 0 -> "Sleep: ${ui.sleepTracksRemaining} trk"
                sleepAtMs != null -> {
                    val remMin = ((sleepAtMs - System.currentTimeMillis()) / 60000L).coerceAtLeast(1L)
                    "Sleep: ${remMin}m"
                }
                else -> "Sleep"
            }
            SonoraChip(
                label = sleepLabel,
                selected = sleepActive,
                onClick = { showSleepSheet = true },
            )
        }
    }

    if (showSleepSheet) {
        BottomSheetOverlay(onDismiss = { showSleepSheet = false }) {
            SonoraText("Sleep Timer", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, fontFamily = SonoraFonts.Manrope)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (mins in listOf(15, 30, 45, 60)) {
                    SonoraButton("${mins}m", compact = true, modifier = Modifier.weight(1f), onClick = {
                        player.configureSleepTimer(mins)
                        showSleepSheet = false
                        onToast("Sleep timer set for $mins minutes (with 20s fade-out)")
                    })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SonoraButton("End of Track", compact = true, modifier = Modifier.weight(1f), onClick = {
                    player.configureSleepTimer(-1)
                    showSleepSheet = false
                    onToast("Will pause at end of current track")
                })
                SonoraButton("After 3 Tracks", compact = true, modifier = Modifier.weight(1f), onClick = {
                    player.configureSleepTimer(-103)
                    showSleepSheet = false
                    onToast("Will pause after 3 tracks")
                })
                SonoraButton("Off", danger = true, compact = true, modifier = Modifier.weight(1f), onClick = {
                    player.clearSleepTimer()
                    showSleepSheet = false
                    onToast("Sleep timer off")
                })
            }
        }
    }
}

@Composable
private fun InfoGridRow(label: String, value: String) {
    val theme = LocalTheme.current
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        SonoraText(label, color = theme.textMuted, fontSize = 12.sp)
        SonoraText(value, color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = SonoraFonts.Manrope)
    }
}
