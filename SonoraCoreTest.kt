package app.sonora.player

import app.sonora.player.audio.DspParams
import app.sonora.player.audio.QueueManager
import app.sonora.player.audio.QueueState
import app.sonora.player.audio.TrackAnalyzer
import app.sonora.player.data.AppState
import app.sonora.player.data.Bookmark
import app.sonora.player.data.Playlist
import app.sonora.player.data.ResumeLast
import app.sonora.player.data.ResumeState
import app.sonora.player.data.SonoraJson
import app.sonora.player.data.TrackAudioConfig
import app.sonora.player.data.TrackRecord
import app.sonora.player.data.UserData
import app.sonora.player.data.applyMidsToEq
import app.sonora.player.data.fmtSec
import app.sonora.player.data.parseAutoEqText
import app.sonora.player.library.LyricsParser
import app.sonora.player.library.SonoraUtil
import app.sonora.player.library.UserDataLogic
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of all legacy unit tests: lyrics-and-dsp.test.ts, section2.test.ts, playlist-and-sheets.test.ts, SonoraUtilTest.java.
 */
class SonoraCoreTest {

    @Test
    fun parseLyricsHandlesStandardLrcMultipleTagsAndOffset() {
        val raw = "[ti:Sample]\n[ar:Sonora]\n[offset:500]\n[00:02.00][00:10.00]Hello world\n[00:05.50]Second line"
        val parsed = LyricsParser.parseLyrics(raw, "test.lrc")
        assertEquals(3, parsed.lines.size)
        assertEquals(1.5, parsed.lines[0].t!!, 1e-4)
        assertEquals("Hello world", parsed.lines[0].text)
        assertEquals(5.0, parsed.lines[1].t!!, 1e-4)
        assertEquals("Second line", parsed.lines[1].text)
        assertEquals(9.5, parsed.lines[2].t!!, 1e-4)
    }

    @Test
    fun parseLyricsHandlesSrtAndVttBlocks() {
        val srt = "1\n00:00:03,200 --> 00:00:06,000\nSomewhere beyond the blue\n\n2\n00:00:06,800 --> 00:00:10,000\nA quiet place to begin again"
        val parsed = LyricsParser.parseLyrics(srt, "sub.srt")
        assertEquals(2, parsed.lines.size)
        assertEquals(3.2, parsed.lines[0].t!!, 1e-4)
        assertEquals("Somewhere beyond the blue", parsed.lines[0].text)
        assertEquals(6.8, parsed.lines[1].t!!, 1e-4)
    }

    @Test
    fun bakeOffsetAndShiftFromAdjustTimestampsAccurately() {
        val lyr = LyricsParser.parseLyrics("[00:04.00]First\n[00:08.00]Second").copy(offset = 1.5)
        val baked = LyricsParser.bakeOffset(lyr)
        assertEquals(0.0, baked.offset, 1e-6)
        assertEquals(5.5, baked.lines[0].t!!, 1e-4)
        assertEquals(9.5, baked.lines[1].t!!, 1e-4)

        val shifted = LyricsParser.shiftFrom(baked, 1, -1.7)
        assertEquals(5.5, shifted.lines[0].t!!, 1e-4)
        assertEquals(7.8, shifted.lines[1].t!!, 1e-4)
    }

    @Test
    fun activeIndexLocatesLineWithBinarySearchAndStampRoundTrips() {
        val lyr = LyricsParser.parseLyrics("[00:00.00]Line 1\n[00:03.20]Line 2\n[00:06.80]Line 3")
        assertEquals(0, LyricsParser.activeIndex(lyr, 0.1))
        assertEquals(1, LyricsParser.activeIndex(lyr, 3.2))
        assertEquals(2, LyricsParser.activeIndex(lyr, 7.0))

        assertEquals("01:05.40", LyricsParser.stamp(65.4))
        assertEquals(65.4, LyricsParser.parseCueTime("01:05.40")!!, 1e-4)
    }

    @Test
    fun sonoraUtilMatchesJavaAndTypeScriptSpecs() {
        val (aArtist, aTitle) = SonoraUtil.titleFromName("01. Sample Artist - Sample Song.flac")
        assertEquals("Sample Artist", aArtist)
        assertEquals("Sample Song", aTitle)

        val (bArtist, bTitle) = SonoraUtil.titleFromName("03_Night_Session.mp3")
        assertEquals("", bArtist)
        assertEquals("Night Session", bTitle)

        val artists = SonoraUtil.extractArtists("Hatsune Miku & Kasane Teto", "Mesmerizer (feat. Akita Neru)")
        assertEquals(listOf("Hatsune Miku", "Kasane Teto", "Akita Neru"), artists)

        assertEquals("song", SonoraUtil.baseKey("Song.MP3"))
        assertEquals("flac", SonoraUtil.extOf("A.FLAC"))
        assertEquals("bin", SonoraUtil.extOf("noext"))
        assertTrue(SonoraUtil.isAudioName("x.OPUS"))
        assertFalse(SonoraUtil.isAudioName("x.jpg"))
        assertTrue(SonoraUtil.isLyricName("x.lrc"))
        assertTrue(SonoraUtil.isTierCUnsupported("track.wma", "audio/x-ms-wma"))
        assertFalse(SonoraUtil.isTierCUnsupported("track.flac", "audio/flac"))
        assertEquals("audio/flac", SonoraUtil.playbackMime("audio/x-flac", "flac"))
        assertEquals("audio/ogg", SonoraUtil.playbackMime("audio/opus", "opus"))
        assertEquals("audio/mpeg", SonoraUtil.playbackMime("audio/x-mp3", "mp3"))
        assertEquals("a.mp3|12|34", SonoraUtil.idFor("a.mp3", 12, 34))
        assertEquals("a9993e364706816aba3e", SonoraUtil.sha1Hex("abc", 20))
        assertTrue(SonoraUtil.naturalCompare("Track 2", "Track 10") < 0)
        assertEquals(4, SonoraUtil.sampleSizeFor(3000, 3000, 512))
    }

    @Test
    fun applyMidsToEqAndParseAutoEqWorkAccurately() {
        val eq = List(10) { 0.0 }
        val updated = applyMidsToEq(eq, 4.0)
        assertEquals(3.0, updated[4], 1e-6)
        assertEquals(4.0, updated[5], 1e-6)
        assertEquals(3.0, updated[6], 1e-6)
        assertEquals("2:05", fmtSec(125.0))
        assertEquals("-2:05", fmtSec(-125.0))

        val autoEq = parseAutoEqText("Preamp: -4.0 dB\nFilter 1: ON LSC Fc 100 Hz Gain 5.0 dB Q 0.71\nFilter 2: ON PK Fc 1000 Hz Gain -2.5 dB Q 1.2")
        assertNotNull(autoEq)
        assertEquals(-4.0, autoEq!!.preampDb, 1e-6)
        assertTrue(autoEq.eq[0] > 0.0)
    }

    @Test
    fun queueOperationsPreserveCurrentTrackAndSequentialCursor() {
        val shuffled = QueueManager.shuffled(listOf(0, 1, 2, 3, 4, 5), 3)
        assertEquals(3, shuffled[0])
        assertEquals(listOf(0, 1, 2, 3, 4, 5), shuffled.sorted())

        var state = QueueState(queue = listOf(0, 1, 2, 3, 4), orderBase = listOf(0, 1, 2, 3, 4), qpos = 1, insertCursor = 0)
        fun cur() = state.queue[state.qpos]

        state = QueueManager.playNextState(state, 4, sequential = false)
        assertEquals(listOf(0, 1, 4, 2, 3), state.queue)
        assertEquals(1, cur())

        state = QueueManager.playNextState(state, 3, sequential = true)
        assertEquals(listOf(0, 1, 4, 3, 2), state.queue)

        state = QueueManager.addToQueueState(state, 0)
        assertEquals(listOf(1, 4, 3, 2, 0), state.queue)
        assertEquals(1, cur())

        state = QueueManager.moveInQueueState(state, 3, 0)
        assertEquals(1, cur())

        val beforeRemove = state.queue.size
        state = QueueManager.removeFromQueueState(state, state.qpos)
        assertEquals(beforeRemove, state.queue.size)

        state = QueueManager.clearUpNextState(state)
        assertEquals(state.qpos + 1, state.queue.size)
    }

    @Test
    fun userDataNormalizeRemapMergeAndResumeMatchLegacy() {
        val incoming = UserDataLogic.normalizeUserData(
            UserData(
                playlists = listOf(Playlist("p", "P", listOf("old1", "old2", "old1"), 1L, 1L)),
                bookmarks = listOf(Bookmark("b", "old1", 3.0, "", 1L)),
                resume = ResumeState(ResumeLast("old2", 9000L), mapOf("old1" to 1000L)),
                trackConfigs = mapOf("old1" to TrackAudioConfig(speed = 1.25)),
            ),
        )
        val mapped = UserDataLogic.remapUserData(incoming, mapOf("old1" to "new1"))
        assertEquals(listOf("new1", "old2"), mapped.playlists[0].trackIds)
        assertEquals("new1", mapped.bookmarks[0].trackId)
        assertEquals(1000L, mapped.resume.byTrack["new1"])
        assertEquals(1.25, mapped.trackConfigs["new1"]!!.speed!!, 1e-6)

        val local = UserData(playlists = listOf(Playlist("q", "Q", emptyList(), 1L, 1L)))
        assertEquals(listOf("q", "p"), UserDataLogic.mergeUserData(local, mapped).playlists.map { it.id })

        var ud = UserData()
        val shortTrack = TrackRecord(id = "short", duration = 200.0)
        val longTrack = TrackRecord(id = "long", duration = 3600.0)
        ud = UserDataLogic.computeSaveResume(ud, "short", 42.0, 200.0, 600.0)
        assertEquals(42.0, UserDataLogic.startPositionFor(ud, shortTrack, "prime", 600.0), 1e-4)
        assertEquals(0.0, UserDataLogic.startPositionFor(ud, shortTrack, "play", 600.0), 1e-4)

        ud = UserDataLogic.computeSaveResume(ud, "long", 900.0, 3600.0, 600.0)
        assertEquals(900.0, UserDataLogic.startPositionFor(ud, longTrack, "play", 600.0), 1e-4)
        ud = UserDataLogic.computeSaveResume(ud, "long", 3595.0, 3600.0, 600.0)
        assertEquals(0.0, UserDataLogic.startPositionFor(ud, longTrack, "play", 600.0), 1e-4)
    }

    @Test
    fun m3uExportImportAndJsonContractRoundTrip() {
        val tracks = listOf(
            TrackRecord(id = "song1", title = "Song 1", artist = "Artist A", fileName = "song1.mp3", path = "music/song1.mp3", duration = 180.0),
            TrackRecord(id = "song2", title = "Song 2", artist = "Artist B", fileName = "song2.mp3", path = "music/song2.mp3", duration = 240.0),
        )
        val pl = Playlist(id = "p1", name = "Favorites", trackIds = listOf("song1", "song2"))
        val m3u = UserDataLogic.playlistToM3U(pl, tracks)
        assertTrue(m3u.contains("#PLAYLIST:Favorites"))
        val (name, matched) = UserDataLogic.parseM3U(m3u, "fallback.m3u8", tracks)
        assertEquals("Favorites", name)
        assertEquals(listOf("song1", "song2"), matched)

        // Verify legacy JSON decoding ignores unknown fields and preserves all contract keys
        val legacyStateJson = """{"version":2,"settings":{"theme":"miku","volume":0.75,"orbit":true,"room":0.4},"lyrics":{},"queueIds":["song1"],"currentTrackId":"song1","unknownExtra":123}"""
        val decoded = SonoraJson.json.decodeFromString(AppState.serializer(), legacyStateJson)
        assertEquals("miku", decoded.settings.theme)
        assertTrue(decoded.settings.orbit)
        assertEquals(0.4, decoded.settings.room, 1e-6)
        assertEquals(listOf("song1"), decoded.queueIds)

        val dsp = DspParams.from(decoded.settings, rgDb = -3.0)
        assertTrue(dsp.orbit)
        assertEquals(0.4, dsp.room, 1e-6)
        assertTrue(dsp.limiter)

        // Verify TrackAnalyzer pure math on synthetic sine wave
        val sr = 8000
        val sine = FloatArray(sr * 2) { i -> (0.5 * sin(2.0 * PI * 440.0 * i / sr)).toFloat() }
        val analysis = TrackAnalyzer.computeFromMonoSamples(sine, sr, 1024, 60, spectro = true)
        assertEquals(180, analysis.bars180.size)
        assertEquals(96, analysis.bars96.size)
        assertNotNull(analysis.spec)
        assertTrue(analysis.lufs < 0.0 && analysis.lufs > -30.0)
    }
}
