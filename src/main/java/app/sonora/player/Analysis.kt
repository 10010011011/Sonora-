package app.sonora.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Radix-2 FFT with a Hann window; returns linear magnitudes for the first n/2 bins. */
object Fft {
    private var tableN = 0
    private var cosT = FloatArray(0)
    private var sinT = FloatArray(0)
    private var win = FloatArray(0)

    @Synchronized
    fun magnitudes(input: FloatArray, n: Int, out: FloatArray) {
        if (tableN != n) {
            cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
            sinT = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
            win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }
            tableN = n
        }
        val re = FloatArray(n) { input[it] * win[it] }
        val im = FloatArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val t = re[i]; re[i] = re[j]; re[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (m in 0 until len / 2) {
                    val wr = cosT[k]; val wi = -sinT[k]
                    val a = i + m; val b = a + len / 2
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
        val scale = 2f / n
        for (i in 0 until min(out.size, n / 2)) out[i] = sqrt(re[i] * re[i] + im[i] * im[i]) * scale
    }
}

class Analysis(
    /** Normalization gain in dB that brings the track to about -18 LUFS, or null when it could not be measured. */
    val gainDb: Double?,
    /** Spectrogram columns (dB per FFT bin), or null when not requested. */
    val spec: Array<FloatArray>?,
    val sampleRate: Int,
    val seconds: Double,
    /** Waveform bar heights (0..1) across the whole track, or null when not requested. */
    val peaks: FloatArray? = null,
)

/** RMS per bar, scaled so the loudest bar is nearly full height and silence still shows a short bar. */
fun barHeights(sumSq: DoubleArray, count: IntArray): FloatArray {
    val rms = DoubleArray(sumSq.size) { if (count[it] > 0) sqrt(sumSq[it] / count[it]) else 0.0 }
    val top = rms.maxOrNull()?.takeIf { it > 1e-6 } ?: return FloatArray(sumSq.size) { 0.14f }
    return FloatArray(sumSq.size) { (0.14 + 0.82 * Math.pow(rms[it] / top, 0.8)).toFloat() }
}

object Analyzer {
    /** Decodes the first audio track to 16-bit PCM, block by block. `onPcm` returns false to stop early. */
    fun decode(ctx: Context, uri: Uri, maxSec: Double, onPcm: (pcm: ShortArray, count: Int, sampleRate: Int, channels: Int) -> Boolean) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return
            ex.selectTrack(ti)
            val format = ex.getTrackFormat(ti)
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            var sr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var chn = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            val maxUs = (maxSec * 1e6).toLong()
            var inDone = false
            var outDone = false
            var tmp = ShortArray(8192)
            var idle = 0
            while (!outDone && idle < 500) {
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val size = ex.readSampleData(c.getInputBuffer(ii)!!, 0)
                        if (size < 0 || ex.sampleTime > maxUs) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    idle = 0
                    val ob = c.getOutputBuffer(oi)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val count = info.size / 2
                    if (tmp.size < count) tmp = ShortArray(count)
                    ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(tmp, 0, count)
                    if (count > 0 && !onPcm(tmp, count, sr, chn)) outDone = true
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sr = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    chn = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else idle++
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }

    /**
     * One streaming pass over the file: gated loudness (for normalization when the file has no ReplayGain tag)
     * and, when `cols` > 0, a full-track spectrogram. Nothing but the results is kept in memory.
     */
    fun analyze(ctx: Context, t: Track, fftSize: Int, cols: Int, maxSec: Double, bars: Int = 0, cancelled: () -> Boolean): Analysis? {
        val dur = if (t.duration > 0) min(t.duration, maxSec) else maxSec
        val spec = if (cols > 0) ArrayList<FloatArray>(cols) else null
        val ring = FloatArray(fftSize)
        val frame = FloatArray(fftSize)
        var ringPos = 0
        var total = 0L
        var nextCol = 0.0
        var hop = 0.0
        var srOut = 44100
        val blocks = ArrayList<Double>()
        var blockSum = 0.0
        var blockN = 0
        var blockLen = 0
        val barSum = if (bars > 0 && t.duration > 0) DoubleArray(bars) else null
        val barN = IntArray(bars)
        var frames = 0L
        var framesTotal = 1.0
        try {
            decode(ctx, t.toUri(), maxSec) { pcm, count, sr, chn ->
                if (hop == 0.0) {
                    srOut = sr
                    hop = max(1.0, sr * dur / max(1, cols))
                    blockLen = (sr * 0.4).toInt()
                    framesTotal = max(1.0, sr * t.duration)
                }
                var i = 0
                while (i + chn <= count) {
                    var m = 0f
                    for (c in 0 until chn) m += pcm[i + c]
                    m /= (32768f * chn)
                    i += chn
                    blockSum += m * m
                    if (barSum != null) {
                        val b = (frames / framesTotal * bars).toInt().coerceIn(0, bars - 1)
                        barSum[b] += (m * m).toDouble()
                        barN[b]++
                    }
                    frames++
                    if (++blockN >= blockLen) {
                        blocks.add(blockSum / blockN)
                        blockSum = 0.0
                        blockN = 0
                    }
                    if (spec != null) {
                        ring[ringPos] = m
                        ringPos = (ringPos + 1) % fftSize
                        total++
                        if (total >= nextCol && total >= fftSize && spec.size < cols) {
                            for (k in 0 until fftSize) frame[k] = ring[(ringPos + k) % fftSize]
                            val mags = FloatArray(fftSize / 2)
                            Fft.magnitudes(frame, fftSize, mags)
                            for (k in mags.indices) mags[k] = (20 * log10(mags[k] + 1e-9f))
                            spec.add(mags)
                            nextCol = total + hop
                        }
                    }
                }
                !cancelled()
            }
        } catch (e: Exception) {
            Diag.log("analyze-failed", "${t.fileName}: ${e.message}")
            return null
        }
        if (cancelled()) return null
        // Simplified BS.1770 gating (no K-weighting): absolute gate -70 dB, relative gate 10 dB under the ungated mean.
        var gain: Double? = null
        val abs = blocks.filter { it > 1e-7 }
        if (abs.size >= 5) {
            val mean = abs.average()
            val kept = abs.filter { it > mean * 0.1 }
            if (kept.isNotEmpty()) gain = (-18.0 - (10 * log10(kept.average()) - 0.691)).coerceIn(-15.0, 12.0)
        }
        return Analysis(gain, spec?.toTypedArray(), srOut, frames.toDouble() / max(1, srOut), barSum?.let { barHeights(it, barN) })
    }
}
