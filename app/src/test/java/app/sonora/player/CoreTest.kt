package app.sonora.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the logic ported from the web app (lyrics.ts, store.ts, tags.ts, userdata.ts). */
class CoreTest {
    @Test
    fun lrcLinesRepeatedTagsAndMetadata() {
        val l = LyricsParser.parse("[ti:x]\n[00:12.50] Hello world\n[00:15.00][00:30.00] Again")
        assertEquals(3, l.lines.size)
        assertEquals(12.5, l.lines[0].t!!, 1e-6)
        assertEquals("Hello world", l.lines[0].text)
        assertEquals(15.0, l.lines[1].t!!, 1e-6)
        assertEquals(30.0, l.lines[2].t!!, 1e-6)
        assertEquals("Again", l.lines[2].text)
    }

    @Test
    fun lrcOffsetTagShiftsEveryLine() {
        val l = LyricsParser.parse("[offset:500]\n[00:10.00]a")
        assertEquals(9.5, l.lines[0].t!!, 1e-6)
    }

    @Test
    fun enhancedLrcKeepsWordTimings() {
        val l = LyricsParser.parse("[00:01.00]<00:01.00>Hello <00:01.50>world")
        val w = l.lines[0].words!!
        assertEquals(2, w.size)
        assertEquals("Hello world", l.lines[0].text)
        assertEquals(1.5, w[1].t, 1e-6)
        assertEquals(0, LyricsParser.activeWordIndex(w, 1.2))
        assertEquals(1, LyricsParser.activeWordIndex(w, 1.6))
    }

    @Test
    fun srtAndVttCues() {
        val l = LyricsParser.parse("1\n00:00:01,000 --> 00:00:02,000\nLine one\n\n2\n00:00:03,500 --> 00:00:04,000\nLine two")
        assertEquals(2, l.lines.size)
        assertEquals(3.5, l.lines[1].t!!, 1e-6)
        val v = LyricsParser.parse("WEBVTT\n\n00:01.000 --> 00:02.000\n<v Miku>Sekai de</v>")
        assertEquals("Sekai de", v.lines[0].text)
    }

    @Test
    fun plainTextIsUntimed() {
        val l = LyricsParser.parse("first\n\nsecond")
        assertEquals(2, l.lines.size)
        assertNull(l.lines[0].t)
    }

    @Test
    fun lrcRoundTripAndOffsetBake() {
        val l = LyricsParser.parse("[00:01.00]a\n[00:02.00]b")
        assertEquals("[00:01.00]a\n[00:02.00]b", LyricsParser.toLRC(l))
        val baked = LyricsParser.bakeOffset(l.copy(offset = 0.5))
        assertEquals(1.5, baked.lines[0].t!!, 1e-6)
        assertEquals(0.0, baked.offset, 1e-9)
        val shifted = LyricsParser.shiftFrom(l, 1, 2.0)
        assertEquals(1.0, shifted.lines[0].t!!, 1e-6)
        assertEquals(4.0, shifted.lines[1].t!!, 1e-6)
    }

    @Test
    fun activeIndexFollowsTimeAndOffset() {
        val l = LyricsParser.parse("[00:01.00]a\n[00:02.00]b\n[00:03.00]c")
        assertEquals(-1, LyricsParser.activeIndex(l, 0.5))
        assertEquals(0, LyricsParser.activeIndex(l, 1.2))
        assertEquals(2, LyricsParser.activeIndex(l, 99.0))
        assertEquals(0, LyricsParser.activeIndex(l.copy(offset = 1.0), 2.5))
    }

    @Test
    fun wordTimingsAreEstimatedInsideTheLine() {
        val l = LyricsParser.parse("[00:10.00]one two three\n[00:14.00]next")
        val w = LyricsParser.estimateWordTimings(l, 0, 60.0)!!
        assertEquals(3, w.size)
        assertEquals(10.0, w[0].t, 1e-6)
        assertTrue(w[1].t > w[0].t && w[2].t > w[1].t && w[2].t < 14.0)
    }

    @Test
    fun cueTimeParsing() {
        assertEquals(83.25, LyricsParser.parseCueTime("01:23.25")!!, 1e-6)
        assertEquals(3723.5, LyricsParser.parseCueTime("1:02:03,500")!!, 1e-6)
        assertNull(LyricsParser.parseCueTime("soon"))
        assertEquals("01:23.25", LyricsParser.stamp(83.25))
    }

    @Test
    fun autoEqParametricAndGraphic() {
        val a = parseAutoEq("Preamp: -4.2 dB\nFilter 1: ON PK Fc 1000 Hz Gain 6 dB Q 1.0")!!
        assertEquals(-4.0, a.preampDb, 1e-6)
        assertEquals(6.0, a.eq[5], 1e-6)
        assertTrue(a.eq[0] < 1.0)
        assertNotNull(parseAutoEq("GraphicEQ: 31 2.0; 1000 -3.0; 16000 1.5"))
        assertNull(parseAutoEq("nothing here"))
    }

    @Test
    fun midsSliderShapesThreeBands() {
        val eq = applyMidsToEq(List(10) { 0.0 }, 4.0)
        assertEquals(listOf(3.0, 4.0, 3.0), eq.subList(4, 7))
    }

    @Test
    fun fuzzySearchToleratesOneTypo() {
        assertTrue(fuzzyMatch("Hatsune Miku - World is Mine", "wrld mine"))
        assertTrue(fuzzyMatch("Kasane Teto", ""))
        assertFalse(fuzzyMatch("abc", "xyz"))
    }

    @Test
    fun featuredArtistsAreSeparated() {
        assertEquals(listOf("A", "B", "C"), extractArtists("A feat. B", "Song (ft. C)"))
        assertEquals(listOf("Unknown artist"), extractArtists("", ""))
    }

    @Test
    fun m3uExportAndImportMatch() {
        val tracks = listOf(
            Track(id = "1", fileName = "one.mp3", path = "Music/one.mp3", title = "One", artist = "A", duration = 61.2),
            Track(id = "2", fileName = "two.flac", path = "two.flac", title = "Two", artist = "B"),
        )
        val text = playlistToM3U(Playlist("p", "Mix", listOf("2", "1")), tracks)
        assertTrue(text.startsWith("#EXTM3U\n#PLAYLIST:Mix"))
        assertTrue(text.contains("#EXTINF:61,A - One"))
        val (name, ids) = matchM3U(text, "whatever.m3u8", tracks)
        assertEquals("Mix", name)
        assertEquals(listOf("2", "1"), ids)
        assertEquals(listOf("2"), matchM3U("C:\\old\\B - Two.mp3", "x.m3u", tracks).second)
    }

    @Test
    fun sortAndGroups() {
        val tracks = listOf(
            Track(id = "1", title = "b", artist = "Z", album = "Alb", playCount = 1, addedAt = 5),
            Track(id = "2", title = "a", artist = "Y feat. Z", album = "Alb", playCount = 9, addedAt = 1),
        )
        assertEquals(listOf("2", "1"), sortTracks(tracks, "title").map { it.id })
        assertEquals(listOf("1", "2"), sortTracks(tracks, "recent").map { it.id })
        assertEquals(listOf("1", "2"), sortTracks(tracks, "manual", listOf("1", "2")).map { it.id })
        val albums = buildGroups("albums", tracks, emptyList(), "title")
        assertEquals(1, albums.size)
        assertEquals("Various artists", albums[0].sub)
        assertEquals(setOf("Y", "Z"), buildGroups("artists", tracks, emptyList(), "title").map { it.title }.toSet())
    }

    @Test
    fun shuffleKeepsTheStartingSongFirst() {
        val ids = (1..30).map { it.toString() }
        val s = shuffledKeeping(ids, "7")
        assertEquals("7", s[0])
        assertEquals(ids.toSet(), s.toSet())
        assertEquals(72, waveformSignature(null).size)
    }
}
