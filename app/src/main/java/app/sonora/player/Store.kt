package app.sonora.player

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToLong

fun Track.toUri(): Uri = if (uri.startsWith("content:") || uri.startsWith("file:")) Uri.parse(uri) else Uri.fromFile(File(uri))

/** Small in-memory event log for Settings > Diagnostics > Copy report. */
object Diag {
    private val lines = ArrayDeque<String>()
    private val t0 = System.currentTimeMillis()

    @Synchronized
    fun log(tag: String, msg: String = "") {
        lines.addLast("+${(System.currentTimeMillis() - t0) / 1000.0}s $tag $msg")
        while (lines.size > 400) lines.removeFirst()
    }

    @Synchronized
    fun report(): String = buildString {
        appendLine("Sonora native ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("tracks=${S.tracks.value.size} queue=${S.queue.value.queue.size} qpos=${S.queue.value.qpos} playing=${S.player.value.playing}")
        val s = S.settings.value
        appendLine("theme=${s.theme} gapless=${s.gapless} crossfade=${s.crossfade} rg=${s.rgMode} speed=${s.speed} limiter=${s.limiter}")
        appendLine("--- events ---")
        lines.forEach { appendLine(it) }
    }
}

@OptIn(FlowPreview::class)
object S {
    lateinit var app: Context
    val json = SonoraJson
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Saves have their own thread so a long import can never delay playlist/settings writes. */
    private val saver = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    val settings = MutableStateFlow(Settings())
    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val user = MutableStateFlow(UserData())
    val lyrics = MutableStateFlow<Map<String, Lyrics>>(emptyMap())
    val queue = MutableStateFlow(QueueState())
    val player = MutableStateFlow(PlayerState())
    val clock = MutableStateFlow(0.0)
    val toastMsg = MutableStateFlow("")
    val imp = MutableStateFlow(ImportState())
    val folders = MutableStateFlow<List<FolderInfo>>(emptyList())
    val filter = MutableStateFlow("all")
    val loaded = MutableStateFlow(false)
    val outs = MutableStateFlow<List<OutDev>>(emptyList())
    val meter = MutableStateFlow(Meter())

    /** Measured waveform of the current song (track id to 72 bar heights), once it is known. */
    val wave = MutableStateFlow<Pair<String, FloatArray>?>(null)

    val dir: File get() = File(app.filesDir, "sonora-native").apply { mkdirs() }
    val mediaDir: File get() = File(dir, "media").apply { mkdirs() }
    val coverDir: File get() = File(dir, "covers").apply { mkdirs() }
    val lyricDir: File get() = File(dir, "lyrics").apply { mkdirs() }
    val waveDir: File get() = File(dir, "waves").apply { mkdirs() }

    private var idxSrc: List<Track>? = null
    private var idx: Map<String, Track> = emptyMap()
    private var toastJob: Job? = null

    fun track(id: String?): Track? {
        if (id == null) return null
        val t = tracks.value
        if (idxSrc !== t) {
            idx = t.associateBy { it.id }
            idxSrc = t
        }
        return idx[id]
    }

    fun current(): Track? = track(player.value.trackId) ?: queue.value.let { track(it.queue.getOrNull(it.qpos)) } ?: tracks.value.firstOrNull()

    fun toast(msg: String) {
        toastMsg.value = msg
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(2800)
            toastMsg.value = ""
        }
    }

    fun set(f: (Settings) -> Settings) = settings.update(f)

    /* ------------------------------------------------------------------ persistence */

    private fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        val dst = File(dir, name)
        if (!tmp.renameTo(dst)) {
            dst.writeText(text)
            tmp.delete()
        }
    }

    private val trackList = ListSerializer(Track.serializer())

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        scope.launch { load() }
    }

    private suspend fun load() {
        var migrated = 0
        val (t, st, u) = withContext(Dispatchers.IO) {
            var t = Legacy.readJson(File(dir, "library.json"), trackList)
            var st = Legacy.readJson(File(dir, "state.json"), StateFile.serializer())
            var u = Legacy.readJson(File(dir, "userdata.json"), UserData.serializer())
            if (t == null) {
                // First launch after updating from the Capacitor build: adopt its library, playlists and settings in place.
                val legacy = File(app.filesDir, "sonora")
                val lt = Legacy.readTracks(legacy)
                if (lt.isNotEmpty()) {
                    t = lt
                    migrated = lt.size
                    if (st == null) st = Legacy.readJson(File(legacy, "state.json"), StateFile.serializer())
                    if (u == null) u = Legacy.readJson(File(legacy, "userdata.json"), UserData.serializer())
                    // Folders that were indexed in place keep their entry in Settings > Library.
                    val trees = lt.mapNotNull { it.treeUri }.distinct()
                    if (st != null && st.folders.isEmpty() && trees.isNotEmpty()) {
                        st = st.copy(folders = trees.map { uri -> FolderInfo(uri, Uri.decode(uri).substringAfterLast(':').substringAfterLast('/').ifEmpty { "Folder" }, 0) })
                    }
                }
            }
            Triple(t ?: emptyList(), st ?: StateFile(), u ?: UserData())
        }
        // Anything imported while the saved copy was loading is newer: merge, never overwrite.
        val merged = LinkedHashMap<String, Track>()
        t.forEach { merged[it.id] = it }
        tracks.value.forEach { merged[it.id] = it }
        tracks.value = merged.values.toList()
        settings.value = st.settings
        lyrics.value = st.lyrics + lyrics.value
        folders.value = st.folders
        user.value = mergeUserData(user.value, u)
        val ids = merged.keys.toList()
        val q = st.queueIds.filter { merged.containsKey(it) }.distinct()
        val used = q.toHashSet()
        val full = q + ids.filter { it !in used }
        val cur = st.currentTrackId ?: u.resume.last?.trackId
        queue.value = QueueState(full, null, if (full.isEmpty()) -1 else max(0, full.indexOf(cur)), 0)
        loaded.value = true
        Diag.log("loaded", "tracks=${ids.size} migrated=$migrated")
        scope.launch { tracks.drop(1).debounce(700).collect { v -> withContext(saver) { runCatching { write("library.json", json.encodeToString(trackList, v)) } } } }
        scope.launch {
            combine(settings, lyrics, queue, folders) { a, b, c, d -> StateFile(2, a, b, c.queue, c.queue.getOrNull(c.qpos), d) }
                .drop(1).debounce(450).collect { v -> withContext(saver) { runCatching { write("state.json", json.encodeToString(StateFile.serializer(), v)) } } }
        }
        scope.launch { user.drop(1).debounce(400).collect { v -> withContext(saver) { runCatching { write("userdata.json", json.encodeToString(UserData.serializer(), v)) } } } }
        if (migrated > 0) {
            flush()
            toast("Moved ${plural(migrated, "song")} from the previous version")
        }
    }

    /** Writes everything now (app going to the background). */
    fun flush() {
        if (!loaded.value) return
        val t = tracks.value
        val q = queue.value
        val st = StateFile(2, settings.value, lyrics.value, q.queue, q.queue.getOrNull(q.qpos), folders.value)
        val u = user.value
        scope.launch(saver) {
            runCatching {
                write("library.json", json.encodeToString(trackList, t))
                write("state.json", json.encodeToString(StateFile.serializer(), st))
                write("userdata.json", json.encodeToString(UserData.serializer(), u))
            }.onFailure { Diag.log("save-failed", it.message ?: "") }
        }
    }

    /* ------------------------------------------------------------------ library edits */

    fun patchTrack(id: String, f: (Track) -> Track) = tracks.update { list -> list.map { if (it.id == id) f(it) else it } }

    fun toggleLike(id: String): Boolean {
        val liked = settings.value.likedIds.contains(id)
        set { it.copy(likedIds = if (liked) it.likedIds - id else it.likedIds + id) }
        return !liked
    }

    fun saveLyrics(id: String, l: Lyrics) = lyrics.update { it + (id to l) }

    /** Title/artist/album edits live in Sonora's index only; the audio file is never changed. */
    fun editTags(id: String, title: String, artist: String, album: String) = patchTrack(id) {
        it.copy(title = title.trim(), artist = artist.trim().ifEmpty { "Unknown artist" }, album = album.trim().ifEmpty { "Local audio" }, edited = true)
    }

    /** Removes tracks from the index; deletes Sonora's own copies (files in the user's folders are never touched). */
    fun removeTracks(ids: Set<String>) {
        if (ids.isEmpty()) return
        val gone = tracks.value.filter { it.id in ids }
        tracks.update { list -> list.filter { it.id !in ids } }
        queue.update { QueueOps.without(it, ids) }
        set { it.copy(likedIds = it.likedIds.filter { id -> id !in ids }) }
        val own = dir.absolutePath
        scope.launch(Dispatchers.IO) {
            for (t in gone) runCatching {
                if (t.storage != "tree" && t.uri.startsWith(own)) File(t.uri).delete()
                listOfNotNull(t.cover, t.thumb, t.sidecar).filter { it.startsWith(own) }.forEach { File(it).delete() }
                File(waveDir, t.id).delete()
            }
        }
    }

    suspend fun loadLyricsFor(t: Track) {
        if (lyrics.value.containsKey(t.id)) return
        val text = withContext(Dispatchers.IO) {
            runCatching {
                val sc = t.sidecar
                when {
                    sc == null -> null
                    sc.startsWith("content:") -> app.contentResolver.openInputStream(Uri.parse(sc))?.use { it.readBytes().toString(Charsets.UTF_8) }
                    else -> File(sc).readText()
                }
            }.getOrNull()
        }
        val parsed = when {
            !text.isNullOrBlank() -> LyricsParser.parse(text, t.sidecar?.substringAfterLast('/') ?: "sidecar")
            !t.embeddedLyrics.isNullOrBlank() -> LyricsParser.parse(t.embeddedLyrics, "embedded")
            else -> null
        }
        if (parsed != null && parsed.lines.isNotEmpty() && !lyrics.value.containsKey(t.id)) saveLyrics(t.id, parsed)
    }

    /* ------------------------------------------------------------------ playlists, bookmarks, per-track sound */

    fun createPlaylist(name: String, ids: List<String> = emptyList()): String {
        val id = uid()
        val now = System.currentTimeMillis()
        user.update { it.copy(playlists = it.playlists + Playlist(id, name.trim().ifEmpty { "New playlist" }, ids.distinct(), now, now)) }
        return id
    }

    private fun editPlaylist(id: String, f: (Playlist) -> Playlist) =
        user.update { u -> u.copy(playlists = u.playlists.map { if (it.id == id) f(it).copy(updatedAt = System.currentTimeMillis()) else it }) }

    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name.trim().ifEmpty { "Playlist" }) }
    fun deletePlaylist(id: String) = user.update { u -> u.copy(playlists = u.playlists.filter { it.id != id }) }

    /** Adds tracks that are not already in the playlist. Returns how many were added. */
    fun addToPlaylist(id: String, ids: List<String>): Int {
        var added = 0
        editPlaylist(id) { p ->
            val have = p.trackIds.toHashSet()
            val fresh = ids.filter { have.add(it) }
            added = fresh.size
            p.copy(trackIds = p.trackIds + fresh)
        }
        return added
    }

    fun removeFromPlaylist(id: String, trackId: String) = editPlaylist(id) { it.copy(trackIds = it.trackIds - trackId) }

    fun movePlaylistTrack(id: String, from: Int, to: Int) = editPlaylist(id) { p ->
        if (from !in p.trackIds.indices || to !in p.trackIds.indices) p
        else p.copy(trackIds = p.trackIds.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun importM3U(text: String, fallbackName: String): Pair<String, Int> {
        val (name, ids) = matchM3U(text, fallbackName, tracks.value)
        createPlaylist(name, ids)
        return name to ids.size
    }

    fun addBookmark(trackId: String, t: Double, label: String = "") =
        user.update { it.copy(bookmarks = it.bookmarks + Bookmark(uid(), trackId, max(0.0, t), label.trim(), System.currentTimeMillis())) }

    fun removeBookmark(id: String) = user.update { u -> u.copy(bookmarks = u.bookmarks.filter { it.id != id }) }
    fun renameBookmark(id: String, label: String) = user.update { u -> u.copy(bookmarks = u.bookmarks.map { if (it.id == id) it.copy(label = label.trim()) else it }) }

    fun saveTrackConfig(trackId: String, cfg: TrackAudioConfig?) =
        user.update { it.copy(trackConfigs = if (cfg == null) it.trackConfigs - trackId else it.trackConfigs + (trackId to cfg)) }

    /* ------------------------------------------------------------------ resume positions */

    private const val END_GUARD_SEC = 10.0

    fun saveResume(trackId: String, seconds: Double, durationSec: Double) {
        val finished = durationSec > 0 && seconds >= durationSec - END_GUARD_SEC
        val ms = if (finished || seconds < 5) 0L else (seconds * 1000).roundToLong()
        val r = user.value.resume
        val long = durationSec >= settings.value.resumeMinSec
        val byTrack = if (long && ms > 0) r.byTrack + (trackId to ms) else r.byTrack - trackId
        if (r.last?.trackId == trackId && r.last.ms == ms && byTrack[trackId] == r.byTrack[trackId]) return
        user.update { it.copy(resume = ResumeState(ResumeLast(trackId, ms), byTrack)) }
    }

    /** Seconds to start a track at. `prime` = restoring the last song at launch (always resumes, paused). */
    fun startPositionFor(t: Track, prime: Boolean): Double {
        val r = user.value.resume
        val ms = if (prime && r.last?.trackId == t.id) r.last.ms else if (t.duration >= settings.value.resumeMinSec) r.byTrack[t.id] ?: 0L else 0L
        val sec = ms / 1000.0
        return if (t.duration > 0 && sec >= t.duration - END_GUARD_SEC) 0.0 else sec
    }

    /* ------------------------------------------------------------------ backup (metadata only, no audio) */

    fun buildBackup(): String = Backup.build(tracks.value, settings.value, user.value, lyrics.value, System.currentTimeMillis())

    /** Restores a backup from this app or from Sonora 1.2.x. Throws IllegalArgumentException when the file is not a backup. */
    fun restoreBackup(text: String): RestoreSummary {
        val r = Backup.restore(text, tracks.value, settings.value, user.value, lyrics.value)
        tracks.value = r.tracks
        settings.value = r.settings
        user.value = r.user
        lyrics.value = r.lyrics
        return r.summary
    }
}
