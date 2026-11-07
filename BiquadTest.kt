package app.sonora.player.audio

import app.sonora.player.data.Settings
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiquadTest {
    private fun gainDb(setup: (Biquad) -> Unit, freq: Double, fs: Double = 48000.0): Double {
        val b = Biquad()
        setup(b)
        val n = 48000
        var sumIn = 0.0
        var sumOut = 0.0
        for (i in 0 until n) {
            val x = sin(2.0 * PI * freq * i / fs)
            val y = b.processLeft(x)
            if (i > n / 2) { sumIn += x * x; sumOut += y * y }
        }
        return 10.0 * log10(sumOut / sumIn)
    }

    @Test fun peakingBoostsCenterFrequency() =
        assertEquals(6.0, gainDb({ it.peaking(48000.0, 1000.0, 1.1, 6.0) }, 1000.0), 0.2)

    @Test fun zeroGainIsTransparent() =
        assertEquals(0.0, gainDb({ it.peaking(48000.0, 1000.0, 1.1, 0.0) }, 1000.0), 0.01)

    @Test fun lowShelfBoostsBass() =
        assertEquals(6.0, gainDb({ it.lowShelf(48000.0, 100.0, 6.0) }, 20.0), 0.4)

    @Test fun highShelfBoostsTreble() =
        assertEquals(6.0, gainDb({ it.highShelf(48000.0, 8000.0, 6.0) }, 20000.0), 0.8)

    @Test fun lowPassCutsHighs() =
        assertTrue(gainDb({ it.lowPass(48000.0, 700.0, 1.0) }, 7000.0) < -15.0)

    @Test fun defaultSettingsAreNeutral() {
        val p = DspParams.from(Settings())
        assertEquals(1.0, p.preampLin, 1e-9)
        assertFalse(p.spatial)
        assertFalse(p.limiter)
    }

    @Test fun autoPreampCompensatesBoostedEq() {
        val eq = List(10) { if (it == 5) 6.0 else 0.0 }
        val p = DspParams.from(Settings(eq = eq))
        assertEquals(DspParams.dbToLin(-6.0), p.preampLin, 1e-6)
    }
}
