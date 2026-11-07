package app.sonora.player.library

import app.sonora.player.data.LyricLine
import app.sonora.player.data.LyricWord
import app.sonora.player.data.Lyrics
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Exact port of lyrics.ts: LRC (including repeated line tags, [offset:], and enhanced <mm:ss.xx> word tags),
 * SRT and WebVTT parsing, word timing estimation, active line binary search, and LRC serialization.
 */
object LyricsParser {
    private val TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    private val INLINE_TIME = Regex("""<((?:\d{1,2}:)?\d{1,2}:\d{2}(?:[.,]\d{1,3})?)>""")
    private val CUE_TIME = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?$""")
    private val OFFSET_TAG = Regex("""^\s*\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)
    private val META_TAG = Regex("""^\s*\[[a-z]+:.*\]\s*$""", RegexOption.IGNORE_CASE)
    private val WORD_TOKEN = Regex("""\S+\s*""")
    private val ALNUM = Regex("""[^\p{L}\p{N}]""")

    private fun toSec(m: String, s: String, f: String?): Double {
        val frac = if (!f.isNullOrEmpty()) f.toInt() / 10.0.pow(f.length.toDouble()) else 0.0
        return m.toInt() * 60.0 + s.toInt() + frac
    }

    fun parseCueTime(value: String): Double? {
        val match = CUE_TIME.matchEntire(value.trim()) ?: return null
        val h = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        val m = match.groupValues[2].toIntOrNull() ?: return null
        val s = match.groupValues[3].toIntOrNull() ?: return null
        val f = match.groupValues[4]
        val frac = if (f.isNotEmpty()) f.toInt() / 10.0.pow(f.length.toDouble()) else 0.0
        return h * 3600.0 + m * 60.0 + s + frac
    }

    private fun cleanCueMarkup(value: String, trim: Boolean = true): String {
        val cleaned = value
            .replace(Regex("""</?(?:c|v|lang|b|i|u)(?:\s+[^>]*)?>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<[^>]+>"""), "")
            .replace(Regex("""&nbsp;""", RegexOption.IGNORE_CASE), " ")
        return if (trim) cleaned.trim() else cleaned
    }

    private fun parseInlineWords(value: String): List<LyricWord>? {
        val words = ArrayList<LyricWord>()
        var current: LyricWord? = null
        var prefix = ""
        var cursor = 0

        for (match in INLINE_TIME.findAll(value)) {
            val between = value.substring(cursor, match.range.first)
            if (current != null) current = current.copy(text = current.text + between)
            else prefix += between

            val time = parseCueTime(match.groupValues[1])
            if (time != null) {
                if (current != null) words.add(current)
                current = LyricWord(t = time, text = "")
            } else if (current != null) {
                current = current.copy(text = current.text + match.value)
            } else {
                prefix += match.value
            }
            cursor = match.range.last + 1
        }

        val last = current ?: return null
        val finalWord = last.copy(text = prefix + last.text + value.substring(cursor))
        words.add(finalWord)

        val cleaned = words
            .map { it.copy(text = cleanCueMarkup(it.text, trim = false)) }
            .filter { it.text.trim().isNotEmpty() }
            .toMutableList()
        if (cleaned.isEmpty()) return null

        cleaned[0] = cleaned[0].copy(text = cleaned[0].text.replaceFirst(Regex("""^\s+"""), ""))
        val lastIdx = cleaned.lastIndex
        cleaned[lastIdx] = cleaned[lastIdx].copy(text = cleaned[lastIdx].text.replaceFirst(Regex("""\s+$"""), ""))
        return cleaned
    }

    private fun sortLines(lines: List<LyricLine>): List<LyricLine> {
        if (lines.any { it.t == null }) return lines
        return lines.sortedBy { it.t ?: 0.0 }
    }

    private fun shiftWords(words: List<LyricWord>?, delta: Double): List<LyricWord>? {
        if (words == null || delta == 0.0) return words
        return words.map { it.copy(t = max(0.0, it.t + delta)) }
    }

    fun parseLyrics(input: String, source: String = "text"): Lyrics {
        val text = input.replace(Regex("""\r\n?"""), "\n").removePrefix("\uFEFF")
        val lines = ArrayList<LyricLine>()

        if (text.contains("-->")) {
            for (block in text.split(Regex("""\n\s*\n"""))) {
                val rows = block.split("\n").filter { it.trim().isNotEmpty() && it.trim() != "WEBVTT" }
                val timeIndex = rows.indexOfFirst { it.contains("-->") }
                if (timeIndex < 0) continue
                val cueTime = parseCueTime(rows[timeIndex].split("-->")[0])
                val rawText = rows.subList(timeIndex + 1, rows.size).joinToString(" ")
                val words = parseInlineWords(rawText)
                val lineText = if (words != null) words.joinToString("") { it.text }.trim() else cleanCueMarkup(rawText)
                if (lineText.isNotEmpty()) {
                    lines.add(LyricLine(t = cueTime, text = lineText, words = words))
                }
            }
            return Lyrics(lines = sortLines(lines), offset = 0.0, source = source)
        }

        var offset = 0.0
        var timed = 0
        for (raw in text.split("\n")) {
            val offsetMatch = OFFSET_TAG.find(raw)
            if (offsetMatch != null) {
                offset = (offsetMatch.groupValues[1].toIntOrNull() ?: 0) / 1000.0
                continue
            }
            if (META_TAG.matches(raw)) continue

            val lineTimes = ArrayList<Double>()
            var lastEnd = 0
            for (match in TAG.findAll(raw)) {
                if (match.range.first != lastEnd && raw.substring(lastEnd, match.range.first).trim().isNotEmpty()) break
                lineTimes.add(toSec(match.groupValues[1], match.groupValues[2], match.groupValues[3].takeIf { it.isNotEmpty() }))
                lastEnd = match.range.last + 1
            }

            val payload = raw.substring(lastEnd)
            val words = parseInlineWords(payload)
            val lineText = if (words != null) {
                words.joinToString("") { it.text }.trim()
            } else {
                cleanCueMarkup(payload.replace(Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>"""), ""))
            }
            if (lineText.isEmpty()) continue

            if (lineTimes.isNotEmpty()) {
                timed++
                for (time in lineTimes) {
                    val delta = if (!words.isNullOrEmpty() && lineTimes.size > 1) time - lineTimes[0] else 0.0
                    lines.add(LyricLine(t = time, text = lineText, words = shiftWords(words, delta)))
                }
            } else if (!words.isNullOrEmpty()) {
                timed++
                lines.add(LyricLine(t = words[0].t, text = lineText, words = words))
            } else {
                lines.add(LyricLine(t = null, text = lineText))
            }
        }

        val sorted = if (timed > 0) sortLines(lines) else lines
        val finalLines = if (offset != 0.0) {
            sorted.map { line ->
                line.copy(
                    t = line.t?.let { max(0.0, it - offset) },
                    words = line.words?.map { w -> w.copy(t = max(0.0, w.t - offset)) },
                )
            }
        } else sorted

        return Lyrics(lines = finalLines, offset = 0.0, source = source)
    }

    fun stamp(seconds: Double): String {
        val value = max(0.0, seconds)
        val minutes = floor(value / 60.0).toInt()
        val remainder = value - minutes * 60.0
        return "%02d:%05.2f".format(Locale.US, minutes, remainder)
    }

    fun toLRC(lyrics: Lyrics, ti: String? = null, ar: String? = null): String {
        val output = ArrayList<String>()
        if (!ti.isNullOrEmpty()) output.add("[ti:$ti]")
        if (!ar.isNullOrEmpty()) output.add("[ar:$ar]")
        for (line in lyrics.lines) {
            val body = if (!line.words.isNullOrEmpty()) {
                line.words.joinToString("") { w -> "<${stamp(w.t + lyrics.offset)}>${w.text}" }
            } else {
                line.text
            }
            output.add(if (line.t == null) body else "[${stamp(line.t + lyrics.offset)}]$body")
        }
        return output.joinToString("\n")
    }

    fun bakeOffset(lyrics: Lyrics): Lyrics = lyrics.copy(
        offset = 0.0,
        lines = lyrics.lines.map { line ->
            line.copy(
                t = line.t?.let { max(0.0, it + lyrics.offset) },
                words = shiftWords(line.words, lyrics.offset),
            )
        },
    )

    fun shiftFrom(lyrics: Lyrics, fromIndex: Int, delta: Double): Lyrics = lyrics.copy(
        lines = lyrics.lines.mapIndexed { index, line ->
            if (index < fromIndex) line
            else line.copy(
                t = line.t?.let { max(0.0, it + delta) },
                words = shiftWords(line.words, delta),
            )
        },
    )

    fun activeIndex(lyrics: Lyrics?, time: Double): Int {
        if (lyrics == null || lyrics.lines.isEmpty()) return -1
        val lines = lyrics.lines
        val target = time + 0.05 - lyrics.offset

        var canBinary = true
        for (i in lines.indices) {
            val cur = lines[i].t
            if (cur == null || (i > 0 && cur < (lines[i - 1].t ?: 0.0))) {
                canBinary = false
                break
            }
        }

        if (canBinary) {
            var lo = 0
            var hi = lines.lastIndex
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if ((lines[mid].t ?: 0.0) <= target) {
                    ans = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
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

    fun activeWordIndex(line: LyricLine?, time: Double, offset: Double = 0.0): Int {
        val words = line?.words ?: return -1
        if (words.isEmpty()) return -1
        var index = -1
        for (i in words.indices) {
            if (words[i].t + offset <= time + 0.035) index = i else break
        }
        return index
    }

    fun estimateWordTimings(lyrics: Lyrics, lineIndex: Int, trackDuration: Double = 0.0): List<LyricWord>? {
        val line = lyrics.lines.getOrNull(lineIndex) ?: return null
        val start = line.t ?: return line.words
        if (line.text.isBlank()) return line.words
        if (!line.words.isNullOrEmpty()) return line.words

        val tokens = WORD_TOKEN.findAll(line.text).map { it.value }.toList()
        if (tokens.isEmpty()) return null

        var nextStart: Double? = null
        for (i in (lineIndex + 1) until lyrics.lines.size) {
            val candidate = lyrics.lines[i].t
            if (candidate != null && candidate > start + 0.03) {
                nextStart = candidate
                break
            }
        }

        var available = when {
            nextStart != null -> nextStart - start
            trackDuration > start -> trackDuration - start
            else -> 0.0
        }

        if (available <= 0.0) {
            val gaps = ArrayList<Double>()
            var previous: Double? = null
            for (i in 0 until lineIndex) {
                val t = lyrics.lines[i].t ?: continue
                if (previous != null && t > previous) gaps.add(t - previous)
                previous = t
            }
            val recent = gaps.takeLast(5).sorted()
            available = if (recent.isNotEmpty()) recent[recent.size / 2] else max(2.2, tokens.size * 0.42)
        }

        val speechWindow = min(available, max(2.2, tokens.size * 0.72))
        val span = max(0.12, speechWindow * 0.9)
        val weights = tokens.map { token ->
            val count = token.trim().replace(ALNUM, "").length
            max(1, count)
        }
        val total = weights.sum().toDouble()
        var elapsed = 0.0
        return tokens.mapIndexed { idx, text ->
            val word = LyricWord(t = start + (elapsed / total) * span, text = text)
            elapsed += weights[idx]
            word
        }
    }
}
