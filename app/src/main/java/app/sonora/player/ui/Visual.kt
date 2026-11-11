package app.sonora.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonora.player.Analysis
import app.sonora.player.Analyzer
import app.sonora.player.Engine
import app.sonora.player.Fft
import app.sonora.player.S
import app.sonora.player.SonoraDsp
import app.sonora.player.fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

private val MAPS = mapOf(
    "magma" to listOf(0xFF000004, 0xFF3B0F70, 0xFF8C2981, 0xFFDE4968, 0xFFFE9F6D, 0xFFFCFDBF),
    "viridis" to listOf(0xFF440154, 0xFF3B528B, 0xFF21918C, 0xFF5EC962, 0xFFFDE725),
    "gray" to listOf(0xFF000000, 0xFFFFFFFF),
    "miku" to listOf(0xFF020A0B, 0xFF0D4A4F, 0xFF39C5BB, 0xFFB8FFF7, 0xFFFFD0EA),
    "teto" to listOf(0xFF0B0305, 0xFF5A1524, 0xFFF0546C, 0xFFE9B56F, 0xFFFFF3D6),
    "neru" to listOf(0xFF0A0800, 0xFF5A4300, 0xFFFFD60A, 0xFFFF8A1F, 0xFFFFF7C9),
)

/** 256-entry colour lookup table for a colormap. */
private fun lut(name: String): IntArray {
    val stops = (MAPS[name] ?: MAPS.getValue("magma")).map { Color(it) }
    return IntArray(256) { i ->
        val x = i / 255f * (stops.size - 1)
        val k = x.toInt().coerceAtMost(stops.size - 2)
        androidx.compose.ui.graphics.lerp(stops[k], stops[k + 1], x - k).toArgb()
    }
}

private fun render(a: Analysis, map: String, log: Boolean, floor: Double): ImageBitmap? {
    val spec = a.spec ?: return null
    if (spec.isEmpty()) return null
    val w = spec.size
    val h = 256
    val bins = spec[0].size
    val ny = a.sampleRate / 2.0
    val colors = lut(map)
    val px = IntArray(w * h)
    val binFor = IntArray(h) { y ->
        val fy = 1 - y / (h - 1.0)
        val f = if (log) 20 * (ny / 20).pow(fy) else fy * ny
        (f / ny * (bins - 1)).roundToInt().coerceIn(0, bins - 1)
    }
    for (x in 0 until w) {
        val col = spec[x]
        for (y in 0 until h) px[y * w + x] = colors[(((col[binFor[y]] - floor) / (-12 - floor)).coerceIn(0.0, 1.0) * 255).toInt()]
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Full-track spectrogram: decoded and analysed once per track / FFT size, then recoloured instantly. */
@Composable
private fun Spectrogram() {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val s by S.settings.collectAsState()
    val ps by S.player.collectAsState()
    val t by S.clock.collectAsState()
    val track = currentTrack()
    var floor by rememberSaveable { mutableStateOf(-90.0) }
    var analysis by remember { mutableStateOf<Analysis?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(track?.id, s.fftSize) {
        analysis = null
        val tr = track ?: return@LaunchedEffect
        busy = true
        val job = coroutineContext[Job]
        analysis = withContext(Dispatchers.Default) { Analyzer.analyze(ctx, tr, s.fftSize, 520, 900.0) { job?.isActive == false } }
        busy = false
    }
    val img = remember(analysis, s.colormap, s.specLog, floor) { analysis?.let { render(it, s.colormap, s.specLog, floor) } }
    val dur = analysis?.seconds?.takeIf { it > 0 } ?: ps.duration
    val durNow by rememberUpdatedState(dur)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MAPS.keys.forEach { m -> Chip(m.replaceFirstChar { it.uppercase() }, active = s.colormap == m) { S.set { it.copy(colormap = m) } } }
    }
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Linear", active = !s.specLog) { S.set { it.copy(specLog = false) } }
        Chip("Log", active = s.specLog) { S.set { it.copy(specLog = true) } }
        listOf(1024, 2048, 4096).forEach { n -> Chip("FFT $n", active = s.fftSize == n) { S.set { it.copy(fftSize = n) } } }
    }
    SSlider("Floor", floor, -110.0, -45.0, 1.0, { "${it.roundToInt()} dB" }) { floor = it }
    Box(Modifier.fillMaxWidth().height(290.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.5f)).border(1.dp, p.border, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        if (img != null) Canvas(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { o -> if (durNow > 0) Engine.seek(o.x / size.width * durNow) } }) {
            drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
            if (dur > 0) {
                val x = (t / dur).toFloat().coerceIn(0f, 1f) * size.width
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
            }
        } else Text(if (track == null) "Play a song to see its spectrogram." else if (busy) "Analysing ${track.title}…" else "This file could not be analysed.", color = p.muted, fontSize = 13.sp)
        analysis?.let { a ->
            val ny = a.sampleRate / 2
            Column(Modifier.align(Alignment.CenterEnd).fillMaxSize().padding(end = 6.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                val ticks = if (s.specLog) listOf(ny, 5000, 1000, 200, 20) else listOf(ny, ny * 3 / 4, ny / 2, ny / 4, 0)
                ticks.forEach { f -> Text(if (f >= 1000) "${f / 1000}k" else "$f", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = Mono) }
            }
        }
    }
    Text("Tap the spectrogram to seek. ${fmt(t)} / ${fmt(dur)}", color = p.faint, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp))
}

/** Live spectrum, oscilloscope or stereo vectorscope, drawn every frame from the DSP output tap. */
@Composable
private fun Live(mode: String) {
    val p = LocalPal.current
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame.longValue = it } }
    // The player only copies its output for the visualisers while one of them is on screen.
    DisposableEffect(Unit) {
        Engine.setTapOn(true)
        onDispose { Engine.setTapOn(false) }
    }
    val n = 2048
    val l = remember { FloatArray(n) }
    val r = remember { FloatArray(n) }
    val mono = remember { FloatArray(n) }
    val mags = remember { FloatArray(n / 2) }
    val bars = remember { FloatArray(56) }
    Canvas(Modifier.fillMaxWidth().height(290.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.45f)).border(1.dp, p.border, RoundedCornerShape(12.dp))) {
        frame.longValue // redraw on every frame
        val w = size.width
        val h = size.height
        Engine.mixedSnapshot(n, l, r)
        when (mode) {
            "spectrum" -> {
                for (i in 0 until n) mono[i] = (l[i] + r[i]) * 0.5f
                Fft.magnitudes(mono, n, mags)
                val ny = Engine.dsp.sampleRate / 2.0
                val bw = w / bars.size
                for (b in bars.indices) {
                    val f0 = 30 * (ny / 30).pow(b / bars.size.toDouble())
                    val f1 = 30 * (ny / 30).pow((b + 1) / bars.size.toDouble())
                    val i0 = (f0 / ny * mags.size).toInt().coerceIn(1, mags.size - 1)
                    val i1 = max(i0 + 1, (f1 / ny * mags.size).toInt().coerceAtMost(mags.size))
                    var m = 0f
                    for (i in i0 until i1) m = max(m, mags[i])
                    val v = ((20 * log10(m + 1e-7f) + 80) / 80).coerceIn(0f, 1f)
                    bars[b] = if (v > bars[b]) v else bars[b] * 0.9f + v * 0.1f // fast attack, slow fall
                    drawRoundRect(Brush.verticalGradient(listOf(p.accent2, p.accent), h - bars[b] * h, h), Offset(b * bw + bw * 0.15f, h - bars[b] * h), Size(bw * 0.7f, bars[b] * h), CornerRadius(3f))
                }
            }
            "scope" -> {
                val path = Path()
                val m = 1024
                for (i in 0 until m) {
                    val x = i / (m - 1f) * w
                    val y = h / 2 - (l[n - m + i] + r[n - m + i]) * 0.5f * h * 0.45f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawLine(p.border, Offset(0f, h / 2), Offset(w, h / 2), 1f)
                drawPath(path, p.accent, style = Stroke(1.8.dp.toPx()))
            }
            else -> {
                // Lissajous rotated 45 degrees: mono is a vertical line, wide stereo spreads sideways.
                val c = Offset(w / 2, h / 2)
                val k = minOf(w, h) * 0.48f
                drawLine(p.border, Offset(c.x, 0f), Offset(c.x, h), 1f)
                drawLine(p.border, Offset(0f, c.y), Offset(w, c.y), 1f)
                val col = p.accent2.copy(alpha = 0.55f)
                for (i in n - 1024 until n) drawCircle(col, 1.4f, Offset(c.x + (l[i] - r[i]) * 0.707f * k, c.y - (l[i] + r[i]) * 0.707f * k))
            }
        }
    }
}

@Composable
fun VisualScreen() {
    val p = LocalPal.current
    var tab by rememberSaveable { mutableStateOf("spectrogram") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 20.dp)) {
        Column(Modifier.padding(start = 4.dp, top = 6.dp, bottom = 10.dp)) {
            Eyebrow("EXPLORE THE DETAILS")
            Heading("Visual", "analyzer.")
            Text("See what your music is made of.", color = p.muted, fontSize = 13.sp)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Back", icon = Ic.Back) { Ui.view.value = "sound" }
            listOf("spectrogram" to "Spectrogram", "spectrum" to "Live spectrum", "scope" to "Oscilloscope", "vector" to "Vectorscope").forEach { (k, label) -> Chip(label, active = tab == k) { tab = k } }
        }
        Panel {
            if (tab == "spectrogram") Spectrogram() else {
                Live(tab)
                Text(
                    when (tab) {
                        "spectrum" -> "Live spectrum of the processed output (after EQ, stage and limiter)."
                        "scope" -> "Oscilloscope: the output waveform, about 20 ms per sweep."
                        else -> "Stereo vectorscope: a vertical line is mono, a wide cloud is wide stereo, a horizontal line is out of phase."
                    },
                    color = p.faint, fontSize = 11.5.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
