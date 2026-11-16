package app.sonora.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.ExoPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Spatial stage: the engine behind 8D orbit and immersive surround. Automatic, no sliders.
 *
 * 1. Pre-scan (background, once per song, 8D only): energy in the vocal band (250-3500 Hz) is followed over time. Gaps in it
 *    cut the song into musical chunks. Each chunk is judged against its neighbours: louder = impact (centre, close); a quieter
 *    repeat of the line before it = echo (far, behind). Bass hits give a beat pulse; loudness gives intensity. The first 45 s
 *    are published as soon as they are decoded, so the real timeline takes over within about a second.
 * 2. Timeline: where each chunk sits (lines alternate sides, glides land as the next line starts, speed-capped), how close
 *    or far it is, how wide the orbiting band is, plus the rear and overhead bed. Until then 8D uses a steady generic swing.
 * 3. Real-time render (audio thread, one stage per player): voice (320-6000 Hz centre) and band (the rest) are placed
 *    binaurally, reverb sends carry the distance, and the spatial part is levelled to the plain signal (never louder in the peaks).
 *    Immersive surround uses the same renderer with a fixed front stage and no analysis at all.
 * The audio thread only reads targets; it never analyses or allocates. Its clock is exact: Media3 reports the stream's start
 * position at every flush.
 */

/** Level of the rear and overhead ambience (a share of the side signal moved out of the front). */
private const val SP_BED = 0.6

/** How far the voice swings from the centre, as a share of the full move. */
private const val SP_VOICE_MOVE = 0.1

/** Maps the processor's sample counter onto media time: t = base + (frames - origin) / sampleRate. */
internal class SpAnchor(val base: Double, val origin: Long)

/** The whole-song automation, sampled every 20 ms. Built once in the background, read by the audio thread. */
internal class SpTimeline(
    val dual: Boolean,
    val az: FloatArray,
    val dep: FloatArray,
    val clo: FloatArray,
    val ti: FloatArray,
    val wd: FloatArray,
    val hg: FloatArray,
    val ig: FloatArray,
    val inten: FloatArray,
    val pulse: FloatArray,
) {
    fun at(arr: FloatArray, t: Double): Float {
        val x = (t / HOP).coerceIn(0.0, (arr.size - 1).toDouble())
        val i = x.toInt()
        val j = min(i + 1, arr.size - 1)
        return arr[i] + (arr[j] - arr[i]) * (x - i).toFloat()
    }

    companion object {
        const val HOP = 0.02
    }
}

/** Timelines by song id. Written on the main thread, read by the audio thread, hence the concurrent map. */
internal object SpStore {
    val map = ConcurrentHashMap<String, SpTimeline>()

    /** Which song a stream belongs to, from the metadata Media3 hands the processor at every flush. */
    fun idOf(m: AudioProcessor.StreamMetadata): String? = try {
        val tl = m.timeline
        val uid = m.periodUid
        if (uid == null || tl.isEmpty) {
            null
        } else {
            val idx = tl.getIndexOfPeriod(uid)
            if (idx == C.INDEX_UNSET) {
                null
            } else {
                tl.getWindow(tl.getPeriod(idx, Timeline.Period()).windowIndex, Timeline.Window()).mediaItem.mediaId.takeIf { it.isNotEmpty() }
            }
        }
    } catch (e: Exception) {
        null
    }
}

/** Delay line for interaural time, fractional, linear interpolation. */
private class SpDelay {
    private val buf = DoubleArray(256)
    private var w = 0

    fun write(x: Double) {
        buf[w] = x
        w = (w + 1) and 255
    }

    /** Reads `d` samples behind the newest one. */
    fun read(d: Double): Double {
        val p = (w - 1) - d
        val fl = floor(p)
        val i = fl.toInt()
        val a = buf[i and 255]
        val b = buf[(i + 1) and 255]
        return a + (b - a) * (p - fl)
    }
}

/**
 * Cheap binaural placement: interaural time and level, far-ear shading, and a pinna shelf for front, back and height.
 * [shadow] scales how strongly the far ear is shaded: full for the voice, gentler for the wide band so it keeps its tone.
 */
private class SpBinaural(private val sr: Double, private val shadow: Double) {
    private val dl = SpDelay()
    private val dr = SpDelay()
    private val shL = Biquad()
    private val shR = Biquad()
    private val pinna = Biquad()
    private val top = min(16000.0, 0.45 * sr)
    private val half = 0.00033 * sr // half of the largest interaural delay
    private val k = 1 - exp(-1 / (0.01 * sr)) // about 10 ms of smoothing
    private var tL = half
    private var tR = half
    private var cL = half
    private var cR = half
    private var tgL = 1.0
    private var tgR = 1.0
    private var cgL = 1.0
    private var cgR = 1.0
    var outL = 0.0
    var outR = 0.0

    init {
        shL.lowPass(top, 0.707, sr)
        shR.lowPass(top, 0.707, sr)
        pinna.highShelf(4500.0, 0.0, sr)
    }

    /** azDeg: 0 = front, +90 = hard right, 180 = behind. elDeg: +up. */
    fun aim(azDeg: Double, elDeg: Double) {
        val a = azDeg * PI / 180
        val e = elDeg * PI / 180
        val s = (sin(a) * cos(e)).coerceIn(-1.0, 1.0)
        val ab = abs(s) * shadow
        tL = half * (1 + s)
        tR = half * (1 - s)
        val far = 10.0.pow(-9.0 * ab / 20)
        tgL = if (s > 0) far else 1.0
        tgR = if (s < 0) far else 1.0
        val cut = max(3500.0, top - 12000.0 * ab)
        shL.lowPass(if (s > 0) cut else top, 0.707, sr)
        shR.lowPass(if (s < 0) cut else top, 0.707, sr)
        val back = max(0.0, -cos(a)) * cos(e)
        val up = max(0.0, sin(e))
        pinna.highShelf(4500.0, -4 * back + 2 * up, sr)
    }

    fun process(x: Double) {
        val y = pinna.run(x, 0)
        dl.write(y)
        dr.write(y)
        cL += (tL - cL) * k
        cR += (tR - cR) * k
        cgL += (tgL - cgL) * k
        cgR += (tgR - cgR) * k
        outL = shL.run(dl.read(cL), 0) * cgL
        outR = shR.run(dr.read(cR), 0) * cgR
    }
}

/** The real-time renderer. One per SonoraDsp, so each player (and each side of a crossfade) has its own. */
internal class SpStage(sr: Int) {
    private val d = sr.toDouble()
    private val kg = 1 - exp(-1 / (0.02 * d))
    private val kEnv = 1 - exp(-1 / (0.8 * d)) // loudness envelopes, about 0.8 s
    private val kUp = 1 - exp(-1 / (0.5 * d)) // level match rises slowly...
    private val kDown = 1 - exp(-1 / (0.05 * d)) // ...and drops fast, so peaks never get past the plain signal
    private val pkRel = exp(-1 / (0.4 * d))
    private val slewStep = 700.0 * 32 / d // at most 700 degrees per second
    private fun cut(f: Double) = min(f, 0.45 * d)

    private val vHp1 = Biquad().highPass(320.0, 0.707, d)
    private val vHp2 = Biquad().highPass(320.0, 0.707, d)
    private val vLp1 = Biquad().lowPass(cut(6000.0), 0.707, d)
    private val vLp2 = Biquad().lowPass(cut(6000.0), 0.707, d)
    private val vDark = Biquad().lowPass(cut(12000.0), 0.707, d)
    private val bandHp = Biquad().highPass(1200.0, 0.707, d)
    private val sHp1 = Biquad().highPass(cut(4000.0), 0.707, d)
    private val sHp2 = Biquad().highPass(cut(4000.0), 0.707, d)
    private val voice = SpBinaural(d, 1.0)
    private val bandL = SpBinaural(d, 0.6)
    private val bandR = SpBinaural(d, 0.6)
    private val rearL = SpBinaural(d, 0.5)
    private val rearR = SpBinaural(d, 0.5)
    private val highL = SpBinaural(d, 0.5)
    private val highR = SpBinaural(d, 0.5)
    private val echo = SpBinaural(d, 0.5)
    private val rev = Reverb(sr)

    private var dual = false
    private var mixT = 0.0
    private var mix = 0.0
    private var tDry = 1.0
    private var dry = 1.0
    private var tCl = 0.0
    private var cl = 0.0
    private var tSend = 0.0
    private var send = 0.0
    private var tBand = 0.0
    private var band = 0.0
    private var tIg = 1.0
    private var ig = 1.0
    private var tRb = 0.0
    private var rb = 0.0
    private var tHb = 0.0
    private var hb = 0.0
    private var azV = 0.0
    private var azL = -38.0
    private var azR = 38.0
    private var pIn = 1e-6
    private var pOut = 1e-6
    private var pkIn = 1e-4
    private var pkOut = 1e-4
    private var match = 1.0
    var outL = 0.0
    var outR = 0.0

    init {
        rearL.aim(-115.0, 0.0)
        rearR.aim(115.0, 0.0)
        highL.aim(-45.0, 35.0)
        highR.aim(45.0, 35.0)
        echo.aim(180.0, 10.0)
    }

    fun busy(): Boolean = mixT > 0.0 || mix > 0.001

    /** Moves an angle towards a target by the shortest way round, at most [slewStep] a block. Angles keep growing; only the difference matters. */
    private fun slewAng(c: Double, target: Double): Double {
        var dd = (target - c) % 360
        if (dd > 180) dd -= 360 else if (dd < -180) dd += 360
        return c + dd.coerceIn(-slewStep, slewStep)
    }

    /** Once per 32 frames. m: 0 off, 1 8D orbit, 2 immersive surround. fAbs = absolute frame index of the block start. */
    fun block(tl: SpTimeline?, an: SpAnchor, fAbs: Long, m: Int) {
        if (m == 0) {
            mixT = 0.0
            return
        }
        mixT = 1.0
        val t = max(0.0, an.base + (fAbs - an.origin) / d)
        when {
            m == 2 -> immersiveTargets()
            tl != null -> timelineTargets(tl, t)
            else -> genericTargets(t)
        }
    }

    /** Immersive: fixed front stage (centre voice, wide band) and rear and overhead ambience taken out of the side signal. */
    private fun immersiveTargets() {
        dual = false
        tDry = 1.0
        tCl = 0.0
        tSend = 0.14
        tBand = 0.08
        tIg = 1.0
        tRb = 0.42
        tHb = 0.32
        voice.aim(0.0, 0.0)
        bandL.aim(-30.0, 0.0)
        bandR.aim(30.0, 0.0)
    }

    /** 8D before the song is analysed: a steady swing, so there is never silence while the timeline is prepared. */
    private fun genericTargets(t: Double) {
        dual = false
        tDry = 0.8
        tCl = 0.0
        tSend = 0.24
        tBand = 0.08
        tIg = 1.0
        tRb = SP_BED * 0.55 * 0.75
        tHb = SP_BED * 0.5 * 0.67
        val orbit = (360 * 0.03 * t) % 360
        azV = slewAng(azV, 70 * sin(2 * PI * t / 10.0))
        azL = slewAng(azL, orbit - 38)
        azR = slewAng(azR, orbit + 38)
        voice.aim(azV, 0.0)
        bandL.aim(azL, 0.0)
        bandR.aim(azR, 0.0)
    }

    /** 8D with the analysed timeline: lines, impacts, echoes, beats and the orbit. */
    private fun timelineTargets(tl: SpTimeline, t: Double) {
        val dep = tl.at(tl.dep, t).toDouble()
        val clo = tl.at(tl.clo, t).toDouble()
        val inten = tl.at(tl.inten, t).toDouble()
        val pulse = tl.at(tl.pulse, t).toDouble()
        val a = clo * PI / 2
        tDry = 10.0.pow(-0.45 * dep) * cos(a)
        tCl = sin(a)
        tSend = (0.2 + 0.3 * dep) * (1 - 0.7 * clo)
        tBand = 0.05 + 0.1 * dep
        tIg = tl.at(tl.ig, t).toDouble()
        tRb = SP_BED * 0.55 * (0.5 + 0.5 * inten) * (1 - 0.4 * clo)
        tHb = SP_BED * 0.5 * (0.35 + 0.65 * max(0.6 * inten, pulse))
        vDark.lowPass(cut(16000.0 * 0.2.pow(dep)), 0.707, d)
        dual = tl.dual
        val el = atan(tl.at(tl.hg, t).toDouble()) * 180 / PI
        if (dual) {
            azL = -80.0
            azR = 80.0
        } else {
            val ti = tl.at(tl.ti, t).toDouble()
            val wd = tl.at(tl.wd, t).toDouble()
            azV = slewAng(azV, tl.at(tl.az, t).toDouble())
            azL = slewAng(azL, ti - wd)
            azR = slewAng(azR, ti + wd)
        }
        voice.aim(azV, 0.0)
        bandL.aim(azL, el)
        bandR.aim(azR, el)
    }

    /** One stereo frame. Results in outL / outR. */
    fun process(l: Double, r: Double) {
        mix += (mixT - mix) * kg
        if (mix < 1e-4 && mixT == 0.0) {
            mix = 0.0
            outL = l
            outR = r
            return
        }
        dry += (tDry - dry) * kg
        cl += (tCl - cl) * kg
        send += (tSend - send) * kg
        band += (tBand - band) * kg
        ig += (tIg - ig) * kg
        rb += (tRb - rb) * kg
        hb += (tHb - hb) * kg

        val m = (l + r) * 0.5
        var v = 0.0
        if (!dual) v = vLp2.run(vLp1.run(vHp2.run(vHp1.run(m, 0), 0), 0), 0)
        val vs = vDark.run(v, 0)
        val xl = l - v
        val xr = r - v

        voice.process(vs)
        var wl = voice.outL * dry + vs * cl
        var wr = voice.outR * dry + vs * cl

        // The rear and overhead layers take their share OUT of the front band (never a second copy of it), so the tone
        // stays clean: rear gets part of the side signal, overhead gets only its highs.
        var fl = xl
        var fr = xr
        if (rb > 1e-4 || hb > 1e-4) {
            val cc = (xl + xr) * 0.5
            val ss = (xl - xr) * 0.5
            val sh = sHp2.run(sHp1.run(ss, 0), 0)
            fl = cc + ss - ss * rb - sh * hb
            fr = cc - ss + ss * rb + sh * hb
            rearL.process(ss * rb)
            rearR.process(-ss * rb)
            highL.process(sh * hb)
            highR.process(-sh * hb)
            wl += rearL.outL + rearR.outL + highL.outL + highR.outL
            wr += rearL.outR + rearR.outR + highL.outR + highR.outR
        }
        bandL.process(fl * ig)
        bandR.process(fr * ig)
        wl += bandL.outL + bandR.outL
        wr += bandL.outR + bandR.outR

        rev.process(vs * send + bandHp.run((xl + xr) * 0.5 * ig, 0) * band)
        echo.process((rev.outL + rev.outR) * 0.5)
        wl += echo.outL + 0.3 * rev.outL
        wr += echo.outR + 0.3 * rev.outR

        // Level match: the spatial version sits at the loudness of the plain signal it replaces and its peaks never get
        // past the plain signal's, so the limiter is not pushed and the effect is judged on placement, not on a level change.
        pIn += ((l * l + r * r) * 0.5 - pIn) * kEnv
        pOut += ((wl * wl + wr * wr) * 0.5 - pOut) * kEnv
        pkIn = max(max(abs(l), abs(r)), pkIn * pkRel)
        pkOut = max(max(abs(wl), abs(wr)), pkOut * pkRel)
        val rmsWant = sqrt((pIn + 1e-10) / (pOut + 1e-10))
        val pkWant = (pkIn + 1e-4) / (pkOut + 1e-4)
        val want = min(rmsWant, pkWant).coerceIn(0.4, 1.25)
        match += (want - match) * (if (want < match) kDown else kUp)
        outL = l * (1 - mix) + wl * match * mix
        outR = r * (1 - mix) + wr * match * mix
    }
}

/** Growable float list for the per-20 ms features. */
private class SpList {
    private var a = FloatArray(4096)
    var size = 0
        private set

    fun add(x: Float) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = x
    }

    fun array(): FloatArray = a.copyOf(size)
}

/** One musical chunk: a line, a hit, or a repeat answering the line before it. */
private class SpPhrase(var s: Double, var e: Double, var l: Float) {
    var kind = 0 // 0 normal, 1 impact (close, centred), 2 echo (far, behind)
    var f = 0f // echo strength
    var sub = false // a piece cut out of a long chunk
    val d: Double get() = e - s

    fun piece(a: Double, b: Double): SpPhrase = SpPhrase(a, b, l).also {
        it.kind = kind
        it.f = f
        it.sub = a > s
    }
}

private fun spSm(x: Float): Float {
    val y = x.coerceIn(0f, 1f)
    return y * y * (3 - 2 * y)
}

/** Zero-phase exponential smoothing, so changes start a little before the event they belong to. */
private fun spZeroPhase(a: FloatArray, tau: Double) {
    if (a.isEmpty()) return
    val k = (1 - exp(-SpTimeline.HOP / tau)).toFloat()
    var y = a[0]
    for (i in a.indices) {
        y += (a[i] - y) * k
        a[i] = y
    }
    y = a[a.size - 1]
    for (i in a.indices.reversed()) {
        y += (a[i] - y) * k
        a[i] = y
    }
}

/**
 * Per-hop features of one song, and the timeline built from them. The audio is averaged down 3-4x first (the bands we
 * follow are all below 3.5 kHz), which makes the whole analysis several times faster.
 */
internal class SpFeatures(private val sr: Int) {
    private val h0 = max(1, (sr * SpTimeline.HOP).roundToInt())
    private val dec = listOf(4, 3, 2).firstOrNull { h0 % it == 0 } ?: 1
    private val d = sr.toDouble() / dec
    private val h = h0 / dec
    private val hp = Biquad().highPass(250.0, 0.707, d)
    private val lp = Biquad().lowPass(3500.0, 0.707, d)
    private val bass = Biquad().lowPass(120.0, 0.707, d)
    private var dn = 0
    private var ai = 0.0
    private var bi = 0.0
    private var cnt = 0
    private var acc = 0.0
    private var tot = 0.0
    private var low = 0.0
    private val svL = SpList()
    private val stL = SpList()
    private val ebL = SpList()
    private var lr = 0.0
    private var ll = 0.0
    private var rr = 0.0
    private var ss = 0.0
    private var mm = 0.0

    var frames = 0L
        private set

    val rate: Int get() = sr

    fun push(l0: Double, r0: Double) {
        frames++
        ai += l0
        bi += r0
        if (++dn < dec) return
        val l = ai / dec
        val r = bi / dec
        ai = 0.0
        bi = 0.0
        dn = 0
        val m = (l + r) * 0.5
        val x = lp.run(hp.run(m, 0), 0)
        val y = bass.run(m, 0)
        acc += x * x
        tot += l * l + r * r
        low += y * y
        lr += l * r
        ll += l * l
        rr += r * r
        ss += (l - r) * (l - r) * 0.25
        mm += m * m
        if (++cnt == h) {
            svL.add((10 * log10(acc / h + 1e-9)).toFloat())
            stL.add((10 * log10(tot / h / 2 + 1e-9)).toFloat())
            ebL.add(sqrt(low / h).toFloat())
            cnt = 0
            acc = 0.0
            tot = 0.0
            low = 0.0
        }
    }

    /** Builds the timeline for everything pushed so far (so it can be called early for a first version, and again at the end). */
    fun timeline(): SpTimeline? {
        val n = svL.size
        if (n < 60) return null
        val sv = svL.array()
        val st = stL.array()
        val eb = ebL.array()
        val hop = h0.toDouble() / sr

        // Smooth: fast attack, slow release, so a line reads as one event rather than a cloud of jitter.
        var s = sv[0]
        var u = st[0]
        for (i in 0 until n) {
            s += (sv[i] - s) * (if (sv[i] > s) 0.6f else 0.3f)
            sv[i] = s
            u += (st[i] - u) * 0.04f
            st[i] = u
        }

        val svSorted = sv.copyOf()
        svSorted.sort()
        val stSorted = st.copyOf()
        stSorted.sort()
        val lo = svSorted[(n * 0.1).toInt()]
        val tl = stSorted[(n * 0.2).toInt()]
        val th = stSorted[(n * 0.95).toInt()]
        var mu = 0.0
        for (x in sv) mu += x
        mu /= n
        var vr = 0.0
        for (x in sv) vr += (x - mu) * (x - mu)
        val fl = lo + 3
        val dp = min(6.5, max(3.5, sqrt(vr / n) * 0.8)).toFloat()
        val dual = lr / sqrt(ll * rr + 1e-12) < 0.3
        val narrow = 10 * log10((ss + 1e-12) / (mm + 1e-12)) < -13

        fun mean(a: Double, b: Double): Float {
            val i0 = (a / hop).toInt()
            val i1 = max(i0 + 1, (b / hop).toInt())
            var w = 0.0
            for (i in i0 until min(i1, n)) w += sv[i]
            return (w / (i1 - i0)).toFloat()
        }

        fun gmin(a: Double, b: Double): Float {
            var m = 99f
            for (i in (a / hop).toInt() until min(n, (b / hop).toInt() + 1)) m = min(m, sv[i])
            return m
        }

        // 1. Gaps in the vocal band cut the song into raw chunks.
        val raw = ArrayList<DoubleArray>()
        var inP = false
        var pk = 0f
        var g = 0
        var gs = 0
        var s0 = 0
        var vm = lo
        for (i in 0 until n) {
            val x = sv[i]
            if (!inP) {
                vm = min(vm, x)
                if (x > fl && x > vm + 4) {
                    inP = true
                    s0 = i
                    pk = x
                    g = 0
                }
            } else {
                pk = max(x, pk - 0.05f)
                if (x < pk - dp || x < fl) {
                    if (g == 0) gs = i
                    g++
                    if (g >= 6) {
                        if ((gs - s0) * hop >= 0.2) raw.add(doubleArrayOf(s0 * hop, gs * hop))
                        inP = false
                        vm = x
                    }
                } else {
                    g = 0
                }
            }
        }
        if (inP && (n - s0) * hop >= 0.4) raw.add(doubleArrayOf(s0 * hop, n * hop))

        // 2. Tiny bursts merge into line-length chunks; deep gaps are kept.
        val merged = ArrayList<SpPhrase>()
        for (z in raw) {
            val o = merged.lastOrNull()
            if (o != null && z[0] - o.e < 0.4 && o.d < 1.2 && o.l - gmin(o.e, z[0]) < 12) {
                o.e = z[1]
                o.l = mean(o.s, o.e)
            } else {
                merged.add(SpPhrase(z[0], z[1], mean(z[0], z[1])))
            }
        }
        val p0 = merged.filter { it.d >= 0.4 }

        // 3. Judge each chunk against its neighbours: louder = impact, quieter repeat of the line before = echo.
        p0.forEachIndexed { k, p ->
            val nb = p0.filter { abs(it.s - p.s) < 16 }.map { it.l }.sorted()
            val rank = nb.indexOf(p.l).toDouble() / max(1, nb.size - 1)
            val rf = nb[(nb.size * 0.45).toInt()]
            val c = if (p.d >= 0.9 && rank >= 0.6) spSm((p.l - rf - 1.2f) / 3f) else 0f
            val o = p0.getOrNull(k - 1)
            var f = 0f
            if (o != null && p.s - o.e < 1.4) {
                val dr = max(o.l, p0.getOrNull(k - 2)?.l ?: -99f) - p.l
                f = spSm((dr - 2f) / 5f) * (if (abs(p.d - o.d) < 0.6 * o.d + 0.5) 1f else 0.5f)
            }
            p.f = f
            p.kind = if (c >= 0.55f) 1 else if (f >= 0.55f) 2 else 0
        }

        // 4. Beat pulse from bass onsets, and intensity from loudness relative to the song.
        val pulse = FloatArray(n)
        val inten = FloatArray(n)
        var mx = 1e-6f
        var pv = 0f
        var la = -9
        var pp = 0f
        for (i in 0 until n) {
            val dd = eb[i] - pv
            pv = eb[i]
            mx = max(mx * 0.9995f, dd)
            if (dd > 0.35f * mx && i - la > 6) {
                la = i
                pp = 1f
            }
            pulse[i] = pp
            pp *= 0.92f
            inten[i] = ((st[i] - tl) / (th - tl + 1e-3f)).coerceIn(0f, 1f)
        }

        // 5. Very long chunks (dense tracks) are cut, only at real dips in the music, so the voice still alternates.
        val cs = DoubleArray(n + 1)
        for (i in 0 until n) cs[i + 1] = cs[i] + sv[i]
        fun av(a0: Int, b0: Int): Double {
            val a = max(0, a0)
            val b = min(n, b0)
            return if (b > a) (cs[b] - cs[a]) / (b - a) else 0.0
        }
        fun notch(i: Int): Double = min(av(i - 25, i - 8), av(i + 8, i + 25)) - sv[i]

        val phrases = ArrayList<SpPhrase>()
        for (p in p0) {
            if (p.d <= 8) {
                phrases.add(p)
                continue
            }
            var a = 0f
            var c0 = 0
            for (i in (p.s / hop).toInt() until min(n, (p.e / hop).toInt())) {
                a += inten[i]
                c0++
            }
            a /= max(1, c0)
            val iv = if (a > 0.65f) 5.0 else if (a > 0.35f) 6.5 else 8.0
            val cnt2 = max(2, (p.d / iv).roundToInt())
            val stp = p.d / cnt2
            var t1 = p.s
            for (j in 1 until cnt2) {
                val tg = p.s + j * stp
                val i0 = max(8, ((tg - 0.3 * stp) / hop).toInt())
                val i1 = min(n - 9, ((tg + 0.3 * stp) / hop).toInt())
                var bi2 = i0
                var bv = -99.0
                for (q in i0..i1) {
                    val z = notch(q)
                    if (z > bv) {
                        bv = z
                        bi2 = q
                    }
                }
                val cut = bi2 * hop
                if (bv >= 2 && cut - t1 >= 3 && p.e - cut >= 2) {
                    phrases.add(p.piece(t1, cut))
                    t1 = cut
                }
            }
            phrases.add(p.piece(t1, p.e))
        }

        // 6. Bake the glides: lines alternate sides, impacts centre, echoes behind; every glide lands as its line starts.
        val np = phrases.size
        val am = if (narrow) 62.0 else 100.0
        val af = if (narrow) 50.0 else 78.0
        val tgt = DoubleArray(np)
        val fl2 = DoubleArray(np)
        val tt = DoubleArray(np)
        var x = 0.0
        var c = 0
        var pn = false
        for (k in 0 until np) {
            val p = phrases[k]
            val o = phrases.getOrNull(k - 1)
            val nk = p.kind == 0
            val fast = o != null && (p.sub || p.s - o.s < 1.6 || p.s - o.e < 0.3)
            val aim = if (nk) {
                val sg = if (c % 2 == 1) 1.0 else -1.0
                c++
                sg * (if (fast) af else am) * (1 - SP_VOICE_MOVE)
            } else if (p.kind == 1) {
                0.0
            } else {
                180.0 + (if (c % 2 == 1) -40.0 else 40.0)
            }
            var dd = ((aim - x) % 360 + 360) % 360
            if (!(SP_VOICE_MOVE < 0.3 && pn && nk && !fast) && dd > 180) dd -= 360
            x = if (k > 0) x + dd else aim
            tgt[k] = x
            pn = nk
            val tm = max(if (fast) 0.25 else 0.45, abs(dd) / 400)
            val arr = if (o != null) p.s + (if (p.sub) 0.06 else 0.02) else 0.0
            val t0 = if (o != null) (if (p.sub) tm else min(1.1, max(tm, arr - (o.e + 0.05)))) else 1.0
            val f0 = if (o != null) (if (p.sub) arr - t0 else max(arr - t0, o.e - 0.12)) else 0.0
            fl2[k] = f0
            tt[k] = max(0.05, if (o != null) arr - f0 else 1.0)
        }

        val az = FloatArray(n)
        val dep = FloatArray(n)
        val clo = FloatArray(n)
        var kk = 0
        var g0 = if (np > 0) tgt[0] else 0.0
        var g1 = g0
        var gs2 = 0.0
        var gT = 1.0
        var cx = g0
        for (i in 0 until n) {
            val t = i * SpTimeline.HOP
            while (kk + 1 < np && fl2[kk + 1] <= t) {
                kk++
                g0 = cx
                g1 = tgt[kk]
                gs2 = fl2[kk]
                gT = tt[kk]
            }
            val u = ((t - gs2) / gT).coerceIn(0.0, 1.0)
            cx = g0 + (g1 - g0) * u * u * u * (u * (u * 6 - 15) + 10)
            az[i] = cx.toFloat()
            val p = if (np > 0) phrases[kk] else null
            dep[i] = min(0.95, 0.2 + 0.75 * (p?.f ?: 0f)).toFloat()
            clo[i] = if (p?.kind == 1) 1f else 0f
        }
        spZeroPhase(dep, 0.25)
        spZeroPhase(clo, 0.2)
        spZeroPhase(inten, 0.8)
        val pu = FloatArray(n) { pulse[it] * 0.8f }
        spZeroPhase(pu, 0.06)

        val ti = FloatArray(n)
        val wd = FloatArray(n)
        val hg = FloatArray(n)
        val ig = FloatArray(n)
        var ang = 0.0
        for (i in 0 until n) {
            val cl = clo[i].toDouble()
            val iz = inten[i].toDouble()
            val pz = pu[i].toDouble()
            ang += 360 * (0.04 + 0.2 * iz) * (1 + 0.5 * pz) * (1 - 0.5 * cl) * SpTimeline.HOP
            ti[i] = ang.toFloat()
            wd[i] = ((30 * (0.6 + 0.4 * iz) + 8 * pz) * (1 - 0.5 * cl) + 14 * max(0.0, dep[i] - 0.3)).toFloat()
            hg[i] = (0.2 * (0.2 + 0.8 * iz)).toFloat()
            ig[i] = (1 - 0.16 * cl).toFloat()
            if (dual) {
                ti[i] = 0f
                wd[i] = (80 + 8 * sin(i * SpTimeline.HOP * 0.3)).toFloat()
            }
        }
        return SpTimeline(dual, az, dep, clo, ti, wd, hg, ig, inten, pu)
    }
}

/** Decodes one song (MediaExtractor + MediaCodec, faster than real time) and builds its timeline. */
private object SpAnalyzer {
    /** Longer than this: skipped (podcasts and long mixes are not worth the wait or the battery). */
    private const val MAX_SECONDS = 2400.0

    /** The first stretch is published as soon as it is decoded, so the real timeline takes over within about a second. */
    private const val PARTIAL_SECONDS = 45.0

    fun build(ctx: Context, uri: Uri, onPartial: (SpTimeline) -> Unit): SpTimeline? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    break
                }
            }
            if (track < 0) return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME) ?: return null)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var feat: SpFeatures? = null
            var ch = 2
            var flt = false
            var inEos = false
            var outEos = false
            var partial = false
            while (!outEos) {
                if (!inEos) {
                    val ii = c.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val ib = c.getInputBuffer(ii)!!
                        val size = ex.readSampleData(ib, 0)
                        if (size < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            c.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 5_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    flt = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    feat = SpFeatures(of.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                } else if (oi >= 0) {
                    val f = feat
                    if (f != null && info.size > 0) {
                        feed(f, c.getOutputBuffer(oi)!!, info.offset, info.size, ch, flt)
                        if (f.frames > MAX_SECONDS * f.rate) {
                            c.releaseOutputBuffer(oi, false)
                            return null
                        }
                        if (!partial && f.frames > PARTIAL_SECONDS * f.rate) {
                            partial = true
                            f.timeline()?.let(onPartial)
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
                }
            }
            return feat?.timeline()
        } catch (e: Exception) {
            return null
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
            }
            codec?.release()
            ex.release()
        }
    }

    private fun feed(f: SpFeatures, ob: ByteBuffer, off: Int, size: Int, ch: Int, flt: Boolean) {
        ob.order(ByteOrder.nativeOrder())
        ob.clear()
        ob.position(off)
        ob.limit(off + size)
        if (flt) {
            val fb = ob.asFloatBuffer()
            for (i in 0 until fb.remaining() / ch) {
                val l = fb.get()
                val r = if (ch > 1) fb.get() else l
                for (c in 2 until ch) fb.get()
                f.push(l.toDouble(), r.toDouble())
            }
        } else {
            val sb = ob.asShortBuffer()
            for (i in 0 until sb.remaining() / ch) {
                val l = sb.get() / 32768.0
                val r = if (ch > 1) sb.get() / 32768.0 else l
                for (c in 2 until ch) sb.get()
                f.push(l, r)
            }
        }
    }
}

/**
 * Main-thread glue. Keeps each chain's immersive / auto flags in step with the settings, tells it which song the player
 * thinks it is on (a fallback; the stream metadata is the primary source), asks for songs to be analysed (the current one
 * urgently, the next one lazily), and re-anchors a clock that has drifted far from the player's.
 */
internal object SpatialHub {
    private val main = Handler(Looper.getMainLooper())
    private val urgentPool = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            r.run()
        }, "sonora-spatial")
    }
    private val lazyPool = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)
            r.run()
        }, "sonora-spatial-next")
    }
    private val cache = object : LinkedHashMap<String, SpTimeline>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SpTimeline>?): Boolean {
            val drop = size > 8
            if (drop && eldest != null) SpStore.map.remove(eldest.key)
            return drop
        }
    }
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()
    private val syncOf = HashMap<ExoPlayer, Player.Listener>()
    private val bad = HashMap<ExoPlayer, Int>()
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 250L)
        }
    }

    fun start() {
        main.removeCallbacks(tick)
        main.post(tick)
    }

    fun stop() {
        main.removeCallbacks(tick)
        syncOf.clear()
        bad.clear()
        pending.clear()
    }

    private fun refresh() {
        val s = S.settings.value
        for (dsp in arrayOf(Engine.dspA, Engine.dspB)) {
            dsp.spAtmos = s.atmos && !s.orbit
            dsp.spAuto = s.spatialAuto
        }
        var any = false
        Engine.forEachPlayer { any = true }
        if (!any) return
        val mainP = Engine.exo
        val mainDsp = Engine.dsp
        val sideDsp = if (mainDsp === Engine.dspA) Engine.dspB else Engine.dspA
        bind(mainP, mainDsp)
        Engine.forEachPlayer { p ->
            if (p !== mainP) bind(p, sideDsp)
        }
    }

    private fun bind(p: ExoPlayer, dsp: SonoraDsp) {
        if (syncOf[p] == null) {
            val l = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    refresh()
                }
            }
            syncOf[p] = l
            p.addListener(l)
        }
        val item = p.currentMediaItem
        val id = item?.mediaId?.takeIf { it.isNotEmpty() }
        dsp.spHint = id
        if (id != null && item != null && dsp.params.orbit) {
            if (!SpStore.map.containsKey(id)) request(item, true)
            val ni = p.nextMediaItemIndex
            if (ni != C.INDEX_UNSET) request(p.getMediaItemAt(ni), false)
        }
        checkClock(p, dsp)
    }

    /**
     * The clock is normally exact (set at every flush). This only catches a clock that is far from the player's, for
     * example if a flush ever arrived without a usable position: three bad readings in a row re-anchor it.
     */
    private fun checkClock(p: ExoPlayer, dsp: SonoraDsp) {
        if (!p.isPlaying) {
            bad[p] = 0
            return
        }
        val an = dsp.spAnchor
        val est = an.base + (dsp.sampleFrames - an.origin) / dsp.sampleRate.toDouble()
        val diff = est - p.currentPosition / 1000.0
        if (diff < -0.6 || diff > 3.0) {
            val n = (bad[p] ?: 0) + 1
            if (n >= 3) {
                dsp.spAnchor = SpAnchor(p.currentPosition / 1000.0 + 0.3, dsp.sampleFrames)
                bad[p] = 0
            } else {
                bad[p] = n
            }
        } else {
            bad[p] = 0
        }
    }

    private fun publish(id: String, tl: SpTimeline) {
        cache[id] = tl
        SpStore.map[id] = tl
    }

    private fun request(item: MediaItem, urgent: Boolean) {
        val id = item.mediaId
        val uri = item.localConfiguration?.uri ?: return
        if (id.isEmpty() || SpStore.map.containsKey(id) || id in failed || !pending.add(id)) return
        val ctx = S.app
        (if (urgent) urgentPool else lazyPool).execute {
            val tl = try {
                SpAnalyzer.build(ctx, uri) { part -> main.post { publish(id, part) } }
            } catch (e: Exception) {
                null
            }
            main.post {
                pending.remove(id)
                if (tl != null) {
                    publish(id, tl)
                } else if (!SpStore.map.containsKey(id)) {
                    failed.add(id)
                }
                refresh()
            }
        }
    }
}
