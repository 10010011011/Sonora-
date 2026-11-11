package app.sonora.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.inspector.MetadataRetriever
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** ReplayGain and embedded lyrics from ID3v2 (TXXX / USLT), Vorbis comments (FLAC / Ogg / Opus) and MP4 atoms. */
object Tags {
    class Extras(var rgTrack: Double? = null, var rgAlbum: Double? = null, var lyrics: String? = null)

    private val NUM = Regex("""[+-]?\d+(?:\.\d+)?""")

    private fun uslt(d: ByteArray): String? = runCatching {
        if (d.size < 5) return null
        val cs = when (d[0].toInt()) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
        val wide = d[0].toInt() == 1 || d[0].toInt() == 2
        var i = 4
        while (i < d.size) { // skip the content descriptor
            if (wide) {
                if (i + 1 < d.size && d[i].toInt() == 0 && d[i + 1].toInt() == 0) { i += 2; break }
                i += 2
            } else {
                if (d[i].toInt() == 0) { i++; break }
                i++
            }
        }
        if (i >= d.size) null else String(d, i, d.size - i, cs).trim('\u0000', ' ', '\n')
    }.getOrNull()

    fun read(md: Metadata, out: Extras) {
        for (i in 0 until md.length()) {
            val e = md.get(i)
            var key: String? = null
            var value: String? = null
            if (e is TextInformationFrame) {
                key = if (e.id == "TXXX") e.description else e.id
                value = e.values.firstOrNull()
            } else if (e is BinaryFrame && e.id == "USLT") {
                key = "LYRICS"
                value = uslt(e.data)
            } else if (e is VorbisComment) {
                key = e.key
                value = e.value
            } else if (e is InternalFrame) { // MP4 freeform atoms (----:com.apple.iTunes:replaygain_track_gain)
                key = e.description
                value = e.text
            }
            val v = value ?: continue
            when (key?.uppercase() ?: continue) {
                "REPLAYGAIN_TRACK_GAIN" -> out.rgTrack = NUM.find(v)?.value?.toDoubleOrNull() ?: out.rgTrack
                "REPLAYGAIN_ALBUM_GAIN" -> out.rgAlbum = NUM.find(v)?.value?.toDoubleOrNull() ?: out.rgAlbum
                "USLT", "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS" -> if (v.isNotBlank()) out.lyrics = v
            }
        }
    }

    /** Reads the container metadata without playing the file. */
    fun probe(uri: Uri): Extras {
        val out = Extras()
        runCatching {
            MetadataRetriever.Builder(S.app, MediaItem.fromUri(uri)).build().use { r ->
                val groups = r.retrieveTrackGroups().get(8, TimeUnit.SECONDS)
                for (g in 0 until groups.length) {
                    val tg = groups.get(g)
                    for (f in 0 until tg.length) tg.getFormat(f).metadata?.let { read(it, out) }
                }
            }
        }
        return out
    }
}

/**
 * Brings music into the library. A picked file is copied once into Sonora's storage and recognised by its
 * content fingerprint if it is added again (even renamed). A granted folder is indexed in place (no copy).
 * Sidecar .lrc / .srt / .vtt files are paired with the song of the same name.
 */
object Importer {
    private val LYRIC = setOf("lrc", "srt", "vtt")
    private val COVER_NAMES = setOf("cover.jpg", "folder.jpg", "front.jpg", "cover.png", "folder.png", "album.jpg")

    @Volatile private var cancel = false
    private var job: Job? = null

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    private fun base(name: String) = name.substringBeforeLast('.').lowercase()
    private fun state(f: (ImportState) -> ImportState) = S.imp.update(f)

    fun cancelImport() {
        cancel = true
    }

    fun closeSheet() {
        if (S.imp.value.phase == "done") S.imp.value = ImportState()
        else state { it.copy(phase = if (it.phase == "running") "hidden" else it.phase) }
    }

    private fun nameAndSize(uri: Uri): Pair<String, Long> {
        if (uri.scheme == "file") return File(uri.path ?: "").let { it.name to it.length() }
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
        var size = 0L
        runCatching {
            S.app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    size = c.getLong(1)
                }
            }
        }
        return name to size
    }

    private class Meta(val title: String, val artist: String, val album: String, val duration: Double, val art: ByteArray?)

    private fun readMeta(file: File?, uri: Uri?, fileName: String): Meta {
        val r = MediaMetadataRetriever()
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var dur = 0.0
        var art: ByteArray? = null
        try {
            if (file != null) r.setDataSource(file.absolutePath) else r.setDataSource(S.app, uri)
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0
            art = r.embeddedPicture
        } catch (e: Exception) {
            Diag.log("meta-failed", "$fileName: ${e.message}")
        } finally {
            runCatching { r.release() }
        }
        // No tags: "Artist - Title.ext" is a common file-name pattern.
        val stem = fileName.substringBeforeLast('.')
        if (title.isNullOrBlank()) {
            val parts = stem.split(" - ", limit = 2)
            if (parts.size == 2 && artist.isNullOrBlank()) {
                artist = parts[0].trim()
                title = parts[1].trim()
            } else title = stem
        }
        return Meta(title!!.trim(), artist?.trim().orEmpty().ifEmpty { "Unknown artist" }, album?.trim().orEmpty().ifEmpty { "Local audio" }, dur, art)
    }

    /** Saves the cover (max 1024 px) and a 160 px thumbnail for list rows. Returns (cover, thumb) paths. */
    private fun saveCover(id: String, bytes: ByteArray?): Pair<String?, String?> {
        if (bytes == null || bytes.isEmpty()) return null to null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 1400) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null to null
            fun scaled(maxPx: Int): Bitmap {
                val k = maxPx.toFloat() / max(bmp.width, bmp.height)
                return if (k >= 1f) bmp else Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).toInt()), max(1, (bmp.height * k).toInt()), true)
            }
            val cover = File(S.coverDir, "$id.jpg")
            val thumb = File(S.coverDir, "${id}_t.jpg")
            cover.outputStream().use { scaled(1024).compress(Bitmap.CompressFormat.JPEG, 88, it) }
            thumb.outputStream().use { scaled(160).compress(Bitmap.CompressFormat.JPEG, 82, it) }
            cover.absolutePath to thumb.absolutePath
        }.getOrDefault(null to null)
    }

    /** Copies user-selected image artwork into Sonora's private cover store and pins it to this track. */
    fun setArtwork(trackId: String, imageUri: Uri) {
        if (S.track(trackId) == null) return
        S.scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = S.app.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Could not read that image")
                    saveCover("$trackId-custom-${System.currentTimeMillis()}", bytes)
                }.onFailure { Diag.log("artwork-failed", it.message ?: "Could not read image") }.getOrNull()
            }
            val cover = saved?.first
            val thumb = saved?.second
            if (cover == null || thumb == null) {
                S.toast("Couldn't use that image")
                return@launch
            }
            S.patchTrack(trackId) { it.copy(cover = cover, thumb = thumb, customArtwork = true) }
            S.toast("Thumbnail updated")
        }
    }

    /** "size:sha1(first 64 KB + last 64 KB)" - the same fingerprint scheme as the Capacitor build. */
    private fun fingerprint(f: File): String {
        val size = f.length()
        val n = min(65536L, size).toInt()
        val head = ByteArray(n)
        val tail = ByteArray(n)
        RandomAccessFile(f, "r").use {
            it.readFully(head)
            it.seek(size - n)
            it.readFully(tail)
        }
        val md = MessageDigest.getInstance("SHA-1")
        md.update(head)
        md.update(tail)
        return "$size:" + md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun readText(uri: Uri): String? = runCatching { S.app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()

    private fun addTracks(fresh: List<Track>) {
        if (fresh.isEmpty()) return
        S.tracks.update { it + fresh }
        S.queue.update { QueueOps.appendNew(it, fresh.map { t -> t.id }) }
        if (S.player.value.trackId == null) S.player.update { it.copy(trackId = fresh[0].id, duration = fresh[0].duration) }
        S.scope.launch { Engine.requeue() } // the player is main-thread only; imports run on IO
    }

    /** Copies picked audio files (and pairs picked lyric files) into the library. */
    fun importUris(uris: List<Uri>, kind: String = "import", playFirst: Boolean = false) {
        if (uris.isEmpty()) return
        if (job?.isActive == true) return S.toast("An import is already running")
        cancel = false
        job = S.scope.launch {
            state { ImportState("running", kind, 0, 0, "", "", emptyList()) }
            var added = 0
            var skipped = 0
            var lyricsPaired = 0
            val failed = ArrayList<ImportFail>()
            var firstId: String? = null
            withContext(Dispatchers.IO) {
                val named = uris.map { it to nameAndSize(it) }
                val lyricFiles = named.filter { ext(it.second.first) in LYRIC }
                val picked = named.filter { ext(it.second.first) !in LYRIC }
                val (unsupported, audio) = picked.partition { supportOf(it.second.first) is Support.Unsupported }
                for ((_, ns) in unsupported) {
                    val label = (supportOf(ns.first) as Support.Unsupported).label
                    // Not retryable (null uri): trying again would fail the same way.
                    failed.add(ImportFail(ns.first, "$label isn't supported yet", null))
                    Diag.log("import-unsupported", ns.first)
                }
                state { it.copy(total = audio.size) }
                for ((i, entry) in audio.withIndex()) {
                    if (cancel) break
                    val (uri, ns) = entry
                    val name = ns.first
                    state { it.copy(done = i, name = name) }
                    var tmp: File? = null
                    try {
                        val id = uid()
                        val e = ext(name).ifEmpty { "audio" }
                        val dst = File(S.mediaDir, "$id.$e")
                        tmp = dst
                        if (S.app.filesDir.usableSpace < ns.second + 20_000_000) throw IllegalStateException("Not enough free storage")
                        (S.app.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Could not open the file")).use { input ->
                            dst.outputStream().use { input.copyTo(it, 1 shl 16) }
                        }
                        if (dst.length() == 0L) throw IllegalStateException("The file is empty")
                        val fp = fingerprint(dst)
                        val dup = S.tracks.value.firstOrNull { it.fp == fp }
                        val pairedLyric = lyricFiles.firstOrNull { base(it.second.first) == base(name) }
                        if (dup != null) {
                            dst.delete()
                            skipped++
                            if (firstId == null) firstId = dup.id
                            if (pairedLyric != null && attachLyric(dup.id, pairedLyric.first, pairedLyric.second.first)) lyricsPaired++
                            continue
                        }
                        val meta = readMeta(dst, null, name)
                        if (meta.duration <= 0 && e !in PLAYABLE_EXTENSIONS) {
                            dst.delete()
                            throw IllegalStateException("Not a playable audio file")
                        }
                        val (cover, thumb) = saveCover(id, meta.art)
                        val extra = Tags.probe(Uri.fromFile(dst))
                        val t = Track(
                            id = id, uri = dst.absolutePath, fileName = name, size = dst.length(), mime = S.app.contentResolver.getType(uri).orEmpty(),
                            lastModified = System.currentTimeMillis(), path = name, title = meta.title, artist = meta.artist, album = meta.album, duration = meta.duration,
                            cover = cover, thumb = thumb, rgTrack = extra.rgTrack, rgAlbum = extra.rgAlbum, embeddedLyrics = extra.lyrics,
                            addedAt = System.currentTimeMillis(), fp = fp, storage = "copy",
                        )
                        addTracks(listOf(t))
                        added++
                        if (firstId == null) firstId = id
                        if (pairedLyric != null && attachLyric(id, pairedLyric.first, pairedLyric.second.first)) lyricsPaired++
                    } catch (ex: Exception) {
                        tmp?.delete()
                        failed.add(ImportFail(name, ex.message ?: "Could not import", uri.toString()))
                        Diag.log("import-failed", "$name: ${ex.message}")
                    }
                }
                // Lyric files picked on their own: pair them with songs already in the library by file name.
                for ((uri, ns) in lyricFiles) {
                    val hit = S.tracks.value.firstOrNull { base(it.fileName) == base(ns.first) && it.sidecar == null } ?: continue
                    if (attachLyric(hit.id, uri, ns.first)) lyricsPaired++
                }
            }
            val notSupported = failed.count { it.uri == null && it.reason.endsWith("isn't supported yet") }
            val parts = arrayListOf("Added ${plural(added, "song")}")
            if (skipped > 0) parts.add("$skipped already in your library")
            if (lyricsPaired > 0) parts.add("${plural(lyricsPaired, "lyric file")} paired")
            if (failed.size - notSupported > 0) parts.add("${failed.size - notSupported} failed")
            if (notSupported > 0) parts.add("$notSupported not supported yet")
            if (cancel) parts.add("cancelled")
            val wasHidden = S.imp.value.phase == "hidden"
            S.imp.value = if (wasHidden && failed.isEmpty()) ImportState() else ImportState("done", kind, 0, 0, "", parts.joinToString(" · "), failed)
            if (wasHidden || kind == "open") S.toast(parts.joinToString(" · "))
            if (kind == "open" && failed.isEmpty()) S.imp.value = ImportState()
            val play = firstId
            if (playFirst && play != null) Engine.playNow(play)
        }
    }

    private fun attachLyric(trackId: String, uri: Uri, name: String): Boolean {
        val text = readText(uri) ?: return false
        if (text.isBlank()) return false
        val f = File(S.lyricDir, "$trackId.${ext(name).ifEmpty { "lrc" }}")
        f.writeText(text)
        S.patchTrack(trackId) { it.copy(sidecar = f.absolutePath) }
        S.lyrics.update { it - trackId }
        return true
    }

    fun retryFailed() {
        val uris = S.imp.value.failed.mapNotNull { it.uri?.let(Uri::parse) }
        S.imp.value = ImportState()
        importUris(uris, "retry")
    }

    fun errorReport(): String = buildString {
        appendLine("Sonora import errors (${S.imp.value.failed.size})")
        S.imp.value.failed.forEach { appendLine("${it.name}: ${it.reason}") }
    }

    /* ------------------------------------------------------------------ folders indexed in place */

    private class Doc(val uri: Uri, val name: String, val size: Long, val modified: Long, val dir: String)

    private fun walk(tree: Uri, docId: String, rel: String, out: MutableList<Doc>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        S.app.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext() && !cancel) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) walk(tree, id, "$rel/$name", out)
                else out.add(Doc(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, c.getLong(3), c.getLong(4), rel))
            }
        }
    }

    fun addFolder(tree: Uri) {
        runCatching { S.app.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = DocumentFile.fromTreeUri(S.app, tree)?.name ?: tree.lastPathSegment?.substringAfterLast(':') ?: "Folder"
        if (S.folders.value.none { it.uri == tree.toString() }) S.folders.update { it + FolderInfo(tree.toString(), name, 0) }
        scan(listOf(FolderInfo(tree.toString(), name, 0)), "folder")
    }

    fun rescanFolders() = scan(S.folders.value, "rescan")

    fun removeFolder(uri: String) {
        S.removeTracks(S.tracks.value.filter { it.treeUri == uri }.map { it.id }.toSet())
        S.folders.update { list -> list.filter { it.uri != uri } }
        runCatching { S.app.contentResolver.releasePersistableUriPermission(Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Engine.requeue()
    }

    fun folderAccessible(uri: String) = S.app.contentResolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isReadPermission }

    /** Finds new, changed and deleted files; unchanged files (same size and modified time) are not re-read. */
    private fun scan(folders: List<FolderInfo>, kind: String) {
        if (folders.isEmpty()) return
        if (job?.isActive == true) return S.toast("An import is already running")
        cancel = false
        job = S.scope.launch {
            state { ImportState("running", kind, 0, 0, "", "", emptyList()) }
            var added = 0
            var updated = 0
            var removed = 0
            var skippedUnsupported = 0
            val failed = ArrayList<ImportFail>()
            withContext(Dispatchers.IO) {
                for (folder in folders) {
                    if (cancel) break
                    val tree = Uri.parse(folder.uri)
                    val docs = ArrayList<Doc>()
                    try {
                        walk(tree, DocumentsContract.getTreeDocumentId(tree), folder.name, docs)
                    } catch (e: Exception) {
                        failed.add(ImportFail(folder.name, "Access was revoked: add the folder again"))
                        continue
                    }
                    val audio = docs.filter { ext(it.name) in PLAYABLE_EXTENSIONS }
                    val unsupportedDocs = docs.filter { supportOf(it.name) is Support.Unsupported }
                    skippedUnsupported += unsupportedDocs.size
                    val byDir = docs.groupBy { it.dir }
                    val existing = S.tracks.value.filter { it.treeUri == folder.uri }.associateBy { it.uri }
                    val seen = HashSet<String>()
                    // Unsupported files stay listed (older versions indexed WMA/AIFF): they fail with a
                    // clear message at play, and are never silently purged by a rescan.
                    unsupportedDocs.forEach { seen.add(it.uri.toString()) }
                    state { it.copy(total = it.total + audio.size) }
                    val fresh = ArrayList<Track>()
                    for (d in audio) {
                        if (cancel) break
                        val key = d.uri.toString()
                        seen.add(key)
                        val old = existing[key]
                        val sidecar = byDir[d.dir]?.firstOrNull { ext(it.name) in LYRIC && base(it.name) == base(d.name) }?.uri?.toString()
                        state { it.copy(done = it.done + 1, name = d.name) }
                        if (old != null && old.size == d.size && old.lastModified == d.modified) {
                            if (old.sidecar != sidecar && (sidecar != null || old.sidecar?.startsWith("content:") == true)) {
                                S.patchTrack(old.id) { it.copy(sidecar = sidecar) }
                                S.lyrics.update { it - old.id }
                            }
                            continue
                        }
                        try {
                            val id = old?.id ?: uid()
                            val meta = readMeta(null, d.uri, d.name)
                            val art = meta.art ?: byDir[d.dir]?.firstOrNull { it.name.lowercase() in COVER_NAMES }?.let { c ->
                                runCatching { S.app.contentResolver.openInputStream(c.uri)?.use { it.readBytes() } }.getOrNull()
                            }
                            val (cover, thumb) = if (old?.customArtwork == true) old.cover to old.thumb else saveCover(id, art)
                            val extra = Tags.probe(d.uri)
                            val t = Track(
                                id = id, uri = key, fileName = d.name, size = d.size, lastModified = d.modified, path = "${d.dir}/${d.name}",
                                title = if (old?.edited == true) old.title else meta.title, artist = if (old?.edited == true) old.artist else meta.artist,
                                album = if (old?.edited == true) old.album else meta.album, duration = meta.duration, cover = cover, thumb = thumb,
                                rgTrack = extra.rgTrack, rgAlbum = extra.rgAlbum, embeddedLyrics = extra.lyrics, sidecar = sidecar,
                                addedAt = old?.addedAt ?: System.currentTimeMillis(), playCount = old?.playCount ?: 0, lastPlayedAt = old?.lastPlayedAt ?: 0,
                                storage = "tree", treeUri = folder.uri, edited = old?.edited ?: false,
                                customArtwork = old?.customArtwork ?: false,
                            )
                            if (old != null) {
                                S.patchTrack(id) { t }
                                updated++
                            } else {
                                fresh.add(t)
                                added++
                                if (fresh.size >= 12) {
                                    addTracks(fresh.toList())
                                    fresh.clear()
                                }
                            }
                        } catch (ex: Exception) {
                            failed.add(ImportFail(d.name, ex.message ?: "Could not read", null))
                        }
                    }
                    addTracks(fresh)
                    if (!cancel) {
                        val gone = existing.filterKeys { it !in seen }.values.map { it.id }.toSet()
                        removed += gone.size
                        S.removeTracks(gone)
                        S.folders.update { list -> list.map { if (it.uri == folder.uri) it.copy(lastScanAt = System.currentTimeMillis()) else it } }
                    }
                }
            }
            val parts = arrayListOf("${plural(added, "new song")}")
            if (updated > 0) parts.add("$updated updated")
            if (removed > 0) parts.add("$removed removed")
            if (failed.isNotEmpty()) parts.add("${failed.size} failed")
            if (cancel) parts.add("cancelled")
            val wasHidden = S.imp.value.phase == "hidden"
            S.imp.value = if (wasHidden && failed.isEmpty()) ImportState() else ImportState("done", kind, 0, 0, "", parts.joinToString(" · "), failed)
            if (wasHidden) S.toast(parts.joinToString(" · "))
            Engine.requeue()
        }
    }

    /** (tracks, bytes of copied songs, free bytes) for Settings > Library. */
    fun stats(): Triple<Int, Long, Long> {
        val t = S.tracks.value
        return Triple(t.size, t.filter { it.storage != "tree" }.sumOf { it.size }, S.app.filesDir.usableSpace)
    }
}
