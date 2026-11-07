// Sonora Native — LiveAnalyzer.kt
// Real-time 2048-point FFT spectrum analyzer, EQ frequency response curve math, and 6 spectrogram colormaps.

package app.sonora.player.audio

import androidx.compose.ui.graphics.Color
import app.sonora.player.data.EQ_FREQS
import app.sonora.player.data.Settings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

val EQ_PRESETS: Map<String, List<Double>> = linkedMapOf(
    "flat" to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
    "vocaloid" to listOf(1.5, 2.0, 0.5, -1.0, 1.0, 2.5, 3.0, 2.0, 2.5, 1.5),
    "bass" to listOf(5.5, 4.5, 3.0, 1.0, 0.0, -0.5, 0.0, 0.5, 1.0, 1.0),
    "vocal" to listOf(-1.5, -1.0, 0.0, 1.5, 3.0, 4.0, 3.0, 1.5, 0.0, -0.5),
    "bright" to listOf(-1.0, -0.5, 0.0, 0.0, 0.5, 1.5, 2.5, 4.0, 4.5, 4.0),
    "warm" to listOf(2.5, 3.0, 2.0, 1.0, 0.5, 0.0, -1.0, -1.5, -2.0, -2.5),
    "vshape" to listOf(4.0, 3.5, 1.5, -1.0, -2.0, -1.5, 1.0, 3.0, 4.0, 3.5),
    "studio" to listOf(1.0, 1.5, 0.5, 0.0, 0.0, 0.5, 1.0, 1.5, 2.0, 1.0),
)

object SonoraColormaps {
    private val STOPS: Map<String, Array<IntArray>> = mapOf(
        "miku" to arrayOf(
            intArrayOf(7, 9, 13),
            intArrayOf(11, 31, 42),
            intArrayOf(15, 92, 102),
            intArrayOf(57, 197, 187),
            intArrayOf(255, 183, 213),
            intArrayOf(255, 255, 255),
        ),
        "teto" to arrayOf(
            intArrayOf(10, 7, 10),
            intArrayOf(42, 11, 20),
            intArrayOf(122, 22, 48),
            intArrayOf(230, 57, 86),
            intArrayOf(255, 193, 69),
            intArrayOf(255, 245, 230),
        ),
        "neru" to arrayOf(
            intArrayOf(10, 9, 6),
            intArrayOf(38, 28, 6),
            intArrayOf(120, 92, 10),
            intArrayOf(245, 196, 0),
            intArrayOf(255, 228, 120),
            intArrayOf(255, 252, 230),
        ),
        "magma" to arrayOf(
            intArrayOf(0, 0, 4),
            intArrayOf(44, 17, 95),
            intArrayOf(114, 31, 129),
            intArrayOf(183, 55, 121),
            intArrayOf(241, 96, 93),
            intArrayOf(254, 176, 120),
            intArrayOf(252, 253, 191),
        ),
        "viridis" to arrayOf(
            intArrayOf(68, 1, 84),
            intArrayOf(59, 82, 139),
            intArrayOf(33, 145, 140),
            intArrayOf(94, 201, 98),
            intArrayOf(253, 231, 37),
        ),
        "gray" to arrayOf(
            intArrayOf(8, 10, 14),
            intArrayOf(60, 66, 78),
            intArrayOf(140, 148, 162),
            intArrayOf(240, 244, 250),
        ),
    )

    private val LUTS: Map<String, IntArray> = STOPS.mapValues { (_, stops) ->
        IntArray(256) { v ->
            val t = (v / 255f) * (stops.size - 1)
            val i = t.toInt().coerceIn(0, stops.size - 2)
            val f = t - i
            val a = stops[i]
            val b = stops[i + 1]
            val r = (a[0] + (b[0] - a[0]) * f).roundToInt().coerceIn(0, 255)
            val g = (a[1] + (b[1] - a[1]) * f).roundToInt().coerceIn(0, 255)
            val bl = (a[2] + (b[2] - a[2]) * f).roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }

    fun getLut(name: String): IntArray = LUTS[name] ?: LUTS["miku"]!!

    fun colorFor(name: String, u8: Int): Color = Color(getLut(name)[u8.coerceIn(0, 255)])
}

object LiveSpectrumAnalyzer {
    private const val FFT_SIZE = 2048
    private val window = FloatArray(FFT_SIZE) { i ->
        (0.42 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1)) + 0.08 * cos(4.0 * PI * i / (FFT_SIZE - 1))).toFloat()
    }

    fun computeBars(processor: SonoraAudioProcessor?, numBars: Int, prevSmoothed: FloatArray? = null): FloatArray {
        val out = FloatArray(numBars)
        if (processor == null) {
            if (prevSmoothed != null && prevSmoothed.size == numBars) {
                for (i in 0 until numBars) out[i] = prevSmoothed[i] * 0.82f
            }
            return out
        }
        val raw = FloatArray(FFT_SIZE)
        processor.snapshot(raw, left = true)
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        var energy = 0f
        for (i in 0 until FFT_SIZE) {
            val s = raw[i]
            energy += s * s
            re[i] = s * window[i]
        }
        if (energy < 1e-8f) {
            if (prevSmoothed != null && prevSmoothed.size == numBars) {
                for (i in 0 until numBars) out[i] = prevSmoothed[i] * 0.82f
            }
            return out
        }

        TrackAnalyzer.fftRadix2(re, im)
        val half = FFT_SIZE / 2
        for (b in 0 until numBars) {
            val t0 = b.toDouble() / numBars.toDouble()
            val t1 = (b + 1).toDouble() / numBars.toDouble()
            val k0 = (1.0 * (half - 1).toDouble().pow(t0)).toInt().coerceIn(1, half - 1)
            val k1 = max(k0 + 1, (1.0 * (half - 1).toDouble().pow(t1)).toInt().coerceAtMost(half))
            var peakMag = 0f
            for (k in k0 until k1) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / half
                if (mag > peakMag) peakMag = mag
            }
            val db = 20f * log10(peakMag.coerceAtLeast(1e-6f))
            val norm = ((db + 72f) / 68f).coerceIn(0f, 1f)
            val prev = if (prevSmoothed != null && b < prevSmoothed.size) prevSmoothed[b] else 0f
            out[b] = if (norm > prev) norm * 0.65f + prev * 0.35f else prev * 0.80f + norm * 0.20f
        }
        return out
    }
}

object EqResponseCurve {
    private data class BiquadCoeffs(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    )

    private fun peakingCoeffs(freq: Double, q: Double, dbGain: Double, fs: Double): BiquadCoeffs {
        if (abs(dbGain) < 1e-3) return BiquadCoeffs(1.0, 0.0, 0.0, 0.0, 0.0)
        val a = 10.0.pow(dbGain / 40.0)
        val w0 = 2.0 * PI * freq / fs
        val cosw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cosw
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cosw
        val a2 = 1.0 - alpha / a
        return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun lowShelfCoeffs(freq: Double, dbGain: Double, fs: Double): BiquadCoeffs {
        if (abs(dbGain) < 1e-3) return BiquadCoeffs(1.0, 0.0, 0.0, 0.0, 0.0)
        val a = 10.0.pow(dbGain / 40.0)
        val w0 = 2.0 * PI * freq / fs
        val cosw = cos(w0)
        val sinw = sin(w0)
        val alpha = sinw / 2.0 * sqrt(2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val b0 = a * ((a + 1.0) - (a - 1.0) * cosw + twoSqrtAAlpha)
        val b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosw)
        val b2 = a * ((a + 1.0) - (a - 1.0) * cosw - twoSqrtAAlpha)
        val a0 = (a + 1.0) + (a - 1.0) * cosw + twoSqrtAAlpha
        val a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosw)
        val a2 = (a + 1.0) + (a - 1.0) * cosw - twoSqrtAAlpha
        return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun highShelfCoeffs(freq: Double, dbGain: Double, fs: Double): BiquadCoeffs {
        if (abs(dbGain) < 1e-3) return BiquadCoeffs(1.0, 0.0, 0.0, 0.0, 0.0)
        val a = 10.0.pow(dbGain / 40.0)
        val w0 = 2.0 * PI * freq / fs
        val cosw = cos(w0)
        val sinw = sin(w0)
        val alpha = sinw / 2.0 * sqrt(2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val b0 = a * ((a + 1.0) + (a - 1.0) * cosw + twoSqrtAAlpha)
        val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosw)
        val b2 = a * ((a + 1.0) + (a - 1.0) * cosw - twoSqrtAAlpha)
        val a0 = (a + 1.0) - (a - 1.0) * cosw + twoSqrtAAlpha
        val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosw)
        val a2 = (a + 1.0) - (a - 1.0) * cosw - twoSqrtAAlpha
        return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun magDbAt(c: BiquadCoeffs, w: Double): Double {
        val cos1 = cos(w)
        val cos2 = cos(2.0 * w)
        val sin1 = sin(w)
        val sin2 = sin(2.0 * w)
        val numRe = c.b0 + c.b1 * cos1 + c.b2 * cos2
        val numIm = -(c.b1 * sin1 + c.b2 * sin2)
        val denRe = 1.0 + c.a1 * cos1 + c.a2 * cos2
        val denIm = -(c.a1 * sin1 + c.a2 * sin2)
        val numMagSq = numRe * numRe + numIm * numIm
        val denMagSq = (denRe * denRe + denIm * denIm).coerceAtLeast(1e-18)
        return 10.0 * log10((numMagSq / denMagSq).coerceAtLeast(1e-18))
    }

    fun computeCurveDb(settings: Settings, points: Int = 200, fs: Double = 48000.0): FloatArray {
        val filters = ArrayList<BiquadCoeffs>(12)
        if (settings.eqOn) {
            for (i in EQ_FREQS.indices) {
                val g = settings.eq.getOrElse(i) { 0.0 }
                if (abs(g) > 1e-3) {
                    filters.add(peakingCoeffs(EQ_FREQS[i], 1.4, g, fs))
                }
            }
            if (abs(settings.bassDb) > 1e-3) {
                filters.add(lowShelfCoeffs(95.0, settings.bassDb, fs))
            }
            if (abs(settings.trebleDb) > 1e-3) {
                filters.add(highShelfCoeffs(7500.0, settings.trebleDb, fs))
            }
        }

        val out = FloatArray(points)
        val minLog = ln(20.0)
        val maxLog = ln(20000.0)
        val offsetDb = settings.preampDb + settings.boostDb * 0.5
        for (i in 0 until points) {
            val frac = i.toDouble() / (points - 1).toDouble()
            val f = exp(minLog + frac * (maxLog - minLog))
            val w = 2.0 * PI * f / fs
            var totalDb = offsetDb
            for (flt in filters) {
                totalDb += magDbAt(flt, w)
            }
            out[i] = totalDb.toFloat()
        }
        return out
    }
}
