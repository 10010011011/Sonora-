package app.sonora.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonora.player.audio.TrackAnalyzer
import app.sonora.player.data.Bookmark
import app.sonora.player.data.TrackRecord
import kotlin.math.max

fun fmtTime(ms: Long): String {
    val neg = ms < 0L
    val s = (kotlin.math.abs(ms) / 1000L).toInt()
    return "%s%d:%02d".format(if (neg) "-" else "", s / 60, s % 60)
}

/**
 * Seek bar that CANNOT get stuck (this is the fix for the old "time freezes while the song plays" bug):
 *  - drag state is reset in `finally`, so a cancelled gesture can never leave the bar frozen;
 *  - exactly ONE seek is sent, on finger release (no seek flooding while dragging);
 *  - while dragging, the thumb follows the finger; otherwise it follows the player position.
 * [onDragTime] reports the time under the finger (null when not dragging) so the caller can show it.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    trackColor: Color,
    fillColor: Color,
    modifier: Modifier = Modifier,
    onDragTime: (Long?) -> Unit = {},
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableIntStateOf(1) }
    val latestDuration by rememberUpdatedState(durationMs)
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestOnDragTime by rememberUpdatedState(onDragTime)
    val fraction = dragFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .onSizeChanged { widthPx = maxOf(1, it.width) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var last = (down.position.x / widthPx).coerceIn(0f, 1f)
                    try {
                        dragFraction = last
                        latestOnDragTime((last * latestDuration).toLong())
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            last = (change.position.x / widthPx).coerceIn(0f, 1f)
                            dragFraction = last
                            latestOnDragTime((last * latestDuration).toLong())
                            if (change.pressed) {
                                change.consume()
                            } else {
                                if (latestDuration > 0) latestOnSeek((last * latestDuration).toLong())
                                break
                            }
                        }
                    } finally {
                        dragFraction = null
                        latestOnDragTime(null)
                    }
                }
            }
            .drawBehind {
                val trackH = 4.dp.toPx()
                val cy = size.height / 2f
                val radius = CornerRadius(trackH / 2f, trackH / 2f)
                drawRoundRect(trackColor, Offset(0f, cy - trackH / 2f), Size(size.width, trackH), radius)
                drawRoundRect(fillColor, Offset(0f, cy - trackH / 2f), Size(size.width * fraction, trackH), radius)
                val thumb = if (dragFraction != null) 9.dp.toPx() else 6.dp.toPx()
                drawCircle(fillColor, thumb, Offset(size.width * fraction, cy))
            },
    )
}

/**
 * Full Sonora Now Playing seek bar (mirrors Player.tsx SeekBar):
 *  - Supports 72-bar interactive waveform mode (`waveformOn`) AND classic track mode.
 *  - Renders A-B loop highlight region (`abA`..`abB`) and track bookmark pins (`bookmarks`).
 *  - Uses the same un-stickable `try/finally` gesture + seek-once-on-release guarantee.
 */
@Composable
fun NowPlayingSeekBar(
    track: TrackRecord?,
    positionMs: Long,
    durationMs: Long,
    waveformOn: Boolean,
    showRemainingTime: Boolean,
    abA: Double?,
    abB: Double?,
    bookmarks: List<Bookmark>,
    onSeek: (Long) -> Unit,
    onToggleRemainingTime: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTheme.current
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableIntStateOf(1) }
    val latestDuration by rememberUpdatedState(durationMs)
    val latestOnSeek by rememberUpdatedState(onSeek)
    val fraction = dragFraction ?: if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val currentDisplayMs = if (dragFraction != null) (dragFraction!! * durationMs).toLong() else positionMs
    val bars = remember(track?.id, track?.duration) {
        TrackAnalyzer.waveformSignatureForTrack(track, 72)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (waveformOn) 40.dp else 28.dp)
                .onSizeChanged { widthPx = max(1, it.width) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var last = (down.position.x / widthPx).coerceIn(0f, 1f)
                        try {
                            dragFraction = last
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                last = (change.position.x / widthPx).coerceIn(0f, 1f)
                                dragFraction = last
                                if (change.pressed) {
                                    change.consume()
                                } else {
                                    if (latestDuration > 0L) latestOnSeek((last * latestDuration).toLong())
                                    break
                                }
                            }
                        } finally {
                            dragFraction = null
                        }
                    }
                }
                .drawBehind {
                    val durSec = durationMs / 1000.0
                    if (abA != null && durSec > 0.0) {
                        val startFrac = (abA / durSec).coerceIn(0.0, 1.0).toFloat()
                        val endSec = abB ?: (currentDisplayMs / 1000.0)
                        val endFrac = (endSec / durSec).coerceIn(startFrac.toDouble() + 0.01, 1.0).toFloat()
                        drawRoundRect(
                            color = t.accent2.copy(alpha = 0.25f),
                            topLeft = Offset(size.width * startFrac, 2.dp.toPx()),
                            size = Size(size.width * (endFrac - startFrac), size.height - 4.dp.toPx()),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        )
                    }

                    if (waveformOn) {
                        val n = bars.size
                        val gap = 2.dp.toPx()
                        val barW = max(1.5f, (size.width - gap * (n - 1)) / n)
                        val maxH = size.height - 8.dp.toPx()
                        val cy = size.height / 2f
                        for (i in 0 until n) {
                            val barPct = (i + 0.5f) / n
                            val active = barPct <= fraction
                            val bh = max(4.dp.toPx(), bars[i] * maxH)
                            val x = i * (barW + gap)
                            drawRoundRect(
                                color = if (active) t.accent.copy(alpha = 0.95f) else t.border.copy(alpha = 0.65f),
                                topLeft = Offset(x, cy - bh / 2f),
                                size = Size(barW, bh),
                                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                            )
                        }
                        if (durSec > 0.0) {
                            for (bm in bookmarks) {
                                val bx = ((bm.t / durSec).coerceIn(0.0, 1.0) * size.width).toFloat()
                                drawCircle(
                                    color = t.accent2,
                                    radius = 3.dp.toPx(),
                                    center = Offset(bx, size.height - 3.dp.toPx()),
                                )
                            }
                        }
                    } else {
                        val trackH = 5.dp.toPx()
                        val cy = size.height / 2f
                        val radius = CornerRadius(trackH / 2f, trackH / 2f)
                        drawRoundRect(t.border, Offset(0f, cy - trackH / 2f), Size(size.width, trackH), radius)
                        drawRoundRect(t.accent, Offset(0f, cy - trackH / 2f), Size(size.width * fraction, trackH), radius)
                        val thumb = if (dragFraction != null) 8.dp.toPx() else 6.dp.toPx()
                        drawCircle(t.bg, thumb + 2.dp.toPx(), Offset(size.width * fraction, cy))
                        drawCircle(t.accent, thumb, Offset(size.width * fraction, cy))
                    }
                },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SonoraText(
                text = fmtTime(currentDisplayMs),
                color = t.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            SonoraText(
                text = if (showRemainingTime) fmtTime(-max(0L, durationMs - currentDisplayMs)) else fmtTime(durationMs),
                color = t.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable(onClick = onToggleRemainingTime),
            )
        }
    }
}
