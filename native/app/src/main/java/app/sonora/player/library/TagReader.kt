package app.sonora.player.library

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.min

/**
 * Minimal, fast metadata reader ported from tags.ts: ID3v2.3/2.4 (MP3) + FLAC Vorbis comments.
 * Only reads the header region of the file (at most 4 MB) — never the whole audio stream.
 */
data class ParsedTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val cover: ByteArray? = null,
    val rgTrack: Double? = null,
    val rgAlbum: Double? = null,
    val lyrics: String? = null,
)

object TagReader {
    private const val MAX_HEAD = 4 * 1024 * 1024

    fun readTags(file: File): ParsedTags {
        if (!file.isFile || file.length() <= 10) return ParsedTags()
        return try {
            FileInputStream(file).use { readTags(it, min(file.length(), MAX_HEAD.toLong()).toInt()) }
        } catch (_: Exception) {
            ParsedTags()
        }
    }

    fun readTags(stream: InputStream, maxBytes: Int = MAX_HEAD): ParsedTags {
        return try {
            val head = readUpTo(stream, min(maxBytes, MAX_HEAD))
            if (head.size < 10) return ParsedTags()
            readId3(head) ?: readFlac(head) ?: ParsedTags()
        } catch (_: Exception) {
            ParsedTags()
        }
    }

    fun readTagsFromBytes(bytes: ByteArray): ParsedTags {
        if (bytes.size < 10) return ParsedTags()
        return try {
            readId3(bytes) ?: readFlac(bytes) ?: ParsedTags()
        } catch (_: Exception) {
            ParsedTags()
        }
    }

    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val buf = ByteArray(min(limit, 64 * 1024))
        val out = java.io.ByteArrayOutputStream()
        var rem = limit
        while (rem > 0) {
            val n = input.read(buf, 0, min(buf.size, rem))
            if (n <= 0) break
            out.write(buf, 0, n)
            rem -= n
        }
        return out.toByteArray()
    }

    private fun decodeText(b: ByteArray, start: Int, end: Int, enc: Int): String {
        if (start >= end || start < 0 || end > b.size) return ""
        return try {
            val slice = b.copyOfRange(start, end)
            val cs: Charset = when (enc) {
                1 -> Charsets.UTF_16
                2 -> Charsets.UTF_16BE
                3 -> Charsets.UTF_8
                else -> Charsets.ISO_8859_1
            }
            String(slice, cs)
                .replace(Regex("""\u0000+$"""), "")
                .replace("\u0000", " / ")
                .trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun findTerm(b: ByteArray, start: Int, end: Int, enc: Int): Int {
        val wide = enc == 1 || enc == 2
        var i = start
        val limit = end - if (wide) 1 else 0
        while (i < limit) {
            if (b[i].toInt() == 0 && (!wide || b[i + 1].toInt() == 0)) return i
            i += if (wide) 2 else 1
        }
        return end
    }

    private fun parseGain(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val m = Regex("""[+-]?\d+(?:\.\d+)?""").find(s) ?: return null
        val v = m.value.toDoubleOrNull() ?: return null
        return if (v.isFinite()) v else null
    }

    private fun synchsafe(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Int =
        ((b0.toInt() and 0x7f) shl 21) or
            ((b1.toInt() and 0x7f) shl 14) or
            ((b2.toInt() and 0x7f) shl 7) or
            (b3.toInt() and 0x7f)

    private fun u32be(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xff) shl 24) or
            ((b[p + 1].toInt() and 0xff) shl 16) or
            ((b[p + 2].toInt() and 0xff) shl 8) or
            (b[p + 3].toInt() and 0xff)

    private fun u32le(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xff) or
            ((b[p + 1].toInt() and 0xff) shl 8) or
            ((b[p + 2].toInt() and 0xff) shl 16) or
            ((b[p + 3].toInt() and 0xff) shl 24)

    private fun readId3(buf: ByteArray): ParsedTags? {
        if (buf.size < 10) return null
        if (buf[0].toInt() != 0x49 || buf[1].toInt() != 0x44 || buf[2].toInt() != 0x33) return null
        val ver = buf[3].toInt() and 0xff
        if (ver < 3) return null
        val tagSize = synchsafe(buf[6], buf[7], buf[8], buf[9])
        val limit = min(buf.size, 10 + min(tagSize, MAX_HEAD))
        var p = 10
        if ((buf[5].toInt() and 0x40) != 0 && p + 4 <= limit) {
            val es = if (ver == 4) synchsafe(buf[p], buf[p + 1], buf[p + 2], buf[p + 3]) else u32be(buf, p) + 4
            p += maxOf(4, es)
        }
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var cover: ByteArray? = null
        var rgTrack: Double? = null
        var rgAlbum: Double? = null
        var lyrics: String? = null

        while (p + 10 < limit) {
            val c0 = buf[p].toInt() and 0xff
            val c1 = buf[p + 1].toInt() and 0xff
            val c2 = buf[p + 2].toInt() and 0xff
            val c3 = buf[p + 3].toInt() and 0xff
            val validId = listOf(c0, c1, c2, c3).all { (it in 0x41..0x5A) || (it in 0x30..0x39) }
            if (!validId) break
            val id = String(charArrayOf(c0.toChar(), c1.toChar(), c2.toChar(), c3.toChar()))
            val fs = if (ver == 4) synchsafe(buf[p + 4], buf[p + 5], buf[p + 6], buf[p + 7]) else u32be(buf, p + 4)
            if (fs <= 0 || p + 10 + fs > limit) break
            val bodyStart = p + 10
            val bodyEnd = bodyStart + fs
            p = bodyEnd
            val enc = buf[bodyStart].toInt() and 0xff
            val step = if (enc == 1 || enc == 2) 2 else 1
            when (id) {
                "TIT2" -> title = decodeText(buf, bodyStart + 1, bodyEnd, enc)
                "TPE1" -> artist = decodeText(buf, bodyStart + 1, bodyEnd, enc)
                "TALB" -> album = decodeText(buf, bodyStart + 1, bodyEnd, enc)
                "TXXX" -> {
                    val e = findTerm(buf, bodyStart + 1, bodyEnd, enc)
                    val desc = decodeText(buf, bodyStart + 1, e, enc).uppercase(Locale.ROOT)
                    val valStart = min(bodyEnd, e + step)
                    val value = decodeText(buf, valStart, bodyEnd, enc)
                    when (desc) {
                        "REPLAYGAIN_TRACK_GAIN" -> rgTrack = parseGain(value)
                        "REPLAYGAIN_ALBUM_GAIN" -> rgAlbum = parseGain(value)
                        "LYRICS" -> if (lyrics.isNullOrEmpty()) lyrics = value
                    }
                }
                "USLT" -> {
                    if (bodyStart + 4 < bodyEnd) {
                        val e = findTerm(buf, bodyStart + 4, bodyEnd, enc)
                        val valStart = min(bodyEnd, e + step)
                        lyrics = decodeText(buf, valStart, bodyEnd, enc)
                    }
                }
                "APIC" -> if (cover == null && bodyStart + 2 < bodyEnd) {
                    var me = bodyStart + 1
                    while (me < bodyEnd && buf[me].toInt() != 0) me++
                    val de = findTerm(buf, min(bodyEnd, me + 2), bodyEnd, enc)
                    val picStart = min(bodyEnd, de + step)
                    if (picStart < bodyEnd) {
                        cover = buf.copyOfRange(picStart, bodyEnd)
                    }
                }
            }
        }
        return ParsedTags(title, artist, album, cover, rgTrack, rgAlbum, lyrics)
    }

    private fun readFlac(buf: ByteArray): ParsedTags? {
        if (buf.size < 8) return null
        if (buf[0].toInt() != 0x66 || buf[1].toInt() != 0x4c || buf[2].toInt() != 0x61 || buf[3].toInt() != 0x43) return null
        var p = 4
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var cover: ByteArray? = null
        var rgTrack: Double? = null
        var rgAlbum: Double? = null
        var lyrics: String? = null

        while (p + 4 <= buf.size) {
            val hdr = buf[p].toInt() and 0xff
            val last = (hdr and 0x80) != 0
            val type = hdr and 0x7f
            val len = ((buf[p + 1].toInt() and 0xff) shl 16) or
                ((buf[p + 2].toInt() and 0xff) shl 8) or
                (buf[p + 3].toInt() and 0xff)
            val s = p + 4
            if (len < 0 || s + len > buf.size) break
            if (type == 4 && s + 8 <= s + len) {
                var q = s
                val vl = u32le(buf, q)
                q += 4 + vl
                if (q + 4 <= s + len) {
                    val n = u32le(buf, q)
                    q += 4
                    var i = 0
                    while (i < n && q + 4 <= s + len) {
                        val l = u32le(buf, q)
                        q += 4
                        if (l < 0 || q + l > s + len) break
                        val kv = String(buf, q, l, Charsets.UTF_8)
                        q += l
                        val eq = kv.indexOf('=')
                        if (eq > 0) {
                            val k = kv.substring(0, eq).uppercase(Locale.ROOT)
                            val v = kv.substring(eq + 1)
                            when (k) {
                                "TITLE" -> title = v
                                "ARTIST" -> artist = v
                                "ALBUM" -> album = v
                                "REPLAYGAIN_TRACK_GAIN" -> rgTrack = parseGain(v)
                                "REPLAYGAIN_ALBUM_GAIN" -> rgAlbum = parseGain(v)
                                "LYRICS", "UNSYNCEDLYRICS" -> lyrics = v
                            }
                        }
                        i++
                    }
                }
            } else if (type == 6 && cover == null && s + 32 <= s + len) {
                var q = s + 4
                val ml = u32be(buf, q)
                q += 4 + maxOf(0, ml)
                if (q + 4 <= s + len) {
                    val dl = u32be(buf, q)
                    q += 4 + maxOf(0, dl) + 16
                    if (q + 4 <= s + len) {
                        val pl = u32be(buf, q)
                        q += 4
                        if (pl > 0 && q + pl <= s + len) {
                            cover = buf.copyOfRange(q, q + pl)
                        }
                    }
                }
            }
            p = s + len
            if (last) break
        }
        return ParsedTags(title, artist, album, cover, rgTrack, rgAlbum, lyrics)
    }
}
