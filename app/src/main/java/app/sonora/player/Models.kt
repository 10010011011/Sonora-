package app.sonora.player

import kotlinx.serialization.Serializable

/*
 * Field names mirror the web/Capacitor Sonora (store.ts / userdata.ts) on purpose:
 * a backup exported from Sonora 1.2.x restores straight into this native app.
 */

@Serializable data class LyricWord(val t: Double = 0.0, val text: String = "")
@Serializable data class LyricLine(val t: Double? = null, val text: String = "", val words: List<LyricWord>? = null)
@Serializable data class Lyrics(val lines: List<LyricLine> = emptyList(), val offset: Double = 0.0, val source: String? = null)

@Serializable
data class Track(
    val id: String,
    /** Absolute file path (Sonora's own copy) or a content:// uri (song left in a granted folder). */
    val uri: String = "",
    val fileName: String = "",
    val size: Long = 0,
    val mime: String = "",
    val lastModified: Long = 0,
    val path: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Double = 0.0,
    val cover: String? = null,
    val thumb: String? = null,
    val rgTrack: Double? = null,
    val rgAlbum: Double? = null,
    val embeddedLyrics: String? = null,
    /** Path or content uri of a .lrc/.srt/.vtt that lives next to the song. */
    val sidecar: String? = null,
    val addedAt: Long = 0,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0,
    /** "size:sha1(first 64 KB + last 64 KB)": how a renamed file is recognised. */
    val fp: String? = null,
    val storage: String = "copy",
    val treeUri: String? = null,
    val edited: Boolean = false,
    /** True once loudness analysis ran (so it is not repeated). */
    val analyzed: Boolean = false,
)

@Serializable
data class SoundProfile(
    val id: String, val name: String, val eq: List<Double> = List(10) { 0.0 }, val preampDb: Double = 0.0,
    val bassDb: Double = 0.0, val midsDb: Double = 0.0, val trebleDb: Double = 0.0, val width: Double = 1.0, val crossfeed: Double = 0.0,
)

@Serializable
data class Settings(
    val theme: String = "dark",
    val themeIcon: Boolean = false,
    val volume: Double = 1.0,
    val muted: Boolean = false,
    val boostDb: Double = 0.0,
    val limiter: Boolean = true,
    val eqOn: Boolean = true,
    val eq: List<Double> = List(10) { 0.0 },
    val preampDb: Double = 0.0,
    val autoPreamp: Boolean = true,
    val bassDb: Double = 0.0,
    val midsDb: Double = 0.0,
    val trebleDb: Double = 0.0,
    val stereoMode: String = "normal",
    val width: Double = 1.0,
    val crossfeed: Double = 0.0,
    val orbit: Boolean = false,
    val orbitSpeed: Double = 0.12,
    val room: Double = 0.0,
    val rgMode: String = "track",
    val rgPreamp: Double = 0.0,
    val rgAnalyze: Boolean = true,
    val gapless: Boolean = true,
    val crossfade: Double = 0.0,
    val playPauseFadeMs: Double = 220.0,
    val pauseOnVolume0: Boolean = true,
    val showRemainingTime: Boolean = false,
    val waveformSeekbar: Boolean = true,
    val seekStepSec: Double = 5.0,
    val repeatCountTarget: Int = 1,
    val speed: Double = 1.0,
    val keepPitch: Boolean = true,
    val tapLatency: Double = 100.0,
    val profiles: List<SoundProfile> = emptyList(),
    val deviceMap: Map<String, String> = emptyMap(),
    val autoResume: Boolean = true,
    val pauseOnDisconnect: Boolean = true,
    val colormap: String = "miku",
    val specLog: Boolean = false,
    val fftSize: Int = 2048,
    val shuffle: Boolean = false,
    val repeat: String = "all",
    val likedIds: List<String> = emptyList(),
    val uiMode: String = "pro",
    val resumeMinSec: Double = 600.0,
    val librarySort: String = "title",
)

@Serializable data class Playlist(val id: String, val name: String = "Playlist", val trackIds: List<String> = emptyList(), val createdAt: Long = 0, val updatedAt: Long = 0)
@Serializable data class Bookmark(val id: String, val trackId: String, val t: Double = 0.0, val label: String = "", val createdAt: Long = 0)
@Serializable data class TrackAudioConfig(val speed: Double? = null, val keepPitch: Boolean? = null, val bassDb: Double? = null, val midsDb: Double? = null, val trebleDb: Double? = null, val boostDb: Double? = null)
@Serializable data class ResumeLast(val trackId: String, val ms: Long = 0)
@Serializable data class ResumeState(val last: ResumeLast? = null, val byTrack: Map<String, Long> = emptyMap())

@Serializable
data class UserData(
    val version: Int = 1,
    val playlists: List<Playlist> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val resume: ResumeState = ResumeState(),
    val trackConfigs: Map<String, TrackAudioConfig> = emptyMap(),
    val libraryOrder: List<String> = emptyList(),
)

@Serializable data class FolderInfo(val uri: String, val name: String = "", val lastScanAt: Long = 0)

/** state.json: same shape the Capacitor build wrote ({version, settings, lyrics, queueIds, currentTrackId}). */
@Serializable
data class StateFile(
    val version: Int = 2,
    val settings: Settings = Settings(),
    val lyrics: Map<String, Lyrics> = emptyMap(),
    val queueIds: List<String> = emptyList(),
    val currentTrackId: String? = null,
    val folders: List<FolderInfo> = emptyList(),
)

@Serializable
data class BackupTrack(
    val id: String, val fp: String? = null, val fileName: String = "", val size: Long = 0, val title: String = "", val artist: String = "",
    val album: String = "", val addedAt: Long = 0, val playCount: Int = 0, val lastPlayedAt: Long = 0, val edited: Boolean = false,
)

/** The play queue: track ids in play order. */
data class QueueState(
    val queue: List<String> = emptyList(),
    /** The order before shuffling (null = as queued). Used to un-shuffle. */
    val orderBase: List<String>? = null,
    val qpos: Int = -1,
    /** Offset from qpos for consecutive "Play next" insertions so several queued songs stay in order. */
    val insertCursor: Int = 0,
)

data class PlayerState(
    val playing: Boolean = false,
    val duration: Double = 0.0,
    val trackId: String? = null,
    val abA: Double? = null,
    val abB: Double? = null,
    val sleepAt: Long? = null,
    val sleepEndOfTrack: Boolean = false,
    val sleepTracksRemaining: Int = 0,
    val repeatRemaining: Int = 0,
    val rgApplied: Double = 0.0,
    val outputLabel: String = "",
)

/** Limiter gain reduction (dB) and clipping. Kept apart from PlayerState: it changes many times a second while music plays. */
data class Meter(val limiting: Double = 0.0, val clipping: Boolean = false)

data class ImportFail(val name: String, val reason: String, val uri: String? = null)
data class ImportState(
    val phase: String = "idle", val kind: String = "", val done: Int = 0, val total: Int = 0,
    val name: String = "", val summary: String = "", val failed: List<ImportFail> = emptyList(),
)

data class OutDev(val id: String, val label: String, val key: String, val headset: Boolean)

data class Group(
    val kind: String, val key: String, val title: String, val sub: String, val cover: String?,
    val tracks: List<Track>, val newest: Long, val plays: Int, val playlistId: String? = null,
)

fun uid(): String = java.util.UUID.randomUUID().toString()
