package app.sonora.player

import java.text.Collator
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

val EQ_FREQS = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

fun fmt(sec: Double?): String {
    if (sec == null || sec.isNaN() || sec.isInfinite()) return "--:--"
    val s = abs(sec)
    return (if (sec < 0) "-" else "") + floor(s / 60).toInt() + ":" + floor(s % 60).toInt().toString().padStart(2, '0')
}

fun fmtBytes(n: Long): String = when {
    n >= 1e9 -> String.format(Locale.US, "%.1f GB", n / 1e9)
    n >= 1e6 -> "${(n / 1e6).roundToInt()} MB"
    else -> "${max(0, (n / 1e3).roundToInt())} KB"
}

fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
private fun half(x: Double) = round(x * 2) / 2

/** Maps the 3-band Mids slider into a smooth bump across the 500 Hz, 1 kHz and 2 kHz bands. */
fun applyMidsToEq(eq: List<Double>, midsDb: Double): List<Double> {
    val next = eq.toMutableList()
    while (next.size < 10) next.add(0.0)
    next[4] = half(midsDb * 0.75)
    next[5] = midsDb
    next[6] = half(midsDb * 0.75)
    return next
}

data class AutoEq(val eq: List<Double>, val preampDb: Double, val bassDb: Double, val midsDb: Double, val trebleDb: Double)

private val AEQ_PRE = Regex("""Preamp\s*:\s*([+-]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
private val AEQ_FILTER = Regex(
    """Filter\s+\d+\s*:\s*ON\s+(PK|PEQ|LS|LSC|HS|HSC)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz\s+Gain\s+([+-]?\d+(?:\.\d+)?)\s*dB(?:\s+Q\s+(\d+(?:\.\d+)?))?""",
    RegexOption.IGNORE_CASE,
)
private val AEQ_GRAPHIC = Regex("""^.*GraphicEQ\s*:""", RegexOption.IGNORE_CASE)

/** Parses an AutoEQ / EqualizerAPO parametric or GraphicEQ preset into the 10-band EQ + preamp. */
fun parseAutoEq(raw: String): AutoEq? {
    var preamp = 0.0
    class F(val type: String, val f: Double, val g: Double, val q: Double)
    val filters = ArrayList<F>()
    for (line in raw.lines()) {
        val pre = AEQ_PRE.find(line)
        if (pre != null) {
            preamp = pre.groupValues[1].toDoubleOrNull() ?: 0.0
            continue
        }
        val fm = AEQ_FILTER.find(line)
        if (fm != null) {
            val t = fm.groupValues[1].uppercase()
            filters.add(F(if (t.startsWith("L")) "LS" else if (t.startsWith("H")) "HS" else "PK", fm.groupValues[2].toDouble(), fm.groupValues[3].toDouble(), fm.groupValues[4].toDoubleOrNull() ?: 1.0))
            continue
        }
        if (AEQ_GRAPHIC.containsMatchIn(line)) {
            for (pair in line.replace(AEQ_GRAPHIC, "").split(";")) {
                val parts = pair.trim().split(Regex("""\s+"""))
                if (parts.size >= 2) {
                    val f = parts[0].toDoubleOrNull()
                    val g = parts[1].toDoubleOrNull()
                    if (f != null && g != null) filters.add(F("PK", f, g, 1.4))
                }
            }
        }
    }
    if (filters.isEmpty()) return null
    val eq = EQ_FREQS.map { fcI ->
        val fc = fcI.toDouble()
        var sum = 0.0
        for (flt in filters) {
            sum += when (flt.type) {
                "LS" -> flt.g * (if (fc <= flt.f) 1.0 else max(0.0, 1 - log2(fc / flt.f) * 0.7))
                "HS" -> flt.g * (if (fc >= flt.f) 1.0 else max(0.0, 1 - log2(flt.f / fc) * 0.7))
                else -> {
                    val oct = abs(log2(fc / max(1.0, flt.f)))
                    val bw = 1 / max(0.25, flt.q)
                    flt.g * exp(-(oct * oct) / (0.36 * bw * bw))
                }
            }
        }
        half(sum).coerceIn(-12.0, 12.0)
    }
    return AutoEq(eq, half(preamp).coerceIn(-15.0, 6.0), half((eq[0] + eq[1]) / 2), half((eq[4] + eq[5] + eq[6]) / 3), half((eq[8] + eq[9]) / 2))
}

private val collator: Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
private fun cmp(a: String, b: String) = collator.compare(a, b)

fun sortTracks(list: List<Track>, key: String, libraryOrder: List<String> = emptyList()): List<Track> = when (key) {
    "artist" -> list.sortedWith { a, b -> cmp(a.artist, b.artist).takeIf { it != 0 } ?: cmp(a.album, b.album).takeIf { it != 0 } ?: cmp(a.title, b.title) }
    "album" -> list.sortedWith { a, b -> cmp(a.album, b.album).takeIf { it != 0 } ?: cmp(a.title, b.title) }
    "recent" -> list.sortedByDescending { it.addedAt }
    "played" -> list.sortedWith { a, b -> (b.playCount - a.playCount).takeIf { it != 0 } ?: cmp(a.title, b.title) }
    "duration" -> list.sortedByDescending { it.duration }
    "manual" -> {
        val rank = HashMap<String, Int>()
        libraryOrder.forEachIndexed { i, id -> rank[id] = i }
        list.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }
    else -> list.sortedWith { a, b -> cmp(a.title, b.title) }
}

/** Typo-tolerant search: every token must appear, or be within one edit of a word (tokens of 4+ letters). */
fun fuzzyMatch(haystack: String, query: String): Boolean = fuzzyLower(haystack.lowercase(), query.trim().lowercase())

/** The same match for text that is already lower-case (lists cache the lower-cased text instead of rebuilding it per keystroke). */
fun fuzzyLower(h: String, q: String): Boolean {
    if (q.isEmpty()) return true
    if (h.contains(q)) return true
    val hWords = h.split(Regex("""[\s\-_.,()/]+""")).filter { it.isNotEmpty() }
    return q.split(Regex("""\s+""")).filter { it.isNotEmpty() }.all { tok ->
        if (h.contains(tok)) return@all true
        if (tok.length < 4) return@all false
        hWords.any { w ->
            if (abs(w.length - tok.length) > 1) return@any false
            var edits = 0
            var i = 0
            var j = 0
            while (i < w.length && j < tok.length) {
                if (w[i] == tok[j]) {
                    i++; j++
                } else {
                    if (++edits > 1) return@any false
                    if (w.length > tok.length) i++ else if (tok.length > w.length) j++ else {
                        i++; j++
                    }
                }
            }
            edits + (w.length - i) + (tok.length - j) <= 1
        }
    }
}

private val ARTIST_SPLIT = Regex("""\s*(?:/|;|,|\s+&\s+|\s+x\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""", RegexOption.IGNORE_CASE)
private val FEAT_IN_TITLE = Regex("""[(\[]?\b(?:feat\.?|ft\.?)\s+([^)\]]+)[)\]]?""", RegexOption.IGNORE_CASE)
private val FEAT_SPLIT = Regex("""\s*(?:/|;|,|\s+&\s+)\s*""")

/** Separates featured / joint artists ("A feat. B", "A & B", "Song (ft. C)"). */
fun extractArtists(artist: String, title: String = ""): List<String> {
    val raw = ArrayList<String>()
    if (artist.isNotBlank()) raw.addAll(artist.split(ARTIST_SPLIT))
    FEAT_IN_TITLE.find(title)?.groupValues?.getOrNull(1)?.let { raw.addAll(it.split(FEAT_SPLIT)) }
    val seen = HashSet<String>()
    val out = raw.map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }
    return out.ifEmpty { listOf(artist.trim().ifEmpty { "Unknown artist" }) }
}

fun folderOf(t: Track) = if (t.storage == "tree") t.path.substringBeforeLast('/', "") else ""

fun buildGroups(mode: String, tracks: List<Track>, playlists: List<Playlist>, sort: String): List<Group> {
    if (mode == "playlists") {
        val byId = tracks.associateBy { it.id }
        return playlists.map { p ->
            val ts = p.trackIds.mapNotNull { byId[it] }
            Group("playlist", p.id, p.name, plural(ts.size, "track"), ts.firstOrNull()?.let { it.thumb ?: it.cover }, ts, p.updatedAt, 0, p.id)
        }
    }
    class B(val kind: String, val key: String, val title: String, var sub: String) {
        val list = ArrayList<Track>()
        val ids = HashSet<String>()
    }
    val map = LinkedHashMap<String, B>()
    fun add(kind: String, key: String, title: String, sub: String, t: Track) {
        val b = map.getOrPut(key) { B(kind, key, title, sub) }
        if (b.ids.add(t.id)) b.list.add(t)
    }
    for (t in tracks) when (mode) {
        "albums" -> add("album", t.album, t.album, t.artist, t)
        "artists" -> for (a in extractArtists(t.artist, t.title)) add("artist", a.lowercase(), a, "", t)
        else -> {
            val dir = folderOf(t)
            add("folder", dir.ifEmpty { "\u0000copied" }, if (dir.isEmpty()) "Imported files" else dir.substringAfterLast('/'), dir.ifEmpty { "Copied into Sonora" }, t)
        }
    }
    val groups = map.values.map { b ->
        val sub = when (b.kind) {
            "album" -> b.list.map { it.artist }.distinct().let { if (it.size > 1) "Various artists" else it.firstOrNull() ?: "" }
            "artist" -> plural(b.list.size, "track")
            else -> b.sub
        }
        Group(b.kind, b.key, b.title, sub, b.list.firstNotNullOfOrNull { it.thumb ?: it.cover }, b.list, b.list.maxOf { it.addedAt }, b.list.sumOf { it.playCount })
    }
    return when (sort) {
        "recent" -> groups.sortedByDescending { it.newest }
        "played" -> groups.sortedWith { a, b -> (b.plays - a.plays).takeIf { it != 0 } ?: cmp(a.title, b.title) }
        else -> groups.sortedWith { a, b -> cmp(a.title, b.title) }
    }
}

/** Standard extended M3U8 text. */
fun playlistToM3U(p: Playlist, tracks: List<Track>): String {
    val byId = tracks.associateBy { it.id }
    val lines = arrayListOf("#EXTM3U", "#PLAYLIST:${p.name}")
    for (id in p.trackIds) {
        val t = byId[id] ?: continue
        lines.add("#EXTINF:${if (t.duration > 0) t.duration.roundToInt() else -1},${t.artist} - ${t.title}")
        lines.add(t.path.ifEmpty { t.fileName })
    }
    return lines.joinToString("\n")
}

/** Matches M3U entries against the library by path, file name, "Artist - Title", or title. */
fun matchM3U(text: String, fallbackName: String, tracks: List<Track>): Pair<String, List<String>> {
    var name = fallbackName.replace(Regex("""\.m3u8?$""", RegexOption.IGNORE_CASE), "").trim().ifEmpty { "Imported playlist" }
    val ids = ArrayList<String>()
    val noExt = Regex("""\.[^.]+$""")
    for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
        if (line.startsWith("#PLAYLIST:")) {
            line.substring(10).trim().takeIf { it.isNotEmpty() }?.let { name = it }
            continue
        }
        if (line.startsWith("#")) continue
        val norm = line.replace('\\', '/').lowercase()
        val base = norm.substringAfterLast('/')
        val baseNoExt = base.replace(noExt, "")
        tracks.firstOrNull { t ->
            t.path.lowercase() == norm || t.fileName.lowercase() == base || t.fileName.replace(noExt, "").lowercase() == baseNoExt ||
                "${t.artist} - ${t.title}".lowercase() == baseNoExt || t.title.lowercase() == baseNoExt
        }?.let { ids.add(it.id) }
    }
    return name to ids
}

/** Deterministic waveform-style signature per track for the seek bar (same algorithm as the web app). */
fun waveformSignature(t: Track?, count: Int = 72): FloatArray {
    var seed = 2166136261L.toInt()
    val str = if (t == null) "sonora" else "${t.id}|${t.title}|${t.duration}"
    for (ch in str) {
        seed = seed xor ch.code
        seed *= 16777619
    }
    return FloatArray(count) { i ->
        val pos = i / max(1, count - 1).toDouble()
        val env = sin(pos * PI)
        val h1 = abs(sin(i * 1.73 + (seed and 0xff) * 0.05))
        val h2 = abs(cos(i * 0.41 + ((seed shr 8) and 0xff) * 0.03))
        val h3 = abs(sin(i * 3.17 + ((seed shr 16) and 0xff) * 0.07))
        max(0.14, min(0.96, (0.2 + 0.55 * (h1 * 0.5 + h2 * 0.3 + h3 * 0.2)) * (0.45 + 0.55 * env))).toFloat()
    }
}

/**
 * Equal-power gains for a true overlap crossfade: `elapsed` seconds into a `total`-second fade.
 * Returns (outgoing, incoming). Both songs are audible together; neither dips in the middle.
 */
fun crossfadeGains(elapsed: Double, total: Double): Pair<Float, Float> {
    if (total <= 0) return 1f to 1f
    val f = (elapsed / total).coerceIn(0.0, 1.0) * PI / 2
    return cos(f).toFloat() to sin(f).toFloat()
}

/** Fisher-Yates shuffle that keeps `first` at the front (the song that starts playing). */
fun shuffledKeeping(ids: List<String>, first: String?): List<String> {
    val rest = ids.filter { it != first }.shuffled()
    return if (first != null && ids.contains(first)) listOf(first) + rest else rest
}

fun hasLyrics(t: Track, map: Map<String, Lyrics>) = t.sidecar != null || !t.embeddedLyrics.isNullOrEmpty() || !map[t.id]?.lines.isNullOrEmpty()
