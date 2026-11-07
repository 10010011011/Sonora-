package app.sonora.player.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One biquad section (direct form II transposed, independent L/R state).
 * Coefficient formulas are the Web Audio spec / RBJ cookbook ones, so the response matches the old BiquadFilterNodes:
 * peaking(Q linear), lowshelf/highshelf with S = 1, lowpass with Q in dB, highpass with linear Q (ITU-R BS.1770 RLB).
 */
class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var l1 = 0.0
    private var l2 = 0.0
    private var r1 = 0.0
    private var r2 = 0.0

    fun setIdentity() { b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0 }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        val inv = 1.0 / na0
        b0 = nb0 * inv; b1 = nb1 * inv; b2 = nb2 * inv; a1 = na1 * inv; a2 = na2 * inv
    }

    private fun invalid(fs: Double, f0: Double) = f0 <= 0.0 || f0 >= fs * 0.5

    fun peaking(fs: Double, f0: Double, q: Double, gainDb: Double) {
        if (invalid(fs, f0)) { setIdentity(); return }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        set(1.0 + alpha * a, -2.0 * c, 1.0 - alpha * a, 1.0 + alpha / a, -2.0 * c, 1.0 - alpha / a)
    }

    fun lowShelf(fs: Double, f0: Double, gainDb: Double) {
        if (invalid(fs, f0)) { setIdentity(); return }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val c = cos(w0)
        val k = 2.0 * sqrt(a) * (sin(w0) / 2.0 * sqrt(2.0))
        set(
            a * ((a + 1.0) - (a - 1.0) * c + k),
            2.0 * a * ((a - 1.0) - (a + 1.0) * c),
            a * ((a + 1.0) - (a - 1.0) * c - k),
            (a + 1.0) + (a - 1.0) * c + k,
            -2.0 * ((a - 1.0) + (a + 1.0) * c),
            (a + 1.0) + (a - 1.0) * c - k,
        )
    }

    fun highShelf(fs: Double, f0: Double, gainDb: Double) {
        if (invalid(fs, f0)) { setIdentity(); return }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val c = cos(w0)
        val k = 2.0 * sqrt(a) * (sin(w0) / 2.0 * sqrt(2.0))
        set(
            a * ((a + 1.0) + (a - 1.0) * c + k),
            -2.0 * a * ((a - 1.0) + (a + 1.0) * c),
            a * ((a + 1.0) + (a - 1.0) * c - k),
            (a + 1.0) - (a - 1.0) * c + k,
            2.0 * ((a - 1.0) - (a + 1.0) * c),
            (a + 1.0) - (a - 1.0) * c - k,
        )
    }

    fun lowPass(fs: Double, f0: Double, qDb: Double) {
        if (invalid(fs, f0)) { setIdentity(); return }
        val w0 = 2.0 * PI * f0 / fs
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * 10.0.pow(qDb / 20.0))
        set((1.0 - c) / 2.0, 1.0 - c, (1.0 - c) / 2.0, 1.0 + alpha, -2.0 * c, 1.0 - alpha)
    }

    fun highPass(fs: Double, f0: Double, qLinear: Double) {
        if (invalid(fs, f0)) { setIdentity(); return }
        val w0 = 2.0 * PI * f0 / fs
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * qLinear.coerceAtLeast(0.1))
        set((1.0 + c) / 2.0, -(1.0 + c), (1.0 + c) / 2.0, 1.0 + alpha, -2.0 * c, 1.0 - alpha)
    }

    fun processLeft(x: Double): Double {
        val y = b0 * x + l1
        l1 = b1 * x - a1 * y + l2
        l2 = b2 * x - a2 * y
        return y
    }

    fun processRight(x: Double): Double {
        val y = b0 * x + r1
        r1 = b1 * x - a1 * y + r2
        r2 = b2 * x - a2 * y
        return y
    }

    fun reset() { l1 = 0.0; l2 = 0.0; r1 = 0.0; r2 = 0.0 }
}
