// Sonora Native — SheetsHost.kt
// Modal bottom sheets and dialogs: TrackMenuSheet, TagEditorSheet, PlaylistPickerSheet,
// SongPickerSheet, QueueSheet, BookmarksSheet, ImportProgressSheet, AskDialogModal.

package app.sonora.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonora.player.Graph
import app.sonora.player.audio.PlayerController
import app.sonora.player.data.TrackAudioConfig
import app.sonora.player.data.TrackRecord
import app.sonora.player.data.fmtSec
import app.sonora.player.library.ImportProgressState
import app.sonora.player.library.LibraryRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AskDialogSpec(
    val title: String,
    val initialValue: String = "",
    val placeholder: String = "",
    val confirmLabel: String = "Save",
    val isConfirmOnly: Boolean = false,
    val message: String? = null,
    val onConfirm: (String) -> Unit,
)

class SheetsStore {
    private val _trackMenuId = MutableStateFlow<String?>(null)
    val trackMenuId: StateFlow<String?> = _trackMenuId.asStateFlow()

    private val _tagEditorId = MutableStateFlow<String?>(null)
    val tagEditorId: StateFlow<String?> = _tagEditorId.asStateFlow()

    private val _playlistPickerTrackId = MutableStateFlow<String?>(null)
    val playlistPickerTrackId: StateFlow<String?> = _playlistPickerTrackId.asStateFlow()

    private val _songPickerPlaylistId = MutableStateFlow<String?>(null)
    val songPickerPlaylistId: StateFlow<String?> = _songPickerPlaylistId.asStateFlow()

    private val _showQueue = MutableStateFlow(false)
    val showQueue: StateFlow<Boolean> = _showQueue.asStateFlow()

    private val _bookmarksTrackId = MutableStateFlow<String?>(null)
    val bookmarksTrackId: StateFlow<String?> = _bookmarksTrackId.asStateFlow()

    private val _showLyricsStudioForTrackId = MutableStateFlow<String?>(null)
    val showLyricsStudioForTrackId: StateFlow<String?> = _showLyricsStudioForTrackId.asStateFlow()

    private val _askDialog = MutableStateFlow<AskDialogSpec?>(null)
    val askDialog: StateFlow<AskDialogSpec?> = _askDialog.asStateFlow()

    fun openTrackMenu(trackId: String) { _trackMenuId.value = trackId }
    fun closeTrackMenu() { _trackMenuId.value = null }

    fun openTagEditor(trackId: String) { _tagEditorId.value = trackId }
    fun closeTagEditor() { _tagEditorId.value = null }

    fun openPlaylistPicker(trackId: String) { _playlistPickerTrackId.value = trackId }
    fun closePlaylistPicker() { _playlistPickerTrackId.value = null }

    fun openSongPicker(playlistId: String) { _songPickerPlaylistId.value = playlistId }
    fun closeSongPicker() { _songPickerPlaylistId.value = null }

    fun openQueue() { _showQueue.value = true }
    fun closeQueue() { _showQueue.value = false }

    fun openBookmarks(trackId: String) { _bookmarksTrackId.value = trackId }
    fun closeBookmarks() { _bookmarksTrackId.value = null }

    fun openLyricsStudio(trackId: String) { _showLyricsStudioForTrackId.value = trackId }
    fun closeLyricsStudio() { _showLyricsStudioForTrackId.value = null }

    fun askPrompt(
        title: String,
        initialValue: String = "",
        placeholder: String = "",
        confirmLabel: String = "Save",
        onConfirm: (String) -> Unit,
    ) {
        _askDialog.value = AskDialogSpec(
            title = title,
            initialValue = initialValue,
            placeholder = placeholder,
            confirmLabel = confirmLabel,
            isConfirmOnly = false,
            onConfirm = onConfirm,
        )
    }

    fun askConfirm(
        title: String,
        message: String,
        confirmLabel: String = "Delete",
        onConfirm: () -> Unit,
    ) {
        _askDialog.value = AskDialogSpec(
            title = title,
            message = message,
            confirmLabel = confirmLabel,
            isConfirmOnly = true,
            onConfirm = { onConfirm() },
        )
    }

    fun closeAskDialog() { _askDialog.value = null }
}

@Composable
fun SheetsHost(
    sheets: SheetsStore,
    repo: LibraryRepo,
    player: PlayerController,
    importProgress: ImportProgressState,
    onPickCoverForTrack: (String) -> Unit,
    onShareTrack: (TrackRecord) -> Unit,
    onToast: (String) -> Unit,
) {
    val theme = LocalTheme.current
    val tracks by repo.tracks.flow.collectAsState()
    val userData by repo.userData.flow.collectAsState()
    val settings by Graph.settings.flow.collectAsState()
    val queueState by player.queueState.flow.collectAsState()
    val posMs by player.positionMs.collectAsState()

    val trackMenuId by sheets.trackMenuId.collectAsState()
    val tagEditorId by sheets.tagEditorId.collectAsState()
    val playlistPickerTrackId by sheets.playlistPickerTrackId.collectAsState()
    val songPickerPlaylistId by sheets.songPickerPlaylistId.collectAsState()
    val showQueue by sheets.showQueue.collectAsState()
    val bookmarksTrackId by sheets.bookmarksTrackId.collectAsState()
    val askDialog by sheets.askDialog.collectAsState()

    // 1. Track Context Menu Sheet
    val menuTrack = remember(trackMenuId, tracks) { repo.trackById(trackMenuId) }
    if (menuTrack != null) {
        val isFav = settings.likedIds.contains(menuTrack.id)
        BottomSheetOverlay(onDismiss = { sheets.closeTrackMenu() }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                CoverArt(track = menuTrack, repo = repo, size = 48.dp, small = true)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    SonoraText(menuTrack.title.ifEmpty { menuTrack.fileName }, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SonoraText("${menuTrack.artist} • ${menuTrack.album}", color = theme.textSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(12.dp))
            SheetActionRow(SonoraIcons.SkipFwd, "Play Next") {
                val idx = repo.indexOfTrackId(menuTrack.id)
                if (idx >= 0) player.playNext(idx)
                sheets.closeTrackMenu()
                onToast("Playing next: ${menuTrack.title}")
            }
            SheetActionRow(SonoraIcons.Queue, "Add to Queue") {
                val idx = repo.indexOfTrackId(menuTrack.id)
                if (idx >= 0) player.addToQueue(idx)
                sheets.closeTrackMenu()
                onToast("Added to queue")
            }
            SheetActionRow(SonoraIcons.Plus, "Add to Playlist...") {
                val id = menuTrack.id
                sheets.closeTrackMenu()
                sheets.openPlaylistPicker(id)
            }
            SheetActionRow(
                if (isFav) SonoraIcons.HeartFill else SonoraIcons.Heart,
                if (isFav) "Remove from Favorites" else "Add to Favorites",
            ) {
                Graph.toggleLikeTrack(menuTrack.id)
                sheets.closeTrackMenu()
            }
            SheetActionRow(SonoraIcons.Lyrics, "Lyrics Studio...") {
                val id = menuTrack.id
                sheets.closeTrackMenu()
                sheets.openLyricsStudio(id)
            }
            SheetActionRow(SonoraIcons.Edit, "Edit Tags & Track Audio...") {
                val id = menuTrack.id
                sheets.closeTrackMenu()
                sheets.openTagEditor(id)
            }
            SheetActionRow(SonoraIcons.Bookmark, "Bookmarks...") {
                val id = menuTrack.id
                sheets.closeTrackMenu()
                sheets.openBookmarks(id)
            }
            SheetActionRow(SonoraIcons.Share, "Share Audio File") {
                sheets.closeTrackMenu()
                onShareTrack(menuTrack)
            }
            SheetActionRow(SonoraIcons.Trash, "Remove from Library", danger = true) {
                val id = menuTrack.id
                val title = menuTrack.title.ifEmpty { menuTrack.fileName }
                sheets.closeTrackMenu()
                sheets.askConfirm("Remove Track", "Remove \"$title\" from your Sonora library?") {
                    repo.deleteTrack(id)
                    onToast("Removed $title")
                }
            }
        }
    }

    // 2. Tag & Per-Track Audio Editor Sheet
    val editTrack = remember(tagEditorId, tracks) { repo.trackById(tagEditorId) }
    if (editTrack != null) {
        var title by remember(editTrack.id) { mutableStateOf(editTrack.title) }
        var artist by remember(editTrack.id) { mutableStateOf(editTrack.artist) }
        var album by remember(editTrack.id) { mutableStateOf(editTrack.album) }

        val existingCfg = repo.getTrackAudioConfig(editTrack.id)
        var usePerTrackAudio by remember(editTrack.id) { mutableStateOf(existingCfg != null) }
        var trkBoost by remember(editTrack.id) { mutableStateOf((existingCfg?.boostDb ?: 0.0).toFloat()) }
        var trkBass by remember(editTrack.id) { mutableStateOf((existingCfg?.bassDb ?: 0.0).toFloat()) }
        var trkMids by remember(editTrack.id) { mutableStateOf((existingCfg?.midsDb ?: 0.0).toFloat()) }
        var trkTreble by remember(editTrack.id) { mutableStateOf((existingCfg?.trebleDb ?: 0.0).toFloat()) }
        var trkSpeed by remember(editTrack.id) { mutableStateOf((existingCfg?.speed ?: 1.0).toFloat()) }

        BottomSheetOverlay(onDismiss = { sheets.closeTagEditor() }) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SonoraText("Edit Track Info", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, fontFamily = SonoraFonts.Manrope)
                    SonoraButton("Change Cover", icon = SonoraIcons.Image, compact = true, onClick = {
                        onPickCoverForTrack(editTrack.id)
                    })
                }
                Spacer(Modifier.height(12.dp))
                SonoraTextField("Title", title) { title = it }
                Spacer(Modifier.height(8.dp))
                SonoraTextField("Artist", artist) { artist = it }
                Spacer(Modifier.height(8.dp))
                SonoraTextField("Album", album) { album = it }

                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SectionKicker("Per-Track Audio Override")
                    SonoraSwitch(checked = usePerTrackAudio, onCheckedChange = { usePerTrackAudio = it })
                }
                if (usePerTrackAudio) {
                    Spacer(Modifier.height(8.dp))
                    LabeledSlider("Boost (${String.format("%.1f", trkBoost)} dB)", trkBoost, 0f..12f) { trkBoost = it }
                    LabeledSlider("Bass (${String.format("%.1f", trkBass)} dB)", trkBass, -12f..12f) { trkBass = it }
                    LabeledSlider("Mids (${String.format("%.1f", trkMids)} dB)", trkMids, -12f..12f) { trkMids = it }
                    LabeledSlider("Treble (${String.format("%.1f", trkTreble)} dB)", trkTreble, -12f..12f) { trkTreble = it }
                    LabeledSlider("Speed (${String.format("%.2fx", trkSpeed)})", trkSpeed, 0.5f..2.0f) { trkSpeed = it }
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    SonoraButton("Cancel", onClick = { sheets.closeTagEditor() }, modifier = Modifier.weight(1f))
                    SonoraButton("Save Changes", primary = true, onClick = {
                        repo.updateTrack(editTrack.id) {
                            it.copy(
                                title = title.trim().ifEmpty { it.title },
                                artist = artist.trim().ifEmpty { it.artist },
                                album = album.trim().ifEmpty { it.album },
                                edited = true,
                            )
                        }
                        repo.setTrackAudioConfig(
                            editTrack.id,
                            if (usePerTrackAudio) TrackAudioConfig(
                                boostDb = trkBoost.toDouble(),
                                bassDb = trkBass.toDouble(),
                                midsDb = trkMids.toDouble(),
                                trebleDb = trkTreble.toDouble(),
                                speed = trkSpeed.toDouble(),
                            ) else null,
                        )
                        sheets.closeTagEditor()
                        onToast("Saved track info")
                    }, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    // 3. Playlist Picker Sheet
    if (playlistPickerTrackId != null) {
        val tid = playlistPickerTrackId!!
        BottomSheetOverlay(onDismiss = { sheets.closePlaylistPicker() }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SonoraText("Add to Playlist", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, fontFamily = SonoraFonts.Manrope)
                SonoraButton("New Playlist", icon = SonoraIcons.Plus, compact = true, onClick = {
                    sheets.askPrompt("New Playlist Name", placeholder = "My Favorites") { name ->
                        val pl = repo.createPlaylist(name)
                        repo.addTrackToPlaylist(pl.id, tid)
                        sheets.closePlaylistPicker()
                        onToast("Added to ${pl.name}")
                    }
                })
            }
            Spacer(Modifier.height(10.dp))
            if (userData.playlists.isEmpty()) {
                SonoraText("No playlists yet. Tap 'New Playlist' above.", color = theme.textMuted)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(userData.playlists, key = { it.id }) { pl ->
                        SheetActionRow(SonoraIcons.Playlist, "${pl.name} (${pl.trackIds.size} tracks)") {
                            repo.addTrackToPlaylist(pl.id, tid)
                            sheets.closePlaylistPicker()
                            onToast("Added to ${pl.name}")
                        }
                    }
                }
            }
        }
    }

    // 4. Multi-Select Song Picker Sheet for a Playlist
    if (songPickerPlaylistId != null) {
        val pid = songPickerPlaylistId!!
        val playlist = userData.playlists.firstOrNull { it.id == pid }
        var filterQuery by remember(pid) { mutableStateOf("") }
        if (playlist != null) {
            BottomSheetOverlay(onDismiss = { sheets.closeSongPicker() }) {
                SonoraText("Add Songs to ${playlist.name}", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, fontFamily = SonoraFonts.Manrope)
                Spacer(Modifier.height(8.dp))
                SonoraTextField("Filter songs...", filterQuery) { filterQuery = it }
                Spacer(Modifier.height(8.dp))
                val filtered = remember(tracks, filterQuery) {
                    val q = filterQuery.trim().lowercase()
                    if (q.isEmpty()) tracks else tracks.filter {
                        it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
                    }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.id }) { t ->
                        val inPl = t.id in playlist.trackIds
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (inPl) repo.removeTrackFromPlaylist(pid, t.id)
                                    else repo.addTrackToPlaylist(pid, t.id)
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            CoverArt(track = t, repo = repo, size = 38.dp, small = true)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                SonoraText(t.title.ifEmpty { t.fileName }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                SonoraText(t.artist, color = theme.textSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            SonoraIcon(
                                spec = if (inPl) SonoraIcons.Check else SonoraIcons.Plus,
                                tint = if (inPl) theme.accent else theme.textMuted,
                                size = 18.dp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SonoraButton("Done", primary = true, onClick = { sheets.closeSongPicker() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    // 5. Up Next Queue Sheet
    if (showQueue) {
        BottomSheetOverlay(onDismiss = { sheets.closeQueue() }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SonoraText("Up Next (${queueState.queue.size})", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, fontFamily = SonoraFonts.Manrope)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SonoraButton("Save Playlist", compact = true, onClick = {
                        sheets.askPrompt("Playlist Name", initialValue = "Queue Mix") { name ->
                            val pl = repo.createPlaylist(name)
                            for (tIdx in queueState.queue) {
                                val t = tracks.getOrNull(tIdx) ?: continue
                                repo.addTrackToPlaylist(pl.id, t.id)
                            }
                            onToast("Saved ${queueState.queue.size} tracks to ${pl.name}")
                        }
                    })
                    SonoraButton("Clear Up Next", danger = true, compact = true, onClick = {
                        player.clearUpNext()
                    })
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                itemsIndexed(queueState.queue) { qIdx, trackIdx ->
                    val t = tracks.getOrNull(trackIdx) ?: return@itemsIndexed
                    val isCurrent = qIdx == queueState.qpos
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isCurrent) theme.accentSoft else Color.Transparent)
                            .clickable { player.playAtQueuePosition(qIdx) }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                    ) {
                        SonoraText(
                            text = "${qIdx + 1}",
                            color = if (isCurrent) theme.accent else theme.textMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(26.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            SonoraText(
                                text = t.title.ifEmpty { t.fileName },
                                color = if (isCurrent) theme.accent else theme.text,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            SonoraText(t.artist, color = theme.textSub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (qIdx > 0) {
                            Box(Modifier.clickable { player.moveInQueue(qIdx, qIdx - 1) }.padding(6.dp)) {
                                SonoraIcon(SonoraIcons.ChevDown, tint = theme.textMuted, size = 14.dp)
                            }
                        }
                        if (!isCurrent) {
                            Box(Modifier.clickable { player.removeFromQueue(qIdx) }.padding(6.dp)) {
                                SonoraIcon(SonoraIcons.Close, tint = theme.textMuted, size = 15.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    // 6. Bookmarks Sheet
    if (bookmarksTrackId != null) {
        val tid = bookmarksTrackId!!
        val bms = remember(userData.bookmarks, tid) { repo.bookmarksFor(tid) }
        val posSec = posMs / 1000.0
        BottomSheetOverlay(onDismiss = { sheets.closeBookmarks() }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SonoraText("Bookmarks (${bms.size})", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, fontFamily = SonoraFonts.Manrope)
                SonoraButton("Add at ${fmtSec(posSec)}", icon = SonoraIcons.Bookmark, primary = true, compact = true, onClick = {
                    val p = posSec
                    sheets.askPrompt("Bookmark Label", initialValue = "Pin at ${fmtSec(p)}") { label ->
                        repo.addBookmark(tid, p, label)
                        onToast("Added bookmark at ${fmtSec(p)}")
                    }
                })
            }
            Spacer(Modifier.height(10.dp))
            if (bms.isEmpty()) {
                SonoraText("No bookmarks for this track yet.", color = theme.textMuted)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(bms, key = { it.id }) { bm ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    player.seekSeconds(bm.t)
                                    sheets.closeBookmarks()
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            SonoraIcon(SonoraIcons.Bookmark, tint = theme.accent, size = 16.dp)
                            Spacer(Modifier.width(10.dp))
                            SonoraText(fmtSec(bm.t), color = theme.accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))
                            SonoraText(bm.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.clickable { repo.removeBookmark(bm.id) }.padding(6.dp)) {
                                SonoraIcon(SonoraIcons.Trash, tint = theme.danger, size = 15.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    // 7. Import Progress Sheet
    if (importProgress.active) {
        BottomSheetOverlay(onDismiss = {}) {
            SonoraText("Importing Audio Library", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, fontFamily = SonoraFonts.Manrope)
            Spacer(Modifier.height(6.dp))
            SonoraText(importProgress.label, color = theme.textSub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            val pct = if (importProgress.total > 0) (importProgress.done.toFloat() / importProgress.total.toFloat()).coerceIn(0f, 1f) else 0.5f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(theme.bgHover),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(pct)
                        .fillMaxHeight()
                        .background(theme.accent),
                )
            }
            Spacer(Modifier.height(6.dp))
            SonoraText("${importProgress.done} / ${importProgress.total} processed", color = theme.textMuted, fontSize = 12.sp)
        }
    }

    // 8. Custom Ask / Confirm Modal
    val dialogSpec = askDialog
    if (dialogSpec != null) {
        var textValue by remember(dialogSpec) { mutableStateOf(dialogSpec.initialValue) }
        BottomSheetOverlay(onDismiss = { sheets.closeAskDialog() }) {
            SonoraText(dialogSpec.title, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, fontFamily = SonoraFonts.Manrope)
            Spacer(Modifier.height(10.dp))
            if (dialogSpec.isConfirmOnly) {
                SonoraText(dialogSpec.message.orEmpty(), color = theme.textSub, fontSize = 14.sp)
            } else {
                SonoraTextField(dialogSpec.placeholder, textValue) { textValue = it }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SonoraButton("Cancel", onClick = { sheets.closeAskDialog() }, modifier = Modifier.weight(1f))
                SonoraButton(
                    text = dialogSpec.confirmLabel,
                    primary = !dialogSpec.isConfirmOnly,
                    danger = dialogSpec.isConfirmOnly,
                    onClick = {
                        val v = textValue
                        sheets.closeAskDialog()
                        dialogSpec.onConfirm(v)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun BottomSheetOverlay(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = LocalTheme.current
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(theme.bgSurface)
                .border(1.dp, theme.borderStrong, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .clickable(enabled = false) {}
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .navigationBarsPadding(),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(theme.borderStrong),
            )
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: IconSpec,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val theme = LocalTheme.current
    val fg = if (danger) theme.danger else theme.text
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
    ) {
        SonoraIcon(icon, tint = if (danger) theme.danger else theme.accent, size = 19.dp)
        Spacer(Modifier.width(12.dp))
        SonoraText(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SonoraTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    val theme = LocalTheme.current
    Column(modifier = modifier) {
        if (label.isNotEmpty()) {
            SonoraText(label, color = theme.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(
                color = theme.text,
                fontSize = 14.sp,
                fontFamily = SonoraFonts.DmSans,
            ),
            cursorBrush = SolidColor(theme.accent),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(theme.bgElevated)
                .border(1.dp, theme.borderStrong, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val theme = LocalTheme.current
    Column(modifier = Modifier.fillMaxWidth()) {
        SonoraText(label, color = theme.textSub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        SonoraSlider(value = value, valueRange = range, onValueChange = onChange)
    }
}
