package app.sonora.player.library

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure helpers ported from SonoraUtil.java, tags.ts (titleFromName, extractArtists), and Library.tsx (fuzzyMatch).
 */
object SonoraUtil {
    private val AUDIO = Regex("""\.(mp3|flac|wav|ogg|oga|opus|m4a|aac|webm|weba|mp4|wma|aiff?|alac|ac3|eac3|dts|wv|ape|tta|dsf|dff|amr|mka)$""", RegexOption.IGNORE_CASE)
    private val TIER_C = Regex("""\.(wma|asf|ape|wv|wavpack|tta|dsf|dff|aif|aiff)$""", RegexOption.IGNORE_CASE)
    private val LYRICS = Regex("""\.(lrc|srt|vtt|txt)$""", RegexOption.IGNORE_CASE)
    private val ARTIST_TITLE = Regex("""^(.+?)\s+-\s+(.+)$""")
    private val LEAD_NUM_OPT = Regex("""^\d+[.\s-]*""")
    private val LEAD_NUM_REQ = Regex("""^\d+[.\s-]+""")
    private val ARTIST_SPLIT = Regex("""\s*(?:/|;|,|\s+&\s+|\s+x\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""", RegexOption.IGNORE_CASE)
    private val FEAT_MATCH = Regex("""(?:\(|\[)?\b(?:feat\.?|ft\.?)\s+([^\]\)]+)(?:\)|\])?""", RegexOption.IGNORE_CASE)
    private val FEAT_SPLIT = Regex("""\s*(?:/|;|,|\s+&\s+)\s*""", RegexOption.IGNORE_CASE)

    const val FP_BYTES = 64 * 1024

    fun isAudioName(name: String?): Boolean = name != null && AUDIO.containsMatchIn(name)
    fun isLyricName(name: String?): Boolean = name != null && LYRICS.containsMatchIn(name)

    /** Tier C containers that Media3 has no extractor for (PLAN 6.4 / T9.2). */
    fun isTierCUnsupported(name: String?, mime: String?): Boolean {
        if (name != null && TIER_C.containsMatchIn(name)) return true
        val m = mime?.lowercase(Locale.ROOT) ?: return false
        return m.contains("x-ms-wma") || m.contains("x-ape") || m.contains("wavpack") || m.contains("aiff")
    }

    fun idFor(name: String, size: Long, lastModified: Long): String = "$name|$size|$lastModified"

    fun newId(): String = UUID.randomUUID().toString()

    fun fpSpan(size: Long): Int = min(FP_BYTES.toLong(), max(0L, size)).toInt()

    fun fingerprint(size: Long, head: ByteArray, tail: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(head)
        md.update(tail)
        return "$size:${hex(md.digest(), 20)}"
    }

    fun sampleSizeFor(width: Int, height: Int, maxPx: Int): Int {
        val longest = max(width, height)
        if (longest <= 0 || maxPx <= 0) return 1
        var sample = 1
        while (longest / (sample * 2) >= maxPx) sample *= 2
        return sample
    }

    fun parentDir(path: String?): String {
        if (path == null) return ""
        val i = path.lastIndexOf('/')
        return if (i <= 0) "" else path.substring(0, i)
    }

    fun baseKey(name: String?): String {
        if (name == null) return ""
        return name.replaceFirst(Regex("""\.[^./]+$"""), "").lowercase(Locale.ROOT)
    }

    fun extOf(name: String?): String {
        if (name == null) return "bin"
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return "bin"
        val ext = name.substring(dot + 1).lowercase(Locale.ROOT)
        return if (ext.matches(Regex("""[a-z0-9]{1,6}"""))) ext else "bin"
    }

    fun mimeForExt(ext: String): String = when (ext.lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg", "oga", "opus" -> "audio/ogg"
        "m4a", "alac", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "webm", "weba", "mka" -> "audio/webm"
        "amr" -> "audio/amr"
        "ac3" -> "audio/ac3"
        "eac3" -> "audio/eac3"
        "dts" -> "audio/vnd.dts"
        "aif", "aiff" -> "audio/aiff"
        "wma" -> "audio/x-ms-wma"
        "lrc", "txt" -> "text/plain"
        "vtt" -> "text/vtt"
        "srt" -> "application/x-subrip"
        else -> "application/octet-stream"
    }

    fun playbackMime(providerMime: String?, ext: String): String {
        val fromExt = mimeForExt(ext)
        if (fromExt.startsWith("audio/")) return fromExt
        return if (providerMime != null && providerMime.startsWith("audio/")) providerMime else fromExt
    }

    /** Returns (artist, title); artist may be empty. Mirrors titleFromName() in tags.ts and SonoraUtil.java. */
    fun titleFromName(name: String?): Pair<String, String> {
        val base = (name ?: "").replaceFirst(Regex("""\.[^.]+$"""), "").replace('_', ' ')
        val m = ARTIST_TITLE.matchEntire(base)
        if (m != null) {
            val artist = m.groupValues[1].replaceFirst(LEAD_NUM_OPT, "").trim()
            val title = m.groupValues[2].trim()
            return artist to title
        }
        val t = base.replaceFirst(LEAD_NUM_REQ, "").trim()
        return "" to (t.ifEmpty { base })
    }

    /** Splits artist & "(feat. X)" credits in title into individual artist names (mirrors tags.ts extractArtists). */
    fun extractArtists(artist: String?, title: String = ""): List<String> {
        val raw = ArrayList<String>()
        if (!artist.isNullOrBlank()) {
            raw.addAll(artist.split(ARTIST_SPLIT))
        }
        val feat = FEAT_MATCH.find(title)
        if (feat != null && feat.groupValues.size > 1 && feat.groupValues[1].isNotEmpty()) {
            raw.addAll(feat.groupValues[1].split(FEAT_SPLIT))
        }
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (item in raw) {
            val clean = item.trim()
            if (clean.isEmpty()) continue
            val key = clean.lowercase(Locale.ROOT)
            if (seen.add(key)) out.add(clean)
        }
        return if (out.isNotEmpty()) out else listOf(artist?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown artist")
    }

    fun sha1Hex(value: String, chars: Int): String =
        hex(MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8)), chars)

    fun sha1Hex(bytes: ByteArray, chars: Int): String =
        hex(MessageDigest.getInstance("SHA-1").digest(bytes), chars)

    private fun hex(bytes: ByteArray, chars: Int): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b))
        return sb.substring(0, min(chars, sb.length))
    }

    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a[i]
            val y = b[j]
            if (x.isDigit() && y.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).replaceFirst(Regex("""^0+(?=.)"""), "")
                val nb = b.substring(sj, j).replaceFirst(Regex("""^0+(?=.)"""), "")
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = x.lowercaseChar().compareTo(y.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /** Lightweight typo-tolerant search: matches all tokens or within 1 edit distance on words (Library.tsx). */
    fun fuzzyMatch(haystack: String, query: String): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return true
        val h = haystack.lowercase(Locale.ROOT)
        if (h.contains(q)) return true
        val qTokens = q.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val hWords = h.split(Regex("""[\s\-_.,()/]+""")).filter { it.isNotEmpty() }
        return qTokens.all { tok ->
            if (h.contains(tok)) return@all true
            if (tok.length < 4) return@all false
            hWords.any { w ->
                if (abs(w.length - tok.length) > 1) return@any false
                var edits = 0
                var wi = 0
                var ti = 0
                while (wi < w.length && ti < tok.length) {
                    if (w[wi] == tok[ti]) {
                        wi++; ti++
                    } else {
                        if (++edits > 1) return@any false
                        if (w.length > tok.length) wi++
                        else if (tok.length > w.length) ti++
                        else { wi++; ti++ }
                    }
                }
                edits + (w.length - wi) + (tok.length - ti) <= 1
            }
        }
    }
}
