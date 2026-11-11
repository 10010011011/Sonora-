package app.sonora.player

import androidx.media3.common.PlaybackException

/** Extensions the importer accepts as playable audio. */
val PLAYABLE_EXTENSIONS = setOf(
    "mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "mka", "amr", "3gp", "mp4", "webm",
)

/**
 * Known-audio extensions Sonora cannot play (no Android container or decoder support, even with FFmpeg,
 * because Media3 has no extractor for these containers). They get a clear "not supported yet" message
 * at import and at play, never a cryptic failure.
 */
val UNSUPPORTED_EXTENSIONS = mapOf(
    "wma" to "WMA",
    "aiff" to "AIFF", "aif" to "AIFF", "aifc" to "AIFF",
    "ape" to "APE (Monkey's Audio)",
    "wv" to "WavPack",
    "dsf" to "DSD", "dff" to "DSD", "dsd" to "DSD",
    "mpc" to "Musepack", "mpp" to "Musepack",
    "tak" to "TAK", "tta" to "TTA",
    "mid" to "MIDI", "midi" to "MIDI", "kar" to "MIDI",
    "mod" to "tracker music", "xm" to "tracker music", "it" to "tracker music", "s3m" to "tracker music",
)

fun extOf(fileName: String) = fileName.substringAfterLast('.', "").lowercase()

sealed interface Support {
    data object Playable : Support
    data class Unsupported(val label: String) : Support
    /** Not on either list: try it; a genuinely unplayable file fails gracefully with its extension shown. */
    data object Unknown : Support
}

fun supportOf(fileName: String): Support {
    val ext = extOf(fileName)
    if (ext.isEmpty()) return Support.Unknown
    if (ext in PLAYABLE_EXTENSIONS) return Support.Playable
    val label = UNSUPPORTED_EXTENSIONS[ext]
    return if (label != null) Support.Unsupported(label) else Support.Unknown
}

/**
 * Container MIME hint for a media item. Only set when certain; otherwise null and ExoPlayer sniffs.
 * (A wrong hint breaks playback; no hint never does.)
 */
fun mimeForFile(fileName: String, stored: String): String? = when (extOf(fileName)) {
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "m4a", "m4b" -> "audio/mp4"
    "aac" -> "audio/aac"
    "ogg", "oga" -> "audio/ogg"
    "opus" -> "audio/opus"
    "wav" -> "audio/wav"
    else -> stored.takeIf { it.startsWith("audio/") }
}

/* ------------------------------------------------------------------ playback failures */

/** The five things that can go wrong, each with its own message. Nothing ever skips silently. */
enum class PlaybackFailure { MISSING, UNSUPPORTED, DAMAGED, READ, OUTPUT }

/** Maps a Media3 error code (stable values since 1.0) to one of the five. Unknown codes are read failures. */
fun classifyFailure(code: Int): PlaybackFailure = when (code) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackFailure.MISSING
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> PlaybackFailure.UNSUPPORTED
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_DECODING_FAILED -> PlaybackFailure.DAMAGED
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED -> PlaybackFailure.OUTPUT
    else -> if (code in 6000..6999) PlaybackFailure.UNSUPPORTED else PlaybackFailure.READ
}

/** Only these are worth retrying once; a missing file or an unsupported format fails the same way twice. */
fun failureRetryable(kind: PlaybackFailure) = kind == PlaybackFailure.READ || kind == PlaybackFailure.DAMAGED || kind == PlaybackFailure.OUTPUT

/** Short user message. `ext` is the upper-case extension without dot, or "" when the file has none. */
fun failureMessage(kind: PlaybackFailure, title: String, ext: String): String {
    val t = "“${title.ifEmpty { "this song" }}”"
    val e = if (ext.isNotEmpty()) " ($ext)" else ""
    return when (kind) {
        PlaybackFailure.MISSING -> "$t couldn't be found. It may have been moved or deleted$e."
        PlaybackFailure.UNSUPPORTED -> "$t uses a format this device can't play$e."
        PlaybackFailure.DAMAGED -> "$t looks damaged, so it couldn't be played$e."
        PlaybackFailure.READ -> "$t couldn't be read$e."
        PlaybackFailure.OUTPUT -> "The audio output had a problem playing $t. Check your headset or speaker."
    }
}

fun unsupportedMessage(title: String, label: String) = "“${title.ifEmpty { "This file" }}” is $label, which Sonora doesn't support yet."
