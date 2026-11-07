// Sonora Native — LyricsViews.kt
// Synchronized LyricsScroller (line + karaoke word highlight, 4s auto-scroll suppression)
// and full-screen LyricsStudioScreen + TapCalibrationModal.

package app.sonora.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonora.player.audio.PlayerController
import app.sonora.player.data.Lyrics
import app.sonora.player.data.fmtSec
import app.sonora.player.library.LibraryRepo
import app.sonora.player.library.LyricsParser
import app.sonora.player.ui.*

@Composable
fun LyricsScroller(
    lyrics: Lyrics?,
    currentTimeSec: Double,
    onSeekToLine: (Double) -> Unit,
    onOpenStudio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalTheme.current
    if (lyrics == null || lyrics.lines.isEmpty()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            SonoraIcon(SonoraIcons.Lyrics, tint = theme.textMuted, size = 36.dp)
            Spacer(Modifier.height(10.dp))
            SonoraText("No lyrics loaded for this track", color = theme.textSub, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            SonoraButton("Open Lyrics Studio", icon = SonoraIcons.Edit, primary = true, onClick = onOpenStudio)
        }
        return
    }

    val activeIdx = remember(lyrics, currentTimeSec) {
        LyricsParser.activeIndex(lyrics, currentTimeSec)
    }

    val listState = rememberLazyListState()
    var lastUserScrollAtMs by remember { mutableLongStateOf(0L) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) {
                    lastUserScrollAtMs = System.currentTimeMillis()
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                lastUserScrollAtMs = System.currentTimeMillis()
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(activeIdx) {
        if (activeIdx >= 0 && System.currentTimeMillis() - lastUserScrollAtMs > 4000L) {
            runCatching {
                listState.animateScrollToItem(index = (activeIdx - 1).coerceAtLeast(0))
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 48.dp, horizontal = 18.dp),
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection),
        ) {
            itemsIndexed(lyrics.lines) { idx, line ->
                val isActive = idx == activeIdx
                val isPast = activeIdx >= 0 && idx < activeIdx
                val baseColor = when {
                    isActive -> theme.text
                    isPast -> theme.textMuted
                    else -> theme.textSub
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isActive) theme.accentSoft else Color.Transparent)
                        .clickable {
                            line.t?.let { onSeekToLine((it - lyrics.offset).coerceAtLeast(0.0)) }
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    val words = line.words
                    if (isActive && !words.isNullOrEmpty()) {
                        val activeWIdx = LyricsParser.activeWordIndex(line, currentTimeSec, lyrics.offset)
                        val annotated = buildAnnotatedString {
                            for ((wIdx, w) in words.withIndex()) {
                                val wordActive = wIdx == activeWIdx
                                val wordDone = activeWIdx >= 0 && wIdx < activeWIdx
                                withStyle(
                                    SpanStyle(
                                        color = when {
                                            wordActive -> theme.accent
                                            wordDone -> theme.text
                                            else -> theme.textSub
                                        },
                                        fontWeight = if (wordActive) FontWeight.ExtraBold else FontWeight.Bold,
                                    )
                                ) {
                                    append(w.text)
                                    if (!w.text.endsWith(" ") && wIdx < words.lastIndex) append(" ")
                                }
                            }
                        }
                        BasicText(
                            text = annotated,
                            style = TextStyle(
                                fontSize = 18.sp,
                                fontFamily = SonoraFonts.Manrope,
                                textAlign = TextAlign.Start,
                            ),
                        )
                    } else {
                        SonoraText(
                            text = line.text.ifEmpty { "♪" },
                            color = if (isActive) theme.accent else baseColor,
                            fontSize = if (isActive) 18.sp else 15.sp,
                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                            fontFamily = SonoraFonts.Manrope,
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        ) {
            SonoraButton("Studio", icon = SonoraIcons.Edit, compact = true, onClick = onOpenStudio)
        }
    }
}

@Composable
fun LyricsStudioScreen(
    trackId: String,
    repo: LibraryRepo,
    player: PlayerController,
    onImportLrcFile: (String) -> Unit,
    onClose: () -> Unit,
    onToast: (String) -> Unit,
) {
    val theme = LocalTheme.current
    val track = repo.trackById(trackId)
    val posMs by player.positionMs.collectAsState()
    val currentTimeSec = posMs / 1000.0

    val initialRaw = remember(trackId) {
        val existing = repo.getLyrics(track)
        if (existing != null) LyricsParser.toLRC(existing, track?.title, track?.artist)
        else track?.embeddedLyrics.orEmpty()
    }
    var rawText by remember(trackId) { mutableStateOf(initialRaw) }
    var cursorLineIdx by remember(trackId) { mutableIntStateOf(0) }
    var showTapModal by remember { mutableStateOf(false) }

    val lines = remember(rawText) { rawText.split("\n") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SectionKicker("Lyrics Studio")
                SonoraText(track?.title ?: "Track", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, fontFamily = SonoraFonts.Manrope, maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SonoraButton("Import .LRC", compact = true, onClick = { onImportLrcFile(trackId) })
                SonoraButton("Close", compact = true, onClick = onClose)
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SonoraButton(
                text = "Stamp (${fmtSec(currentTimeSec)})",
                icon = SonoraIcons.Clock,
                primary = true,
                compact = true,
                onClick = {
                    if (lines.isNotEmpty()) {
                        val idx = cursorLineIdx.coerceIn(0, lines.lastIndex)
                        val clean = lines[idx].replace(Regex("""^\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]\s*"""), "")
                        val stamped = "[${LyricsParser.stamp(currentTimeSec)}]$clean"
                        val next = lines.toMutableList()
                        next[idx] = stamped
                        rawText = next.joinToString("\n")
                        cursorLineIdx = (idx + 1).coerceAtMost(next.lastIndex)
                    }
                },
            )
            SonoraButton("-0.2s", compact = true, onClick = {
                val parsed = LyricsParser.parseLyrics(rawText)
                val shifted = LyricsParser.shiftFrom(parsed, 0, -0.2)
                rawText = LyricsParser.toLRC(shifted)
            })
            SonoraButton("+0.2s", compact = true, onClick = {
                val parsed = LyricsParser.parseLyrics(rawText)
                val shifted = LyricsParser.shiftFrom(parsed, 0, +0.2)
                rawText = LyricsParser.toLRC(shifted)
            })
            SonoraButton("Tap Sync", compact = true, onClick = { showTapModal = true })
        }

        Spacer(Modifier.height(10.dp))
        SonoraText(
            text = "Active Stamp Line #${cursorLineIdx + 1}: ${lines.getOrNull(cursorLineIdx)?.take(45).orEmpty()}",
            color = theme.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))

        BasicTextField(
            value = rawText,
            onValueChange = { rawText = it },
            textStyle = TextStyle(
                color = theme.text,
                fontSize = 13.sp,
                fontFamily = SonoraFonts.DmSans,
                lineHeight = 20.sp,
            ),
            cursorBrush = SolidColor(theme.accent),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(theme.bgElevated)
                .border(1.dp, theme.borderStrong, RoundedCornerShape(14.dp))
                .padding(14.dp),
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SonoraButton("Clear Lyrics", danger = true, onClick = {
                repo.saveLyrics(trackId, null)
                rawText = ""
                onToast("Cleared custom lyrics")
            }, modifier = Modifier.weight(1f))
            SonoraButton("Save Lyrics", primary = true, onClick = {
                val parsed = if (rawText.isBlank()) null else LyricsParser.parseLyrics(rawText, "user")
                repo.saveLyrics(trackId, parsed)
                onToast("Saved lyrics")
                onClose()
            }, modifier = Modifier.weight(1f))
        }
    }

    if (showTapModal) {
        TapCalibrationModal(
            rawLyrics = rawText,
            currentTimeSec = currentTimeSec,
            onApply = { updated ->
                rawText = updated
                showTapModal = false
            },
            onDismiss = { showTapModal = false },
        )
    }
}

@Composable
fun TapCalibrationModal(
    rawLyrics: String,
    currentTimeSec: Double,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalTheme.current
    val strippedLines = remember(rawLyrics) {
        rawLyrics.split(Regex("""\r?\n"""))
            .map { it.replace(Regex("""^\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]\s*"""), "").trim() }
            .filter { it.isNotEmpty() }
    }
    var stepIdx by remember { mutableIntStateOf(0) }
    val stampedOut = remember { mutableStateListOf<String>() }

    BottomSheetOverlay(onDismiss = onDismiss) {
        SectionKicker("Tap-to-Line Calibration")
        Spacer(Modifier.height(6.dp))
        if (strippedLines.isEmpty()) {
            SonoraText("Paste plain lyrics in the studio first before tap-calibrating.", color = theme.textSub)
            Spacer(Modifier.height(12.dp))
            SonoraButton("Close", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        } else if (stepIdx < strippedLines.size) {
            SonoraText("Line ${stepIdx + 1} of ${strippedLines.size}", color = theme.textMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(theme.accentSoft)
                    .border(2.dp, theme.accent, RoundedCornerShape(14.dp))
                    .clickable {
                        val stamp = LyricsParser.stamp(currentTimeSec)
                        stampedOut.add("[$stamp]${strippedLines[stepIdx]}")
                        stepIdx++
                    }
                    .padding(16.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SonoraText(
                        text = strippedLines[stepIdx],
                        color = theme.accent,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = SonoraFonts.Manrope,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    SonoraText("TAP HERE WHEN THIS LINE STARTS (${fmtSec(currentTimeSec)})", color = theme.textSub, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            SonoraButton("Cancel", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        } else {
            SonoraText("All ${strippedLines.size} lines stamped!", color = theme.accent, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SonoraButton("Discard", onClick = onDismiss, modifier = Modifier.weight(1f))
                SonoraButton("Apply Synced LRC", primary = true, onClick = {
                    onApply(stampedOut.joinToString("\n"))
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}
