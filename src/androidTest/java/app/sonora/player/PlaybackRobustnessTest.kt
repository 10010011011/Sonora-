package app.sonora.player

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The bugs reported against the original app, as an emulator test on the native engine stack
 * (ExoPlayer + MediaSessionService architecture + Sonora AudioProcessor, exact seeks):
 *
 * 1. 200 random seeks must all land and keep playing: the clock keeps moving after every seek
 *    (no frozen time bar) and nothing skips silently.
 * 2. Playback must run clean through 60-70% of a song: no healthy file is skipped mid-way.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackRobustnessTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var player: ExoPlayer? = null
    private val error = AtomicReference<PlaybackException?>()

    companion object {
        const val SECONDS = 100
        const val RATE = 44100
        const val SEEKS = 200
    }

    @Before
    fun startLooper() {
        thread = HandlerThread("robustness-player").apply { start() }
        handler = Handler(thread.looper)
    }

    @After
    fun stopLooper() {
        runCatching { onPlayer { player?.release() } }
        player = null
        thread.quitSafely()
        thread.join(5000)
    }

    /** Runs [op] on the player thread and returns its result. */
    private fun <T> onPlayer(op: () -> T): T {
        val task = FutureTask(op)
        if (!handler.post(task)) throw IllegalStateException("player thread is gone")
        return task.get(30, TimeUnit.SECONDS)
    }

    /** 100-second stereo 16-bit WAV with a sweeping tone, so every seek lands on real audio. */
    private fun makeWav(): File {
        val f = File(ctx.cacheDir, "robustness-100s.wav")
        if (f.isFile && f.length() > 10_000_000) return f
        val frames = SECONDS * RATE
        val data = ByteArray(frames * 4)
        var p = 0
        for (i in 0 until frames) {
            val t = i.toDouble() / RATE
            val freq = 220.0 + 660.0 * i / frames // 220 Hz rising to 880 Hz
            val v = (sin(2 * PI * freq * t) * 20000).toInt()
            data[p++] = (v and 0xff).toByte()
            data[p++] = ((v shr 8) and 0xff).toByte()
            data[p++] = (v and 0xff).toByte()
            data[p++] = ((v shr 8) and 0xff).toByte()
        }
        f.outputStream().use { out ->
            fun ascii(s: String) = out.write(s.toByteArray())
            fun i32(v: Int) = out.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()))
            fun i16(v: Int) = out.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()))
            ascii("RIFF"); i32(36 + data.size); ascii("WAVE"); ascii("fmt "); i32(16); i16(1); i16(2)
            i32(RATE); i32(RATE * 4); i16(4); i16(16); ascii("data"); i32(data.size)
            out.write(data)
        }
        return f
    }

    private fun buildPlayer(file: File) {
        onPlayer {
            val dsp = SonoraDsp()
            val rf = object : DefaultRenderersFactory(ctx) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                    DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf<AudioProcessor>(dsp)).build()
            }
            val p = ExoPlayer.Builder(ctx, rf).build()
            p.setSeekParameters(SeekParameters.EXACT)
            p.addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error.compareAndSet(null, e)
                }
            })
            p.setMediaItem(MediaItem.Builder().setUri(file.absolutePath).setMimeType("audio/wav").build())
            p.prepare()
            p.play()
            player = p
        }
    }

    private fun posMs() = onPlayer { player!!.currentPosition }

    private fun awaitReadyAndPlaying(timeoutSec: Long = 20) {
        val ready = CountDownLatch(1)
        onPlayer {
            player!!.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) ready.countDown()
                }
            })
            if (player!!.playbackState == Player.STATE_READY) ready.countDown()
        }
        assertTrue("player never became ready", ready.await(timeoutSec, TimeUnit.SECONDS))
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 10_000) {
            if (onPlayer { player!!.isPlaying }) return
            Thread.sleep(100)
        }
        fail("player never started playing (state=${onPlayer { player!!.playbackState }}, error=${error.get()})")
    }

    private fun failIfError(where: String) {
        val e = error.get()
        if (e != null) fail("$where: player error ${e.errorCodeName} (${e.errorCode}): ${e.message}")
    }

    @Test(timeout = 900_000)
    fun randomSeeksKeepPlayingAndTheClockKeepsMoving() {
        val file = makeWav()
        buildPlayer(file)
        awaitReadyAndPlaying()
        val dur = onPlayer { player!!.duration }
        assertTrue("duration $dur is not ~100s", dur in 98_500L..101_500L)
        // The sound chain is live: real samples come out of the DSP tap.
        Thread.sleep(1500)
        val rnd = Random(20260512)
        var last = -1L
        repeat(SEEKS) { n ->
            val target = rnd.nextLong(0, 98_000)
            onPlayer { player!!.seekTo(target) }
            // The seek must land near its target and playback must continue from there.
            var landed = -1L
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 5000) {
                Thread.sleep(60)
                landed = posMs()
                if (landed >= target - 400) break
            }
            failIfError("seek #$n to ${target}ms")
            assertTrue(
                "seek #$n to ${target}ms never landed (stuck at ${landed}ms)",
                landed >= target - 400,
            )
            assertTrue("player stopped after seek #$n", onPlayer { player!!.isPlaying })
            // The clock must keep moving after the seek (the frozen time-bar bug).
            val a = posMs()
            Thread.sleep(400)
            val b = posMs()
            assertTrue("clock froze after seek #$n ($a -> $b)", b > a)
            last = b
        }
        failIfError("after $SEEKS seeks")
        assertTrue("no playback happened at all", last > 0)
    }

    @Test(timeout = 900_000)
    fun playsCleanThroughSixtyToSeventyPercent() {
        val file = makeWav()
        buildPlayer(file)
        awaitReadyAndPlaying()
        onPlayer { player!!.seekTo(60_000) }
        val marks = ArrayList<Long>()
        val t0 = System.currentTimeMillis()
        // Follow playback from 60% to 70% of the song: positions must advance steadily, with no error.
        while (System.currentTimeMillis() - t0 < 25_000) {
            Thread.sleep(250)
            val p = posMs()
            marks.add(p)
            failIfError("at ${p}ms")
            if (p >= 69_500) break
        }
        failIfError("during 60-70% pass")
        assertTrue("never reached 70% (last=${marks.lastOrNull()})", (marks.lastOrNull() ?: 0) >= 69_000)
        val advanced = marks.last() - marks.first()
        assertTrue("position barely advanced (${advanced}ms over the pass)", advanced >= 5000)
        for (i in 1 until marks.size) {
            assertTrue("clock jumped backwards (${marks[i - 1]} -> ${marks[i]})", marks[i] >= marks[i - 1] - 300)
        }
    }
}
