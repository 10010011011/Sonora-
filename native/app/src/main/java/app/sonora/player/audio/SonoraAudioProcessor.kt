package app.sonora.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import app.sonora.player.data.EQ_FREQS
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The whole Sonora DSP chain in ONE pass (16-bit PCM in -> float math -> 16-bit PCM out; always stereo out):
 *   preamp*replayGain -> 10-band EQ -> bass shelf -> treble shelf -> stereo matrix + crossfeed ->
 *   Orbit 8D (D-01: ITD/ILD + head shadow lowpass + rear high-shelf cut) ->
 *   Room ambience (D-02: Freeverb 8-comb + 4-allpass stereo network, ~2.0s decay) ->
 *   boost -> limiter (-1.5 dBFS, 2ms attack, 120ms release).
 * Multichannel files (> 2 channels) bypass the DSP (D-05); mono is upmixed to stereo.
 */
@OptIn(UnstableApi::class)
class SonoraAudioProcessor(private val holder: DspParamsHolder) : BaseAudioProcessor() {

    companion object {
        const val TAP_SIZE = 4096                     // power of two
        private const val SMOOTH_SEC = 0.025
        private const val RG_SMOOTH_SEC = 0.5
        private const val CF_DELAY_SEC = 0.0003
        private const val CF_LP_HZ = 700.0
        private const val ORBIT_MAX_ITD_SEC = 0.00066 // Woodworth max ITD ~0.66 ms
        private const val LIM_THR = 0.8414            // -1.5 dBFS
        private const val LIM_ATTACK_SEC = 0.002
        private const val LIM_RELEASE_SEC = 0.12
        private const val BASS_HZ = 100.0
        private const val TREBLE_HZ = 8000.0
        private const val EQ_Q = 1.1

        // Freeverb base delay lengths at 44100 Hz (stereo spread = +23 samples on R)
        private val COMB_TUNING = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val ALLPASS_TUNING = intArrayOf(556, 441, 341, 225)
        private const val STEREO_SPREAD = 23
    }

    private var inChannels = 2
    private var fs = 44100.0
    private var kS = 0.001
    private var kRg = 0.0001
    private var limAtk = 0.01
    private var limRel = 0.0001

    private val eq = Array(10) { Biquad() }
    private val eqActive = BooleanArray(10)
    private val curEq = DoubleArray(10)
    private val lastEq = DoubleArray(10) { Double.NaN }
    private val bass = Biquad()
    private val treble = Biquad()
    private var bassActive = false
    private var trebleActive = false
    private var curBass = 0.0
    private var curTreble = 0.0
    private var lastBass = Double.NaN
    private var lastTreble = Double.NaN

    private val cfLpL = Biquad()
    private val cfLpR = Biquad()
    private var cfDelayL = DoubleArray(1)
    private var cfDelayR = DoubleArray(1)
    private var cfPos = 0

    // Orbit 8D state (D-01)
    private var orbitPhase = 0.0
    private var orbitDelayL = DoubleArray(64)
    private var orbitDelayR = DoubleArray(64)
    private var orbitWrite = 0
    private var orbitLpL = 0.0
    private var orbitLpR = 0.0
    private val orbitRearShelf = Biquad()
    private var lastRearCutDb = Double.NaN
    private var curOrbitDry = 1.0
    private var curOrbitWet = 0.0

    // Room Freeverb state (D-02)
    private var combBufL = Array(8) { DoubleArray(1) }
    private var combBufR = Array(8) { DoubleArray(1) }
    private val combIdxL = IntArray(8)
    private val combIdxR = IntArray(8)
    private val combFilterL = DoubleArray(8)
    private val combFilterR = DoubleArray(8)
    private var allpassBufL = Array(4) { DoubleArray(1) }
    private var allpassBufR = Array(4) { DoubleArray(1) }
    private val allpassIdxL = IntArray(4)
    private val allpassIdxR = IntArray(4)
    private var curRoomDry = 1.0
    private var curRoomWet = 0.0

    private var curPre = 1.0
    private var curRg = 1.0
    private var curBoost = 1.0
    private var curCf = 0.0
    private var mLL = 1.0
    private var mRL = 0.0
    private var mLR = 0.0
    private var mRR = 1.0
    private var limEnv = 1.0
    private var wasLimiter = false

    // Visualizer tap (post-DSP) and Limiter telemetry
    val tapL = FloatArray(TAP_SIZE)
    val tapR = FloatArray(TAP_SIZE)
    @Volatile
    var tapWrite = 0
    @Volatile
    var tapSampleRate = 44100
    @Volatile
    var limitingDb: Double = 0.0
    @Volatile
    var clipping: Boolean = false

    init { holder.processor = this }

    fun snapshot(dest: FloatArray, left: Boolean) {
        val src = if (left) tapL else tapR
        val end = tapWrite
        val n = minOf(dest.size, TAP_SIZE)
        for (i in 0 until n) dest[i] = src[(end - n + i) and (TAP_SIZE - 1)]
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        // Deviation D-05: > 2 channel files bypass the stereo DSP
        if (inputAudioFormat.channelCount < 1 || inputAudioFormat.channelCount > 2) return AudioFormat.NOT_SET
        inChannels = inputAudioFormat.channelCount
        fs = inputAudioFormat.sampleRate.toDouble()
        tapSampleRate = inputAudioFormat.sampleRate
        kS = 1.0 - exp(-1.0 / (fs * SMOOTH_SEC))
        kRg = 1.0 - exp(-1.0 / (fs * RG_SMOOTH_SEC))
        limAtk = 1.0 - exp(-1.0 / (fs * LIM_ATTACK_SEC))
        limRel = 1.0 - exp(-1.0 / (fs * LIM_RELEASE_SEC))
        resetState()
        return AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        resetState()
    }

    override fun onReset() {
        // nothing to release
    }

    private fun resetState() {
        val p = holder.p
        for (i in 0 until 10) { eq[i].reset(); curEq[i] = p.eqGainsDb[i]; lastEq[i] = Double.NaN; eqActive[i] = false }
        bass.reset(); treble.reset()
        curBass = p.bassDb; curTreble = p.trebleDb; lastBass = Double.NaN; lastTreble = Double.NaN
        cfLpL.lowPass(fs, CF_LP_HZ, 1.0); cfLpR.lowPass(fs, CF_LP_HZ, 1.0)
        cfLpL.reset(); cfLpR.reset()
        val nCf = max(1, (fs * CF_DELAY_SEC).roundToInt())
        cfDelayL = DoubleArray(nCf); cfDelayR = DoubleArray(nCf); cfPos = 0

        val nOrbit = max(8, (fs * ORBIT_MAX_ITD_SEC).roundToInt() + 4)
        orbitDelayL = DoubleArray(nOrbit)
        orbitDelayR = DoubleArray(nOrbit)
        orbitWrite = 0
        orbitLpL = 0.0
        orbitLpR = 0.0
        orbitRearShelf.reset()
        lastRearCutDb = Double.NaN
        curOrbitDry = p.orbitDry
        curOrbitWet = p.orbitWet

        val scale = fs / 44100.0
        for (i in 0 until 8) {
            val lenL = max(16, (COMB_TUNING[i] * scale).roundToInt())
            val lenR = max(16, ((COMB_TUNING[i] + STEREO_SPREAD) * scale).roundToInt())
            combBufL[i] = DoubleArray(lenL)
            combBufR[i] = DoubleArray(lenR)
            combIdxL[i] = 0
            combIdxR[i] = 0
            combFilterL[i] = 0.0
            combFilterR[i] = 0.0
        }
        for (i in 0 until 4) {
            val lenL = max(8, (ALLPASS_TUNING[i] * scale).roundToInt())
            val lenR = max(8, ((ALLPASS_TUNING[i] + STEREO_SPREAD) * scale).roundToInt())
            allpassBufL[i] = DoubleArray(lenL)
            allpassBufR[i] = DoubleArray(lenR)
            allpassIdxL[i] = 0
            allpassIdxR[i] = 0
        }
        curRoomDry = p.roomDry
        curRoomWet = p.roomWet

        curPre = p.preampLin; curRg = p.rgLin; curBoost = p.boostLin; curCf = p.crossfeedGain
        mLL = p.mLL; mRL = p.mRL; mLR = p.mLR; mRR = p.mRR
        limEnv = 1.0
        limitingDb = 0.0
        clipping = false
        updateFilters()
    }

    private fun configureBand(i: Int, gainDb: Double) {
        when (i) {
            0 -> eq[i].lowShelf(fs, EQ_FREQS[0], gainDb)
            9 -> eq[i].highShelf(fs, EQ_FREQS[9], gainDb)
            else -> eq[i].peaking(fs, EQ_FREQS[i], EQ_Q, gainDb)
        }
    }

    private fun updateFilters() {
        for (i in 0 until 10) {
            val g = curEq[i]
            if (g != lastEq[i]) {
                val active = abs(g) > 1e-4
                if (active && !eqActive[i]) eq[i].reset()
                eqActive[i] = active
                if (active) configureBand(i, g) else eq[i].setIdentity()
                lastEq[i] = g
            }
        }
        if (curBass != lastBass) {
            val active = abs(curBass) > 1e-4
            if (active && !bassActive) bass.reset()
            bassActive = active
            if (active) bass.lowShelf(fs, BASS_HZ, curBass) else bass.setIdentity()
            lastBass = curBass
        }
        if (curTreble != lastTreble) {
            val active = abs(curTreble) > 1e-4
            if (active && !trebleActive) treble.reset()
            trebleActive = active
            if (active) treble.highShelf(fs, TREBLE_HZ, curTreble) else treble.setIdentity()
            lastTreble = curTreble
        }
    }

    private fun smoothBlock(p: DspParams, blockSec: Double) {
        val kb = 1.0 - exp(-blockSec / SMOOTH_SEC)
        for (i in 0 until 10) {
            val target = p.eqGainsDb[i]
            var c = curEq[i] + (target - curEq[i]) * kb
            if (abs(target - c) < 0.005) c = target
            curEq[i] = c
        }
        var cb = curBass + (p.bassDb - curBass) * kb
        if (abs(p.bassDb - cb) < 0.005) cb = p.bassDb
        curBass = cb
        var ct = curTreble + (p.trebleDb - curTreble) * kb
        if (abs(p.trebleDb - ct) < 0.005) ct = p.trebleDb
        curTreble = ct
        mLL += (p.mLL - mLL) * kb
        mRL += (p.mRL - mRL) * kb
        mLR += (p.mLR - mLR) * kb
        mRR += (p.mRR - mRR) * kb
        curCf += (p.crossfeedGain - curCf) * kb
        curOrbitDry += (p.orbitDry - curOrbitDry) * kb
        curOrbitWet += (p.orbitWet - curOrbitWet) * kb
        curRoomDry += (p.roomDry - curRoomDry) * kb
        curRoomWet += (p.roomWet - curRoomWet) * kb
        updateFilters()
    }

    private fun toShort(x: Double): Short {
        val v = (x * 32767.0).roundToInt()
        return (if (v > 32767) 32767 else if (v < -32768) -32768 else v).toShort()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (2 * inChannels)
        if (frames == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val p = holder.p
        val out = replaceOutputBuffer(frames * 4)
        smoothBlock(p, frames / fs)

        val limiterOn = p.limiter
        if (limiterOn != wasLimiter) { limEnv = 1.0; wasLimiter = limiterOn }
        val spatialActive = p.spatial || abs(mLL - 1.0) > 1e-4 || abs(mRR - 1.0) > 1e-4 ||
            abs(mRL) > 1e-4 || abs(mLR) > 1e-4 || curCf > 1e-4
        val orbitActive = p.orbit || curOrbitWet > 1e-4
        val roomActive = p.room > 0.001 || curRoomWet > 1e-4

        // Precompute block-rate Orbit 8D ITD/ILD + head shadow coefficients (D-01)
        var orbitGainL = 1.0
        var orbitGainR = 1.0
        var orbitDelaySamplesL = 0
        var orbitDelaySamplesR = 0
        var orbitAlphaL = 1.0
        var orbitAlphaR = 1.0
        if (orbitActive) {
            val az = orbitPhase
            val sinAz = sin(az) // +1 = right, -1 = left
            val cosAz = cos(az) // +1 = front, -1 = behind
            val maxItdSamples = (fs * ORBIT_MAX_ITD_SEC).roundToInt().coerceAtMost(orbitDelayL.size - 2)
            // Woodworth ITD formula: delay on the far ear
            val woodworth = (abs(sinAz) + abs(sinAz).coerceAtMost(1.0)) * 0.5
            val itdSamples = (woodworth * maxItdSamples).roundToInt()
            if (sinAz >= 0.0) {
                orbitDelaySamplesL = itdSamples
                orbitDelaySamplesR = 0
            } else {
                orbitDelaySamplesL = 0
                orbitDelaySamplesR = itdSamples
            }
            // Equal-power ILD panning with 0.25 floor on far ear for natural head-shadowing
            val panAngle = (sinAz + 1.0) * 0.25 * PI
            orbitGainL = 0.25 + 0.75 * cos(panAngle)
            orbitGainR = 0.25 + 0.75 * sin(panAngle)

            // One-pole lowpass on far ear (1.5 kHz to 6.0 kHz by angle)
            val shadowL = if (sinAz > 0.0) 6000.0 - 4500.0 * sinAz else 16000.0
            val shadowR = if (sinAz < 0.0) 6000.0 - 4500.0 * (-sinAz) else 16000.0
            orbitAlphaL = 1.0 - exp(-2.0 * PI * shadowL / fs)
            orbitAlphaR = 1.0 - exp(-2.0 * PI * shadowR / fs)

            // Mild high-shelf cut when source is behind (cosAz < 0)
            val rearCutDb = if (cosAz < 0.0) cosAz * 3.5 else 0.0
            if (abs(rearCutDb - lastRearCutDb) > 0.15 || lastRearCutDb.isNaN()) {
                orbitRearShelf.highShelf(fs, 3200.0, rearCutDb)
                lastRearCutDb = rearCutDb
            }
        }

        // Room Freeverb parameters (~2.0s decay, damp = 0.28, feedback = 0.86)
        val roomFeedback = 0.84 + 0.04 * p.room
        val roomDamp1 = 0.28
        val roomDamp2 = 1.0 - roomDamp1

        var tw = tapWrite
        val inv = 1.0 / 32768.0
        val phaseStep = 2.0 * PI * p.orbitSpeed / fs
        var blockClip = false
        var minLimEnv = 1.0

        for (f in 0 until frames) {
            var l = inputBuffer.getShort().toDouble() * inv
            var r = if (inChannels == 2) inputBuffer.getShort().toDouble() * inv else l

            curPre += (p.preampLin - curPre) * kS
            curRg += (p.rgLin - curRg) * kRg
            val g = curPre * curRg
            l *= g
            r *= g

            for (i in 0 until 10) {
                if (eqActive[i]) { l = eq[i].processLeft(l); r = eq[i].processRight(r) }
            }
            if (bassActive) { l = bass.processLeft(l); r = bass.processRight(r) }
            if (trebleActive) { l = treble.processLeft(l); r = treble.processRight(r) }

            if (spatialActive) {
                val lpL = cfLpL.processLeft(l)
                val lpR = cfLpR.processLeft(r)
                val dl = cfDelayL[cfPos]
                val dr = cfDelayR[cfPos]
                cfDelayL[cfPos] = lpL
                cfDelayR[cfPos] = lpR
                cfPos = (cfPos + 1) % cfDelayL.size
                val outL = l * mLL + r * mRL + dr * curCf
                val outR = l * mLR + r * mRR + dl * curCf
                l = outL
                r = outR
            }

            if (orbitActive) {
                orbitPhase += phaseStep
                if (orbitPhase >= 2.0 * PI) orbitPhase -= 2.0 * PI
                val mono = (l + r) * 0.5
                val shMono = orbitRearShelf.processLeft(mono)
                orbitDelayL[orbitWrite] = shMono
                orbitDelayR[orbitWrite] = shMono
                val sizeO = orbitDelayL.size
                val dL = orbitDelayL[(orbitWrite - orbitDelaySamplesL + sizeO) % sizeO]
                val dR = orbitDelayR[(orbitWrite - orbitDelaySamplesR + sizeO) % sizeO]
                orbitWrite = (orbitWrite + 1) % sizeO

                orbitLpL += orbitAlphaL * (dL - orbitLpL)
                orbitLpR += orbitAlphaR * (dR - orbitLpR)
                val wetL = orbitLpL * orbitGainL
                val wetR = orbitLpR * orbitGainR
                l = l * curOrbitDry + wetL * curOrbitWet
                r = r * curOrbitDry + wetR * curOrbitWet
            }

            if (roomActive) {
                val inputMix = (l + r) * 0.015
                var outL = 0.0
                var outR = 0.0
                for (i in 0 until 8) {
                    val bufL = combBufL[i]
                    val idxL = combIdxL[i]
                    val delL = bufL[idxL]
                    val fltL = delL * roomDamp2 + combFilterL[i] * roomDamp1
                    combFilterL[i] = fltL
                    bufL[idxL] = inputMix + fltL * roomFeedback
                    combIdxL[i] = if (idxL + 1 >= bufL.size) 0 else idxL + 1
                    outL += delL

                    val bufR = combBufR[i]
                    val idxR = combIdxR[i]
                    val delR = bufR[idxR]
                    val fltR = delR * roomDamp2 + combFilterR[i] * roomDamp1
                    combFilterR[i] = fltR
                    bufR[idxR] = inputMix + fltR * roomFeedback
                    combIdxR[i] = if (idxR + 1 >= bufR.size) 0 else idxR + 1
                    outR += delR
                }
                for (i in 0 until 4) {
                    val bufL = allpassBufL[i]
                    val idxL = allpassIdxL[i]
                    val bufOutL = bufL[idxL]
                    bufL[idxL] = outL + bufOutL * 0.5
                    outL = bufOutL - outL
                    allpassIdxL[i] = if (idxL + 1 >= bufL.size) 0 else idxL + 1

                    val bufR = allpassBufR[i]
                    val idxR = allpassIdxR[i]
                    val bufOutR = bufR[idxR]
                    bufR[idxR] = outR + bufOutR * 0.5
                    outR = bufOutR - outR
                    allpassIdxR[i] = if (idxR + 1 >= bufR.size) 0 else idxR + 1
                }
                l = l * curRoomDry + outL * curRoomWet
                r = r * curRoomDry + outR * curRoomWet
            }

            curBoost += (p.boostLin - curBoost) * kS
            l *= curBoost
            r *= curBoost

            val peak = max(abs(l), abs(r))
            if (limiterOn) {
                val target = if (peak > LIM_THR) LIM_THR / peak else 1.0
                limEnv += (target - limEnv) * (if (target < limEnv) limAtk else limRel)
                l *= limEnv
                r *= limEnv
                if (limEnv < minLimEnv) minLimEnv = limEnv
            } else if (peak >= 0.999) {
                blockClip = true
            }

            tapL[tw] = l.toFloat()
            tapR[tw] = r.toFloat()
            tw = (tw + 1) and (TAP_SIZE - 1)

            out.putShort(toShort(l))
            out.putShort(toShort(r))
        }
        tapWrite = tw
        limitingDb = if (limiterOn && minLimEnv < 0.9999) 20.0 * log10(max(1e-4, minLimEnv)) else 0.0
        clipping = blockClip
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}
