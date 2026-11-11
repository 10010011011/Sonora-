package app.sonora.player.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.sonora.player.Engine
import app.sonora.player.Lyrics
import app.sonora.player.LyricsParser
import app.sonora.player.S
import app.sonora.player.fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

private val STRIP = Regex("""^(\s*\[\d+:\d+(?:[.:]\d+)?])+""")

@Composable
fun LyricsStudio() {
    val p = LocalPal.current
    val actions = LocalActions.current
    val track = currentTrack()
    val map by S.lyrics.collectAsState()
    val s by S.settings.collectAsState()
    var mode by rememberSaveable { mutableStateOf("preview") }
    var draft by remember(track?.id) { mutableStateOf("") }
    var shiftAmt by rememberSaveable { mutableStateOf("1.7") }
    var fromCurrent by rememberSaveable { mutableStateOf(false) }
    var tapNext by remember(track?.id) { mutableIntStateOf(0) }
    val lyr = track?.let { map[it.id] }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(track?.id, track?.sidecar, track?.embeddedLyrics) { track?.let { S.loadLyricsFor(it) } }
    LaunchedEffect(track?.id, lyr?.lines?.size) {
        draft = lyr?.let { LyricsParser.toLRC(it) } ?: ""
        tapNext = lyr?.lines?.indexOfFirst { it.t == null }?.takeIf { it >= 0 } ?: 0
    }
    if (track == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text("Select or import a song to open Lyrics studio.", color = p.muted, fontSize = 14.sp, textAlign = TextAlign.Center) }
        return
    }
    val lines = lyr?.lines ?: emptyList()
    val off = lyr?.offset ?: 0.0
    val latency = s.tapLatency
    KeepScreenOn(mode == "tap")

    /** "Now", minus the user's measured reaction + output delay. */
    fun stampNow() = round(max(0.0, Engine.time - latency / 1000 * s.speed - off) * 100) / 100

    fun stampLineAt(i: Int) {
        val l = lyr ?: return
        if (i !in l.lines.indices) return
        val st = stampNow()
        S.saveLyrics(track.id, l.copy(lines = l.lines.mapIndexed { j, line ->
            if (j != i) line else {
                val prev = line.t ?: line.words?.firstOrNull()?.t
                val delta = if (prev == null) 0.0 else st - prev
                line.copy(t = st, words = line.words?.map { it.copy(t = max(0.0, it.t + delta)) })
            }
        }))
        val next = min(l.lines.size, i + 1)
        tapNext = next
        scope.launch { state.animateScrollToItem(5 + min(next, l.lines.size - 1), -state.layoutInfo.viewportSize.height / 3) }
    }

    fun undoTap() {
        val l = lyr ?: return
        if (tapNext <= 0) return
        val prev = tapNext - 1
        S.saveLyrics(track.id, l.copy(lines = l.lines.mapIndexed { j, line -> if (j == prev) line.copy(t = null, words = null) else line }))
        tapNext = prev
    }

    fun adopt(parsed: Lyrics, verb: String) {
        S.saveLyrics(track.id, parsed)
        tapNext = 0
        mode = if (parsed.lines.any { it.t == null }) "tap" else "preview"
        S.toast("${parsed.lines.size} lyric lines $verb")
    }

    LazyColumn(state = state, contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp), modifier = Modifier.fillMaxSize()) {
        item("head") {
            Column(Modifier.padding(start = 4.dp, top = 6.dp)) {
                Eyebrow("WORDS IN SYNC")
                Heading("Lyrics", "studio.")
                Text("Bring your words to life, one line at a time.", color = p.muted, fontSize = 13.sp)
            }
        }
        item("card") {
            Panel(Modifier.padding(top = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) { Icon(Ic.Mic, null, tint = p.accent, modifier = Modifier.size(26.dp)) }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("EDITING LYRICS FOR", color = p.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        Text(track.title, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist + (lyr?.source?.let { " · $it" } ?: ""), color = p.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Btn("Import lyrics", Modifier.padding(top = 12.dp), icon = Ic.Upload, accent = false) {
                    actions.pickText { name, text ->
                        val parsed = LyricsParser.parse(text, name)
                        if (parsed.lines.isEmpty()) S.toast("No readable lyrics found in that file") else {
                            draft = text
                            adopt(parsed, "imported")
                        }
                    }
                }
            }
        }
        item("tabs") {
            Row(Modifier.padding(top = 14.dp).clip(RoundedCornerShape(50)).background(p.input).padding(3.dp)) {
                listOf("edit" to "Text editor", "tap" to "Tap to sync", "preview" to "Preview").forEach { (id, label) ->
                    Text(label, color = if (mode == id) p.accentInk else p.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (mode == id) p.accent else Color.Transparent).clickable {
                            if (id == "edit") draft = lyr?.let { LyricsParser.toLRC(it) } ?: draft
                            mode = id
                        }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
        if (mode == "edit") item("editor") {
            Panel(Modifier.padding(top = 12.dp)) {
                Text("PASTE LYRICS OR LRC TIMESTAMPS", color = p.muted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("Supports .lrc, .srt, .vtt, .txt", color = p.faint, fontSize = 11.5.sp, modifier = Modifier.padding(bottom = 8.dp))
                Field(draft, { draft = it }, "Paste your lyrics here...\n\nOne line at a time. You can add timestamps later with Tap to sync.\n\nOr paste LRC like:\n[00:12.50] Your first line", Modifier.fillMaxWidth(), singleLine = false, mono = true, minHeight = 240.dp)
                Text("Text is applied when you tap Use these lyrics.", color = p.faint, fontSize = 11.5.sp, modifier = Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Strip times", accent = false) { draft = draft.lines().joinToString("\n") { it.replace(STRIP, "") } }
                    Btn("Use these lyrics", icon = Ic.Right) {
                        val parsed = LyricsParser.parse(draft, "edited")
                        if (parsed.lines.isEmpty()) S.toast("Paste some lyrics first") else adopt(parsed, "ready")
                    }
                }
            }
        } else {
            item("wsHead") {
                Column(Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (mode == "tap") "Tap when each line begins" else "Fine-tune your timing", Modifier.weight(1f), color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("${lines.size} LINES", color = p.faint, fontSize = 10.5.sp, fontFamily = Mono)
                    }
                    Text(
                        if (mode == "tap") "Play your song and tap each line in rhythm (subtracts ${latency.roundToInt()}ms reaction delay)." else "Edit a timestamp or lyric directly. Changes save automatically.",
                        color = p.muted, fontSize = 12.sp,
                    )
                    if (mode == "tap") Box(Modifier.padding(top = 8.dp)) { Chip("Calibrate (${latency.roundToInt()}ms)") { Ui.calibrate.value = true } }
                }
            }
            // Sticky so the tap pad stays under your thumb while the lines scroll.
            stickyHeader("tapBar") {
                if (mode == "tap" && lines.isNotEmpty()) {
                    val tap by rememberUpdatedState { stampLineAt(tapNext) }
                    Row(Modifier.fillMaxWidth().background(p.bg).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Btn("Undo", accent = false, enabled = tapNext > 0) { undoTap() }
                        val done = tapNext >= lines.size
                        Box(
                            Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(50)).background(if (done) p.input else p.accent)
                                .pointerInput(done) { if (!done) awaitEachGesture { awaitFirstDown(); tap() } }, // stamps on touch-down, not release
                            contentAlignment = Alignment.Center,
                        ) { Text(if (done) "All lines synced" else "TAP LINE ${tapNext + 1}", color = if (done) p.muted else p.accentInk, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold) }
                    }
                }
            }
            if (lines.isEmpty()) item("none") { Text("No lyrics yet. Paste text in Text editor or import a lyrics file to begin.", color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(20.dp)) }
            itemsIndexed(lines) { i, l ->
                val next = mode == "tap" && i == tapNext
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(if (next) p.accentSoft else Color.Transparent).padding(horizontal = 4.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (mode == "tap") Box(Modifier.size(30.dp).clip(CircleShape).background(if (l.t != null) p.accent else p.input).border(1.dp, p.border, CircleShape).clickable { stampLineAt(i) }, contentAlignment = Alignment.Center) {
                        Icon(Ic.Check, "Stamp line ${i + 1}", tint = if (l.t != null) p.accentInk else p.faint, modifier = Modifier.size(15.dp))
                    } else Text((i + 1).toString().padStart(2, '0'), color = p.faint, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
                    var text by remember(track.id, i, lines.size, lyr?.source) { mutableStateOf(l.text) }
                    BasicTextField(text, {
                        text = it
                        lyr?.let { cur -> S.saveLyrics(track.id, cur.copy(lines = cur.lines.mapIndexed { j, line -> if (j == i) line.copy(text = it, words = null) else line })) }
                    }, singleLine = true, cursorBrush = SolidColor(p.accent), textStyle = TextStyle(color = p.text, fontSize = 14.sp, fontFamily = DmSans), modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp))
                    var stamp by remember(l.t) { mutableStateOf(l.t?.let { LyricsParser.stamp(it) } ?: "") }
                    BasicTextField(stamp, { stamp = it }, singleLine = true, cursorBrush = SolidColor(p.accent), textStyle = TextStyle(color = p.accent, fontSize = 12.sp, fontFamily = Mono, textAlign = TextAlign.End),
                        modifier = Modifier.width(74.dp).clip(RoundedCornerShape(8.dp)).background(p.input).padding(horizontal = 6.dp, vertical = 7.dp).onFocusChanged { f ->
                            val cur = lyr
                            if (!f.isFocused && cur != null && stamp != (l.t?.let { LyricsParser.stamp(it) } ?: "")) {
                                val n = LyricsParser.parseCueTime(stamp)
                                if (n != null || stamp.isBlank()) S.saveLyrics(track.id, cur.copy(lines = cur.lines.mapIndexed { j, line ->
                                    if (j != i) line else {
                                        val prev = line.t ?: line.words?.firstOrNull()?.t
                                        val delta = if (n == null || prev == null) 0.0 else n - prev
                                        line.copy(t = n, words = if (n == null) null else line.words?.map { it.copy(t = max(0.0, it.t + delta)) })
                                    }
                                })) else stamp = l.t?.let { LyricsParser.stamp(it) } ?: ""
                            }
                        })
                    if (mode == "preview") IconBtn(Ic.Play, "Jump to line ${i + 1}", tint = p.accent, size = 14.dp, enabled = l.t != null, onClick = { l.t?.let { Engine.seek(it + off) } })
                }
            }
            item("wsFoot") {
                val pos by S.clock.collectAsState()
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Current position ${fmt(pos)}", color = p.muted, fontSize = 12.sp, fontFamily = Mono)
                    if (mode == "tap") Text("Next: ${min(tapNext + 1, lines.size)} / ${lines.size}", color = p.muted, fontSize = 12.sp, fontFamily = Mono)
                }
            }
        }
        item("shift") {
            Panel(Modifier.padding(top = 14.dp)) {
                Text("Shift all timestamps", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Fix lyrics that are early or late in one move.", color = p.muted, fontSize = 12.sp)
                fun shift(sign: Int) {
                    val l = lyr ?: return
                    val n = shiftAmt.toDoubleOrNull() ?: return
                    val from = if (fromCurrent) max(0, LyricsParser.activeIndex(l, Engine.time)) else 0
                    S.saveLyrics(track.id, LyricsParser.shiftFrom(l, from, sign * n))
                    S.toast("Shifted ${if (fromCurrent) "from line ${from + 1}" else "all timed lines"} ${if (sign > 0) "+" else "-"}${n}s")
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Ic.Minus, "Shift earlier", tint = p.text, onClick = { shift(-1) })
                    Field(shiftAmt, { shiftAmt = it }, "0.0", Modifier.width(74.dp), mono = true, number = true)
                    Text(" sec", color = p.muted, fontSize = 12.sp)
                    IconBtn(Ic.Plus, "Shift later", tint = p.text, onClick = { shift(1) })
                    Checkbox(fromCurrent, { fromCurrent = it }, colors = CheckboxDefaults.colors(checkedColor = p.accent, checkmarkColor = p.accentInk, uncheckedColor = p.muted))
                    Text("From current line", color = p.text, fontSize = 12.5.sp)
                }
                Btn("Export LRC", Modifier.padding(top = 8.dp), icon = Ic.Download, accent = false) {
                    actions.saveText("${track.artist} - ${track.title}.lrc", LyricsParser.toLRC(lyr ?: LyricsParser.parse(draft), track.title, track.artist))
                }
            }
        }
        if (lyr != null && lines.any { it.t != null }) item("offset") {
            Panel(Modifier.padding(top = 12.dp)) {
                Text("Live preview offset", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Nudge sync on the fly or bake permanently into timestamps.", color = p.muted, fontSize = 12.sp)
                fun nudge(d: Double) = S.saveLyrics(track.id, lyr.copy(offset = round((off + d) * 100) / 100))
                Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("-0.5s") { nudge(-0.5) }
                    Chip("-0.1s") { nudge(-0.1) }
                    Text("${if (off >= 0) "+" else ""}${"%.2f".format(off)}s", color = p.accent, fontSize = 13.sp, fontFamily = Mono)
                    Chip("+0.1s") { nudge(0.1) }
                    Chip("+0.5s") { nudge(0.5) }
                    if (off != 0.0) {
                        Chip("Bake") { S.saveLyrics(track.id, LyricsParser.bakeOffset(lyr)); S.toast("Offset baked into timestamps") }
                        Chip("Reset") { S.saveLyrics(track.id, lyr.copy(offset = 0.0)) }
                    }
                }
            }
        }
    }
}

/**
 * Tap-latency calibration: 16 clicks, the first 4 are a count-in, then tap on each click.
 * The median of (tap - click) is the user's reaction + audio output delay, saved as the tap offset.
 */
@Composable
fun CalibrateDialog(onClose: () -> Unit) {
    val p = LocalPal.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf("idle") }
    var count by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<Int?>(null) }
    val taps = remember { ArrayList<Double>() }
    val t0 = remember { longArrayOf(0L) }
    val beat = 0.6
    val total = 16
    val countIn = 4

    fun start() {
        val sr = 44100
        val n = ((total * beat + 0.8) * sr).toInt()
        val pcm = ShortArray(n)
        for (i in 0 until total) {
            val at = ((0.4 + i * beat) * sr).toInt()
            val f = if (i < countIn) 880.0 else 1320.0
            for (k in 0 until (0.035 * sr).toInt()) pcm[at + k] = (sin(2 * PI * f * k / sr) * exp(-k / (0.006 * sr)) * 26000).toInt().toShort()
        }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(n * 2).build()
        }.getOrNull() ?: return S.toast("Could not start the click track")
        taps.clear()
        count = 0
        result = null
        phase = "run"
        track.write(pcm, 0, n)
        t0[0] = System.nanoTime()
        track.play()
        scope.launch {
            delay(((total * beat + 0.9) * 1000).toLong())
            runCatching { track.release() }
            val d = taps.sorted()
            if (d.size >= 4) {
                val ms = (d[d.size / 2] * 1000).roundToInt().coerceIn(0, 600)
                result = ms
                S.set { it.copy(tapLatency = ms.toDouble()) }
                S.toast("Tap offset set to $ms ms")
            }
            phase = "done"
        }
    }

    fun tap() {
        val now = (System.nanoTime() - t0[0]) / 1e9 - 0.4
        val bi = round(now / beat)
        val d = now - bi * beat
        if (bi >= countIn && bi < total && abs(d) < beat / 2) taps.add(d)
        count++
    }
    val onPad by rememberUpdatedState { if (phase == "run") tap() else start() }

    Dialog(onDismissRequest = onClose) {
        Panel(pad = 20.dp) {
            Text("PRECISION TIMING", color = p.muted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text("Tap-latency calibration", fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = p.text)
            Text("You will hear 16 clicks. Let the first 4 pass, then tap the pad on each click. We measure your reaction + audio output delay and subtract it from every tap-sync stamp.", color = p.muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
            Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(132.dp).clip(CircleShape).background(p.accent).pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); onPad() } }, contentAlignment = Alignment.Center) {
                    Text(when (phase) { "idle" -> "START"; "run" -> "TAP $count"; else -> result?.let { "$it ms" } ?: "RETRY" }, color = p.accentInk, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Btn(if (phase == "done" && result != null) "Done" else "Close", accent = phase == "done" && result != null, onClick = onClose) }
        }
    }
}
