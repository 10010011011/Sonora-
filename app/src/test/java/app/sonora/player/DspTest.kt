package app.sonora.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** The sound chain, measured: what goes in against what comes out. */
class DspTest {
    private val sr = 44100
    private val n = 44100 // one second, stereo frames

    private fun run(dsp: SonoraDsp, input: ShortArray, channels: Int = 2): ShortArray {
        dsp.configure(AudioProcessor.AudioFormat(sr, channels, C.ENCODING_PCM_16BIT))
        dsp.flush()
        val buf = ByteBuffer.allocateDirect(input.size * 2).order(ByteOrder.nativeOrder())
        buf.asShortBuffer().put(input)
        dsp.queueInput(buf)
        val out = dsp.output
        return ShortArray(out.remaining() / 2).also { out.asShortBuffer().get(it) }
    }

    private fun sine(freq: Double, amp: Double, left: Boolean = true, right: Boolean = true) = ShortArray(n * 2) { i ->
        val v = (sin(2 * PI * freq * (i / 2) / sr) * amp * 32767).toInt().toShort()
        if (i % 2 == 0) (if (left) v else 0) else (if (right) v else 0)
    }

    /** RMS of one channel over the last half second (after every smoothing ramp has settled). */
    private fun rms(x: ShortArray, ch: Int = 0): Double {
        var sum = 0.0
        var count = 0
        for (f in n / 2 until n) {
            val v = x[f * 2 + ch] / 32768.0
            sum += v * v
            count++
        }
        return sqrt(sum / count)
    }

    private fun db(out: ShortArray, inp: ShortArray, ch: Int = 0) = 20 * log10(rms(out, ch) / rms(inp, ch))
    private fun dsp(p: DspParams) = SonoraDsp().also { it.params = p }

    @Test
    fun nothingSwitchedOnIsBitPerfect() {
        val rnd = Random(7)
        val input = ShortArray(n * 2) { if (it % 997 == 0) Short.MAX_VALUE else if (it % 991 == 0) Short.MIN_VALUE else rnd.nextInt(-32768, 32768).toShort() }
        assertArrayEquals(input, run(dsp(DspParams()), input))
        // Mono files too.
        assertArrayEquals(input, run(dsp(DspParams()), input, channels = 1))
    }

    @Test
    fun theLimiterLeavesAFullScaleMasterAloneWhenNothingBoosts() {
        val loud = sine(1000.0, 1.0)
        assertArrayEquals(loud, run(dsp(DspParams(limiter = true)), loud))
        // ReplayGain turning a loud master down is plain gain: still no limiting.
        val d = dsp(DspParams(limiter = true, rgDb = -6.0))
        assertEquals(-6.0, db(run(d, loud), loud), 0.05)
        assertEquals(0f, d.reductionDb, 1e-6f)
    }

    @Test
    fun boostIsHeldUnderFullScaleByTheLimiter() {
        val loud = sine(1000.0, 0.9)
        val d = dsp(DspParams(boostDb = 12.0, limiter = true))
        val out = run(d, loud)
        val peak = out.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak <= (SonoraDsp.THRESHOLD * 32767).toInt() + 1)
        assertTrue(rms(out) > rms(loud)) // louder than the input, but never clipped
        assertTrue(d.reductionDb < -3f)
    }

    @Test
    fun aQuietSongIsBoostedByTheFullAmount() {
        val quiet = sine(1000.0, 0.05)
        assertEquals(12.0, db(run(dsp(DspParams(boostDb = 12.0)), quiet), quiet), 0.1)
    }

    @Test
    fun withTheLimiterOffOverloadsClipAndAreReported() {
        val d = dsp(DspParams(boostDb = 12.0, limiter = false))
        val out = run(d, sine(1000.0, 0.9))
        assertEquals(32767, out.maxOf { abs(it.toInt()) })
        assertTrue(d.clipAt > 0)
    }

    @Test
    fun bassAndTrebleShelvesActOnTheirOwnEnds() {
        val low = sine(30.0, 0.1)
        val mid = sine(1000.0, 0.1)
        val high = sine(18000.0, 0.1)
        val bass = DspParams(bassDb = 6.0, autoPreamp = false)
        assertEquals(6.0, db(run(dsp(bass), low), low), 0.8)
        assertEquals(0.0, db(run(dsp(bass), high), high), 0.3)
        val treble = DspParams(trebleDb = -6.0, autoPreamp = false)
        assertEquals(-6.0, db(run(dsp(treble), high), high), 0.8)
        assertEquals(0.0, db(run(dsp(treble), low), low), 0.3)
        assertEquals(0.0, db(run(dsp(DspParams(bassDb = 6.0, eqOn = false)), low), low), 0.01) // EQ bypass
        assertEquals(0.0, db(run(dsp(bass), mid), mid), 0.6)
    }

    @Test
    fun aGraphicBandPeaksAtItsOwnFrequency() {
        val eq = List(10) { if (it == 5) 6.0 else 0.0 } // 1 kHz band
        val p = DspParams(eq = eq, autoPreamp = false)
        val at = sine(1000.0, 0.1)
        val far = sine(8000.0, 0.1)
        assertEquals(6.0, db(run(dsp(p), at), at), 0.3)
        assertEquals(0.0, db(run(dsp(p), far), far), 0.5)
        // The drawn response curve comes from the same filters.
        val curve = SonoraDsp.response(p, doubleArrayOf(1000.0, 8000.0))
        assertEquals(6.0, curve[0], 0.3)
        assertEquals(0.0, curve[1], 0.5)
    }

    @Test
    fun autoHeadroomTurnsEverythingDownByTheLargestBoost() {
        val mid = sine(1000.0, 0.1)
        assertEquals(-6.0, db(run(dsp(DspParams(bassDb = 6.0, autoPreamp = true)), mid), mid), 0.6)
        assertEquals(-4.5, db(run(dsp(DspParams(preampDb = -4.5)), mid), mid), 0.05)
    }

    @Test
    fun monoMakesBothSidesTheSame() {
        val leftOnly = sine(440.0, 0.5, right = false)
        val out = run(dsp(DspParams(stereoMode = "mono")), leftOnly)
        for (f in n / 2 until n) assertTrue(abs(out[f * 2] - out[f * 2 + 1]) <= 1)
        assertEquals(-6.0, db(out, leftOnly), 0.1) // half of the left signal on each side
    }

    @Test
    fun karaokeRemovesWhatIsInTheCentreAndKeepsTheSides() {
        val centre = sine(1000.0, 0.5)
        assertTrue(db(run(dsp(DspParams(stereoMode = "karaoke")), centre), centre) < -25)
        val side = sine(1000.0, 0.5, right = false)
        assertTrue(rms(run(dsp(DspParams(stereoMode = "karaoke")), side)) > 0.1)
    }

    @Test
    fun widthZeroCollapsesAndWidthTwoWidens() {
        val leftOnly = sine(440.0, 0.3, right = false)
        val narrow = run(dsp(DspParams(width = 0.0)), leftOnly)
        assertEquals(rms(narrow, 0), rms(narrow, 1), 1e-3)
        val wide = run(dsp(DspParams(width = 2.0)), leftOnly)
        assertTrue(rms(wide, 0) > rms(leftOnly, 0) * 1.3)
    }

    @Test
    fun roomAndOrbitStayUnderFullScale() {
        val loud = sine(220.0, 0.95)
        for (p in listOf(DspParams(room = 1.0), DspParams(orbit = true, orbitSpeed = 0.6), DspParams(crossfeed = 1.0, width = 2.5))) {
            val out = run(dsp(p), loud)
            assertTrue("peak ${out.maxOf { abs(it.toInt()) }} with $p", out.maxOf { abs(it.toInt()) } <= (SonoraDsp.THRESHOLD * 32767).toInt() + 1)
            assertTrue("rms ${rms(out)} with $p", rms(out) > 0.05)
        }
    }

    @Test
    fun waveformBarsAreScaledToTheLoudestPart() {
        val bars = barHeights(doubleArrayOf(0.0, 0.25, 1.0), intArrayOf(1, 1, 1))
        assertEquals(0.14f, bars[0], 1e-6f)
        assertEquals(0.96f, bars[2], 1e-6f)
        assertTrue(bars[1] > bars[0] && bars[1] < bars[2])
        assertEquals(0.14f, barHeights(doubleArrayOf(0.0, 0.0), intArrayOf(0, 4))[1], 1e-6f) // silence
    }
}
