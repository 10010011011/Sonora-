package app.sonora.player

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Port of lyrics.ts: LRC (repeated tags, [offset:], enhanced word tags), SRT and WebVTT. */
object LyricsParser {
    private val TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val INLINE = Regex("""<((?:\d{1,2}:)?\d{1,2}:\d{2}(?:[.,]\d{1,3})?)>""")
    private val CUE = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?$""")
    private val KNOWN_MARKUP = Regex("""</?(?:c|v|lang|b|i|u)(?:\s+[^>]*)?>""", RegexOption.IGNORE_CASE)
    private val ANY_MARKUP = Regex("<[^>]+>")
    private val LOOSE_WORD_TAG = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val OFFSET_TAG = Regex("""^\s*\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val META_TAG = Regex("""^\s*\[[a-z]+:.*]\s*$""", RegexOption.IGNORE_CASE)
    private val TOKEN = Regex("""\S+\s*""")

    private fun frac(f: String) = if (f.isEmpty()) 0.0 else f.toInt() / 10.0.pow(f.length)
    private fun toSec(m: String, s: String, f: String) = m.toInt() * 60 + s.toInt() + frac(f)

    fun parseCueTime(value: String): Double? {
        val g = CUE.find(value.trim())?.groupValues ?: return null
        return (if (g[1].isNotEmpty()) g[1].toInt() * 3600 else 0) + g[2].toInt() * 60 + g[3].toInt() + frac(g[4])
    }

    private fun clean(value: String, trim: Boolean = true): String {
        val c = value.replace(KNOWN_MARKUP, "").replace(ANY_MARKUP, "").replace("&nbsp;", " ", ignoreCase = true)
        return if (trim) c.trim() else c
    }

    /** Enhanced LRC marks each word with <mm:ss.xx>; WebVTT karaoke cues use <hh:mm:ss.mmm>. Text belongs to the marker before it. */
    private fun inlineWords(value: String): List<LyricWord>? {
        val times = ArrayList<Double>()
        val texts = ArrayList<StringBuilder>()
        val prefix = StringBuilder()
        var cursor = 0
        for (m in INLINE.findAll(value)) {
            val between = value.substring(cursor, m.range.first)
            if (texts.isEmpty()) prefix.append(between) else texts.last().append(between)
            val time = parseCueTime(m.groupValues[1])
            if (time != null) {
                times.add(time)
                texts.add(StringBuilder())
            } else if (texts.isEmpty()) prefix.append(m.value) else texts.last().append(m.value)
            cursor = m.range.last + 1
        }
        if (texts.isEmpty()) return null
        texts.last().append(value.substring(cursor))
        texts[0].insert(0, prefix)
        val cleaned = times.indices.map { LyricWord(times[it], clean(texts[it].toString(), false)) }.filter { it.text.isNotBlank() }.toMutableList()
        if (cleaned.isEmpty()) return null
        cleaned[0] = cleaned[0].copy(text = cleaned[0].text.trimStart())
        cleaned[cleaned.lastIndex] = cleaned.last().copy(text = cleaned.last().text.trimEnd())
        return cleaned
    }

    private fun sortLines(lines: List<LyricLine>) = if (lines.any { it.t == null }) lines else lines.sortedBy { it.t ?: 0.0 }
    private fun shiftWords(words: List<LyricWord>?, delta: Double) =
        if (words == null || delta == 0.0) words else words.map { it.copy(t = max(0.0, it.t + delta)) }

    fun parse(input: String, source: String = "text"): Lyrics {
        val text = input.replace("\r\n", "\n").replace('\r', '\n').removePrefix("\uFEFF")
        val lines = ArrayList<LyricLine>()
        if (text.contains("-->")) {
            for (block in text.split(Regex("""\n\s*\n"""))) {
                val rows = block.split("\n").filter { it.isNotBlank() && it.trim() != "WEBVTT" }
                val ti = rows.indexOfFirst { it.contains("-->") }
                if (ti < 0) continue
                val cueTime = parseCueTime(rows[ti].substringBefore("-->"))
                val rawText = rows.drop(ti + 1).joinToString(" ")
                val words = inlineWords(rawText)
                val lineText = words?.joinToString("") { it.text }?.trim() ?: clean(rawText)
                if (lineText.isNotEmpty()) lines.add(LyricLine(cueTime, lineText, words))
            }
            return Lyrics(sortLines(lines), 0.0, source)
        }
        var offset = 0.0
        var timed = 0
        for (raw in text.split("\n")) {
            val off = OFFSET_TAG.find(raw)
            if (off != null) {
                offset = off.groupValues[1].toInt() / 1000.0
                continue
            }
            if (META_TAG.matches(raw)) continue
            val lineTimes = ArrayList<Double>()
            var lastEnd = 0
            for (m in TAG.findAll(raw)) {
                if (m.range.first != lastEnd && raw.substring(lastEnd, m.range.first).isNotBlank()) break
                lineTimes.add(toSec(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
                lastEnd = m.range.last + 1
            }
            val payload = raw.substring(lastEnd)
            val words = inlineWords(payload)
            val lineText = words?.joinToString("") { it.text }?.trim() ?: clean(payload.replace(LOOSE_WORD_TAG, ""))
            if (lineText.isEmpty()) continue
            if (lineTimes.isNotEmpty()) {
                timed++
                // Repeated LRC line tags denote repeated performances of the same lyric.
                for (time in lineTimes) {
                    val delta = if (!words.isNullOrEmpty() && lineTimes.size > 1) time - lineTimes[0] else 0.0
                    lines.add(LyricLine(time, lineText, shiftWords(words, delta)))
                }
            } else if (!words.isNullOrEmpty()) {
                timed++
                lines.add(LyricLine(words[0].t, lineText, words))
            } else lines.add(LyricLine(null, lineText))
        }
        var result: List<LyricLine> = if (timed > 0) sortLines(lines) else lines
        if (offset != 0.0) result = result.map { l ->
            l.copy(t = l.t?.let { max(0.0, it - offset) }, words = l.words?.map { it.copy(t = max(0.0, it.t - offset)) })
        }
        return Lyrics(result, 0.0, source)
    }

    fun stamp(seconds: Double): String {
        val v = max(0.0, seconds)
        val minutes = (v / 60).toInt()
        return String.format(Locale.US, "%02d:%05.2f", minutes, v - minutes * 60)
    }

    fun toLRC(lyrics: Lyrics, title: String? = null, artist: String? = null): String {
        val out = ArrayList<String>()
        if (!title.isNullOrEmpty()) out.add("[ti:$title]")
        if (!artist.isNullOrEmpty()) out.add("[ar:$artist]")
        for (line in lyrics.lines) {
            val body = if (!line.words.isNullOrEmpty()) line.words.joinToString("") { "<" + stamp(it.t + lyrics.offset) + ">" + it.text } else line.text
            out.add(if (line.t == null) body else "[" + stamp(line.t + lyrics.offset) + "]" + body)
        }
        return out.joinToString("\n")
    }

    fun bakeOffset(l: Lyrics) = l.copy(offset = 0.0, lines = l.lines.map { it.copy(t = it.t?.let { t -> max(0.0, t + l.offset) }, words = shiftWords(it.words, l.offset)) })

    fun shiftFrom(l: Lyrics, fromIndex: Int, delta: Double) = l.copy(lines = l.lines.mapIndexed { i, line ->
        if (i < fromIndex) line else line.copy(t = line.t?.let { max(0.0, it + delta) }, words = shiftWords(line.words, delta))
    })

    /** Active line for `time`: binary search when every line is timed and in order, linear scan otherwise. */
    fun activeIndex(lyrics: Lyrics?, time: Double): Int {
        val lines = lyrics?.lines ?: return -1
        if (lines.isEmpty()) return -1
        val target = time + 0.05 - lyrics.offset
        var canBinary = true
        for (i in lines.indices) {
            val t = lines[i].t
            if (t == null || (i > 0 && t < (lines[i - 1].t ?: 0.0))) {
                canBinary = false
                break
            }
        }
        if (canBinary) {
            var lo = 0
            var hi = lines.size - 1
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if ((lines[mid].t ?: 0.0) <= target) {
                    ans = mid
                    lo = mid + 1
                } else hi = mid - 1
            }
            return ans
        }
        var index = -1
        for (i in lines.indices) {
            val t = lines[i].t ?: continue
            if (t <= target) index = i else break
        }
        return index
    }

    fun activeWordIndex(words: List<LyricWord>?, time: Double, offset: Double = 0.0): Int {
        if (words.isNullOrEmpty()) return -1
        var index = -1
        for (i in words.indices) if (words[i].t + offset <= time + 0.035) index = i else break
        return index
    }

    /**
     * Plain LRC times lines, not words. Without enhanced word stamps, estimate a readable karaoke
     * progression inside the line's time window (a visual estimate, not word recognition).
     */
    fun estimateWordTimings(lyrics: Lyrics, lineIndex: Int, trackDuration: Double = 0.0): List<LyricWord>? {
        val line = lyrics.lines.getOrNull(lineIndex) ?: return null
        val start = line.t
        if (start == null || line.text.isBlank()) return line.words
        if (!line.words.isNullOrEmpty()) return line.words
        val tokens = TOKEN.findAll(line.text).map { it.value }.toList()
        if (tokens.isEmpty()) return null
        var nextStart: Double? = null
        for (i in lineIndex + 1 until lyrics.lines.size) {
            val c = lyrics.lines[i].t
            if (c != null && c > start + 0.03) {
                nextStart = c
                break
            }
        }
        var available = if (nextStart != null) nextStart - start else if (trackDuration > start) trackDuration - start else 0.0
        if (available <= 0) {
            val gaps = ArrayList<Double>()
            var previous: Double? = null
            for (i in 0 until lineIndex) {
                val time = lyrics.lines[i].t ?: continue
                if (previous != null && time > previous) gaps.add(time - previous)
                previous = time
            }
            val recent = gaps.takeLast(5).sorted()
            available = if (recent.isNotEmpty()) recent[recent.size / 2] else max(2.2, tokens.size * 0.42)
        }
        // Avoid stretching the last line across a long instrumental outro.
        val span = max(0.12, min(available, max(2.2, tokens.size * 0.72)) * 0.9)
        val weights = tokens.map { tok -> max(1, tok.count { it.isLetterOrDigit() }) }
        val total = weights.sum().toDouble()
        var elapsed = 0
        return tokens.mapIndexed { i, text ->
            val w = LyricWord(start + (elapsed / total) * span, text)
            elapsed += weights[i]
            w
        }
    }
}
