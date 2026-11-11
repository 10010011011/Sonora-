package app.sonora.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import app.sonora.player.Engine
import app.sonora.player.LyricLine
import app.sonora.player.Lyrics
import app.sonora.player.LyricsParser
import app.sonora.player.S
import app.sonora.player.Track
import app.sonora.player.fmt
import app.sonora.player.waveformSignature
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

/** Waveform seek bar with A-B region and bookmark pins (or a plain line when the waveform is turned off). */
@Composable
fun SeekBar(track: Track?) {
    val p = LocalPal.current
    val t by S.clock.collectAsState()
    val ps by S.player.collectAsState()
    val s by S.settings.collectAsState()
    val user by S.user.collectAsState()
    val dur = if (ps.duration > 0) ps.duration else track?.duration ?: 0.0
    var scrub by remember { mutableStateOf<Float?>(null) }
    val cur = scrub?.let { it * dur } ?: t
    val frac = if (dur > 0) (cur / dur).toFloat().coerceIn(0f, 1f) else 0f
    // The song's measured waveform once the background pass has it; until then, the placeholder pattern.
    val measured by S.wave.collectAsState()
    val bars = remember(track?.id, track?.duration, measured) { measured?.takeIf { it.first == track?.id }?.second ?: waveformSignature(track, 72) }
    val marks = remember(user.bookmarks, track?.id) { user.bookmarks.filter { it.trackId == track?.id } }
    val durNow by rememberUpdatedState(dur)
    val wave = s.waveformSeekbar
    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(if (wave) 46.dp else 26.dp).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    scrub = (down.position.x / size.width).coerceIn(0f, 1f)
                    drag(down.id) {
                        scrub = (it.position.x / size.width).coerceIn(0f, 1f)
                        it.consume()
                    }
                    scrub?.let { Engine.seek(it * durNow) }
                    scrub = null
                }
            },
        ) {
            val w = size.width
            val h = size.height
            val a = ps.abA
            if (a != null && dur > 0) {
                val x0 = (a / dur).toFloat() * w
                val x1 = ((ps.abB ?: cur) / dur).toFloat() * w
                drawRoundRect(p.accent2.copy(alpha = 0.25f), Offset(x0, 0f), Size(max(2f, x1 - x0), h), CornerRadius(6f))
            }
            if (wave) {
                val n = bars.size
                val bw = w / n
                for (i in 0 until n) {
                    val active = (i + 0.5f) / n <= frac
                    val bh = bars[i] * h * 0.92f
                    drawRoundRect(if (active) p.accent else p.border, Offset(i * bw + bw * 0.18f, (h - bh) / 2), Size(bw * 0.64f, bh), CornerRadius(bw), alpha = if (active) 0.95f else 0.8f)
                }
                for (bm in marks) if (dur > 0) drawCircle(p.accent2, 3.dp.toPx(), Offset((bm.t / dur).toFloat().coerceIn(0f, 1f) * w, h - 3.dp.toPx()))
            } else {
                val th = 5.dp.toPx()
                drawRoundRect(p.border, Offset(0f, (h - th) / 2), Size(w, th), CornerRadius(th))
                drawRoundRect(p.accent, Offset(0f, (h - th) / 2), Size(w * frac, th), CornerRadius(th))
                for (bm in marks) if (dur > 0) drawCircle(p.accent2, 2.5.dp.toPx(), Offset((bm.t / dur).toFloat().coerceIn(0f, 1f) * w, h / 2 + th * 1.4f))
                drawCircle(p.accent, 7.dp.toPx(), Offset(w * frac, h / 2))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(cur), color = p.muted, fontSize = 11.5.sp, fontFamily = Mono)
            // Tap to switch between total and remaining time.
            Text(if (s.showRemainingTime) fmt(-max(0.0, dur - cur)) else fmt(dur), color = p.muted, fontSize = 11.5.sp, fontFamily = Mono,
                modifier = Modifier.clickable { S.set { it.copy(showRemainingTime = !it.showRemainingTime) } })
        }
    }
}

@Composable
fun Transport(scale: Float = 1f) {
    val p = LocalPal.current
    val ps by S.player.collectAsState()
    val s by S.settings.collectAsState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Ic.Shuffle, "Shuffle", tint = if (s.shuffle) p.accent else p.muted, size = (20 * scale).dp, onClick = { Engine.setShuffle(!s.shuffle) })
        IconBtn(Ic.Prev, "Previous", tint = p.text, size = (30 * scale).dp, onClick = { Engine.prev() })
        Box(Modifier.size((64 * scale).dp).clip(CircleShape).background(p.accent).clickable { Engine.toggle() }, contentAlignment = Alignment.Center) {
            Icon(if (ps.playing) Ic.Pause else Ic.Play, if (ps.playing) "Pause" else "Play", tint = p.accentInk, modifier = Modifier.size((32 * scale).dp))
        }
        IconBtn(Ic.Next, "Next", tint = p.text, size = (30 * scale).dp, onClick = { Engine.next() })
        Box(contentAlignment = Alignment.TopEnd) {
            val on = s.repeat != "off" || s.repeatCountTarget > 1
            IconBtn(Ic.Repeat, "Repeat: ${s.repeat}", tint = if (on) p.accent else p.muted, size = (20 * scale).dp, onClick = {
                S.set { it.copy(repeat = when (it.repeat) { "off" -> "all"; "all" -> "one"; else -> "off" }) }
            })
            if (s.repeat == "one" || s.repeatCountTarget > 1) Text(
                if (s.repeat == "one") "1" else "${ps.repeatRemaining + 1}×", color = p.accentInk, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp, end = 2.dp).clip(CircleShape).background(p.accent).padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * Live lyric reader. Exact enhanced-LRC / WebVTT word times are used when present; ordinary line-timed
 * LRC gets an estimated word-by-word highlight. Tap a line to jump there. Follows playback unless you scrolled recently.
 */
@Composable
fun LyricsScroller(modifier: Modifier, track: Track?, big: Boolean = false, onOpenStudio: (() -> Unit)? = null) {
    val p = LocalPal.current
    val actions = LocalActions.current
    val map by S.lyrics.collectAsState()
    val clock = S.clock.collectAsState()
    val ps by S.player.collectAsState()
    val lyrics = track?.let { map[it.id] }
    val state = rememberLazyListState()
    val userAt = remember { mutableLongStateOf(0L) }
    val active by remember(lyrics) { derivedStateOf { LyricsParser.activeIndex(lyrics, clock.value) } }
    LaunchedEffect(track?.id, track?.sidecar, track?.embeddedLyrics) { track?.let { S.loadLyricsFor(it) } }
    LaunchedEffect(state) { state.interactionSource.interactions.collect { if (it is DragInteraction.Start) userAt.longValue = System.currentTimeMillis() } }
    LaunchedEffect(active, track?.id, lyrics?.lines?.size) {
        if (active >= 0 && System.currentTimeMillis() - userAt.longValue > 3200) state.animateScrollToItem(active, -(state.layoutInfo.viewportSize.height / 2 - 70))
    }
    val lines = lyrics?.lines ?: emptyList()
    if (lines.isEmpty()) {
        Column(modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Ic.Mic, null, tint = p.faint, modifier = Modifier.size(30.dp))
            Text(if (track != null) "No lyrics yet" else "Nothing playing", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Text(if (track != null) "Import an LRC file or paste your lyrics in Lyrics studio." else "Choose a song to see its lyrics here.", color = p.muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            if (track != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Import lyrics", icon = Ic.Upload) {
                    actions.pickText { name, text ->
                        val parsed = LyricsParser.parse(text, name)
                        if (parsed.lines.isEmpty()) S.toast("No readable lyrics found in that file") else {
                            S.saveLyrics(track.id, parsed)
                            S.toast("Imported ${parsed.lines.size} lyric lines")
                        }
                    }
                }
                if (onOpenStudio != null) Btn("Lyrics studio", icon = Ic.Right, accent = false, onClick = onOpenStudio)
            }
        }
        return
    }
    val offset = lyrics?.offset ?: 0.0
    val dur = max(ps.duration, track?.duration ?: 0.0)
    val estimated = lines.any { it.t != null } && lines.none { !it.words.isNullOrEmpty() }
    LazyColumn(modifier, state = state, contentPadding = PaddingValues(vertical = 60.dp, horizontal = 6.dp)) {
        itemsIndexed(lines) { i, line ->
            LyricLineView(line, i, i == active, i < active, lyrics!!, dur, big) {
                val lt = line.t
                if (lt != null) {
                    Engine.seek(lt + offset)
                    if (!S.player.value.playing) Engine.play()
                }
            }
        }
        if (estimated) item { Text("Word highlighting is estimated from line timestamps. Enhanced LRC provides precise word timing.", color = p.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 18.dp, start = 8.dp, end = 8.dp)) }
    }
}

@Composable
private fun LyricLineView(line: LyricLine, index: Int, active: Boolean, past: Boolean, lyrics: Lyrics, dur: Double, big: Boolean, onClick: () -> Unit) {
    val p = LocalPal.current
    val scale by animateFloatAsState(if (active) 1f else 0.94f, label = "lyric")
    val size = if (big) 23.sp else 19.sp
    val mod = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp).scale(scale)
    if (!active) {
        Text(line.text.ifEmpty { "♪" }, color = if (past) p.faint.copy(alpha = 0.7f) else p.muted, fontSize = size, lineHeight = size * 1.3f, fontFamily = Manrope, fontWeight = FontWeight.Bold, modifier = mod)
        return
    }
    // Only the active line follows the clock, so long lyric files stay cheap to render.
    val time by S.clock.collectAsState()
    val words = remember(line, lyrics) { if (!line.words.isNullOrEmpty()) line.words else LyricsParser.estimateWordTimings(lyrics, index, dur) }
    val aw = LyricsParser.activeWordIndex(words, time, lyrics.offset)
    val text = buildAnnotatedString {
        if (words.isNullOrEmpty()) append(line.text.ifEmpty { "♪" })
        else words.forEachIndexed { wi, w -> withStyle(SpanStyle(color = if (wi == aw) p.accent else if (wi < aw) p.text else p.muted)) { append(w.text) } }
    }
    Text(text, color = p.text, fontSize = size, lineHeight = size * 1.3f, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, modifier = mod)
}

/** Vinyl theme: the cover becomes the label of a record that spins while music plays. */
@Composable
private fun Record(cover: String?, size: Dp, playing: Boolean) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        while (playing) {
            angle.animateTo(angle.value + 360f, tween(9000, easing = LinearEasing))
            if (angle.value > 3600f) angle.snapTo(angle.value % 360f)
        }
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            drawCircle(Color(0xFF09080F))
            for (i in 1..9) drawCircle(Color.White.copy(alpha = 0.05f), this.size.minDimension / 2 * (0.5f + i * 0.05f), style = Stroke(1.2f))
            drawCircle(Color.White.copy(alpha = 0.10f), this.size.minDimension / 2, style = Stroke(2f))
        }
        Box(Modifier.rotate(angle.value)) { Cover(cover, size * 0.46f, radius = size) }
        Box(Modifier.size(size * 0.045f).clip(CircleShape).background(Color(0xFF09080F)))
    }
}

@Composable
private fun PillTabs(options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    val p = LocalPal.current
    Row(Modifier.clip(RoundedCornerShape(50)).background(p.input).padding(3.dp)) {
        options.forEach { (id, label) ->
            Text(label, color = if (value == id) p.accentInk else p.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (value == id) p.accent else Color.Transparent).clickable { onPick(id) }.padding(horizontal = 18.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPal.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(p.input).border(1.dp, p.border, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = p.accent, modifier = Modifier.size(18.dp))
        Text(label, color = p.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
fun NowPlayingScreen() {
    val p = LocalPal.current
    val track = currentTrack()
    val ps by S.player.collectAsState()
    val s by S.settings.collectAsState()
    val t by S.clock.collectAsState()
    var surface by remember { mutableStateOf("cover") }
    val scope = rememberCoroutineScope()
    val liked = track != null && s.likedIds.contains(track.id)
    KeepScreenOn(surface == "lyrics" && ps.playing)
    var pull by remember { mutableFloatStateOf(0f) }
    // Swipe down anywhere that is not itself scrolling to close the player.
    val swipeDown = Modifier.pointerInput(Unit) {
        val settle = { scope.launch { Animatable(pull).animateTo(0f) { pull = value } } }
        detectVerticalDragGestures(
            onDragEnd = { if (pull > 110.dp.toPx()) Ui.player.value = false else settle() },
            onDragCancel = { settle() },
        ) { ch, dy ->
            ch.consume()
            pull = (pull + dy).coerceAtLeast(0f)
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer { translationY = pull; alpha = 1f - (pull / size.height.coerceAtLeast(1f)).coerceIn(0f, 0.5f) }.pointerInput(Unit) { detectTapGestures { } }.then(swipeDown)) {
        ThemedBackground(track?.cover)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Down, "Back to home", tint = p.text, size = 24.dp, onClick = { Ui.player.value = false })
                Text("Now playing", Modifier.weight(1f), color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, textAlign = TextAlign.Center)
                IconBtn(Ic.More, "More options", tint = p.text, size = 22.dp, enabled = track != null, onClick = { track?.let { Ui.menu.value = it.id to null } })
            }
            if (track == null) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Ic.Music, null, tint = p.faint, modifier = Modifier.size(30.dp))
                    Text("No track selected", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    Text("Choose a song from your library.", color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 14.dp))
                    Btn("Library") { Ui.go("library") }
                }
                return@Column
            }
            val tabs: @Composable () -> Unit = {
                PillTabs(listOf("cover" to "Cover", "lyrics" to "Lyrics"), surface) { surface = it }
            }
            val art: @Composable (Modifier) -> Unit = { m ->
                BoxWithConstraints(m, contentAlignment = Alignment.Center) {
                    if (surface == "cover") {
                        val side = min(maxWidth, maxHeight)
                        val scale by animateFloatAsState(if (ps.playing) 1f else 0.94f, label = "cover")
                        // Double-tap to like, hold for 1.5x speed.
                        Box(Modifier.size(side).scale(scale).pointerInput(Unit) {
                            // Swipe the cover left / right to skip.
                            var dx = 0f
                            detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx < -70.dp.toPx()) Engine.next() else if (dx > 70.dp.toPx()) Engine.prev() }) { ch, d ->
                                ch.consume()
                                dx += d
                            }
                        }.pointerInput(track.id) {
                            detectTapGestures(
                                onDoubleTap = { S.toast(if (S.toggleLike(track.id)) "Added to Liked songs" else "Removed from Liked songs") },
                                onPress = {
                                    val hold = scope.launch {
                                        delay(380)
                                        if (S.player.value.playing) {
                                            Engine.holdSpeed(true)
                                            S.toast("1.5× speed (release to restore)")
                                        }
                                    }
                                    tryAwaitRelease()
                                    hold.cancel()
                                    Engine.holdSpeed(false)
                                },
                            )
                        }, contentAlignment = Alignment.Center) {
                            if (p.id == "vinyl") Record(track.cover, side, ps.playing) else Cover(track.cover, side, radius = 24.dp)
                        }
                    } else LyricsScroller(Modifier.fillMaxSize(), track, big = true) { Ui.go("lyrics") }
                }
            }
            val controls: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(track.title, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, color = p.muted, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconBtn(if (liked) Ic.Heart else Ic.HeartOutline, if (liked) "Unlike track" else "Like track", tint = if (liked) p.accent2 else p.muted, size = 24.dp, onClick = { S.toggleLike(track.id) })
                }
                Spacer(Modifier.height(10.dp))
                SeekBar(track)
                Spacer(Modifier.height(6.dp))
                Transport(1.05f)
                // A-B loop, sleep timer, mute and volume (the desktop player bar's right-hand cluster).
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val ab = if (ps.abA == null) "A-B" else if (ps.abB == null) "A→?" else "A•B"
                    Text(ab, color = if (ps.abA != null) p.accentInk else p.muted, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = Mono,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (ps.abA != null) p.accent else p.input).clickable {
                            val a = ps.abA
                            if (a == null) {
                                S.player.update { it.copy(abA = t) }
                                S.toast("Loop A set at ${fmt(t)}")
                            } else if (ps.abB == null) {
                                if (t <= a) S.toast("Set B after point A") else {
                                    S.player.update { it.copy(abB = t) }
                                    S.toast("A-B loop is active")
                                }
                            } else {
                                S.player.update { it.copy(abA = null, abB = null) }
                                S.toast("A-B loop cleared")
                            }
                        }.padding(horizontal = 9.dp, vertical = 6.dp))
                    val sleeping = ps.sleepAt != null || ps.sleepEndOfTrack || ps.sleepTracksRemaining > 0
                    IconBtn(Ic.Timer, "Sleep timer", tint = if (sleeping) p.accent else p.muted, onClick = { Ui.sleep.value = true })
                    IconBtn(if (s.muted) Ic.VolumeOff else Ic.Volume, if (s.muted) "Unmute" else "Mute", onClick = { S.set { it.copy(muted = !it.muted) } })
                    Slider(
                        value = if (s.muted) 0f else s.volume.toFloat(), onValueChange = { Engine.setVolume(it.toDouble()) }, modifier = Modifier.weight(1f).height(30.dp),
                        colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.border),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAction(Ic.Lyrics, "Lyrics studio", Modifier.weight(1f)) { Ui.go("lyrics") }
                    QuickAction(Ic.Bookmark, "Bookmarks", Modifier.weight(1f)) { Ui.bookmarks.value = true }
                    QuickAction(Ic.Queue, "Queue", Modifier.weight(1f)) { Ui.queue.value = true }
                    if (s.uiMode != "simple") QuickAction(Ic.Sliders, "Sound", Modifier.weight(1f)) { Ui.go("sound") }
                }
            }
            // Portrait: cover above the controls. Landscape: controls beside the cover.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (maxWidth > maxHeight * 1.15f) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        tabs()
                        art(Modifier.weight(1f).fillMaxWidth().padding(10.dp))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().padding(start = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) { controls() }
                } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    tabs()
                    art(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp))
                    controls()
                }
            }
        }
    }
}
