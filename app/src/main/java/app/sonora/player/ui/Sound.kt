package app.sonora.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonora.player.DspParams
import app.sonora.player.EQ_FREQS
import app.sonora.player.Engine
import app.sonora.player.S
import app.sonora.player.Settings
import app.sonora.player.SonoraDsp
import app.sonora.player.TrackAudioConfig
import app.sonora.player.applyMidsToEq
import app.sonora.player.parseAutoEq
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

private class Preset(val name: String, val bass: Double, val mids: Double, val treble: Double, val eq: List<Double>)

private val PRESETS = listOf(
    Preset("Flat", 0.0, 0.0, 0.0, List(10) { 0.0 }),
    Preset("Deep bass", 6.0, -2.0, 2.0, listOf(6.0, 5.0, 3.0, 1.0, -1.5, -2.0, -1.5, 0.0, 1.5, 2.0)),
    Preset("Clarity", 2.0, 3.0, 4.0, listOf(2.0, 1.5, 0.0, 1.0, 2.5, 3.0, 2.5, 3.5, 4.0, 4.0)),
    Preset("V-shape", 4.0, -1.0, 4.0, listOf(5.0, 4.0, 2.0, 0.0, -1.0, -1.5, -1.0, 2.0, 4.0, 5.0)),
    Preset("Vocaloid pop", 3.0, 3.0, 3.0, listOf(3.0, 2.0, 0.0, -1.0, 1.5, 3.0, 3.5, 4.0, 3.0, 2.0)),
    Preset("Harman-ish", 4.0, 1.0, -1.0, listOf(4.0, 3.5, 2.0, 0.0, 0.5, 1.0, 1.5, 2.5, 0.0, -2.0)),
)

private fun pct(v: Double) = "${(v * 100).roundToInt()}%"
private fun r4(v: Double) = (v * 10000).roundToInt() / 10000.0

@Composable
private fun CardHead(icon: ImageVector, eyebrow: String, title: String, trailing: @Composable () -> Unit = {}) {
    val p = LocalPal.current
    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) { Icon(icon, null, tint = p.accent, modifier = Modifier.size(23.dp)) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(eyebrow, color = p.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text(title, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = p.text)
        }
        trailing()
    }
}

/** Live frequency response of the EQ chain (bass shelf + 10 bands + treble shelf). */
@Composable
private fun ResponseCurve(s: Settings) {
    val p = LocalPal.current
    val freqs = remember { DoubleArray(140) { 20 * 1000.0.pow(it / 139.0) } }
    val resp = remember(s.eq, s.bassDb, s.trebleDb, s.eqOn) { SonoraDsp.response(DspParams(s.eqOn, s.eq, s.bassDb, s.trebleDb), freqs) }
    Canvas(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)).background(p.input)) {
        val w = size.width
        val h = size.height
        drawLine(p.border, Offset(0f, h / 2), Offset(w, h / 2), 1f)
        for (f in EQ_FREQS) {
            val x = (log2(f / 20.0) / log2(1000.0)).toFloat() * w
            drawLine(p.border.copy(alpha = 0.4f), Offset(x, 0f), Offset(x, h), 1f)
        }
        val path = Path()
        for (i in resp.indices) {
            val x = i / (resp.size - 1f) * w
            val y = (h / 2 - (resp[i] / 15) * (h / 2 - 6)).toFloat().coerceIn(2f, h - 2f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val grad = Brush.horizontalGradient(listOf(p.accent, p.accent2))
        drawPath(path, grad, style = Stroke(2.4.dp.toPx()))
        path.lineTo(w, h / 2)
        path.lineTo(0f, h / 2)
        path.close()
        drawPath(path, grad, alpha = 0.14f)
    }
}

@Composable
private fun VSlider(value: Double, onChange: (Double) -> Unit, modifier: Modifier) {
    val p = LocalPal.current
    val cb by rememberUpdatedState(onChange)
    Canvas(modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            fun v(y: Float) = ((12 - (y / size.height).coerceIn(0f, 1f) * 24) * 2).roundToInt() / 2.0
            cb(v(down.position.y))
            drag(down.id) {
                cb(v(it.position.y))
                it.consume()
            }
        }
    }) {
        val cx = size.width / 2
        val y = ((12 - value) / 24).toFloat() * size.height
        drawRoundRect(p.border, Offset(cx - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height), CornerRadius(4f))
        drawRoundRect(p.accent, Offset(cx - 2.dp.toPx(), minOf(y, size.height / 2)), Size(4.dp.toPx(), abs(y - size.height / 2)), CornerRadius(4f))
        drawLine(p.faint, Offset(cx - 7.dp.toPx(), size.height / 2), Offset(cx + 7.dp.toPx(), size.height / 2), 1f)
        drawCircle(p.accent, 8.dp.toPx(), Offset(cx, y.coerceIn(0f, size.height)))
    }
}

@Composable
private fun LimiterMeter() {
    val p = LocalPal.current
    val ps by S.meter.collectAsState()
    val s by S.settings.collectAsState()
    Column(Modifier.padding(top = 4.dp)) {
        Text(if (ps.clipping) "CLIPPING DETECTED — enable limiter or lower boost" else if (!s.limiter) "Limiter bypassed" else if (ps.limiting < -0.1) "Brick-wall limiter working" else "Limiter ready (steps in only when peaks would clip)", color = if (ps.clipping) p.accent2 else p.muted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
        if (s.limiter) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(50)).background(p.border)) {
                Box(Modifier.fillMaxWidth((-ps.limiting / 12).toFloat().coerceIn(0f, 1f)).height(5.dp).background(p.accent2))
            }
            Text("${"%.1f".format(ps.limiting)} dB", color = p.muted, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
fun SoundScreen() {
    val p = LocalPal.current
    val actions = LocalActions.current
    val s by S.settings.collectAsState()
    val ps by S.player.collectAsState()
    val user by S.user.collectAsState()
    val outs by S.outs.collectAsState()
    val track = currentTrack()
    var advanced by rememberSaveable { mutableStateOf(false) }
    var autoEqOpen by remember { mutableStateOf(false) }
    var autoEqText by remember { mutableStateOf("") }
    var profileName by remember { mutableStateOf("") }
    var bindKey by remember { mutableStateOf("") }
    val set = { f: (Settings) -> Settings -> S.set(f) }
    val normalized = s.rgMode != "off"
    val cfg = track?.let { user.trackConfigs[it.id] }
    val semis = (12 * log2(s.speed.coerceAtLeast(0.25))).roundToInt()

    LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(start = 4.dp, top = 6.dp)) {
                Eyebrow("SHAPE YOUR SOUND")
                Heading("Sound", "lab.")
                Text("A finer listening experience, tuned by you.", color = p.muted, fontSize = 13.sp)
            }
        }
        item {
            Panel {
                CardHead(Ic.Sliders, "THREE-BAND EQUALIZER", "Make it yours") {
                    Btn("Reset", accent = false) { set { it.copy(bassDb = 0.0, midsDb = 0.0, trebleDb = 0.0, boostDb = 0.0, eq = List(10) { 0.0 }) } }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESETS.forEach { pr ->
                        Chip(pr.name, active = s.bassDb == pr.bass && s.midsDb == pr.mids && s.trebleDb == pr.treble) { set { it.copy(bassDb = pr.bass, midsDb = pr.mids, trebleDb = pr.treble, eq = pr.eq, eqOn = true) } }
                    }
                }
                Spacer(Modifier.height(10.dp))
                SSlider("Bass", s.bassDb, -12.0, 12.0, 0.5) { v -> set { it.copy(bassDb = v, eqOn = true) } }
                SSlider("Mids", s.midsDb, -12.0, 12.0, 0.5) { v -> set { it.copy(midsDb = v, eq = applyMidsToEq(it.eq, v), eqOn = true) } }
                SSlider("Treble", s.trebleDb, -12.0, 12.0, 0.5) { v -> set { it.copy(trebleDb = v, eqOn = true) } }
                Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(p.border))
                Text("Safe sound boost", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Increase gain behind a brick-wall limiter to prevent clipping.", color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                SSlider("Boost level", s.boostDb, 0.0, 15.0, 0.5) { v -> set { it.copy(boostDb = v, limiter = true) } }
                LimiterMeter()
                SettingRow(
                    "Smart normalization (ReplayGain / EBU R128)",
                    "Reads ReplayGain tags, or measures track loudness in the background, and evens out playback levels" + (if (normalized) " (active: ${if (ps.rgApplied > 0) "+" else ""}${"%.1f".format(ps.rgApplied)} dB)" else "."),
                ) { Toggle(normalized) { on -> set { it.copy(rgMode = if (on) "track" else "off", rgAnalyze = true) } } }
                if (normalized) Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Track gain", active = s.rgMode == "track") { set { it.copy(rgMode = "track") } }
                    Chip("Album gain", active = s.rgMode == "album") { set { it.copy(rgMode = "album") } }
                }
                if (track != null) {
                    Text("Per-track sound memory: ${track.title}", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    Text(
                        if (cfg != null) "Custom tuning pinned (${"%.2f".format(cfg.speed ?: 1.0)}× · Bass ${cfg.bassDb ?: 0.0}dB · Mids ${cfg.midsDb ?: 0.0}dB · Treble ${cfg.trebleDb ?: 0.0}dB)"
                        else "Pin the current EQ & speed specifically to this song so it restores whenever played.",
                        color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(if (cfg != null) "Update pin" else "Pin to track", active = cfg != null) {
                            S.saveTrackConfig(track.id, TrackAudioConfig(s.speed, s.keepPitch, s.bassDb, s.midsDb, s.trebleDb, s.boostDb))
                            S.toast("Pinned tuning for \"${track.title}\"")
                        }
                        if (cfg != null) Chip("Clear") { S.saveTrackConfig(track.id, null); S.toast("Cleared per-track tuning") }
                    }
                }
            }
        }
        item {
            Panel {
                CardHead(Ic.Bolt, "PRECISION AUDIO ENGINE", "Advanced DSP & 10-band EQ")
                Btn(if (advanced) "Hide advanced" else "Open advanced", icon = if (advanced) Ic.Up else Ic.Down, accent = false) { advanced = !advanced }
                if (!advanced) Text("Fine-tune 10 bands with AutoEQ import, live frequency response curve, 8D spatial orbit, crossfeed, gapless crossfade, and 432Hz / semitone pitch modes.", color = p.muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp))
                else {
                    Text("10-Band Equalizer & Live Response", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Import AutoEQ", active = autoEqOpen) { autoEqOpen = !autoEqOpen }
                        Chip(if (s.eqOn) "EQ Active" else "EQ Bypassed", active = s.eqOn) { set { it.copy(eqOn = !it.eqOn) } }
                    }
                    if (autoEqOpen) {
                        Text("Paste an AutoEQ / EqualizerAPO parametric preset (Preamp: -4.2 dB, Filter 1: ON PK Fc 105 Hz Gain 4.5 dB Q 0.71, or GraphicEQ: ...):", color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                        Field(autoEqText, { autoEqText = it }, "Preamp: -3.5 dB\nFilter 1: ON LSC Fc 105 Hz Gain 4.5 dB Q 0.70\nFilter 2: ON PK Fc 1200 Hz Gain -2.0 dB Q 1.20", Modifier.fillMaxWidth(), singleLine = false, mono = true, minHeight = 96.dp)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Cancel") { autoEqOpen = false }
                            Chip("Apply AutoEQ", active = true) {
                                val a = parseAutoEq(autoEqText)
                                if (a == null) S.toast("No valid AutoEQ / Parametric filters found") else {
                                    set { it.copy(eq = a.eq, preampDb = a.preampDb, bassDb = a.bassDb, midsDb = a.midsDb, trebleDb = a.trebleDb, eqOn = true) }
                                    autoEqOpen = false
                                    S.toast("AutoEQ curve imported")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    ResponseCurve(s)
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        EQ_FREQS.forEachIndexed { i, f ->
                            val g = s.eq.getOrElse(i) { 0.0 }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${if (g > 0) "+" else ""}${"%.1f".format(g)}", color = p.accent, fontSize = 9.sp, fontFamily = Mono, maxLines = 1)
                                VSlider(g, { v -> set { it.copy(eq = it.eq.toMutableList().apply { while (size < 10) add(0.0); this[i] = v }) } }, Modifier.fillMaxWidth().height(150.dp).padding(vertical = 8.dp))
                                Text(if (f >= 1000) "${f / 1000}k" else "$f", color = p.muted, fontSize = 9.5.sp, fontFamily = Mono, textAlign = TextAlign.Center)
                            }
                        }
                    }
                    SSlider("Pre-amp headroom", s.preampDb, -15.0, 6.0, 0.5) { v -> set { it.copy(preampDb = v) } }
                    ToggleRow("Auto headroom", "Lowers pre-amp by largest EQ boost to eliminate digital clipping", s.autoPreamp) { on -> set { it.copy(autoPreamp = on) } }
                    Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(p.border))
                    Text("Spatial Stage & 8D Orbit", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("normal" to "Stereo", "mono" to "Mono", "vocal" to "Vocal focus", "karaoke" to "Karaoke (no vocals)").forEach { (k, l) -> Chip(l, active = s.stereoMode == k) { set { it.copy(stereoMode = k) } } }
                    }
                    Spacer(Modifier.height(8.dp))
                    SSlider("Stereo width", s.width, 0.0, 2.5, 0.05, ::pct) { v -> set { it.copy(width = v, stereoMode = "normal") } }
                    SSlider("Headphone crossfeed", s.crossfeed, 0.0, 1.0, 0.05, ::pct) { v -> set { it.copy(crossfeed = v) } }
                    SSlider("Room ambience", s.room, 0.0, 1.0, 0.05, ::pct) { v -> set { it.copy(room = v) } }
                    ToggleRow("8D Orbit", "Pans the sound slowly around your head (best on headphones)", s.orbit) { on -> set { it.copy(orbit = on) } }
                    if (s.orbit) SSlider("Orbit speed", s.orbitSpeed, 0.03, 0.6, 0.01, { "${"%.1f".format(1 / it)}s / rev" }) { v -> set { it.copy(orbitSpeed = v) } }
                    Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(p.border))
                    Text("Transitions, ReplayGain & Speed/Pitch", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    ToggleRow("Gapless playback", "Pre-buffers the next track for zero-gap transitions", s.gapless) { on -> set { it.copy(gapless = on) } }
                    SSlider("Crossfade duration", s.crossfade, 0.0, 12.0, 0.5, { if (it > 0) "${it}s" else "Off" }) { v -> set { it.copy(crossfade = v) } }
                    SSlider("Play / pause smooth fade", s.playPauseFadeMs, 0.0, 600.0, 20.0, { if (it > 0) "${it.roundToInt()} ms" else "Instant" }) { v -> set { it.copy(playPauseFadeMs = v) } }
                    SSlider("ReplayGain pre-amp", s.rgPreamp, -6.0, 6.0, 0.5) { v -> set { it.copy(rgPreamp = v) } }
                    SSlider("Playback speed", s.speed, 0.5, 2.0, 0.01, { "${"%.2f".format(it)}×" }) { v -> set { it.copy(speed = v) } }
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("432Hz Natural", active = abs(s.speed - 432.0 / 440) < 0.002 && !s.keepPitch) { set { it.copy(speed = r4(432.0 / 440), keepPitch = false) } }
                        Chip("Slowed + Reverb", active = s.speed == 0.85 && !s.keepPitch) { set { it.copy(speed = 0.85, keepPitch = false, room = maxOf(it.room, 0.25)) } }
                        Chip("1.0× Normal", active = s.speed == 1.0 && s.keepPitch) { set { it.copy(speed = 1.0, keepPitch = true) } }
                        Chip("Nightcore", active = s.speed == 1.25 && !s.keepPitch) { set { it.copy(speed = 1.25, keepPitch = false) } }
                        Chip("-1 Semitone") { set { it.copy(speed = r4(2.0.pow(maxOf(-12, semis - 1) / 12.0)), keepPitch = false) } }
                        Chip("+1 Semitone") { set { it.copy(speed = r4(2.0.pow(minOf(12, semis + 1) / 12.0)), keepPitch = false) } }
                    }
                }
            }
        }
        item {
            Panel {
                CardHead(Ic.Wave, "EXPLORE THE DETAILS", "Visual analyzer")
                Text("Inspect full-track FFT spectrograms (including hidden spectrogram art), live spectrum, oscilloscope, and stereo vectorscope.", color = p.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
                Btn("Open spectrogram", Modifier.padding(top = 12.dp), icon = Ic.Right) { Ui.view.value = "visual" }
            }
        }
        item {
            Panel {
                CardHead(Ic.Headphones, "PERSONAL LISTENING", "Output profile")
                Text("Save this EQ & spatial setup for your selected output device. Profiles apply automatically when a bound headset connects.", color = p.muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 10.dp))
                Select(bindKey, listOf("" to "No device (apply manually)") + outs.map { it.key to it.label }) {
                    bindKey = it
                    S.toast(if (it.isEmpty()) "Profile will not be bound to a device" else "Profile will bind to this output")
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(profileName, { profileName = it }, "Profile name (e.g. Buds Pro)", Modifier.weight(1f))
                    Btn("Save profile") {
                        val name = profileName.trim().ifEmpty { "Output ${s.profiles.size + 1}" }
                        Engine.saveProfile(name, bindKey.ifEmpty { null })
                        profileName = ""
                        S.toast("Saved profile \"$name\"")
                    }
                }
                s.profiles.forEach { pr ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(p.input).border(1.dp, p.border, RoundedCornerShape(10.dp)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pr.name, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            s.deviceMap.entries.firstOrNull { it.value == pr.id }?.let { Text("Bound to ${it.key}", color = p.faint, fontSize = 11.sp) }
                        }
                        Chip("Apply") { Engine.applyProfile(pr); S.toast("Applied \"${pr.name}\"") }
                        IconBtn(Ic.Close, "Delete profile ${pr.name}", size = 15.dp, onClick = { set { st -> st.copy(profiles = st.profiles.filter { it.id != pr.id }, deviceMap = st.deviceMap.filterValues { it != pr.id }) } })
                    }
                }
                Text("Android plays through your connected headset or speaker automatically." + (if (ps.outputLabel.isNotEmpty()) " Connected: ${ps.outputLabel}." else ""), color = p.faint, fontSize = 11.5.sp, modifier = Modifier.padding(top = 10.dp))
                if (Engine.namesHidden()) Btn("Show real headset names (allow Nearby devices)", Modifier.padding(top = 8.dp), accent = false) { actions.askBluetooth() }
            }
        }
    }
}
