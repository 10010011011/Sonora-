// Sonora Native — LibraryRepo.kt
// Persistent JSON storage for library.json, userdata.json, trees.json, cover cache, and backups.

package app.sonora.player.library

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.sonora.player.data.*
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlinx.serialization.builtins.ListSerializer

object UserDataLogic {
    fun normalizeUserData(ud: UserData): UserData {
        val cleanPlaylists = ud.playlists.map { pl ->
            pl.copy(trackIds = pl.trackIds.filter { it.isNotEmpty() }.distinct())
        }
        val cleanBookmarks = ud.bookmarks
            .filter { it.id.isNotEmpty() && it.trackId.isNotEmpty() && it.t.isFinite() && it.t >= 0.0 }
            .sortedBy { it.t }
        return ud.copy(
            playlists = cleanPlaylists,
            bookmarks = cleanBookmarks,
        )
    }

    fun remapUserData(ud: UserData, idMap: Map<String, String>): UserData {
        if (idMap.isEmpty()) return normalizeUserData(ud)
        fun mapId(id: String): String = idMap[id] ?: id
        return normalizeUserData(
            ud.copy(
                playlists = ud.playlists.map { pl ->
                    pl.copy(trackIds = pl.trackIds.map { mapId(it) }.distinct())
                },
                bookmarks = ud.bookmarks.map { bm ->
                    bm.copy(trackId = mapId(bm.trackId))
                },
                resume = ResumeState(
                    last = ud.resume.last?.let { it.copy(trackId = mapId(it.trackId)) },
                    byTrack = ud.resume.byTrack.entries.associate { (k, v) -> mapId(k) to v },
                ),
                trackConfigs = ud.trackConfigs.entries.associate { (k, v) -> mapId(k) to v },
                libraryOrder = ud.libraryOrder.map { mapId(it) }.distinct(),
            )
        )
    }

    fun mergeUserData(local: UserData, incoming: UserData): UserData {
        val plById = LinkedHashMap<String, Playlist>()
        for (p in local.playlists) plById[p.id] = p
        for (p in incoming.playlists) {
            val prev = plById[p.id]
            plById[p.id] = if (prev == null) p else prev.copy(trackIds = (prev.trackIds + p.trackIds).distinct())
        }
        val bmById = LinkedHashMap<String, Bookmark>()
        for (b in local.bookmarks) bmById[b.id] = b
        for (b in incoming.bookmarks) bmById[b.id] = b

        return normalizeUserData(
            local.copy(
                playlists = plById.values.toList(),
                bookmarks = bmById.values.toList(),
                resume = incoming.resume.let { incRes ->
                    ResumeState(
                        last = incRes.last ?: local.resume.last,
                        byTrack = local.resume.byTrack + incRes.byTrack,
                    )
                },
                trackConfigs = local.trackConfigs + incoming.trackConfigs,
                libraryOrder = (local.libraryOrder + incoming.libraryOrder).distinct(),
            )
        )
    }

    fun computeSaveResume(
        ud: UserData,
        trackId: String,
        posSec: Double,
        durSec: Double,
        resumeMinSec: Double = 600.0,
    ): UserData {
        if (trackId.isEmpty() || !posSec.isFinite()) return ud
        val posMs = (max(0.0, posSec) * 1000.0).toLong()
        val nextLast = ResumeLast(trackId = trackId, ms = posMs)
        val nextByTrack = ud.resume.byTrack.toMutableMap()
        if (durSec >= resumeMinSec && posSec >= 10.0) {
            if (durSec - posSec <= 15.0) {
                nextByTrack.remove(trackId)
            } else {
                nextByTrack[trackId] = posMs
            }
        }
        return ud.copy(resume = ResumeState(last = nextLast, byTrack = nextByTrack))
    }

    fun startPositionFor(
        ud: UserData,
        track: TrackRecord,
        startKind: String,
        resumeMinSec: Double = 600.0,
    ): Double {
        if (startKind == "prime") {
            val last = ud.resume.last
            if (last != null && last.trackId == track.id && last.ms > 0L) {
                return (last.ms / 1000.0).coerceAtMost(max(0.0, track.duration - 1.0))
            }
        }
        if (track.duration >= resumeMinSec) {
            val savedMs = ud.resume.byTrack[track.id] ?: 0L
            if (savedMs > 0L) {
                return (savedMs / 1000.0).coerceAtMost(max(0.0, track.duration - 1.0))
            }
        }
        return 0.0
    }

    fun playlistToM3U(pl: Playlist, tracks: List<TrackRecord>): String {
        val byId = tracks.associateBy { it.id }
        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#PLAYLIST:${pl.name}")
        for (tid in pl.trackIds) {
            val t = byId[tid] ?: continue
            val dur = t.duration.toInt().coerceAtLeast(-1)
            val artistTitle = if (t.artist.isNotBlank()) "${t.artist} - ${t.title}" else t.title
            sb.appendLine("#EXTINF:$dur,$artistTitle")
            sb.appendLine(t.path.ifEmpty { t.fileName.ifEmpty { "${t.title}.mp3" } })
        }
        return sb.toString()
    }

    fun parseM3U(text: String, fileName: String, tracks: List<TrackRecord>): Pair<String, List<String>> {
        var plName = fileName.replaceFirst(Regex("""\.[^.]+$"""), "").ifEmpty { "Imported Playlist" }
        val matchedIds = ArrayList<String>()
        var extInfTitle: String? = null

        for (rawLine in text.split(Regex("""\r?\n"""))) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#PLAYLIST:", ignoreCase = true)) {
                val p = line.substringAfter(":").trim()
                if (p.isNotEmpty()) plName = p
                continue
            }
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                extInfTitle = line.substringAfter(",", "").trim().takeIf { it.isNotEmpty() }
                continue
            }
            if (line.startsWith("#")) continue

            val base = line.replace('\\', '/').substringAfterLast('/').lowercase()
            val stem = SonoraUtil.baseKey(base)
            val hit = tracks.firstOrNull { t ->
                val tBase = t.fileName.replace('\\', '/').substringAfterLast('/').lowercase()
                tBase == base || SonoraUtil.baseKey(tBase) == stem
            } ?: extInfTitle?.let { ext ->
                val extLower = ext.lowercase()
                tracks.firstOrNull { t ->
                    "${t.artist} - ${t.title}".lowercase() == extLower || t.title.lowercase() == extLower
                }
            }
            if (hit != null && !matchedIds.contains(hit.id)) {
                matchedIds.add(hit.id)
            }
            extInfTitle = null
        }
        return plName to matchedIds
    }
}

class LibraryRepo(val root: File) {
    val mediaDir: File = File(root, "media").apply { mkdirs() }
    val coversDir: File = File(root, "covers").apply { mkdirs() }
    val thumbsDir: File = File(root, "thumbs").apply { mkdirs() }
    val analysisDir: File = File(root, "analysis").apply { mkdirs() }

    private val libraryFile = JsonFile(
        File(root, "library.json"),
        ListSerializer(TrackRecord.serializer()),
    ) { emptyList() }

    private val userDataFile = JsonFile(
        File(root, "userdata.json"),
        UserData.serializer(),
    ) { UserData() }

    private val treesFile = JsonFile(
        File(root, "trees.json"),
        ListSerializer(TreeRecord.serializer()),
    ) { emptyList() }

    val tracks = Store<List<TrackRecord>>(emptyList())
    val userData = Store(UserData())
    val trees = Store<List<TreeRecord>>(emptyList())
    val lyrics = Store<Map<String, Lyrics>>(emptyMap())

    fun loadAll(initialLyrics: Map<String, Lyrics> = emptyMap()) {
        tracks.replace(libraryFile.load())
        userData.replace(UserDataLogic.normalizeUserData(userDataFile.load()))
        trees.replace(treesFile.load())
        lyrics.replace(initialLyrics)
    }

    private fun saveLibrary() {
        runCatching { libraryFile.save(tracks.value) }
    }

    private fun saveUserData() {
        runCatching { userDataFile.save(userData.value) }
    }

    private fun saveTrees() {
        runCatching { treesFile.save(trees.value) }
    }

    fun trackById(id: String?): TrackRecord? {
        if (id.isNullOrEmpty()) return null
        return tracks.value.firstOrNull { it.id == id }
    }

    fun indexOfTrackId(id: String?): Int {
        if (id.isNullOrEmpty()) return -1
        return tracks.value.indexOfFirst { it.id == id }
    }

    fun getTrackAudioConfig(trackId: String): TrackAudioConfig? =
        userData.value.trackConfigs[trackId]

    fun setTrackAudioConfig(trackId: String, cfg: TrackAudioConfig?) {
        userData.set { ud ->
            val next = ud.trackConfigs.toMutableMap()
            if (cfg == null) next.remove(trackId) else next[trackId] = cfg
            ud.copy(trackConfigs = next)
        }
        saveUserData()
    }

    fun resolveTrackUri(track: TrackRecord): Uri? {
        if (track.storage == "tree" && track.uri.isNotEmpty()) {
            return Uri.parse(track.uri)
        }
        if (track.file.isNotEmpty()) {
            val f = File(mediaDir, track.file)
            if (f.exists()) return Uri.fromFile(f)
        }
        if (track.uri.isNotEmpty()) return Uri.parse(track.uri)
        return null
    }

    fun resolveCoverFile(track: TrackRecord, small: Boolean): File? {
        if (track.coverName.isEmpty()) return null
        val primary = if (small) File(thumbsDir, track.coverName) else File(coversDir, track.coverName)
        if (primary.exists()) return primary
        val fallback = if (small) File(coversDir, track.coverName) else File(thumbsDir, track.coverName)
        return if (fallback.exists()) fallback else null
    }

    fun mediaItemFor(track: TrackRecord): MediaItem {
        val uri = resolveTrackUri(track) ?: Uri.EMPTY
        val coverFile = resolveCoverFile(track, small = false)
        val meta = MediaMetadata.Builder()
            .setTitle(track.title.ifEmpty { track.fileName })
            .setArtist(track.artist.ifEmpty { "Unknown artist" })
            .setAlbumTitle(track.album)
            .apply {
                if (coverFile != null) setArtworkUri(Uri.fromFile(coverFile))
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .setMediaMetadata(meta)
            .build()
    }

    fun upsertTracks(addedOrUpdated: List<TrackRecord>) {
        if (addedOrUpdated.isEmpty()) return
        val byId = LinkedHashMap<String, TrackRecord>()
        for (t in tracks.value) byId[t.id] = t
        for (t in addedOrUpdated) byId[t.id] = t
        tracks.replace(byId.values.toList())
        saveLibrary()
    }

    fun updateTrack(trackId: String, transform: (TrackRecord) -> TrackRecord) {
        var changed = false
        tracks.set { list ->
            list.map { t ->
                if (t.id == trackId) {
                    changed = true
                    transform(t)
                } else t
            }
        }
        if (changed) saveLibrary()
    }

    fun deleteTrack(trackId: String) {
        val target = trackById(trackId)
        tracks.set { list -> list.filterNot { it.id == trackId } }
        saveLibrary()
        if (target != null && target.storage == "copy" && target.file.isNotEmpty()) {
            runCatching { File(mediaDir, target.file).delete() }
        }
    }

    fun upsertTree(tree: TreeRecord) {
        trees.set { list ->
            val m = list.toMutableList()
            val idx = m.indexOfFirst { it.uri == tree.uri }
            if (idx >= 0) m[idx] = tree else m.add(tree)
            m
        }
        saveTrees()
    }

    fun removeTree(treeUri: String) {
        tracks.set { list -> list.filterNot { it.treeUri == treeUri } }
        trees.set { list -> list.filterNot { it.uri == treeUri } }
        saveLibrary()
        saveTrees()
    }

    fun recordPlayCount(trackId: String) {
        val now = System.currentTimeMillis()
        updateTrack(trackId) { t ->
            t.copy(playCount = t.playCount + 1, lastPlayedAt = now)
        }
    }

    fun saveResume(trackId: String, posSec: Double, durSec: Double, resumeMinSec: Double) {
        userData.set { ud -> UserDataLogic.computeSaveResume(ud, trackId, posSec, durSec, resumeMinSec) }
        saveUserData()
    }

    fun createPlaylist(name: String): Playlist {
        val clean = name.trim().ifEmpty { "Playlist ${userData.value.playlists.size + 1}" }
        val now = System.currentTimeMillis()
        val pl = Playlist(
            id = SonoraUtil.newId(),
            name = clean,
            trackIds = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        userData.set { ud -> ud.copy(playlists = ud.playlists + pl) }
        saveUserData()
        return pl
    }

    fun renamePlaylist(id: String, name: String) {
        val clean = name.trim().ifEmpty { return }
        val now = System.currentTimeMillis()
        userData.set { ud ->
            ud.copy(playlists = ud.playlists.map { if (it.id == id) it.copy(name = clean, updatedAt = now) else it })
        }
        saveUserData()
    }

    fun deletePlaylist(id: String) {
        userData.set { ud -> ud.copy(playlists = ud.playlists.filterNot { it.id == id }) }
        saveUserData()
    }

    fun addTrackToPlaylist(playlistId: String, trackId: String) {
        val now = System.currentTimeMillis()
        userData.set { ud ->
            ud.copy(playlists = ud.playlists.map { pl ->
                if (pl.id == playlistId && !pl.trackIds.contains(trackId)) {
                    pl.copy(trackIds = pl.trackIds + trackId, updatedAt = now)
                } else pl
            })
        }
        saveUserData()
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        val now = System.currentTimeMillis()
        userData.set { ud ->
            ud.copy(playlists = ud.playlists.map { pl ->
                if (pl.id == playlistId) pl.copy(trackIds = pl.trackIds.filterNot { it == trackId }, updatedAt = now) else pl
            })
        }
        saveUserData()
    }

    fun addBookmark(trackId: String, tSec: Double, label: String): Bookmark {
        val bm = Bookmark(
            id = SonoraUtil.newId(),
            trackId = trackId,
            t = max(0.0, tSec),
            label = label.trim().ifEmpty { fmtSec(tSec) },
            createdAt = System.currentTimeMillis(),
        )
        userData.set { ud ->
            UserDataLogic.normalizeUserData(ud.copy(bookmarks = ud.bookmarks + bm))
        }
        saveUserData()
        return bm
    }

    fun removeBookmark(bookmarkId: String) {
        userData.set { ud -> ud.copy(bookmarks = ud.bookmarks.filterNot { it.id == bookmarkId }) }
        saveUserData()
    }

    fun bookmarksFor(trackId: String?): List<Bookmark> {
        if (trackId.isNullOrEmpty()) return emptyList()
        return userData.value.bookmarks.filter { it.trackId == trackId }.sortedBy { it.t }
    }

    fun getLyrics(track: TrackRecord?): Lyrics? {
        if (track == null) return null
        lyrics.value[track.id]?.let { return it }
        if (!track.embeddedLyrics.isNullOrBlank()) {
            val parsed = LyricsParser.parseLyrics(track.embeddedLyrics, "embedded")
            lyrics.set { it + (track.id to parsed) }
            return parsed
        }
        return null
    }

    fun saveLyrics(trackId: String, lyr: Lyrics?) {
        lyrics.set { map ->
            val next = map.toMutableMap()
            if (lyr == null || lyr.lines.isEmpty()) next.remove(trackId) else next[trackId] = lyr
            next
        }
    }

    fun exportBackupJson(settings: Settings): String {
        val bf = BackupFile(
            app = "sonora",
            version = 1,
            createdAt = System.currentTimeMillis(),
            tracks = tracks.value.map { t ->
                BackupTrack(
                    id = t.id,
                    fp = t.fp,
                    fileName = t.fileName,
                    size = t.size,
                    title = t.title,
                    artist = t.artist,
                    album = t.album,
                    addedAt = t.addedAt,
                    playCount = t.playCount,
                    lastPlayedAt = t.lastPlayedAt,
                    edited = t.edited,
                )
            },
            settings = settings,
            userdata = userData.value,
            lyrics = lyrics.value,
        )
        return SonoraJson.json.encodeToString(BackupFile.serializer(), bf)
    }

    fun restoreBackupJson(rawJson: String): Pair<Settings, Int> {
        val bf = SonoraJson.json.decodeFromString(BackupFile.serializer(), rawJson)
        val curTracks = tracks.value.toMutableList()
        val idMap = HashMap<String, String>()
        var matched = 0

        for (bt in bf.tracks) {
            val idx = curTracks.indexOfFirst { ct ->
                (!bt.fp.isNullOrEmpty() && ct.fp == bt.fp) ||
                    (ct.fileName.equals(bt.fileName, ignoreCase = true) && ct.size == bt.size && bt.size > 0L) ||
                    (ct.title.equals(bt.title, ignoreCase = true) && ct.artist.equals(bt.artist, ignoreCase = true))
            }
            if (idx >= 0) {
                val ct = curTracks[idx]
                idMap[bt.id] = ct.id
                curTracks[idx] = ct.copy(
                    playCount = max(ct.playCount, bt.playCount ?: 0),
                    lastPlayedAt = max(ct.lastPlayedAt, bt.lastPlayedAt ?: 0L),
                )
                matched++
            }
        }
        tracks.replace(curTracks)
        saveLibrary()

        val remappedUd = UserDataLogic.remapUserData(bf.userdata, idMap)
        userData.replace(UserDataLogic.mergeUserData(userData.value, remappedUd))
        saveUserData()

        val remappedLyrics = bf.lyrics.entries.associate { (k, v) -> (idMap[k] ?: k) to v }
        lyrics.set { it + remappedLyrics }
        return bf.settings to matched
    }

    suspend fun replaceCoverFromUri(context: Context, trackId: String, uri: Uri): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext false
                val coverName = "${SonoraUtil.sha1Hex(bytes, 16)}.jpg"
                if (LibraryImporter.saveCoverPair(root, coverName, bytes)) {
                    updateTrack(trackId) { it.copy(coverName = coverName, edited = true) }
                    true
                } else false
            }.getOrDefault(false)
        }
}
