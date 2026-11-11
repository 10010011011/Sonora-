package app.sonora.player

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/*
 * Reading what the previous (Capacitor) version left on disk, and backup export / restore.
 * Everything here is plain Kotlin with no Android classes, so the upgrade path is covered by unit tests.
 */

val SonoraJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

data class RestoreSummary(val matched: Int, val unmatched: Int, val playlists: Int)
class RestoreResult(val tracks: List<Track>, val settings: Settings, val user: UserData, val lyrics: Map<String, Lyrics>, val summary: RestoreSummary)

object Legacy {
    private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content.orEmpty()
    private fun JsonObject.num(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
    private fun JsonObject.long(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L
    private fun JsonObject.flag(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false

    /**
     * Reads `<root>/library.json` written by Sonora 1.2 (LibraryStore.java). `root` is `files/sonora`.
     * Copied songs are referenced where they already are (`media/`, `covers/`, `thumbs/`, `lyrics/`): nothing is duplicated.
     * Records whose audio file is gone are skipped. A damaged or missing index yields an empty list, never an exception.
     */
    fun readTracks(root: File): List<Track> {
        val f = File(root, "library.json")
        if (!f.isFile) return emptyList()
        val arr = runCatching { SonoraJson.parseToJsonElement(f.readText()).jsonArray }.getOrNull() ?: return emptyList()
        val out = ArrayList<Track>()
        val seen = HashSet<String>()
        for (el in arr) {
            val r = el as? JsonObject ?: continue
            val id = r.str("id")
            if (id.isEmpty() || !seen.add(id)) continue
            val tree = r.str("storage") == "tree"
            val uri = if (tree) r.str("uri") else File(File(root, "media"), r.str("file").ifEmpty { r.str("key") }).takeIf { it.isFile }?.absolutePath.orEmpty()
            if (uri.isEmpty()) continue
            fun existing(sub: String, name: String) = if (name.isEmpty()) null else File(File(root, sub), name).takeIf { it.isFile }?.absolutePath
            val fileName = r.str("fileName")
            val cover = r.str("coverName")
            var dur = r.num("duration") ?: 0.0
            if (dur.isNaN() || dur < 0) dur = 0.0
            if (dur > 100_000) dur /= 1000.0 // defensive: a value this large can only be milliseconds
            out.add(
                Track(
                    id = id, uri = uri, fileName = fileName, size = r.long("size"), mime = r.str("mime"), lastModified = r.long("lastModified"),
                    path = r.str("path").ifEmpty { fileName }, title = r.str("title").ifEmpty { fileName.substringBeforeLast('.') },
                    artist = r.str("artist").ifEmpty { "Unknown artist" }, album = r.str("album").ifEmpty { "Local audio" }, duration = dur,
                    cover = existing("covers", cover), thumb = existing("thumbs", cover) ?: existing("covers", cover), sidecar = existing("lyrics", r.str("sidecarFile")),
                    rgTrack = r.num("rgTrack"), rgAlbum = r.num("rgAlbum"), embeddedLyrics = r.str("embeddedLyrics").ifEmpty { null },
                    addedAt = r.long("addedAt"), playCount = r.long("playCount").toInt(), lastPlayedAt = r.long("lastPlayedAt"),
                    fp = r.str("fp").ifEmpty { null }, storage = if (tree) "tree" else "copy", treeUri = r.str("treeUri").ifEmpty { null }, edited = r.flag("edited"),
                ),
            )
        }
        return out
    }

    inline fun <reified T> readJson(file: File, serializer: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { if (file.isFile) SonoraJson.decodeFromString(serializer, file.readText()) else null }.getOrNull()
}

/** Merges restored / late-loaded data into what is on this device. Local playlists and bookmarks are never deleted. */
fun mergeUserData(local: UserData, incoming: UserData): UserData {
    val pl = local.playlists.map { it.id }.toHashSet()
    val bm = local.bookmarks.map { it.id }.toHashSet()
    return UserData(
        1, local.playlists + incoming.playlists.filter { it.id !in pl }, local.bookmarks + incoming.bookmarks.filter { it.id !in bm },
        ResumeState(local.resume.last ?: incoming.resume.last, incoming.resume.byTrack + local.resume.byTrack),
        incoming.trackConfigs + local.trackConfigs, (local.libraryOrder + incoming.libraryOrder).distinct(),
    )
}

/** Rewrites every track id (restore after a reinstall, when ids differ but fingerprints match). */
fun remapUserData(d: UserData, m: (String) -> String) = UserData(
    1, d.playlists.map { it.copy(trackIds = it.trackIds.map(m).distinct()) }, d.bookmarks.map { it.copy(trackId = m(it.trackId)) },
    ResumeState(d.resume.last?.let { it.copy(trackId = m(it.trackId)) }, d.resume.byTrack.mapKeys { m(it.key) }),
    d.trackConfigs.mapKeys { m(it.key) }, d.libraryOrder.map(m).distinct(),
)

object Backup {
    private val lyricsMap = MapSerializer(String.serializer(), Lyrics.serializer())
    private val backupTracks = ListSerializer(BackupTrack.serializer())

    /** Metadata only, no audio. Same JSON shape as Sonora 1.2's backup. */
    fun build(tracks: List<Track>, settings: Settings, user: UserData, lyrics: Map<String, Lyrics>, now: Long): String = buildJsonObject {
        put("app", "sonora")
        put("version", 1)
        put("exportedAt", now)
        put("tracks", SonoraJson.encodeToJsonElement(backupTracks, tracks.map { BackupTrack(it.id, it.fp, it.fileName, it.size, it.title, it.artist, it.album, it.addedAt, it.playCount, it.lastPlayedAt, it.edited) }))
        put("settings", SonoraJson.encodeToJsonElement(Settings.serializer(), settings))
        put("userdata", SonoraJson.encodeToJsonElement(UserData.serializer(), user))
        put("lyrics", SonoraJson.encodeToJsonElement(lyricsMap, lyrics))
    }.toString()

    /**
     * Applies a backup from this app or from Sonora 1.2 to the given state and returns the new state.
     * Songs are matched by id, then content fingerprint, then file name + size.
     * Throws IllegalArgumentException when the text is not a Sonora backup.
     */
    fun restore(text: String, tracks: List<Track>, settings: Settings, user: UserData, lyrics: Map<String, Lyrics>): RestoreResult {
        val root = runCatching { SonoraJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw IllegalArgumentException("This is not a Sonora backup")
        val app = runCatching { root["app"]?.jsonPrimitive?.content }.getOrNull()
        val rawTracks = root["tracks"]
        if (app != "sonora" || rawTracks == null) throw IllegalArgumentException("This is not a Sonora backup")
        val bt = runCatching { SonoraJson.decodeFromJsonElement(backupTracks, rawTracks) }.getOrNull() ?: throw IllegalArgumentException("This backup's song list could not be read")
        val byId = tracks.associateBy { it.id }
        val byFp = tracks.filter { it.fp != null }.associateBy { it.fp!! }
        val bySig = tracks.associateBy { "${it.fileName}|${it.size}" }
        val idMap = HashMap<String, String>()
        val patches = HashMap<String, BackupTrack>()
        for (b in bt) {
            val hit = byId[b.id] ?: b.fp?.let { byFp[it] } ?: bySig["${b.fileName}|${b.size}"] ?: continue
            idMap[b.id] = hit.id
            patches[hit.id] = b
        }
        val newTracks = tracks.map { t ->
            val b = patches[t.id] ?: return@map t
            var n = t
            if (b.edited) n = n.copy(title = b.title.ifEmpty { t.title }, artist = b.artist.ifEmpty { t.artist }, album = b.album.ifEmpty { t.album }, edited = true)
            if (b.playCount > t.playCount) n = n.copy(playCount = b.playCount, lastPlayedAt = b.lastPlayedAt)
            if (b.addedAt in 1 until t.addedAt) n = n.copy(addedAt = b.addedAt)
            n
        }
        val m = { id: String -> idMap[id] ?: id }
        val inSettings = root["settings"]?.let { runCatching { SonoraJson.decodeFromJsonElement(Settings.serializer(), it) }.getOrNull() }
        val liked = (settings.likedIds + (inSettings?.likedIds ?: emptyList()).map(m)).distinct()
        val inUser = (root["userdata"] ?: root["userData"])?.let { runCatching { SonoraJson.decodeFromJsonElement(UserData.serializer(), it) }.getOrNull() }
        val inLyrics = root["lyrics"]?.let { runCatching { SonoraJson.decodeFromJsonElement(lyricsMap, it) }.getOrNull() } ?: emptyMap()
        return RestoreResult(
            newTracks,
            (inSettings ?: settings).copy(likedIds = liked),
            if (inUser != null) mergeUserData(user, remapUserData(inUser, m)) else user,
            lyrics + inLyrics.mapKeys { m(it.key) }.filterKeys { it !in lyrics }, // lyrics edited on this device are never overwritten
            RestoreSummary(idMap.size, bt.size - idMap.size, inUser?.playlists?.size ?: 0),
        )
    }
}
