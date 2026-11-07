// Sonora Native — SoundAndVisualScreens.kt
// Sound Tab (Live EQ Response Curve, 10-band EQ, Presets, AutoEQ Import, Tone & Dynamics,
// Spatial/Pitch/Crossfade, Orbit 8D & Room FX, Sound Profiles + Device Binding, Limiter Meter)
// and Visual Tab (Waveform, 2D Spectrogram with 6 colormaps, Live 2048-FFT Spectrum, Technical readout).

package app.sonora.player.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonora.player.Graph
import app.sonora.player.audio.*
import app.sonora.player.data.*
import app.sonora.player.library.LibraryRepo
import app.sonora.player.library.SonoraUtil
import app.sonora.player.ui.*
import kotlin.math.max
import kotlin.math.pow
import kotlinx.coroutines.delay

@Composable
fun ResponseCurveCanvas(
    settings: Settings,
    modifier: Modifier = Modifier,
) {
    val theme = LocalTheme.current
    val curveDb = remember(
        settings.eq,
        settings.eqOn,
        settings.bassDb,
        settings.trebleDb,
        settings.preampDb,
        settings.boostDb,
    ) {
        EqResponseCurve.computeCurveDb(settings, points = 200)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(theme.bgElevated)
            .border(1.dp, theme.borderStrong, RoundedCornerShape(14.dp))
            .padding(8.dp),
    ) {
        val w = size.width
        val h = size.height
        val midY = h / 2f

        for (db in listOf(-12f, -6f, 0f, 6f, 12f)) {
            val y = midY - (db / 15f) * (h / 2f)
            drawLine(
                color = if (db == 0f) theme.borderStrong else theme.border,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = if (db == 0f) 1.5f else 1f,
            )
        }

        val linePath = Path()
        val fillPath = Path()
        for (i in curveDb.indices) {
            val x = (i.toFloat() / (curveDb.size - 1).toFloat()) * w
            val db = curveDb[i].coerceIn(-15f, 15f)
            val y = midY - (db / 15f) * (h / 2f)
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, midY)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, midY)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(theme.accent.copy(alpha = 0.30f), Color.Transparent),
            ),
        )
        drawPath(
            path = linePath,
            color = theme.accent,
            style = Stroke(width = 2.4.dp.toPx()),
        )
    }
}

@Composable
fun LimiterMeterRow(
    player: PlayerController,
    modifier: Modifier = Modifier,
) {
    val theme = LocalTheme.current
    val ui by player.state.collectAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(theme.bgElevated)
            .border(1.dp, theme.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        SonoraText("Limiter Gain Reduction", color = theme.textSub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SonoraText(
                text = String.format(java.util.Locale.US, "-%.1f dB", ui.limitingDb),
                color = if (ui.limitingDb > 0.5) theme.warn else theme.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = SonoraFonts.Manrope,
            )
            if (ui.clipping) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(theme.danger)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    SonoraText("CLIP", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
fun SoundScreen(
    settings: Settings,
    deviceManager: DeviceManager,
    player: PlayerController,
    sheets: SheetsStore,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onToast: (String) -> Unit,
) {
    val theme = LocalTheme.current
    val activeRoute by deviceManager.activeRoute.flow.collectAsState()
    var activePresetKey by remember { mutableStateOf("custom") }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        SectionKicker("Composite Frequency Response (20 Hz – 20 kHz)")
        ResponseCurveCanvas(settings = settings)
        LimiterMeterRow(player = player)

        // 10-Band EQ Card
        SoundCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SectionKicker("10-Band Equalizer")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SonoraButton("Import AutoEQ", compact = true, onClick = {
                        sheets.askPrompt(
                            title = "Paste AutoEQ / Parametric EQ Text",
                            placeholder = "Preamp: -4.2 dB\nFilter 1: ON PK Fc 62 Hz Gain 3.5 dB Q 1.4",
                            confirmLabel = "Apply AutoEQ",
                        ) { text ->
                            val preset = parseAutoEqText(text)
                            if (preset != null) {
                                onUpdateSettings {
                                    it.copy(
                                        eq = preset.eq,
                                        preampDb = preset.preampDb,
                                        bassDb = preset.bassDb,
                                        midsDb = preset.midsDb,
                                        trebleDb = preset.trebleDb,
                                        eqOn = true,
                                    )
                                }
                                activePresetKey = "autoeq"
                                onToast("Applied AutoEQ curve")
                            } else {
                                onToast("Could not parse AutoEQ text")
                            }
                        }
                    })
                    SonoraSwitch(
                        checked = settings.eqOn,
                        onCheckedChange = { on -> onUpdateSettings { it.copy(eqOn = on) } },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(EQ_PRESETS.keys.toList()) { presetKey ->
                    SonoraChip(
                        label = presetKey.replaceFirstChar { it.uppercase() },
                        selected = activePresetKey == presetKey,
                        onClick = {
                            val gains = EQ_PRESETS[presetKey] ?: return@SonoraChip
                            activePresetKey = presetKey
                            onUpdateSettings { it.copy(eq = gains, eqOn = true) }
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val labels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
                for (i in 0 until 10) {
                    SonoraVerticalEqSlider(
                        freqLabel = labels[i],
                        dbValue = settings.eq.getOrElse(i) { 0.0 },
                        enabled = settings.eqOn,
                        onValueChange = { newDb ->
                            activePresetKey = "custom"
                            onUpdateSettings { s ->
                                val next = s.eq.toMutableList()
                                while (next.size < 10) next.add(0.0)
                                next[i] = newDb
                                s.copy(eq = next)
                            }
                        },
                    )
                }
            }
        }

        // Tone & Dynamics Card
        SoundCard {
            SectionKicker("Tone & Dynamics")
            Spacer(Modifier.height(8.dp))
            SoundSliderRow("Bass Shelf (95 Hz)", "${String.format("%.1f", settings.bassDb)} dB", settings.bassDb.toFloat(), -12f..12f) { v ->
                onUpdateSettings { it.copy(bassDb = v.toDouble()) }
            }
            SoundSliderRow("Vocal Mids Macro", "${String.format("%.1f", settings.midsDb)} dB", settings.midsDb.toFloat(), -12f..12f) { v ->
                onUpdateSettings { s -> s.copy(midsDb = v.toDouble(), eq = applyMidsToEq(s.eq, v.toDouble())) }
            }
            SoundSliderRow("Treble Air (7.5 kHz)", "${String.format("%.1f", settings.trebleDb)} dB", settings.trebleDb.toFloat(), -12f..12f) { v ->
                onUpdateSettings { it.copy(trebleDb = v.toDouble()) }
            }
            SoundSliderRow("Preamp", "${String.format("%.1f", settings.preampDb)} dB", settings.preampDb.toFloat(), -12f..12f) { v ->
                onUpdateSettings { it.copy(preampDb = v.toDouble()) }
            }
            SoundSliderRow("Boost", "+${String.format("%.1f", settings.boostDb)} dB", settings.boostDb.toFloat(), 0f..12f) { v ->
                onUpdateSettings { it.copy(boostDb = v.toDouble()) }
            }
            Spacer(Modifier.height(6.dp))
            ToggleSettingRow("Auto Preamp Headroom Compensation", settings.autoPreamp) { on ->
                onUpdateSettings { it.copy(autoPreamp = on) }
            }
            ToggleSettingRow("Brickwall Safety Limiter (-0.3 dBFS)", settings.limiter) { on ->
                onUpdateSettings { it.copy(limiter = on) }
            }
            ToggleSettingRow("ReplayGain / LUFS Normalization", settings.rgMode != "off") { on ->
                onUpdateSettings { it.copy(rgMode = if (on) "track" else "off") }
            }
        }

        // Spatial, Pitch & Transitions Card
        SoundCard {
            SectionKicker("Spatial, Pitch & Transitions")
            Spacer(Modifier.height(8.dp))
            SoundSliderRow("Stereo Width", "${(settings.width * 100).toInt()}%", settings.width.toFloat(), 0f..2f) { v ->
                onUpdateSettings { it.copy(width = v.toDouble()) }
            }
            SoundSliderRow("Binaural Crossfeed", "${(settings.crossfeed * 100).toInt()}%", settings.crossfeed.toFloat(), 0f..1f) { v ->
                onUpdateSettings { it.copy(crossfeed = v.toDouble()) }
            }
            SoundSliderRow("Playback Speed", String.format("%.2fx", settings.speed), settings.speed.toFloat(), 0.5f..2.0f) { v ->
                onUpdateSettings { it.copy(speed = v.toDouble()) }
            }
            SoundSliderRow("Crossfade Overlap", "${String.format("%.1f", settings.crossfade)} s", settings.crossfade.toFloat(), 0f..12f) { v ->
                onUpdateSettings { it.copy(crossfade = v.toDouble()) }
            }
            Spacer(Modifier.height(6.dp))
            ToggleSettingRow("Preserve Pitch When Changing Speed", settings.keepPitch) { on ->
                onUpdateSettings { it.copy(keepPitch = on) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for (mode in listOf("normal", "mono", "karaoke", "vocal")) {
                    SonoraChip(
                        label = mode.replaceFirstChar { it.uppercase() },
                        selected = settings.stereoMode == mode,
                        onClick = { onUpdateSettings { it.copy(stereoMode = mode) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Creative FX: Orbit 8D & Room Reverb
        SoundCard {
            SectionKicker("Spatial FX — Orbit 8D & Room Reverb")
            Spacer(Modifier.height(8.dp))
            ToggleSettingRow("Orbit 8D Binaural Panner (ITD + ILD + Pinna Shadow)", settings.orbit) { on ->
                onUpdateSettings { it.copy(orbit = on) }
            }
            if (settings.orbit) {
                SoundSliderRow("Orbit Rotation Speed", "${String.format("%.2f", settings.orbitSpeed)} Hz", settings.orbitSpeed.toFloat(), 0.04f..0.45f) { v ->
                    onUpdateSettings { it.copy(orbitSpeed = v.toDouble()) }
                }
            }
            Spacer(Modifier.height(4.dp))
            SoundSliderRow("Studio Room Reverb (Freeverb)", "${(settings.room * 100).toInt()}%", settings.room.toFloat(), 0f..1f) { v ->
                onUpdateSettings { it.copy(room = v.toDouble()) }
            }
        }

        // Sound Profiles & Output Device Binding
        SoundCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionKicker("Sound Profiles & Output Binding")
                    SonoraText("Active Output: ${activeRoute.name} (${activeRoute.kind})", color = theme.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SonoraButton("Save", icon = SonoraIcons.Plus, compact = true, onClick = {
                        sheets.askPrompt("Save Sound Profile", initialValue = activeRoute.name) { name ->
                            val prof = SoundProfile(
                                id = SonoraUtil.newId(),
                                name = name.trim().ifEmpty { "Profile" },
                                eq = settings.eq,
                                preampDb = settings.preampDb,
                                bassDb = settings.bassDb,
                                midsDb = settings.midsDb,
                                trebleDb = settings.trebleDb,
                                width = settings.width,
                                crossfeed = settings.crossfeed,
                            )
                            onUpdateSettings { it.copy(profiles = it.profiles + prof) }
                            onToast("Saved profile ${prof.name}")
                        }
                    })
                    SonoraButton("Bind Device", compact = true, onClick = {
                        sheets.askPrompt("Save & Bind to ${activeRoute.name}", initialValue = "${activeRoute.name} Profile") { name ->
                            val prof = SoundProfile(
                                id = SonoraUtil.newId(),
                                name = name.trim().ifEmpty { activeRoute.name },
                                eq = settings.eq,
                                preampDb = settings.preampDb,
                                bassDb = settings.bassDb,
                                midsDb = settings.midsDb,
                                trebleDb = settings.trebleDb,
                                width = settings.width,
                                crossfeed = settings.crossfeed,
                            )
                            onUpdateSettings {
                                it.copy(
                                    profiles = it.profiles + prof,
                                    deviceMap = it.deviceMap + (activeRoute.id to prof.id),
                                )
                            }
                            onToast("Bound ${prof.name} to ${activeRoute.name}")
                        }
                    })
                }
            }
            if (settings.profiles.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                for (prof in settings.profiles) {
                    val isBound = settings.deviceMap.containsValue(prof.id)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(theme.bgElevated)
                            .border(1.dp, if (isBound) theme.accent else theme.border, RoundedCornerShape(10.dp))
                            .clickable {
                                onUpdateSettings {
                                    it.copy(
                                        eq = prof.eq,
                                        preampDb = prof.preampDb,
                                        bassDb = prof.bassDb,
                                        midsDb = prof.midsDb,
                                        trebleDb = prof.trebleDb,
                                        width = prof.width,
                                        crossfeed = prof.crossfeed,
                                    )
                                }
                                onToast("Applied profile ${prof.name}")
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            SonoraText(prof.name, fontWeight = FontWeight.Bold, color = theme.text)
                            if (isBound) {
                                SonoraText("Bound to audio output route", color = theme.accent, fontSize = 11.sp)
                            }
                        }
                        Box(Modifier.clickable {
                            onUpdateSettings { s ->
                                s.copy(
                                    profiles = s.profiles.filterNot { it.id == prof.id },
                                    deviceMap = s.deviceMap.filterValues { it != prof.id },
                                )
                            }
                        }.padding(6.dp)) {
                            SonoraIcon(SonoraIcons.Trash, tint = theme.danger, size = 15.dp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            SonoraButton("Reset All Audio DSP to Flat", danger = true, compact = true, onClick = {
                onUpdateSettings {
                    it.copy(
                        eq = List(10) { 0.0 },
                        preampDb = 0.0,
                        bassDb = 0.0,
                        midsDb = 0.0,
                        trebleDb = 0.0,
                        boostDb = 0.0,
                        width = 1.0,
                        crossfeed = 0.0,
                        stereoMode = "normal",
                        orbit = false,
                        room = 0.0,
                        speed = 1.0,
                    )
                }
                activePresetKey = "flat"
                onToast("Reset DSP to flat")
            }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun VisualScreen(
    repo: LibraryRepo,
    player: PlayerController,
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
) {
    val context = LocalContext.current
    val theme = LocalTheme.current
    val ui by player.state.collectAsState()
    val posMs by player.positionMs.collectAsState()
    val tracks by repo.tracks.flow.collectAsState()

    val currentTrack = remember(ui.mediaId, tracks) { player.currentTrack() }
    var analysis by remember(currentTrack?.id) {
        mutableStateOf(TrackAnalyzer.getCachedAnalysis(currentTrack?.id))
    }

    LaunchedEffect(currentTrack?.id) {
        val trk = currentTrack ?: return@LaunchedEffect
        val existing = TrackAnalyzer.getCachedAnalysis(trk.id)
        if (existing != null && existing.spec != null) {
            analysis = existing
        } else {
            val computed = TrackAnalyzer.analyze(context, repo, trk, fftSize = 1024, barsCount = 180, spectro = true)
            if (computed != null) analysis = computed
        }
    }

    val peaks180 = analysis?.bars180 ?: emptyList()

    var liveBars by remember { mutableStateOf(FloatArray(48)) }
    LaunchedEffect(ui.isPlaying) {
        while (ui.isPlaying) {
            liveBars = LiveSpectrumAnalyzer.computeBars(Graph.dsp.processor, 48, liveBars)
            delay(33)
        }
    }

    val progress = if (ui.durationMs > 0L) (posMs.toFloat() / ui.durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // 1. Track Waveform Overview (180 Bars, Click-to-Seek)
        SoundCard {
            SectionKicker("180-Bar Track Waveform Overview")
            Spacer(Modifier.height(8.dp))
            var widthPx by remember { mutableIntStateOf(1) }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .onSizeChanged { widthPx = max(1, it.width) }
                    .pointerInput(ui.durationMs) {
                        if (ui.durationMs <= 0L) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val f = (down.position.x / widthPx.toFloat()).coerceIn(0f, 1f)
                            player.seekTo((f * ui.durationMs).toLong())
                        }
                    }
            ) {
                val n = 180
                val step = size.width / n
                val barW = (step * 0.65f).coerceAtLeast(1.5f)
                val midY = size.height / 2f
                for (i in 0 until n) {
                    val pk = if (peaks180.isNotEmpty()) peaks180[(i * peaks180.size) / n] else 0.25f
                    val bh = (pk.coerceIn(0.06f, 1f) * size.height * 0.88f)
                    val bx = i * step
                    val played = (i.toFloat() / n.toFloat()) <= progress
                    drawRoundRect(
                        color = if (played) theme.accent else theme.bgHover,
                        topLeft = Offset(bx, midY - bh / 2f),
                        size = Size(barW, bh),
                        cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                    )
                }
            }
        }

        // 2. 2D STFT Spectrogram + 6 Colormap Selector
        SoundCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SectionKicker("STFT Spectrogram (224x96)")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (cm in listOf("miku", "teto", "neru", "magma", "viridis", "gray")) {
                        item {
                            SonoraChip(
                                label = cm,
                                selected = settings.colormap == cm,
                                onClick = { onUpdateSettings { it.copy(colormap = cm) } },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            var specWidthPx by remember { mutableIntStateOf(1) }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(theme.bg)
                    .onSizeChanged { specWidthPx = max(1, it.width) }
                    .pointerInput(ui.durationMs) {
                        if (ui.durationMs <= 0L) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val f = (down.position.x / specWidthPx.toFloat()).coerceIn(0f, 1f)
                            player.seekTo((f * ui.durationMs).toLong())
                        }
                    }
            ) {
                val specBytes = analysis?.spec
                val cols = analysis?.specCols ?: 224
                val bins = analysis?.specBins ?: 96
                if (specBytes != null && specBytes.size >= cols * bins) {
                    val cw = size.width / cols
                    val ch = size.height / bins
                    var c = 0
                    while (c < cols) {
                        var b = 0
                        while (b < bins) {
                            val u8 = specBytes[c * bins + b].toInt() and 0xFF
                            val col = SonoraColormaps.colorFor(settings.colormap, u8)
                            drawRect(
                                color = col,
                                topLeft = Offset(c * cw, size.height - (b + 2) * ch),
                                size = Size(cw * 2.1f, ch * 2.1f),
                            )
                            b += 2
                        }
                        c += 2
                    }
                }
                val playheadX = size.width * progress
                drawLine(
                    color = theme.accent,
                    start = Offset(playheadX, 0f),
                    end = Offset(playheadX, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }

        // 3. Real-Time Post-DSP 2048-FFT Spectrum Analyzer
        SoundCard {
            SectionKicker("Real-Time Post-DSP Spectrum (2048-pt Blackman FFT)")
            Spacer(Modifier.height(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(theme.bg)
                    .padding(8.dp),
            ) {
                val n = liveBars.size
                val step = size.width / n
                val barW = (step * 0.72f).coerceAtLeast(2f)
                for (i in 0 until n) {
                    val v = liveBars[i].coerceIn(0.02f, 1f)
                    val bh = v * size.height
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(theme.accent2, theme.accent),
                        ),
                        topLeft = Offset(i * step, size.height - bh),
                        size = Size(barW, bh),
                        cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                    )
                }
            }
        }

        // 4. Loudness & Dynamic Range Summary Card
        if (currentTrack != null) {
            SoundCard {
                SectionKicker("Track Mastering & Signal Readout")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    MetricBox("LUFS", analysis?.let { String.format("%.1f", it.lufs) } ?: "--", Modifier.weight(1f))
                    MetricBox("DR", analysis?.let { String.format("%.1f dB", it.drDb) } ?: "--", Modifier.weight(1f))
                    MetricBox("Peak", analysis?.let { String.format("%.1f dB", it.peakDb) } ?: "--", Modifier.weight(1f))
                    MetricBox("FMT", SonoraUtil.extOf(currentTrack.fileName).uppercase(), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SoundCard(content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(theme.bgSurface)
            .border(1.dp, theme.border, RoundedCornerShape(16.dp))
            .padding(14.dp),
        content = content,
    )
}

@Composable
private fun SoundSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val theme = LocalTheme.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SonoraText(label, color = theme.textSub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            SonoraText(valueText, color = theme.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = SonoraFonts.Manrope)
        }
        SonoraSlider(value = value, valueRange = range, onValueChange = onChange)
    }
}

@Composable
fun ToggleSettingRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val theme = LocalTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        SonoraText(label, color = theme.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        SonoraSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MetricBox(label: String, value: String, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(theme.bgElevated)
            .padding(vertical = 8.dp, horizontal = 6.dp),
    ) {
        SonoraText(label, color = theme.textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        SonoraText(value, color = theme.accent, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = SonoraFonts.Manrope)
    }
}
