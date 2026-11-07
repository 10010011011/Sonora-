package app.sonora.player.data

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlinx.serialization.Serializable

/**
 * DATA CONTRACT with the legacy WebView app. Field names and JSON shapes are IDENTICAL to the old files in
 * <filesDir>/sonora/: state.json, library.json, userdata.json, trees.json. Never rename a field here.
 */

val WIDGET_IDS = listOf("now", "lyrics", "viz", "next", "quick", "sleep", "loop")
val EQ_FREQS = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

@Serializable
data class WidgetPref(val id: String = "", val on: Boolean = false)

@Serializable
data class SoundProfile(
    val id: String = "",
    val name: String = "",
    val eq: List<Double> = List(10) { 0.0 },
    val preampDb: Double = 0.0,
    val bassDb: Double = 0.0,
    val midsDb: Double = 0.0,
    val trebleDb: Double = 0.0,
    val width: Double = 1.0,
    val crossfeed: Double = 0.0,
)

@Serializable
data class LyricWord(
    val t: Double = 0.0,
    val text: String = "",
)

@Serializable
data class LyricLine(
    val t: Double? = null,
    val text: String = "",
    val words: List<LyricWord>? = null,
)

@Serializable
data class Lyrics(
    val lines: List<LyricLine> = emptyList(),
    val offset: Double = 0.0,
    val source: String? = null,
)

/** Mirror of the web app's Settings (store.ts defaultSettings). All numbers are Double so any JSON number decodes. */
@Serializable
data class Settings(
    val theme: String = "dark",                 // dark|light|system|miku|teto|neru|vinyl
    val themeIcon: Boolean = false,
    val volume: Double = 0.8,
    val muted: Boolean = false,
    val boostDb: Double = 0.0,
    val limiter: Boolean = true,
    val eqOn: Boolean = true,
    val eq: List<Double> = List(10) { 0.0 },
    val preampDb: Double = 0.0,
    val autoPreamp: Boolean = true,
    val bassDb: Double = 0.0,
    val midsDb: Double = 0.0,
    val trebleDb: Double = 0.0,
    val stereoMode: String = "normal",          // normal|mono|karaoke|vocal
    val width: Double = 1.0,
    val crossfeed: Double = 0.0,
    val orbit: Boolean = false,
    val orbitSpeed: Double = 0.12,
    val room: Double = 0.0,
    val rgMode: String = "track",               // off|track|album
    val rgPreamp: Double = 0.0,
    val rgAnalyze: Boolean = true,
    val gapless: Boolean = true,
    val crossfade: Double = 0.0,
    val playPauseFadeMs: Double = 220.0,
    val pauseOnVolume0: Boolean = true,
    val showRemainingTime: Boolean = false,
    val waveformSeekbar: Boolean = true,
    val seekStepSec: Double = 5.0,
    val repeatCountTarget: Double = 1.0,
    val speed: Double = 1.0,
    val keepPitch: Boolean = true,
    val tapLatency: Double = 100.0,
    val profiles: List<SoundProfile> = emptyList(),
    val deviceMap: Map<String, String> = emptyMap(),
    val autoResume: Boolean = true,
    val pauseOnDisconnect: Boolean = true,
    val widgets: List<WidgetPref> = WIDGET_IDS.map { WidgetPref(it, it != "now" && it != "next") },
    val showRoomWidgets: Boolean = false,
    val floatingMini: Boolean = false,
    val colormap: String = "miku",
    val specLog: Boolean = false,
    val fftSize: Double = 2048.0,
    val shuffle: Boolean = false,
    val repeat: String = "all",                 // off|all|one
    val likedIds: List<String> = emptyList(),
    val uiMode: String = "pro",
    val resumeMinSec: Double = 600.0,
    val librarySort: String = "title",
)

/** state.json written by the legacy app: {version:2, settings, lyrics, queueIds, currentTrackId}. */
@Serializable
data class AppState(
    val version: Int = 2,
    val settings: Settings = Settings(),
    val lyrics: Map<String, Lyrics> = emptyMap(),
    val queueIds: List<String>? = null,
    val currentTrackId: String? = null,
)

/** One entry of library.json (written by the legacy LibraryImporter/LibraryStore). */
@Serializable
data class TrackRecord(
    val id: String = "",
    val key: String = "",                 // 20 hex chars: media file stem and URL key
    val file: String = "",                // file name inside files/sonora/media (storage == "copy")
    val storage: String = "copy",         // copy | tree
    val uri: String = "",                 // document uri (storage == "tree")
    val treeUri: String = "",
    val srcIds: List<String> = emptyList(),
    val fp: String = "",                  // content fingerprint
    val fileName: String = "",
    val path: String = "",
    val size: Long = 0,
    val lastModified: Long = 0,
    val mime: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Double = 0.0,           // SECONDS
    val coverName: String = "",           // 16 hex .jpg in files/sonora/covers (thumbs/ has the small version)
    val tagsDone: Boolean = false,
    val addedAt: Long = 0,
    val sidecarFile: String = "",
    val sidecarName: String = "",
    val sidecarSize: Long = 0,
    val sidecarLastModified: Long = 0,
    val rgTrack: Double? = null,
    val rgAlbum: Double? = null,
    val embeddedLyrics: String? = null,
    val edited: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0,
)

@Serializable
data class Playlist(
    val id: String = "",
    val name: String = "",
    val trackIds: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class Bookmark(
    val id: String = "",
    val trackId: String = "",
    val t: Double = 0.0,
    val label: String = "",
    val createdAt: Long = 0L,
)

@Serializable
data class TrackAudioConfig(
    val speed: Double? = null,
    val keepPitch: Boolean? = null,
    val bassDb: Double? = null,
    val midsDb: Double? = null,
    val trebleDb: Double? = null,
    val boostDb: Double? = null,
)

@Serializable
data class ResumeLast(
    val trackId: String = "",
    val ms: Long = 0L,
)

@Serializable
data class ResumeState(
    val last: ResumeLast? = null,
    val byTrack: Map<String, Long> = emptyMap(),
)

/** userdata.json (playlists, bookmarks, resume, per-track audio config, library order). */
@Serializable
data class UserData(
    val version: Int = 1,
    val playlists: List<Playlist> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val resume: ResumeState = ResumeState(),
    val trackConfigs: Map<String, TrackAudioConfig> = emptyMap(),
    val libraryOrder: List<String> = emptyList(),
)

/** One entry in trees.json (SAF folders granted by the user). */
@Serializable
data class TreeRecord(
    val uri: String = "",
    val name: String = "Folder",
    val addedAt: Long = 0L,
    val lastScanAt: Long = 0L,
    val count: Int = 0,
)

@Serializable
data class BackupTrack(
    val id: String = "",
    val fp: String? = null,
    val fileName: String = "",
    val size: Long = 0L,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val addedAt: Long? = null,
    val playCount: Int? = null,
    val lastPlayedAt: Long? = null,
    val edited: Boolean? = null,
)

@Serializable
data class BackupFile(
    val app: String = "sonora",
    val version: Int = 1,
    val createdAt: Long = 0L,
    val tracks: List<BackupTrack> = emptyList(),
    val settings: Settings = Settings(),
    val userdata: UserData = UserData(),
    val lyrics: Map<String, Lyrics> = emptyMap(),
)

data class AutoEqPreset(
    val eq: List<Double>,
    val preampDb: Double,
    val bassDb: Double,
    val midsDb: Double,
    val trebleDb: Double,
)

private fun roundHalf(v: Double): Double = round(v * 2.0) / 2.0
private fun log2(x: Double): Double = ln(x) / ln(2.0)

/** Maps Sonora's 3-band Mids slider (-12..+12 dB) into the 500 Hz, 1 kHz, and 2 kHz bands of the 10-band EQ. */
fun applyMidsToEq(eq: List<Double>, midsDb: Double): List<Double> {
    val next = MutableList(10) { eq.getOrElse(it) { 0.0 } }
    next[4] = roundHalf(midsDb * 0.75)
    next[5] = midsDb
    next[6] = roundHalf(midsDb * 0.75)
    return next
}

/** Merges per-track audio overrides onto the global Settings (mirrors engine.ts effectiveSettings). */
fun effectiveSettings(s: Settings, cfg: TrackAudioConfig?): Settings {
    if (cfg == null) return s
    val mids = cfg.midsDb ?: s.midsDb
    return s.copy(
        speed = cfg.speed ?: s.speed,
        keepPitch = cfg.keepPitch ?: s.keepPitch,
        bassDb = cfg.bassDb ?: s.bassDb,
        midsDb = mids,
        trebleDb = cfg.trebleDb ?: s.trebleDb,
        boostDb = cfg.boostDb ?: s.boostDb,
        eq = if (cfg.midsDb != null) applyMidsToEq(s.eq, mids) else s.eq,
    )
}

/** Parses an AutoEQ / Parametric / GraphicEQ text preset into Sonora's 10-band EQ + preamp (mirrors store.ts). */
fun parseAutoEqText(raw: String): AutoEqPreset? {
    data class Flt(val type: String, val f: Double, val g: Double, val q: Double)
    val filters = ArrayList<Flt>()
    var preamp = 0.0
    val preRegex = Regex("""Preamp\s*:\s*([+-]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
    val fltRegex = Regex(
        """Filter\s+\d+\s*:\s*ON\s+(PK|PEQ|LS|LSC|HS|HSC)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz\s+Gain\s+([+-]?\d+(?:\.\d+)?)\s*dB(?:\s+Q\s+(\d+(?:\.\d+)?))?""",
        RegexOption.IGNORE_CASE,
    )
    val geqRegex = Regex("""GraphicEQ\s*:""", RegexOption.IGNORE_CASE)

    for (line in raw.split(Regex("""\r?\n"""))) {
        val pm = preRegex.find(line)
        if (pm != null) {
            preamp = pm.groupValues[1].toDoubleOrNull() ?: 0.0
            continue
        }
        val fm = fltRegex.find(line)
        if (fm != null) {
            val tRaw = fm.groupValues[1].uppercase()
            val type = when {
                tRaw.startsWith("L") -> "LS"
                tRaw.startsWith("H") -> "HS"
                else -> "PK"
            }
            val f = fm.groupValues[2].toDoubleOrNull() ?: continue
            val g = fm.groupValues[3].toDoubleOrNull() ?: continue
            val q = fm.groupValues[4].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 1.0
            filters.add(Flt(type, f, g, q))
            continue
        }
        if (geqRegex.containsMatchIn(line)) {
            val body = line.replace(Regex("""^.*GraphicEQ\s*:""", RegexOption.IGNORE_CASE), "")
            for (pair in body.split(";")) {
                val parts = pair.trim().split(Regex("""\s+"""))
                if (parts.size >= 2) {
                    val f = parts[0].toDoubleOrNull()
                    val g = parts[1].toDoubleOrNull()
                    if (f != null && g != null && f.isFinite() && g.isFinite()) {
                        filters.add(Flt("PK", f, g, 1.4))
                    }
                }
            }
        }
    }
    if (filters.isEmpty()) return null

    val eq = EQ_FREQS.map { fc ->
        var sum = 0.0
        for (flt in filters) {
            when (flt.type) {
                "LS" -> {
                    val ratio = if (fc <= flt.f) 1.0 else max(0.0, 1.0 - log2(fc / flt.f) * 0.7)
                    sum += flt.g * ratio
                }
                "HS" -> {
                    val ratio = if (fc >= flt.f) 1.0 else max(0.0, 1.0 - log2(flt.f / fc) * 0.7)
                    sum += flt.g * ratio
                }
                else -> {
                    val oct = abs(log2(fc / max(1.0, flt.f)))
                    val bw = 1.0 / max(0.25, flt.q)
                    val w = exp(-(oct * oct) / (0.36 * bw * bw))
                    sum += flt.g * w
                }
            }
        }
        max(-12.0, min(12.0, roundHalf(sum)))
    }
    val bassDb = roundHalf((eq[0] + eq[1]) / 2.0)
    val midsDb = roundHalf((eq[4] + eq[5] + eq[6]) / 3.0)
    val trebleDb = roundHalf((eq[8] + eq[9]) / 2.0)
    return AutoEqPreset(
        eq = eq,
        preampDb = max(-15.0, min(6.0, roundHalf(preamp))),
        bassDb = bassDb,
        midsDb = midsDb,
        trebleDb = trebleDb,
    )
}

fun fmtSec(sec: Double?): String {
    if (sec == null || !sec.isFinite()) return "--:--"
    val neg = sec < 0.0
    val s = max(0.0, abs(sec)).toInt()
    return "%s%d:%02d".format(if (neg) "-" else "", s / 60, s % 60)
}
