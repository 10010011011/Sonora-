// Sonora Native — LibraryImporter.kt
// File picker import, single-URI Open-With import, SAF folder tree scanning, cover thumbnail generation, and sidecar lyrics pairing.

package app.sonora.player.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.sonora.player.data.Store
import app.sonora.player.data.TrackRecord
import app.sonora.player.data.TreeRecord
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportProgressState(
    val active: Boolean = false,
    val label: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val skipped: Int = 0,
)

class LibraryImporter(
    private val context: Context,
    private val repo: LibraryRepo,
    private val scope: CoroutineScope,
    private val onToast: (String) -> Unit,
    private val onLibraryChanged: (List<TrackRecord>) -> Unit = {},
) {
    val progress = Store(ImportProgressState())

    fun startBackgroundUpkeep() {
        scope.launch(Dispatchers.IO) {
            for (tree in repo.trees.value) {
                runCatching { scanSafTreeInternal(Uri.parse(tree.uri), silent = true) }
            }
        }
    }

    /** Imports a single audio URI (e.g. from Intent.ACTION_VIEW "Open with Sonora") and invokes [onComplete] on Main. */
    fun importSingleUri(uri: Uri, onComplete: (TrackRecord?) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val record = runCatching {
                val displayName = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "track.mp3"
                val fileSize = queryFileSize(uri)
                val span = SonoraUtil.fpSpan(fileSize)
                val head = context.contentResolver.openInputStream(uri)?.use { ins ->
                    val b = ByteArray(span)
                    val r = ins.read(b)
                    if (r > 0) b.copyOf(r) else ByteArray(0)
                } ?: ByteArray(0)
                val fp = SonoraUtil.fingerprint(fileSize, head, head)

                val existing = repo.tracks.value.firstOrNull { it.fp.isNotEmpty() && it.fp == fp }
                if (existing != null && existing.storage == "copy" && File(repo.mediaDir, existing.file).exists()) {
                    return@runCatching existing
                }

                val key = SonoraUtil.sha1Hex("$displayName|$fileSize|${System.currentTimeMillis()}", 20)
                val ext = SonoraUtil.extOf(displayName)
                val destFileName = "$key.$ext"
                val destFile = File(repo.mediaDir, destFileName)
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    FileOutputStream(destFile).use { outs -> ins.copyTo(outs, 65536) }
                }

                val rec = buildTrackRecord(
                    id = existing?.id ?: SonoraUtil.idFor(displayName, destFile.length(), destFile.lastModified()),
                    key = key,
                    file = destFileName,
                    storage = "copy",
                    uriStr = "",
                    treeUriStr = "",
                    fileName = displayName,
                    path = displayName,
                    size = destFile.length(),
                    lastModified = destFile.lastModified(),
                    fp = fp,
                    sourceUri = Uri.fromFile(destFile),
                    existing = existing,
                )
                repo.upsertTracks(listOf(rec))
                onLibraryChanged(listOf(rec))
                rec
            }.getOrNull()

            withContext(Dispatchers.Main) {
                onComplete(record)
            }
        }
    }

    fun importPickedUris(uris: List<Uri>, playFirstNow: Boolean = false) {
        if (uris.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val audioUris = ArrayList<Pair<Uri, String>>()
            val lyricUris = HashMap<String, Uri>()

            for (uri in uris) {
                val name = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "track.mp3"
                when {
                    SonoraUtil.isAudioName(name) -> audioUris.add(uri to name)
                    SonoraUtil.isLyricName(name) -> lyricUris[SonoraUtil.baseKey(name)] = uri
                }
            }

            if (audioUris.isEmpty() && lyricUris.isNotEmpty()) {
                var paired = 0
                for ((stem, lrcUri) in lyricUris) {
                    val match = repo.tracks.value.firstOrNull { SonoraUtil.baseKey(it.fileName) == stem }
                    if (match != null) {
                        val txt = readUriText(lrcUri)
                        if (!txt.isNullOrBlank()) {
                            repo.saveLyrics(match.id, LyricsParser.parseLyrics(txt, "sidecar"))
                            paired++
                        }
                    }
                }
                onToast(if (paired > 0) "Paired $paired lyrics file(s)" else "No matching track for lyrics")
                return@launch
            }

            progress.replace(ImportProgressState(active = true, label = "Importing...", done = 0, total = audioUris.size))
            val existingByFp = repo.tracks.value.filter { it.fp.isNotEmpty() }.associateBy { it.fp }.toMutableMap()
            val added = ArrayList<TrackRecord>()
            var skipped = 0

            for ((idx, pair) in audioUris.withIndex()) {
                val (uri, displayName) = pair
                progress.replace(progress.value.copy(label = displayName, done = idx))
                runCatching {
                    val fileSize = queryFileSize(uri)
                    val span = SonoraUtil.fpSpan(fileSize)
                    val head = context.contentResolver.openInputStream(uri)?.use { ins ->
                        val b = ByteArray(span)
                        val r = ins.read(b)
                        if (r > 0) b.copyOf(r) else ByteArray(0)
                    } ?: ByteArray(0)
                    val fp = SonoraUtil.fingerprint(fileSize, head, head)

                    val dup = existingByFp[fp]
                    if (dup != null && dup.storage == "copy" && File(repo.mediaDir, dup.file).exists()) {
                        skipped++
                        return@runCatching
                    }

                    val key = SonoraUtil.sha1Hex("$displayName|$fileSize|${System.currentTimeMillis()}", 20)
                    val ext = SonoraUtil.extOf(displayName)
                    val destFileName = "$key.$ext"
                    val destFile = File(repo.mediaDir, destFileName)
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        FileOutputStream(destFile).use { outs -> ins.copyTo(outs, 65536) }
                    }

                    val rec = buildTrackRecord(
                        id = dup?.id ?: SonoraUtil.idFor(displayName, destFile.length(), destFile.lastModified()),
                        key = key,
                        file = destFileName,
                        storage = "copy",
                        uriStr = "",
                        treeUriStr = "",
                        fileName = displayName,
                        path = displayName,
                        size = destFile.length(),
                        lastModified = destFile.lastModified(),
                        fp = fp,
                        sourceUri = Uri.fromFile(destFile),
                        existing = dup,
                    )
                    added.add(rec)
                    existingByFp[fp] = rec

                    val stem = SonoraUtil.baseKey(displayName)
                    lyricUris[stem]?.let { lrcUri ->
                        readUriText(lrcUri)?.takeIf { it.isNotBlank() }?.let { raw ->
                            repo.saveLyrics(rec.id, LyricsParser.parseLyrics(raw, "sidecar"))
                        }
                    }
                }.onFailure {
                    skipped++
                }
            }

            if (added.isNotEmpty()) {
                repo.upsertTracks(added)
                onLibraryChanged(added)
            }
            progress.replace(ImportProgressState(active = false))
            onToast(
                when {
                    added.isNotEmpty() && skipped > 0 -> "Added ${added.size} track(s) ($skipped skipped)"
                    added.isNotEmpty() -> "Added ${added.size} track(s)"
                    else -> "No new tracks imported ($skipped skipped)"
                }
            )
        }
    }

    fun linkAndScanSafTree(treeUri: Uri) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            scanSafTreeInternal(treeUri, silent = false)
        }
    }

    fun rescanAllTrees() {
        scope.launch(Dispatchers.IO) {
            for (tree in repo.trees.value) {
                scanSafTreeInternal(Uri.parse(tree.uri), silent = false)
            }
        }
    }

    private fun scanSafTreeInternal(treeUri: Uri, silent: Boolean) {
        val treeStr = treeUri.toString()
        val treeName = treeUri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "Folder"
        if (!silent) {
            progress.replace(ImportProgressState(active = true, label = "Scanning $treeName...", done = 0, total = 0))
        }

        val audioDocs = ArrayList<SafEntry>()
        val sidecarDocs = HashMap<String, Uri>()
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        walkSaf(treeUri, rootDocId, "", audioDocs, sidecarDocs)

        if (!silent) {
            progress.replace(progress.value.copy(total = audioDocs.size))
        }

        val existingByUri = repo.tracks.value.filter { it.treeUri == treeStr }.associateBy { it.uri }
        val updated = ArrayList<TrackRecord>()
        val seenUris = HashSet<String>()

        for ((idx, doc) in audioDocs.withIndex()) {
            val uStr = doc.uri.toString()
            seenUris.add(uStr)
            if (!silent) {
                progress.replace(progress.value.copy(label = doc.name, done = idx))
            }

            val prev = existingByUri[uStr]
            if (prev != null && prev.size == doc.size && prev.lastModified == doc.lastModified) {
                continue
            }

            runCatching {
                val span = SonoraUtil.fpSpan(doc.size)
                val head = context.contentResolver.openInputStream(doc.uri)?.use { ins ->
                    val b = ByteArray(span)
                    val r = ins.read(b)
                    if (r > 0) b.copyOf(r) else ByteArray(0)
                } ?: ByteArray(0)
                val fp = SonoraUtil.fingerprint(doc.size, head, head)
                val key = SonoraUtil.sha1Hex(uStr, 20)

                val rec = buildTrackRecord(
                    id = prev?.id ?: SonoraUtil.idFor(doc.name, doc.size, doc.lastModified),
                    key = key,
                    file = "",
                    storage = "tree",
                    uriStr = uStr,
                    treeUriStr = treeStr,
                    fileName = doc.name,
                    path = "${doc.relPath}/${doc.name}".removePrefix("/"),
                    size = doc.size,
                    lastModified = doc.lastModified,
                    fp = fp,
                    sourceUri = doc.uri,
                    existing = prev,
                )
                updated.add(rec)

                val sidecarKey = "${doc.relPath}/${SonoraUtil.baseKey(doc.name)}"
                sidecarDocs[sidecarKey]?.let { lrcUri ->
                    readUriText(lrcUri)?.takeIf { it.isNotBlank() }?.let { raw ->
                        repo.saveLyrics(rec.id, LyricsParser.parseLyrics(raw, "sidecar"))
                    }
                }
            }
        }

        for (old in existingByUri.values) {
            if (!seenUris.contains(old.uri)) {
                repo.deleteTrack(old.id)
            }
        }
        if (updated.isNotEmpty()) {
            repo.upsertTracks(updated)
            onLibraryChanged(updated)
        }
        repo.upsertTree(
            TreeRecord(
                uri = treeStr,
                name = treeName,
                addedAt = repo.trees.value.firstOrNull { it.uri == treeStr }?.addedAt ?: System.currentTimeMillis(),
                lastScanAt = System.currentTimeMillis(),
                count = audioDocs.size,
            )
        )
        if (!silent) {
            progress.replace(ImportProgressState(active = false))
            onToast("Scanned $treeName (${audioDocs.size} tracks)")
        }
    }

    private data class SafEntry(
        val uri: Uri,
        val name: String,
        val size: Long,
        val lastModified: Long,
        val relPath: String,
    )

    private fun walkSaf(
        treeUri: Uri,
        parentDocId: String,
        relPath: String,
        audioOut: MutableList<SafEntry>,
        sidecarOut: MutableMap<String, Uri>,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        runCatching {
            context.contentResolver.query(childrenUri, proj, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (c.moveToNext()) {
                    val docId = c.getString(idCol) ?: continue
                    val name = c.getString(nameCol) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = c.getString(mimeCol) ?: ""
                    val size = if (c.isNull(sizeCol)) 0L else c.getLong(sizeCol)
                    val lastMod = if (c.isNull(modCol)) 0L else c.getLong(modCol)

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walkSaf(treeUri, docId, "$relPath/$name", audioOut, sidecarOut)
                    } else {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        when {
                            SonoraUtil.isAudioName(name) -> audioOut.add(SafEntry(docUri, name, size, lastMod, relPath))
                            SonoraUtil.isLyricName(name) -> sidecarOut["$relPath/${SonoraUtil.baseKey(name)}"] = docUri
                        }
                    }
                }
            }
        }
    }

    private fun buildTrackRecord(
        id: String,
        key: String,
        file: String,
        storage: String,
        uriStr: String,
        treeUriStr: String,
        fileName: String,
        path: String,
        size: Long,
        lastModified: Long,
        fp: String,
        sourceUri: Uri,
        existing: TrackRecord?,
    ): TrackRecord {
        var title = ""
        var artist = ""
        var album = ""
        var durationSec = 0.0
        var coverBytes: ByteArray? = null

        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, sourceUri)
            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim()
            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim()
            album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim()
            durationSec = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
            coverBytes = mmr.embeddedPicture
        } catch (_: Throwable) {
        } finally {
            runCatching { mmr.release() }
        }

        val parsed = runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { TagReader.readTags(it) }
        }.getOrNull()

        if (parsed != null) {
            if (title.isEmpty() && !parsed.title.isNullOrBlank()) title = parsed.title
            if (artist.isEmpty() && !parsed.artist.isNullOrBlank()) artist = parsed.artist
            if (album.isEmpty() && !parsed.album.isNullOrBlank()) album = parsed.album
            if (coverBytes == null && parsed.cover != null) coverBytes = parsed.cover
        }

        if (title.isEmpty() || artist.isEmpty()) {
            val (guessArtist, guessTitle) = SonoraUtil.titleFromName(fileName)
            if (title.isEmpty()) title = guessTitle
            if (artist.isEmpty()) artist = guessArtist.ifEmpty { "Unknown artist" }
        }
        if (album.isEmpty()) album = "Unknown album"

        var coverName = existing?.coverName.orEmpty()
        if (coverBytes != null && coverBytes.isNotEmpty()) {
            val cname = "${SonoraUtil.sha1Hex(coverBytes, 16)}.jpg"
            if (saveCoverPair(repo.root, cname, coverBytes)) {
                coverName = cname
            }
        }

        val ext = SonoraUtil.extOf(fileName)
        return TrackRecord(
            id = id,
            key = key,
            file = file,
            storage = storage,
            uri = uriStr,
            treeUri = treeUriStr,
            fp = fp,
            fileName = fileName,
            path = path,
            size = size,
            lastModified = lastModified,
            mime = SonoraUtil.playbackMime(null, ext),
            title = title,
            artist = artist,
            album = album,
            duration = durationSec,
            coverName = coverName,
            tagsDone = true,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            rgTrack = parsed?.rgTrack ?: existing?.rgTrack,
            rgAlbum = parsed?.rgAlbum ?: existing?.rgAlbum,
            embeddedLyrics = parsed?.lyrics ?: existing?.embeddedLyrics,
            edited = existing?.edited ?: false,
            playCount = existing?.playCount ?: 0,
            lastPlayedAt = existing?.lastPlayedAt ?: 0L,
        )
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    private fun queryFileSize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun readUriText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    companion object {
        fun saveCoverPair(root: File, coverName: String, imageBytes: ByteArray): Boolean {
            return runCatching {
                val coversDir = File(root, "covers").apply { mkdirs() }
                val thumbsDir = File(root, "thumbs").apply { mkdirs() }
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return false

                val decOpts = BitmapFactory.Options().apply {
                    inSampleSize = SonoraUtil.sampleSizeFor(opts.outWidth, opts.outHeight, 600)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decOpts) ?: return false
                val fullBmp = scaleDown(decoded, 600)
                val thumbBmp = scaleDown(fullBmp, 128)

                FileOutputStream(File(coversDir, coverName)).use { fullBmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                FileOutputStream(File(thumbsDir, coverName)).use { thumbBmp.compress(Bitmap.CompressFormat.JPEG, 76, it) }

                if (fullBmp !== decoded) decoded.recycle()
                if (thumbBmp !== fullBmp) thumbBmp.recycle()
                fullBmp.recycle()
                true
            }.getOrDefault(false)
        }

        private fun scaleDown(src: Bitmap, maxEdge: Int): Bitmap {
            val longest = max(src.width, src.height)
            if (longest <= maxEdge) return src
            val scale = maxEdge.toFloat() / longest.toFloat()
            val nw = max(1, (src.width * scale).roundToInt())
            val nh = max(1, (src.height * scale).roundToInt())
            return Bitmap.createScaledBitmap(src, nw, nh, true)
        }
    }
}
