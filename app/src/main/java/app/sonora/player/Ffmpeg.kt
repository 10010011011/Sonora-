package app.sonora.player

import android.os.Handler
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.Renderer

/**
 * FFmpeg audio decoders (ALAC and other software fallbacks).
 *
 * Media3 does not publish this module on Maven, so Sonora builds it from the Media3 1.11.1
 * source (see lib-ffmpeg/README.md) and vendors the AAR at app/libs/. The app runs fine without
 * it: everything here is reflection, so when the library is absent these just return null/false.
 *
 * The renderer shares Sonora's DSP stage and is added AFTER the MediaCodec renderer, so hardware
 * decoding stays preferred (battery, behavior) and FFmpeg is the fallback that saves files the
 * device can't decode itself.
 */
object Ffmpeg {
    @Volatile private var cached: Boolean? = null

    /** True when the FFmpeg native library loaded and its decoders are usable. */
    fun isAvailable(): Boolean {
        cached?.let { return it }
        val ok = runCatching {
            Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
                .getMethod("isAvailable").invoke(null) as Boolean
        }.getOrDefault(false)
        cached = ok
        if (ok) Diag.log("ffmpeg", "software decoders available")
        return ok
    }

    /**
     * An FfmpegAudioRenderer running audio through [dsp], or null when the library is absent
     * (or built from a different Media3 version, in which case the constructor won't match).
     */
    fun createRenderer(dsp: SonoraDsp): Renderer? {
        if (!isAvailable()) return null
        return runCatching {
            val cls = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
            // Matched by shape, not names: R8 renames everything except the kept entry points.
            // This is (Handler, AudioRendererEventListener, AudioProcessor...); the (Handler,
            // Listener, AudioSink) sibling is excluded because its third parameter is not an array.
            val ctor = cls.constructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 3 && p[0] == Handler::class.java &&
                    p[2].isArray && p[2].componentType == AudioProcessor::class.java
            } ?: return null
            ctor.newInstance(null, null, arrayOf(dsp)) as Renderer
        }.getOrNull()
    }
}
