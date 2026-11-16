package app.sonora.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/** The spatial stage is judged here the way a listener would: it must stay finite, keep the level, and never be sloppy about timing. */
class SpatialTest {
    /** Runs noise through the stage in one mode and returns [level change in dB over the second half, peak]. */
    private fun run(mode: Int, seconds: Int): DoubleArray {
        val sr = 44100
        val st = SpStage(sr)
        val rnd = Random(7)
        var inE = 0.0
        var outE = 0.0
        var peak = 0.0
        for (i in 0 until sr * seconds) {
            if (i % 32 == 0) st.block(null, SpAnchor(0.0, 0), i.toLong(), mode)
            val l = rnd.nextGaussian() * 0.15
            val r = rnd.nextGaussian() * 0.15
            st.process(l, r)
            assertTrue("sample $i is not finite", st.outL.isFinite() && st.outR.isFinite())
            if (i > sr * seconds / 2) {
                inE += l * l + r * r
                outE += st.outL * st.outL + st.outR * st.outR
                peak = max(peak, max(abs(st.outL), abs(st.outR)))
            }
        }
        return doubleArrayOf(10 * log10(outE / inE), peak)
    }

    @Test
    fun immersiveSurroundIsFiniteAndKeepsTheLevel() {
        val r = run(2, 4)
        assertTrue("level change ${r[0]} dB", r[0] > -6.0 && r[0] < 3.0)
        assertTrue("peak ${r[1]}", r[1] < 1.5)
    }

    @Test
    fun eightDWithoutAnalysisIsFiniteAndKeepsTheLevel() {
        val r = run(1, 4)
        assertTrue("level change ${r[0]} dB", r[0] > -6.0 && r[0] < 3.0)
        assertTrue("peak ${r[1]}", r[1] < 1.5)
    }

    @Test
    fun offIsExactlyTransparent() {
        val st = SpStage(44100)
        st.block(null, SpAnchor(0.0, 0), 0, 0)
        st.process(0.25, -0.5)
        assertEquals(0.25, st.outL, 0.0)
        assertEquals(-0.5, st.outR, 0.0)
    }

    /** A song of voice bursts over a quiet pad: the timeline must exist, be finite, and glide at a sane speed. */
    @Test
    fun analysisOfASyntheticSongGivesASaneTimeline() {
        val sr = 44100
        val f = SpFeatures(sr)
        for (i in 0 until sr * 24) {
            val t = i.toDouble() / sr
            val voice = if (t % 2.5 < 2.0 && t < 20.0) 0.3 * sin(2 * PI * 1000.0 * t) else 0.0
            val pad = 0.01 * sin(2 * PI * 150.0 * t)
            f.push(voice + pad, voice - pad)
        }
        val tl = f.timeline()!!
        assertTrue("hops ${tl.az.size}", tl.az.size in 1100..1250)
        var maxStep = 0f
        for (i in 1 until tl.az.size) {
            assertTrue("az[$i] is not finite", tl.az[i].isFinite() && tl.dep[i].isFinite() && tl.ti[i].isFinite() && tl.wd[i].isFinite())
            maxStep = max(maxStep, abs(tl.az[i] - tl.az[i - 1]))
        }
        assertTrue("glides are speed capped, got $maxStep degrees per 20 ms", maxStep < 20f)
    }
}
