package app.sonora.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonora.player.Engine
import app.sonora.player.S
import app.sonora.player.buildGroups
import app.sonora.player.fuzzyLower
import app.sonora.player.fuzzyMatch
import app.sonora.player.hasLyrics
import app.sonora.player.playlistToM3U
import app.sonora.player.plural
import app.sonora.player.sortTracks
import kotlinx.coroutines.flow.update

private val SORTS = listOf("title" to "Title", "artist" to "Artist", "album" to "Album", "recent" to "Recently added", "played" to "Most played", "duration" to "Longest", "manual" to "Manual (drag to reorder)")
private val FILTERS = listOf("all" to "All", "liked" to "Liked", "mostPlayed" to "Most played", "rediscover" to "Rediscover", "withLyrics" to "Lyrics")
private val MODES = listOf("songs" to "Songs", "albums" to "Albums", "artists" to "Artists", "folders" to "Folders", "playlists" to "Playlists")

@Composable
fun LibraryScreen() {
    val p = LocalPal.current
    val actions = LocalActions.current
    val tracks by S.tracks.collectAsState()
    val s by S.settings.collectAsState()
    val user by S.user.collectAsState()
    val lyr by S.lyrics.collectAsState()
    val ps by S.player.collectAsState()
    val filter by S.filter.collectAsState()
    val imp by S.imp.collectAsState()
    var mode by rememberSaveable { mutableStateOf("songs") }
    var q by rememberSaveable { mutableStateOf("") }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val liked = remember(s.likedIds) { s.likedIds.toHashSet() }
    val sort = s.librarySort
    BackHandler(enabled = openKey != null) { openKey = null }

    val groups = remember(mode, tracks, user.playlists, sort) { if (mode == "songs") emptyList() else buildGroups(mode, tracks, user.playlists, sort) }
    val group = openKey?.let { k -> groups.firstOrNull { it.key == k } }
    // Lower-cased search text per song, built on first use and reused for every keystroke.
    val hay = remember(tracks, lyr) { HashMap<String, String>() }
    val list = remember(tracks, group, q, filter, liked, sort, lyr, user.libraryOrder) {
        val now = System.currentTimeMillis()
        val ql = q.trim().lowercase()
        val kept = (group?.tracks ?: tracks).filter { t ->
            when (filter) {
                "liked" -> if (t.id !in liked) return@filter false
                "mostPlayed" -> if (t.playCount < 1) return@filter false
                "rediscover" -> if (t.playCount > 0 && now - t.lastPlayedAt < 7 * 86400000L) return@filter false
                "withLyrics" -> if (!hasLyrics(t, lyr)) return@filter false
            }
            if (ql.isEmpty()) true
            else fuzzyLower(hay.getOrPut(t.id) { "${t.title} ${t.artist} ${t.album} ${t.fileName} ${lyr[t.id]?.lines?.joinToString(" ") { it.text } ?: t.embeddedLyrics.orEmpty()}".lowercase() }, ql)
        }
        if (group?.kind == "playlist") kept
        else sortTracks(kept, if (filter == "mostPlayed") "played" else if (group?.kind == "album") "album" else sort, user.libraryOrder)
    }
    val ids = remember(list) { list.map { it.id } }
    val shownGroups = remember(groups, q) { if (q.isBlank()) groups else groups.filter { fuzzyMatch("${it.title} ${it.sub}", q) } }
    // Playlist order is stored in the playlist; the all-songs order is saved separately by track id.
    val reorderPlaylist = group?.kind == "playlist" && filter == "all" && q.isBlank()
    val reorderLibrary = group == null && mode == "songs" && filter == "all" && q.isBlank()
    val ds = remember { DragState() }
    ds.rowPx = with(LocalDensity.current) { 66.dp.toPx() }
    val state = rememberLazyListState()
    DragAutoScroll(ds, state)
    val subtitle = if (group != null) plural(list.size, "track") else if (mode == "songs") plural(tracks.size, "track") else "${shownGroups.size} $mode"
    val pid = group?.playlistId

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            modifier = Modifier.fillMaxSize().onGloballyPositioned { val b = it.boundsInRoot(); ds.top = b.top; ds.bottom = b.bottom },
        ) {
            item("head") {
                Column(Modifier.padding(start = 6.dp, top = 6.dp)) {
                    Eyebrow("LIBRARY")
                    Text(group?.title ?: "Library", fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, color = p.text, letterSpacing = (-0.8).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (group != null && group.sub.isNotEmpty() && group.kind != "playlist") group.sub + " · " else "") + subtitle + (if (imp.phase == "running" || imp.phase == "hidden") " · Loading…" else ""),
                        color = p.muted, fontSize = 13.sp,
                    )
                }
            }
            item("actions") {
                if (group != null) {
                    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("Back", icon = Ic.Back, accent = false) { openKey = null }
                        Btn("Play", icon = Ic.Play, enabled = ids.isNotEmpty()) { Engine.playIds(ids, 0, false) }
                        Btn("Shuffle", icon = Ic.Shuffle, accent = false, enabled = ids.isNotEmpty()) { Engine.playShuffled(ids) }
                        Btn("Play next", icon = Ic.Next, accent = false, enabled = ids.isNotEmpty()) { ids.forEach { Engine.playNext(it, true) }; S.toast("Plays next") }
                        Btn("Add to queue", icon = Ic.Queue, accent = false, enabled = ids.isNotEmpty()) { Engine.enqueueMany(ids); S.toast("Added to queue") }
                        if (pid != null) {
                            Btn("Add songs", icon = Ic.Plus) { Ui.songPicker.value = pid }
                            if (ids.isNotEmpty()) Btn("Copy to another playlist", icon = Ic.Plus, accent = false) { Ui.picker.value = ids }
                            Btn("Export M3U", icon = Ic.Download, accent = false) {
                                user.playlists.firstOrNull { it.id == pid }?.let { actions.saveText("${it.name}.m3u8", playlistToM3U(it, tracks)) }
                            }
                            Btn("Rename", accent = false) { Ui.askText("Rename playlist", group.title, "Rename") { n -> if (n.isNotBlank()) S.renamePlaylist(pid, n) } }
                            Btn("Delete", accent = false) {
                                Ui.askConfirm("Delete playlist", "“${group.title}” is deleted. The songs stay in your library.", "Delete") {
                                    S.deletePlaylist(pid)
                                    openKey = null
                                }
                            }
                        } else Btn("Add to playlist", icon = Ic.Plus, accent = false, enabled = ids.isNotEmpty()) { Ui.picker.value = ids }
                    }
                } else {
                    Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()).clip(RoundedCornerShape(50)).background(p.input).padding(3.dp)) {
                        MODES.forEach { (id, label) ->
                            Text(
                                label, color = if (mode == id) p.accentInk else p.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (mode == id) p.accent else androidx.compose.ui.graphics.Color.Transparent).clickable { mode = id; q = "" }.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            item("tools") {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (mode == "songs" && group == null && tracks.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("Play", icon = Ic.Play, enabled = ids.isNotEmpty()) { Engine.playIds(ids, 0, false) }
                        Btn("Shuffle", icon = Ic.Shuffle, accent = false, enabled = ids.isNotEmpty()) { Engine.playShuffled(ids) }
                    }
                    Field(q, { q = it }, if (mode == "songs" || group != null) "Search title, artist, album, or lyrics..." else "Search $mode...", Modifier.fillMaxWidth(), leading = Ic.Search)
                    if (mode == "songs" || group != null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FILTERS.forEach { (id, label) -> Chip(if (id == "liked") "Liked (${s.likedIds.size})" else label, active = filter == id) { S.filter.value = id } }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (group?.kind != "playlist") Select(sort, SORTS) { v -> S.set { it.copy(librarySort = v) } }
                        if (mode == "songs" && group == null) {
                            Btn("Add music", icon = Ic.Plus) { actions.addFiles() }
                            Btn("Add folder", icon = Ic.Folder, accent = false) { actions.addFolder() }
                        }
                        if (mode == "playlists" && group == null) {
                            Btn("New playlist", icon = Ic.Plus) {
                                Ui.askText("New playlist", "", "Create") { name ->
                                    if (name.isNotBlank()) {
                                        val id = S.createPlaylist(name)
                                        S.toast("Created “${name.trim()}”")
                                        Ui.songPicker.value = id // a new playlist opens the song picker straight away
                                    }
                                }
                            }
                            Btn("Import M3U", icon = Ic.Upload, accent = false) {
                                actions.pickText { name, text ->
                                    val (n, matched) = S.importM3U(text, name)
                                    S.toast("Imported \"$n\" ($matched matched)")
                                }
                            }
                        }
                    }
                }
            }
            if (mode == "songs" || group != null) {
                item("cols") {
                    Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("SONG", color = p.faint, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            Text("DURATION", color = p.faint, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        }
                        if ((reorderPlaylist || reorderLibrary) && list.size > 1) Text("Press and hold a song, then drag to reorder ${if (reorderPlaylist) "this playlist" else "your library"}.", color = p.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                if (list.isEmpty()) item("empty") {
                    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when {
                                filter == "liked" -> "No liked songs yet."
                                filter == "mostPlayed" -> "No play counts recorded yet. Listen to a song for 30s to count a play."
                                filter == "withLyrics" -> "No tracks with lyrics yet."
                                pid != null -> "This playlist is empty. Tap “Add songs” above to fill it."
                                group != null -> "Nothing here yet."
                                else -> "No songs yet. Add music to your library."
                            },
                            color = p.muted, fontSize = 13.5.sp,
                        )
                        if (pid != null) Btn("Add songs to playlist", Modifier.padding(top = 12.dp), icon = Ic.Plus) { Ui.songPicker.value = pid }
                    }
                }
                itemsIndexed(list, key = { _, t -> t.id }) { i, t ->
                    ReorderItem(ds, i, list.size, reorderPlaylist || reorderLibrary, onMove = { from, to ->
                        if (reorderPlaylist && pid != null) {
                            val pl = user.playlists.firstOrNull { it.id == pid }
                            val a = pl?.trackIds?.indexOf(ids[from]) ?: -1
                            val b = pl?.trackIds?.indexOf(ids[to]) ?: -1
                            if (a >= 0 && b >= 0) S.movePlaylistTrack(pid, a, b)
                        } else {
                            val next = ids.toMutableList().apply { add(to, removeAt(from)) }
                            S.user.update { it.copy(libraryOrder = next) }
                            S.set { it.copy(librarySort = "manual") }
                            S.toast("Manual song order saved")
                        }
                    }) { _, dragging ->
                        val isCur = t.id == ps.trackId
                        TrackRow(t, isCur, ps.playing, t.id in liked, hasLyrics(t, lyr), dragging,
                            onClick = { if (ds.from < 0) { if (isCur) Engine.toggle() else Engine.playFromList(ids, t.id) } },
                            onLike = { S.toggleLike(t.id) }, onMore = { Ui.menu.value = t.id to pid })
                    }
                }
            } else {
                if (shownGroups.isEmpty()) item("noGroups") {
                    Text(
                        if (mode == "playlists") "No playlists yet. Make one, then add songs with “Add songs”, or from any song’s ⋯ menu." else "Nothing to show yet.",
                        color = p.muted, fontSize = 13.5.sp, modifier = Modifier.padding(24.dp),
                    )
                }
                items(shownGroups, key = { it.kind + it.key }) { g ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).clickable { openKey = g.key; q = "" }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(g.cover, 56.dp, radius = if (g.kind == "artist") 28.dp else 10.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(g.title.ifEmpty { "Unknown" }, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(g.sub, if (g.kind != "artist" && g.kind != "playlist") "${g.tracks.size} tracks" else "").filter { it.isNotEmpty() }.joinToString(" · "), color = p.muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        androidx.compose.material3.Icon(Ic.Right, null, tint = p.faint)
                    }
                }
            }
            item("foot") {
                Text(
                    "Reads ID3v2, FLAC, Ogg and MP4 tags (embedded cover art, ReplayGain, and lyrics), separates featured artists, and automatically pairs sidecar .lrc / .srt / .vtt files from your folder.",
                    color = p.faint, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 18.dp),
                )
            }
        }
        FastScrollbar(state, Modifier.align(Alignment.CenterEnd))
    }
}
