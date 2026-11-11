package app.sonora.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Format support, error classification and crossfade math. */
class FormatsTest {
    @Test
    fun playableAndUnsupportedSets() {
        assertTrue(supportOf("song.mp3") is Support.Playable)
        assertTrue(supportOf("Song.M4A") is Support.Playable)
        assertTrue(supportOf("x.opus") is Support.Playable)
        assertTrue(supportOf("x.wav") is Support.Playable)
        assertTrue(supportOf("x.flac") is Support.Playable)
        assertTrue(supportOf("x.ogg") is Support.Playable)
        assertTrue(supportOf("x.aac") is Support.Playable)
        assertEquals(Support.Unsupported("WMA"), supportOf("x.wma"))
        assertEquals(Support.Unsupported("AIFF"), supportOf("x.aiff"))
        assertEquals(Support.Unsupported("AIFF"), supportOf("x.aif"))
        assertTrue(supportOf("x.ape") is Support.Unsupported)
        assertTrue(supportOf("x.wv") is Support.Unsupported)
        assertTrue(supportOf("x.dsf") is Support.Unsupported)
        assertTrue(supportOf("x.dff") is Support.Unsupported)
        assertTrue(supportOf("x.xyz") is Support.Unknown)
        assertTrue(supportOf("noextension") is Support.Unknown)
    }

    @Test
    fun mimeHintsOnlyWhenCertain() {
        assertEquals("audio/mpeg", mimeForFile("x.mp3", ""))
        assertEquals("audio/flac", mimeForFile("x.flac", ""))
        assertEquals("audio/mp4", mimeForFile("x.m4a", ""))
        assertEquals("audio/aac", mimeForFile("x.aac", ""))
        assertEquals("audio/ogg", mimeForFile("x.ogg", ""))
        assertEquals("audio/opus", mimeForFile("x.opus", ""))
        assertEquals("audio/wav", mimeForFile("x.wav", ""))
        assertNull(mimeForFile("x.mka", "")) // sniffed
        assertNull(mimeForFile("x.mp4", "video/mp4")) // never trust a video mime for audio playback
        assertEquals("audio/x-wav", mimeForFile("x.unknown", "audio/x-wav"))
    }

    @Test
    fun everyErrorLandsInExactlyOneBucket() {
        assertEquals(PlaybackFailure.MISSING, classifyFailure(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals(PlaybackFailure.UNSUPPORTED, classifyFailure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertEquals(PlaybackFailure.UNSUPPORTED, classifyFailure(PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED))
        assertEquals(PlaybackFailure.UNSUPPORTED, classifyFailure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(PlaybackFailure.UNSUPPORTED, classifyFailure(PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED))
        assertEquals(PlaybackFailure.UNSUPPORTED, classifyFailure(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(PlaybackFailure.UNSUPPORTED, classifyFailure(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES))
        assertEquals(PlaybackFailure.DAMAGED, classifyFailure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertEquals(PlaybackFailure.DAMAGED, classifyFailure(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED))
        assertEquals(PlaybackFailure.DAMAGED, classifyFailure(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertEquals(PlaybackFailure.OUTPUT, classifyFailure(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertEquals(PlaybackFailure.OUTPUT, classifyFailure(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
        assertEquals(PlaybackFailure.OUTPUT, classifyFailure(PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED))
        assertEquals(PlaybackFailure.OUTPUT, classifyFailure(PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED))
        assertEquals(PlaybackFailure.READ, classifyFailure(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertEquals(PlaybackFailure.READ, classifyFailure(PlaybackException.ERROR_CODE_IO_NO_PERMISSION))
        assertEquals(PlaybackFailure.READ, classifyFailure(PlaybackException.ERROR_CODE_TIMEOUT))
        assertEquals(PlaybackFailure.READ, classifyFailure(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK))
        assertEquals(PlaybackFailure.READ, classifyFailure(123456789)) // unknown codes stay readable, never crash
        assertEquals(PlaybackFailure.UNSUPPORTED, classifyFailure(6000)) // DRM range
        assertTrue(failureRetryable(PlaybackFailure.READ))
        assertTrue(failureRetryable(PlaybackFailure.DAMAGED))
        assertTrue(failureRetryable(PlaybackFailure.OUTPUT))
        assertFalse(failureRetryable(PlaybackFailure.MISSING))
        assertFalse(failureRetryable(PlaybackFailure.UNSUPPORTED))
    }

    @Test
    fun messagesNameTheSongAndTheFormat() {
        val m = failureMessage(PlaybackFailure.READ, "World is Mine", "MP3")
        assertTrue(m.contains("World is Mine") && m.contains("MP3"))
        assertTrue(failureMessage(PlaybackFailure.MISSING, "Gone", "FLAC").contains("moved or deleted"))
        assertTrue(failureMessage(PlaybackFailure.UNSUPPORTED, "X", "WMA").contains("can't play"))
        assertTrue(failureMessage(PlaybackFailure.DAMAGED, "X", "").contains("damaged"))
        assertTrue(failureMessage(PlaybackFailure.OUTPUT, "X", "").contains("headset"))
        assertTrue(unsupportedMessage("Hit", "WMA").contains("WMA") && unsupportedMessage("Hit", "WMA").contains("doesn't support yet"))
    }

    @Test
    fun crossfadeIsATrueEqualPowerOverlap() {
        val (o0, i0) = crossfadeGains(0.0, 12.0)
        assertEquals(1f, o0, 1e-6f)
        assertEquals(0f, i0, 1e-6f)
        val (o1, i1) = crossfadeGains(12.0, 12.0)
        assertEquals(0f, o1, 1e-6f)
        assertEquals(1f, i1, 1e-6f)
        for (e in listOf(1.0, 3.0, 6.0, 9.0, 11.0)) {
            val (o, i) = crossfadeGains(e, 12.0)
            assertEquals(1.0, (o * o + i * i).toDouble(), 1e-5) // constant power: no dip in the middle
            assertTrue(o in 0f..1f && i in 0f..1f)
        }
        val (om, im) = crossfadeGains(6.0, 12.0)
        assertEquals(0.7071, om.toDouble(), 1e-3)
        assertEquals(0.7071, im.toDouble(), 1e-3)
        assertTrue(abs(crossfadeGains(3.0, 12.0).first) > abs(crossfadeGains(9.0, 12.0).first)) // outgoing falls, incoming rises
    }
}
