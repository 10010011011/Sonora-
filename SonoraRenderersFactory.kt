package app.sonora.player.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Hooks the Sonora DSP into ExoPlayer and enables the FFmpeg extension renderer (if the -PwithFfmpeg module is present)
 * as a FALLBACK behind the platform MediaCodec decoders.
 * One factory instance per ExoPlayer: a processor holds per-stream state and must never be shared between players.
 */
@OptIn(UnstableApi::class)
class SonoraRenderersFactory(context: Context, private val holder: DspParamsHolder) : DefaultRenderersFactory(context) {

    init {
        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val processor = SonoraAudioProcessor(holder)
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(processor))
            .build()
    }
}
