package app.sonora.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.sonora.player.Engine
import app.sonora.player.Importer
import app.sonora.player.S
import app.sonora.player.fmt
import app.sonora.player.plural
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

@Composable
fun Sheet(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPal.current
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface, contentColor = p.text) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 22.dp)) {
            Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconBtn(Ic.Close, "Close", onClick = onClose)
            }
            content()
        }
    }
}

@Composable
fun SheetRow(icon: ImageVector, text: String, enabled: Boolean = true, trailing: String? = null, tint: Color = LocalPal.current.muted, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 13.dp).graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, Modifier.weight(1f), color = p.text, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) Text(trailing, color = p.faint, fontSize = 12.sp)
    }
}

/** Rendered once in App. */
@Composable
fun SheetsHost() {
    val menu by Ui.menu.collectAsState()
    val tags by Ui.tags.collectAsState()
    val picker by Ui.picker.collectAsState()
    val songPicker by Ui.songPicker.collectAsState()
    val queue by Ui.queue.collectAsState()
    val bookmarks by Ui.bookmarks.collectAsState()
    val dialog by Ui.dialog.collectAsState()
    val calibrate by Ui.calibrate.collectAsState()
    val imp by S.imp.collectAsState()
    menu?.let { if (tags == null && picker == null) TrackMenu(it.first, it.second) }
    tags?.let { TagEditor(it) }
    picker?.let { PlaylistPicker(it) }
    songPicker?.let { SongPicker(it) }
    if (queue) QueueSheet()
    if (bookmarks) BookmarksSheet()
    if (imp.phase == "running" || imp.phase == "done") ImportSheet()
    dialog?.let { AskDialog(it) }
    if (calibrate) CalibrateDialog { Ui.calibrate.value = false }
    val sleep by Ui.sleep.collectAsState()
    if (sleep) SleepSheet()
}

/** Sleep timer in two taps from the player, with a live countdown. */
@Composable
private fun SleepSheet() {
    val p = LocalPal.current
    val ps by S.player.collectAsState()
    val at = ps.sleepAt
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) {
        while (at != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val on = at != null || ps.sleepEndOfTrack || ps.sleepTracksRemaining > 0
    val status = when {
        ps.sleepEndOfTrack -> "Stops at the end of this track."
        ps.sleepTracksRemaining > 0 -> "Stops after ${plural(ps.sleepTracksRemaining, "more track")}."
        at != null -> "Stops in ${fmt(max(0.0, (at - now) / 1000.0))}. The last 20 seconds fade out."
        else -> "Off. Pick how long to keep playing."
    }
    Sheet("Sleep timer", { Ui.sleep.value = false }) {
        Text(status, color = if (on) p.accent else p.muted, fontSize = 13.5.sp, modifier = Modifier.padding(start = 2.dp, bottom = 12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 45, 60, 90).forEach { m -> Chip("$m min") { Engine.setSleep(minutes = m) } }
            Chip("End of this track", active = ps.sleepEndOfTrack) { Engine.setSleep(endOfTrack = true) }
            listOf(2, 3, 5).forEach { n -> Chip("After $n tracks", active = ps.sleepTracksRemaining == n) { Engine.setSleep(tracks = n) } }
        }
        if (on) Btn("Turn off", Modifier.padding(top = 14.dp), accent = false) { Engine.setSleep() }
    }
}

/** In-app dialogs for naming and confirming. */
@Composable
private fun AskDialog(d: DialogState) {
    val p = LocalPal.current
    var value by remember(d) { mutableStateOf(d.value ?: "") }
    val close = { Ui.dialog.value = null }
    Dialog(onDismissRequest = close) {
        Panel(pad = 20.dp) {
            Text(d.title, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = p.text)
            if (d.message != null) Text(d.message, color = p.muted, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
            if (d.value != null) Field(value, { value = it }, "Name", Modifier.fillMaxWidth().padding(top = 14.dp))
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                Btn("Cancel", accent = false, onClick = close)
                Btn(d.okLabel) {
                    close()
                    d.onOk(value)
                }
            }
        }
    }
}

@Composable
private fun TrackMenu(id: String, playlistId: String?) {
    val p = LocalPal.current
    val tracks by S.tracks.collectAsState()
    val s by S.settings.collectAsState()
    val ps by S.player.collectAsState()
    val user by S.user.collectAsState()
    val track = remember(tracks, id) { S.track(id) }
    val artworkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Importer.setArtwork(id, uri)
    }
    if (track == null) {
        LaunchedEffect(Unit) { Ui.menu.value = null }
        return
    }
    val playlist = playlistId?.let { pid -> user.playlists.firstOrNull { it.id == pid } }
    val pos = playlist?.trackIds?.indexOf(id) ?: -1
    val count = playlist?.trackIds?.size ?: 0
    val liked = s.likedIds.contains(id)
    val close = { Ui.closeSheets() }
    fun act(f: () -> Unit): () -> Unit = { close(); f() }
    Sheet(track.title, close) {
        Text(listOf(track.artist, track.album).filter { it.isNotEmpty() }.joinToString(" · "), color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        SheetRow(Ic.Next, "Play next", onClick = act { Engine.playNext(id, true); S.toast("Plays next") })
        SheetRow(Ic.Queue, "Add to queue", onClick = act { Engine.addToQueue(id); S.toast("Added to queue") })
        SheetRow(Ic.Plus, "Add to playlist…") { Ui.picker.value = listOf(id) }
        if (playlistId != null) SheetRow(Ic.Minus, "Remove from this playlist", onClick = act { S.removeFromPlaylist(playlistId, id) })
        if (playlistId != null && pos > 0) SheetRow(Ic.Up, "Move up in playlist", onClick = act { S.movePlaylistTrack(playlistId, pos, pos - 1) })
        if (playlistId != null && pos >= 0 && pos < count - 1) SheetRow(Ic.Down, "Move down in playlist", onClick = act { S.movePlaylistTrack(playlistId, pos, pos + 1) })
        SheetRow(if (liked) Ic.Heart else Ic.HeartOutline, if (liked) "Remove from Liked songs" else "Add to Liked songs", tint = if (liked) p.accent2 else p.muted, onClick = act { S.toggleLike(id) })
        if (ps.trackId == id) SheetRow(Ic.Clock, "Bookmark this moment", onClick = act { S.addBookmark(id, Engine.time); S.toast("Bookmarked ${fmt(Engine.time)}") })
        SheetRow(Ic.Image, "Set thumbnail…") { artworkPicker.launch(arrayOf("image/*")) }
        SheetRow(Ic.Edit, "Edit title, artist, album…") { Ui.tags.value = id }
        if (ps.trackId != id) SheetRow(Ic.Minus, "Remove from library", onClick = act {
            Ui.askConfirm("Remove from library", "“${track.title}” is removed from Sonora. Files in your own folders are not deleted.", "Remove") {
                S.removeTracks(setOf(id))
                Engine.requeue()
                S.toast("Removed from library")
            }
        })
    }
}

@Composable
private fun TagEditor(id: String) {
    val p = LocalPal.current
    val track = remember(id) { S.track(id) } ?: return
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    var album by remember { mutableStateOf(track.album) }
    Sheet("Edit details", { Ui.closeSheets() }) {
        for ((label, v, set) in listOf(Triple("Title", title) { x: String -> title = x }, Triple("Artist", artist) { x: String -> artist = x }, Triple("Album", album) { x: String -> album = x })) {
            Text(label, color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            Field(v, set, label, Modifier.fillMaxWidth())
        }
        Text("Saved inside Sonora only. Your audio file is never changed, and the edit survives re-importing the file.", color = p.faint, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 12.dp))
        Btn("Save", Modifier.fillMaxWidth()) {
            if (title.isBlank()) S.toast("A title is required") else {
                S.editTags(id, title, artist, album)
                S.toast("Saved")
                Ui.closeSheets()
            }
        }
    }
}

@Composable
private fun PlaylistPicker(ids: List<String>) {
    val p = LocalPal.current
    val user by S.user.collectAsState()
    var name by remember { mutableStateOf("") }
    val finish = { pid: String ->
        val n = S.addToPlaylist(pid, ids)
        S.toast(if (n > 0) "Added ${plural(n, "track")}" else "Already in that playlist")
        Ui.closeSheets()
    }
    Sheet("Add to playlist", { Ui.picker.value = null; Ui.menu.value = null }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(name, { name = it }, "New playlist name", Modifier.weight(1f))
            Btn("Create", enabled = name.isNotBlank()) { finish(S.createPlaylist(name)) }
        }
        if (user.playlists.isEmpty()) Text("No playlists yet. Name one above to start.", color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
            items(user.playlists, key = { it.id }) { pl -> SheetRow(Ic.Queue, pl.name, trailing = "${pl.trackIds.size}") { finish(pl.id) } }
        }
    }
}

private const val SONG_PICK_LIMIT = 150

/** "Add songs" for a playlist: search the library, tick songs, add them all in one go. Works on a brand-new empty playlist. */
@Composable
private fun SongPicker(playlistId: String) {
    val p = LocalPal.current
    val tracks by S.tracks.collectAsState()
    val user by S.user.collectAsState()
    val playlist = user.playlists.firstOrNull { it.id == playlistId }
    var q by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    if (playlist == null) {
        LaunchedEffect(Unit) { Ui.songPicker.value = null }
        return
    }
    val have = remember(playlist) { playlist.trackIds.toHashSet() }
    val matches = remember(tracks, q) {
        val words = q.lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) tracks else tracks.filter { t -> "${t.title} ${t.artist} ${t.album} ${t.fileName}".lowercase().let { hay -> words.all { hay.contains(it) } } }
    }
    val addable = remember(matches, have) { matches.filter { it.id !in have } }
    val done = {
        val n = if (picked.isNotEmpty()) S.addToPlaylist(playlistId, picked.toList()) else 0
        if (n > 0) S.toast("Added ${plural(n, "song")} to “${playlist.name}”")
        Ui.songPicker.value = null
    }
    Sheet("Add songs to ${playlist.name}", { Ui.songPicker.value = null }) {
        if (tracks.isEmpty()) Text("Your library is empty. Import songs first, then add them here.", color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        else {
            Field(q, { q = it }, "Search your songs", Modifier.fillMaxWidth(), leading = Ic.Search)
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Select all${if (q.isNotBlank()) " results" else ""} (${addable.size})", accent = false, enabled = addable.isNotEmpty()) { picked = picked + addable.map { it.id } }
                Btn("Clear", accent = false, enabled = picked.isNotEmpty()) { picked = emptySet() }
            }
            if (matches.isEmpty()) Text("No songs match “${q.trim()}”.", color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(matches.take(SONG_PICK_LIMIT), key = { it.id }) { t ->
                    val inList = t.id in have
                    val on = t.id in picked
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) p.accentSoft else Color.Transparent)
                            .clickable(enabled = !inList) { picked = if (on) picked - t.id else picked + t.id }.padding(horizontal = 8.dp, vertical = 9.dp).graphicsLayer { alpha = if (inList) 0.55f else 1f },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (inList || on) Ic.Check else Ic.Plus, null, tint = if (on) p.accent else p.muted, modifier = Modifier.size(19.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(t.title, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(t.artist, t.album).filter { it.isNotEmpty() }.joinToString(" · "), color = p.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (inList) Text("In playlist", color = p.faint, fontSize = 11.sp)
                    }
                }
                if (matches.size > SONG_PICK_LIMIT) item { Text("Showing the first $SONG_PICK_LIMIT of ${matches.size}. Search to narrow the list.", color = p.faint, fontSize = 12.sp, modifier = Modifier.padding(10.dp)) }
            }
        }
        Btn(if (picked.isNotEmpty()) "Add ${plural(picked.size, "song")}" else "Done", Modifier.fillMaxWidth().padding(top = 12.dp), onClick = done)
    }
}

@Composable
private fun QueueSheet() {
    val p = LocalPal.current
    val qs by S.queue.collectAsState()
    val tracks by S.tracks.collectAsState()
    val s by S.settings.collectAsState()
    var filter by remember { mutableStateOf("") }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val ds = remember { DragState() }
    ds.rowPx = with(LocalDensity.current) { 56.dp.toPx() }
    DragAutoScroll(ds, state)
    val rows = remember(qs.queue, tracks) { qs.queue.mapIndexedNotNull { pos, id -> S.track(id)?.let { pos to it } } }
    val shown = if (filter.isBlank()) rows else rows.filter { (_, t) -> "${t.title} ${t.artist} ${t.album}".lowercase().contains(filter.trim().lowercase()) }
    // Open with the current song in the comfort zone.
    LaunchedEffect(Unit) { if (qs.qpos > 2) state.scrollToItem(max(0, qs.qpos - 2)) }
    Sheet("Queue · ${qs.queue.size}", { Ui.queue.value = false }) {
        Text(if (s.shuffle) "Shuffled" else "In order", color = p.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (qs.qpos >= 0 && filter.isBlank()) Chip("Jump to playing") { scope.launch { state.animateScrollToItem(max(0, qs.qpos - 1)) } }
            Chip("Save as playlist", enabled = qs.queue.isNotEmpty()) {
                val name = "Queue · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date())}"
                S.createPlaylist(name, qs.queue)
                S.toast("Saved \"$name\"")
            }
            Chip("Clear up next", enabled = qs.qpos < qs.queue.size - 1) { Engine.clearUpNext() }
        }
        if (qs.queue.size > 5) Field(filter, { filter = it }, "Search in queue...", Modifier.fillMaxWidth().padding(top = 10.dp), leading = Ic.Search)
        LazyColumn(state = state, modifier = Modifier.heightIn(max = 430.dp).padding(top = 8.dp).onGloballyPositioned { val b = it.boundsInRoot(); ds.top = b.top; ds.bottom = b.bottom }) {
            itemsIndexed(shown, key = { _, r -> "${r.second.id}#${r.first}" }) { i, (pos, t) ->
                val current = pos == qs.qpos
                ReorderItem(ds, i, shown.size, filter.isBlank(), onMove = { from, to -> Engine.moveInQueue(shown[from].first, shown[to].first) }, handleOnly = true) { handle, dragging ->
                    Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(if (dragging) p.hover else if (current) p.accentSoft else Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                        if (filter.isBlank()) Box(handle.size(40.dp), contentAlignment = Alignment.Center) { Icon(Ic.Drag, "Move ${t.title}", tint = p.faint, modifier = Modifier.size(18.dp)) }
                        Row(Modifier.weight(1f).clickable { Engine.playAt(pos); filter = "" }.padding(start = if (filter.isBlank()) 0.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(t.thumb ?: t.cover, 38.dp, radius = 7.dp)
                            Column(Modifier.padding(horizontal = 10.dp)) {
                                Text(t.title, color = if (current) p.accent else p.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(t.artist, color = p.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (current) Icon(Ic.Wave, "Playing", tint = p.accent, modifier = Modifier.padding(horizontal = 13.dp).size(16.dp))
                        else IconBtn(Ic.Close, "Remove ${t.title} from queue", size = 16.dp, onClick = { Engine.removeFromQueue(pos) })
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarksSheet() {
    val p = LocalPal.current
    val ps by S.player.collectAsState()
    val user by S.user.collectAsState()
    val now by S.clock.collectAsState()
    val mine = user.bookmarks.filter { it.trackId == ps.trackId }.sortedBy { it.t }
    Sheet("Bookmarks", { Ui.bookmarks.value = false }) {
        Btn("Bookmark ${fmt(now)}", Modifier.fillMaxWidth(), icon = Ic.Plus, enabled = ps.trackId != null) { ps.trackId?.let { S.addBookmark(it, Engine.time) } }
        if (mine.isEmpty()) Text("No bookmarks on this track yet.", color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
        LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
            items(mine, key = { it.id }) { b ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { Engine.seek(b.t) }.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Ic.Clock, null, tint = p.accent2, modifier = Modifier.size(18.dp))
                        Text(fmt(b.t), color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, modifier = Modifier.padding(horizontal = 12.dp))
                        if (b.label.isNotEmpty()) Text(b.label, color = p.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconBtn(Ic.Edit, "Rename bookmark", size = 16.dp, onClick = { Ui.askText("Rename bookmark", b.label, "Save") { v -> S.renameBookmark(b.id, v) } })
                    IconBtn(Ic.Close, "Delete bookmark", size = 16.dp, onClick = { S.removeBookmark(b.id) })
                }
            }
        }
    }
}

/** Import progress, then the summary with anything that failed (retry / copy report). */
@Composable
private fun ImportSheet() {
    val p = LocalPal.current
    val st by S.imp.collectAsState()
    val actions = LocalActions.current
    val running = st.phase == "running"
    val title = when (st.kind) { "folder" -> "Adding folder"; "rescan" -> "Scanning folders"; "retry" -> "Retrying"; else -> "Importing" }
    val retryable = st.failed.filter { it.uri != null }
    Sheet(if (running) title else "Done", { Importer.closeSheet() }) {
        if (running) {
            val pct = if (st.total > 0) st.done.toFloat() / st.total else 0f
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(p.border)) { Box(Modifier.fillMaxWidth(pct.coerceIn(0f, 1f)).height(6.dp).background(p.accent)) }
            Text((if (st.total > 0) "${minOf(st.done + 1, st.total)} of ${st.total}" else "Working…") + (if (st.name.isNotEmpty()) " · ${st.name}" else ""), color = p.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            Text("You can close this: it keeps going in the background.", color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            Btn("Cancel", accent = false) { Importer.cancelImport() }
        } else {
            Text(st.summary, color = p.text, fontSize = 14.sp)
            if (st.failed.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 260.dp).padding(top = 10.dp)) {
                items(st.failed.take(40)) { f ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Text(f.name, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(f.reason, color = p.muted, fontSize = 12.sp)
                    }
                }
                if (st.failed.size > 40) item { Text("…and ${st.failed.size - 40} more (see the report)", color = p.faint, fontSize = 12.sp) }
            }
            FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (retryable.isNotEmpty()) Btn("Retry ${retryable.size}") { Importer.retryFailed() }
                if (st.failed.isNotEmpty()) Btn("Copy error report", accent = false) { actions.copy(Importer.errorReport(), "Error report copied") }
                Btn("Close", accent = st.failed.isEmpty()) { Importer.closeSheet() }
            }
        }
    }
}
