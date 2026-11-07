// Sonora Native — TrackAnalyzer.kt
// Streaming MediaExtractor + MediaCodec background track analyzer + ITU-R BS.1770-4 two-stage K-weighted LUFS.

package app.sonora.player.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.sonora.player.data.TrackRecord
import app.sonora.player.library.LibraryRepo
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TrackAnalysisResult(
    val bars180: List<Float>,
    val bars96: List<Float>,
    val lufs: Double,
    val peakDb: Double,
    val rmsDb: Double,
    val crestDb: Double,
    val drDb: Double,
    val clipped: Int,
    val specCols: Int = 224,
    val specBins: Int = 96,
    val spec: ByteArray? = null,
)

object TrackAnalyzer {
    private val cache = ConcurrentHashMap<String, TrackAnalysisResult>()
    private val rgCache = ConcurrentHashMap<String, Double>()

    fun getCachedAnalysis(trackId: String?): TrackAnalysisResult? =
        if (trackId.isNullOrEmpty()) null else cache[trackId]

    fun getAnalyzedRgDb(trackId: String): Double? = rgCache[trackId]

    fun rgFor(track: TrackRecord, rgMode: String, rgPreamp: Double): Double {
        if (rgMode == "off") return 0.0
        val tagGain = when (rgMode) {
            "album" -> track.rgAlbum ?: track.rgTrack
            else -> track.rgTrack ?: track.rgAlbum
        }
        val base = tagGain ?: rgCache[track.id] ?: return 0.0
        return (base + rgPreamp).coerceIn(-18.0, 12.0)
    }

    /** Fast deterministic fallback or cached waveform signature for NowPlayingSeekBar (72 bars). */
    fun waveformSignatureForTrack(track: TrackRecord?, count: Int = 72): FloatArray {
        if (track == null) return FloatArray(count) { 0.25f }
        val cached = cache[track.id]
        if (cached != null && cached.bars96.isNotEmpty()) {
            val src = cached.bars96
            return FloatArray(count) { i ->
                src[(i * src.size) / count].coerceIn(0.08f, 1.0f)
            }
        }
        var seed = track.id.hashCode()
        if (seed == 0) seed = 0x5A17C3
        return FloatArray(count) { i ->
            seed = seed xor (seed shl 13)
            seed = seed xor (seed ushr 17)
            seed = seed xor (seed shl 5)
            val env = sin(PI * (i + 0.5) / count).toFloat()
            val rand = ((seed and 0x7FFFFFFF) % 1000) / 1000f
            (0.18f + (0.35f + 0.47f * rand) * env).coerceIn(0.12f, 0.98f)
        }
    }

    /**
     * Pure-Kotlin analysis on mono samples implementing ITU-R BS.1770-4 two-stage K-weighting:
     *  - Stage 1: High-shelf filter at 1681.97 Hz (+4.0 dB) modeling outer-ear head resonance
     *  - Stage 2: RLB High-pass filter at 38.13 Hz (Q = 0.5)
     *  - 400 ms gating blocks with -70 LKFS absolute gate and -10 LU relative gate.
     */
    fun computeFromMonoSamples(
        samples: FloatArray,
        sampleRate: Int,
        fftSize: Int = 1024,
        cols: Int = 224,
        spectro: Boolean = true,
    ): TrackAnalysisResult {
        if (samples.isEmpty()) {
            return TrackAnalysisResult(
                bars180 = List(180) { 0.1f },
                bars96 = List(96) { 0.1f },
                lufs = -18.0,
                peakDb = -60.0,
                rmsDb = -60.0,
                crestDb = 0.0,
                drDb = 0.0,
                clipped = 0,
                specCols = cols,
                specBins = 96,
                spec = null,
            )
        }

        val bars180 = computeBars(samples, 180)
        val bars96 = computeBars(samples, 96)

        val fs = sampleRate.toDouble().coerceAtLeast(4000.0)
        val kwStage1 = Biquad().apply { highShelf(fs, 1681.97.coerceAtMost(fs * 0.42), 4.0) }
        val kwStage2 = Biquad().apply { highPass(fs, 38.13, 0.5) }

        var peak = 1e-9
        var sumSq = 0.0
        var clipped = 0
        val blockLen = max(256, (sampleRate * 0.4).toInt())
        var blockSq = 0.0
        var blockCount = 0
        val blocks = ArrayList<Double>()

        for (s in samples) {
            val d = s.toDouble()
            val a = abs(d)
            if (a > peak) peak = a
            if (a >= 0.999) clipped++
            sumSq += d * d

            val kw = kwStage2.processLeft(kwStage1.processLeft(d))
            blockSq += kw * kw
            if (++blockCount >= blockLen) {
                blocks.add(blockSq / blockCount)
                blockSq = 0.0
                blockCount = 0
            }
        }
        if (blockCount > 0) blocks.add(blockSq / blockCount)

        val meanSq = (sumSq / samples.size).coerceAtLeast(1e-12)
        val absGate = 10.0.pow((-70.0 + 0.691) / 10.0)
        val g1 = blocks.filter { it > absGate }
        val m1 = if (g1.isNotEmpty()) g1.average() else meanSq
        val g2 = g1.filter { it > m1 * 0.1 }
        val gatedMean = if (g2.isNotEmpty()) g2.average() else m1
        val lufs = (-0.691 + 10.0 * log10(gatedMean.coerceAtLeast(1e-12))).coerceIn(-60.0, 0.0)

        val rmsDb = 20.0 * log10(sqrt(meanSq))
        val peakDb = 20.0 * log10(peak)
        val crestDb = max(0.0, peakDb - rmsDb)

        val sorted = g1.sorted()
        val p95 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)] else gatedMean
        val p10 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.10).toInt()] else gatedMean * 0.1
        val drDb = (10.0 * log10((p95 / p10.coerceAtLeast(1e-12)).coerceAtLeast(1.0))).coerceIn(0.0, 35.0)

        var specBytes: ByteArray? = null
        val bins = 96
        if (spectro) {
            val n = if (fftSize.countOneBits() == 1) fftSize else 512
            val half = n / 2
            val hann = FloatArray(n) { i -> (0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))).toFloat() }
            val re = FloatArray(n)
            val im = FloatArray(n)
            specBytes = ByteArray(cols * bins)
            for (c in 0 until cols) {
                val start = ((c.toLong() * max(0, samples.size - n)) / max(1, cols - 1)).toInt()
                for (k in 0 until n) {
                    val s = if (start + k < samples.size) samples[start + k] else 0f
                    re[k] = s * hann[k]
                    im[k] = 0f
                }
                fftRadix2(re, im)
                for (b in 0 until bins) {
                    val frac = b.toDouble() / (bins - 1).toDouble()
                    val kIdx = (1.0 * (half - 1).toDouble().pow(frac)).toInt().coerceIn(1, half - 1)
                    val mag = (sqrt(re[kIdx] * re[kIdx] + im[kIdx] * im[kIdx]) / half).coerceAtLeast(1e-7f)
                    val db = 20f * log10(mag)
                    val norm = ((db + 80f) / 76f).coerceIn(0f, 1f)
                    specBytes[c * bins + b] = (norm * 255f).toInt().coerceIn(0, 255).toByte()
                }
            }
        }

        return TrackAnalysisResult(
            bars180 = bars180,
            bars96 = bars96,
            lufs = lufs,
            peakDb = peakDb,
            rmsDb = rmsDb,
            crestDb = crestDb,
            drDb = drDb,
            clipped = clipped,
            specCols = cols,
            specBins = bins,
            spec = specBytes,
        )
    }

    private fun computeBars(samples: FloatArray, count: Int): List<Float> {
        val out = FloatArray(count)
        var maxV = 1e-6f
        for (i in 0 until count) {
            val s0 = (i.toLong() * samples.size / count).toInt().coerceIn(0, samples.lastIndex)
            val s1 = max(s0 + 1, ((i + 1L) * samples.size / count).toInt().coerceAtMost(samples.size))
            var sumSq = 0.0
            var pk = 0f
            for (j in s0 until s1) {
                val v = abs(samples[j])
                sumSq += v * v
                if (v > pk) pk = v
            }
            val rms = sqrt(sumSq / (s1 - s0)).toFloat()
            val combined = 0.65f * rms + 0.35f * pk
            out[i] = combined
            if (combined > maxV) maxV = combined
        }
        return out.map { (it / maxV).coerceIn(0.06f, 1.0f) }
    }

    suspend fun analyze(
        context: Context,
        repo: LibraryRepo,
        track: TrackRecord,
        fftSize: Int = 1024,
        barsCount: Int = 180,
        spectro: Boolean = true,
        loudnessOnly: Boolean = false,
    ): TrackAnalysisResult? = withContext(Dispatchers.Default) {
        cache[track.id]?.let {
            if (!spectro || it.spec != null) return@withContext it
        }
        val uri = repo.resolveTrackUri(track) ?: return@withContext null
        val decoded = runCatching { decodeMonoDownsampled(context, uri) }.getOrNull() ?: return@withContext null
        val res = computeFromMonoSamples(
            samples = decoded.first,
            sampleRate = decoded.second,
            fftSize = fftSize,
            cols = 224,
            spectro = spectro && !loudnessOnly,
        )
        cache[track.id] = res
        val rgDb = (-16.0 - res.lufs).coerceIn(-15.0, 12.0)
        rgCache[track.id] = rgDb
        res
    }

    private fun decodeMonoDownsampled(context: Context, uri: android.net.Uri): Pair<FloatArray, Int>? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    trackIdx = i
                    format = f
                    break
                }
            }
            if (trackIdx < 0 || format == null) return null
            extractor.selectTrack(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sr = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val ch = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) else 2

            val decimate = 4
            val maxOutSamples = (sr / decimate) * 600
            val out = FloatArray(maxOutSamples)
            var outLen = 0

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inEos = false
                var outEos = false
                var frameCounter = 0

                while (!outEos && outLen < maxOutSamples) {
                    if (!inEos) {
                        val inIdx = codec.dequeueInputBuffer(5000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val sz = extractor.readSampleData(buf, 0)
                            if (sz < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inEos = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 5000)
                    if (outIdx >= 0) {
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
                        val ob = codec.getOutputBuffer(outIdx)
                        if (ob != null && info.size > 0) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val frames = sb.remaining() / ch
                            for (f in 0 until frames) {
                                var mono = 0f
                                for (c in 0 until ch) mono += sb.get() / 32768f
                                mono /= ch
                                if (frameCounter++ % decimate == 0 && outLen < maxOutSamples) {
                                    out[outLen++] = mono
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            if (outLen == 0) return null
            return out.copyOf(outLen) to max(4000, sr / decimate)
        } finally {
            runCatching { extractor.release() }
        }
    }

    fun fftRadix2(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val ang = -2.0 * PI / len
            val wlenRe = cos(ang).toFloat()
            val wlenIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val vIm = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val nextWRe = wRe * wlenRe - wIm * wlenIm
                    wIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
