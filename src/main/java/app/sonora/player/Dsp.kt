package app.sonora.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Everything the audio thread needs, as one immutable snapshot (swapped atomically from the UI thread). */
data class DspParams(
    val eqOn: Boolean = true,
    val eq: List<Double> = List(10) { 0.0 },
    val bassDb: Double = 0.0,
    val trebleDb: Double = 0.0,
    val preampDb: Double = 0.0,
    val autoPreamp: Boolean = true,
    val boostDb: Double = 0.0,
    val limiter: Boolean = true,
    val stereoMode: String = "normal",
    val width: Double = 1.0,
    val crossfeed: Double = 0.0,
    val orbit: Boolean = false,
    val orbitSpeed: Double = 0.12,
    val room: Double = 0.0,
    /** ReplayGain / normalization gain for the current track, dB. */
    val rgDb: Double = 0.0,
)

fun dbToLin(db: Double) = 10.0.pow(db / 20.0)

/** RBJ cookbook biquad, transposed direct form II, two channels of state. */
class Biquad {
    var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
    private val z = DoubleArray(4)

    private fun norm(nb0: Double, nb1: Double, nb2: Double, a0: Double, na1: Double, na2: Double) {
        b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb2 / a0; a1 = na1 / a0; a2 = na2 / a0
    }

    fun peaking(f: Double, q: Double, db: Double, sr: Double): Biquad {
        val a = 10.0.pow(db / 40); val w = 2 * PI * f.coerceAtMost(sr * 0.45) / sr; val al = sin(w) / (2 * q); val c = cos(w)
        norm(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
        return this
    }

    fun lowShelf(f: Double, db: Double, sr: Double): Biquad {
        val a = 10.0.pow(db / 40); val w = 2 * PI * f / sr; val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val s = 2 * sqrt(a) * al
        norm(a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s), (a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s)
        return this
    }

    fun highShelf(f: Double, db: Double, sr: Double): Biquad {
        val a = 10.0.pow(db / 40); val w = 2 * PI * f.coerceAtMost(sr * 0.45) / sr; val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val s = 2 * sqrt(a) * al
        norm(a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s), (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s)
        return this
    }

    fun lowPass(f: Double, q: Double, sr: Double): Biquad {
        val w = 2 * PI * f / sr; val c = cos(w); val al = sin(w) / (2 * q)
        norm((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
        return this
    }

    fun run(x: Double, ch: Int): Double {
        val i = ch * 2
        val y = b0 * x + z[i]
        z[i] = b1 * x - a1 * y + z[i + 1]
        z[i + 1] = b2 * x - a2 * y
        return y
    }

    fun magDb(f: Double, sr: Double): Double {
        val w = 2 * PI * f / sr; val c1 = cos(w); val c2 = cos(2 * w)
        val num = b0 * b0 + b1 * b1 + b2 * b2 + 2 * (b0 * b1 + b1 * b2) * c1 + 2 * b0 * b2 * c2
        val den = 1 + a1 * a1 + a2 * a2 + 2 * (a1 + a1 * a2) * c1 + 2 * a2 * c2
        return 10 * log10(max(1e-12, num / max(1e-12, den)))
    }
}

/** Small Schroeder reverb (4 damped combs + 2 all-passes per side) for "Room ambience". */
private class Reverb(sr: Int) {
    private val k = sr / 44100.0
    private val combLen = intArrayOf(1557, 1617, 1491, 1422)
    private val apLen = intArrayOf(225, 556)
    private val comb = Array(8) { FloatArray(((combLen[it % 4] + (if (it >= 4) 23 else 0)) * k).toInt() + 1) }
    private val combPos = IntArray(8)
    private val damp = FloatArray(8)
    private val ap = Array(4) { FloatArray(((apLen[it % 2] + (if (it >= 2) 23 else 0)) * k).toInt() + 1) }
    private val apPos = IntArray(4)
    var outL = 0.0
    var outR = 0.0

    fun process(x: Double) {
        val inp = (x * 0.25).toFloat()
        for (side in 0..1) {
            var acc = 0f
            for (c in 0..3) {
                val i = side * 4 + c
                val buf = comb[i]
                val y = buf[combPos[i]]
                damp[i] = y * 0.7f + damp[i] * 0.3f
                buf[combPos[i]] = inp + damp[i] * 0.8f
                combPos[i] = (combPos[i] + 1) % buf.size
                acc += y
            }
            for (a in 0..1) {
                val i = side * 2 + a
                val buf = ap[i]
                val bo = buf[apPos[i]]
                val y = -acc + bo
                buf[apPos[i]] = acc + bo * 0.5f
                apPos[i] = (apPos[i] + 1) % buf.size
                acc = y
            }
            if (side == 0) outL = acc.toDouble() else outR = acc.toDouble()
        }
    }
}

/**
 * Sonora's signal chain as a Media3 AudioProcessor (the native counterpart of the WebAudio graph):
 * ReplayGain x pre-amp -> bass shelf -> 10-band EQ -> treble shelf -> mid/side stage (mono / vocal focus / karaoke / width)
 * -> crossfeed -> 8D orbit -> room ambience -> boost -> brick-wall limiter. Also feeds the visualisers and meters.
 */
class SonoraDsp : BaseAudioProcessor() {
    @Volatile var params = DspParams()
    @Volatile var reductionDb = 0f
    @Volatile var clipAt = 0L

    /** The visualisers switch this on while they are on screen; otherwise the output is not copied for them. */
    @Volatile var tapOn = false

    /** When the last samples were written; the visualiser mixes only DSPs that wrote recently. */
    @Volatile var lastWriteMs = 0L

    val tapL = FloatArray(TAP)
    val tapR = FloatArray(TAP)
    @Volatile var tapPos = 0
    @Volatile var sampleRate = 44100

    private var ch = 2
    private var applied: DspParams? = null
    private val bands = Array(12) { Biquad() }
    private var active = emptyArray<Biquad>()
    private val bassKeep = Biquad()
    private val cfLp = Biquad()
    private var reverb: Reverb? = null
    private var gPre = 1.0; private var gPreT = 1.0
    private var gBoost = 1.0; private var gBoostT = 1.0
    private var sideT = 1.0; private var side = 1.0
    private var karaoke = false
    private var spatial = false
    private var cfG = 0.0
    private var orbitMix = 0.0; private var orbitT = 0.0; private var phase = 0.0; private var orbitInc = 0.0
    private var roomMix = 0.0; private var roomT = 0.0
    private var limG = 1.0
    private var rel = 0.0002
    private var limiterOn = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount > 2) return AudioProcessor.AudioFormat.NOT_SET
        sampleRate = inputAudioFormat.sampleRate
        ch = inputAudioFormat.channelCount
        applied = null
        reverb = null
        return inputAudioFormat
    }

    private fun recalc(p: DspParams) {
        val sr = sampleRate.toDouble()
        val list = ArrayList<Biquad>()
        var maxBoost = 0.0
        if (p.eqOn) {
            if (abs(p.bassDb) > 0.05) list.add(bands[10].lowShelf(100.0, p.bassDb, sr))
            for (i in 0 until 10) {
                val g = p.eq.getOrElse(i) { 0.0 }
                if (abs(g) > 0.05) list.add(bands[i].peaking(EQ_FREQS[i].toDouble(), 1.41, g, sr))
            }
            if (abs(p.trebleDb) > 0.05) list.add(bands[11].highShelf(8000.0, p.trebleDb, sr))
            maxBoost = max(0.0, max(max(p.bassDb, p.trebleDb), p.eq.maxOrNull() ?: 0.0))
        }
        active = list.toTypedArray()
        gPreT = dbToLin(p.rgDb + p.preampDb - (if (p.autoPreamp) maxBoost else 0.0))
        gBoostT = dbToLin(p.boostDb)
        karaoke = p.stereoMode == "karaoke"
        sideT = when (p.stereoMode) { "mono" -> 0.0; "vocal" -> 0.25; else -> p.width }
        cfG = p.crossfeed * 0.45
        spatial = p.stereoMode != "normal" || abs(p.width - 1) > 0.005 || p.crossfeed > 0.005
        bassKeep.lowPass(140.0, 0.707, sr)
        cfLp.lowPass(700.0, 0.707, sr)
        orbitT = if (p.orbit) 1.0 else 0.0
        orbitInc = 2 * PI * p.orbitSpeed / sr
        roomT = p.room
        rel = 1 - exp(-1.0 / (0.12 * sr))
        // The limiter only earns its place when something can push peaks past full scale. With flat EQ, no boost and
        // ReplayGain attenuating, the signal is the file itself, and a limiter in the path would only colour loud masters.
        val net = p.boostDb + (p.preampDb - (if (p.autoPreamp) maxBoost else 0.0)) + maxBoost + p.rgDb
        val widens = spatial && (karaoke || p.width > 1.005 || p.crossfeed > 0.005)
        limiterOn = p.limiter && (net > 0.05 || widens || p.orbit || p.room > 0.01)
        applied = p
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        lastWriteMs = System.currentTimeMillis()
        val out = replaceOutputBuffer(n)
        val p = params
        if (p !== applied) recalc(p)
        val stereo = ch == 2
        val frames = n / (2 * ch)
        var pos = inputBuffer.position()
        var tp = tapPos
        var minG = 1.0
        val filters = active
        val tap = tapOn
        // Effects that are still fading out keep the limiter in the path until they are silent.
        val limiter = limiterOn || (p.limiter && (orbitMix > 0.001 || roomMix > 0.001))
        if (!limiter && filters.isEmpty() && !spatial && orbitT == 0.0 && orbitMix <= 0.001 && roomT == 0.0 && roomMix <= 0.001 &&
            abs(gPreT - 1) < 1e-4 && abs(gPre - 1) < 2e-3 && abs(gBoostT - 1) < 1e-4 && abs(gBoost - 1) < 2e-3
        ) {
            // Nothing is switched on: pass the samples through untouched (bit-perfect), at almost no CPU cost.
            gPre = 1.0; gBoost = 1.0; limG = 1.0; orbitMix = 0.0; roomMix = 0.0
            if (tap) {
                for (f in 0 until frames) {
                    val l = inputBuffer.getShort(pos) / 32768f
                    tapL[tp] = l
                    tapR[tp] = if (stereo) inputBuffer.getShort(pos + 2) / 32768f else l
                    tp = (tp + 1) and (TAP - 1)
                    pos += 2 * ch
                }
                tapPos = tp
            }
            reductionDb = 0f
            out.put(inputBuffer)
            out.flip()
            return
        }
        if (!limiter) limG = 1.0
        for (f in 0 until frames) {
            var l = inputBuffer.getShort(pos) / 32768.0
            var r = if (stereo) inputBuffer.getShort(pos + 2) / 32768.0 else l
            pos += 2 * ch
            gPre += (gPreT - gPre) * 0.0015
            l *= gPre; r *= gPre
            for (bq in filters) {
                l = bq.run(l, 0)
                if (stereo) r = bq.run(r, 1)
            }
            if (!stereo) r = l
            if (stereo) {
                if (spatial) {
                    side += (sideT - side) * 0.0015
                    val m = (l + r) * 0.5
                    val s = (l - r) * 0.5
                    if (karaoke) {
                        // Cancel whatever is panned dead centre (the lead vocal) but keep the bass.
                        val low = bassKeep.run(m, 0)
                        l = s + low; r = -s + low
                    } else {
                        l = m + s * side; r = m - s * side
                    }
                    if (cfG > 0.001) {
                        val ll = cfLp.run(l, 0)
                        val rl = cfLp.run(r, 1)
                        val nrm = 1 / (1 + cfG * 0.5)
                        l = (l + cfG * rl) * nrm; r = (r + cfG * ll) * nrm
                    }
                }
                orbitMix += (orbitT - orbitMix) * 0.0004
                if (orbitMix > 0.001) {
                    phase += orbitInc
                    if (phase > 2 * PI) phase -= 2 * PI
                    val m = (l + r) * 0.5
                    val a = (sin(phase) + 1) * PI / 4
                    val depth = (0.8 + 0.2 * cos(phase)) * 1.414
                    l = l * (1 - 0.8 * orbitMix) + m * cos(a) * depth * orbitMix
                    r = r * (1 - 0.8 * orbitMix) + m * sin(a) * depth * orbitMix
                }
                roomMix += (roomT - roomMix) * 0.0004
                if (roomMix > 0.001) {
                    val rv = reverb ?: Reverb(sampleRate).also { reverb = it }
                    rv.process(l + r)
                    l = l * (1 - roomMix * 0.3) + rv.outL * roomMix * 0.55
                    r = r * (1 - roomMix * 0.3) + rv.outR * roomMix * 0.55
                }
            }
            gBoost += (gBoostT - gBoost) * 0.0015
            l *= gBoost; r *= gBoost
            val peak = max(abs(l), abs(r))
            if (limiter) {
                // Instant attack, slow release. The release step is capped by what this sample needs, so no peak passes the ceiling.
                val need = if (peak > THRESHOLD) THRESHOLD / peak else 1.0
                val relaxed = limG + (1 - limG) * rel
                limG = if (need < relaxed) need else relaxed
                if (limG < minG) minG = limG
                l *= limG; r *= limG
            } else if (peak > 1.0) {
                if (!p.limiter) clipAt = System.currentTimeMillis() // only a warning when the user switched the limiter off
                l = l.coerceIn(-1.0, 1.0); r = r.coerceIn(-1.0, 1.0)
            }
            if (tap) {
                tapL[tp] = l.toFloat(); tapR[tp] = r.toFloat()
                tp = (tp + 1) and (TAP - 1)
            }
            out.putShort((l * 32767.0).toInt().toShort())
            if (stereo) out.putShort((r * 32767.0).toInt().toShort())
        }
        tapPos = tp
        reductionDb = if (limiter) (20 * log10(minG)).toFloat() else 0f
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    /** Latest `n` samples (oldest first) for the live spectrum / oscilloscope / vectorscope. */
    fun snapshot(n: Int, left: FloatArray, right: FloatArray) {
        val end = tapPos
        for (i in 0 until n) {
            val j = (end - n + i) and (TAP - 1)
            left[i] = tapL[j]; right[i] = tapR[j]
        }
    }

    companion object {
        const val TAP = 8192
        const val THRESHOLD = 0.966

        /** Combined EQ response in dB at each frequency, for the live response curve. */
        fun response(p: DspParams, freqs: DoubleArray): DoubleArray {
            val sr = 48000.0
            val list = ArrayList<Biquad>()
            if (p.eqOn) {
                if (abs(p.bassDb) > 0.05) list.add(Biquad().lowShelf(100.0, p.bassDb, sr))
                for (i in 0 until 10) p.eq.getOrElse(i) { 0.0 }.let { if (abs(it) > 0.05) list.add(Biquad().peaking(EQ_FREQS[i].toDouble(), 1.41, it, sr)) }
                if (abs(p.trebleDb) > 0.05) list.add(Biquad().highShelf(8000.0, p.trebleDb, sr))
            }
            return DoubleArray(freqs.size) { i -> list.sumOf { it.magDb(freqs[i], sr) } }
        }
    }
}
