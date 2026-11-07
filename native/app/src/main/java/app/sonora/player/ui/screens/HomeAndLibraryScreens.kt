// Sonora Native — HomeAndLibraryScreens.kt
// Home tab (Continue Listening, Quick Stats, Recently Added, Most Played, Favorites)
// and Library tab (Tracks, Albums, Artists, Playlists, Folders + Search + Sort + Grid/List).

package app.sonora.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonora.player.Graph
import app.sonora.player.audio.PlayerController
import app.sonora.player.data.Playlist
import app.sonora.player.data.TrackRecord
import app.sonora.player.data.fmtSec
import app.sonora.player.library.LibraryImporter
import app.sonora.player.library.LibraryRepo
import app.sonora.player.library.SonoraUtil
import app.sonora.player.ui.*

@Composable
fun TrackRowItem(
    track: TrackRecord,
    repo: LibraryRepo,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFav: Boolean,
    onPlay: () -> Unit,
    onToggleFav: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalTheme.current
    val ext = SonoraUtil.extOf(track.fileName).uppercase()
    val isLossless = ext == "FLAC" || ext == "WAV" || ext == "ALAC"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent) theme.accentSoft else theme.bgSurface.copy(alpha = 0.55f))
            .border(1.dp, if (isCurrent) theme.accent else theme.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onPlay)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        CoverArt(track = track, repo = repo, size = 44.dp, small = true, isPlaying = isCurrent && isPlaying)
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SonoraText(
                    text = track.title.ifEmpty { track.fileName },
                    color = if (isCurrent) theme.accent else theme.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SonoraFonts.Manrope,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isLossless) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(theme.warn.copy(alpha = 0.2f))
                            .border(1.dp, theme.warn, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        SonoraText(ext, color = theme.warn, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            SonoraText(
                text = "${track.artist.ifEmpty { "Unknown artist" }} • ${track.album.ifEmpty { "Unknown album" }}",
                color = theme.textSub,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        SonoraText(
            text = fmtSec(track.duration),
            color = theme.textMuted,
            fontSize = 11.sp,
            fontFamily = SonoraFonts.Manrope,
        )
        Box(
            modifier = Modifier
                .clickable(onClick = onToggleFav)
                .padding(8.dp),
        ) {
            SonoraIcon(
                spec = if (isFav) SonoraIcons.HeartFill else SonoraIcons.Heart,
                tint = if (isFav) theme.accent2 else theme.textMuted,
                size = 17.dp,
            )
        }
        Box(
            modifier = Modifier
                .clickable(onClick = onMore)
                .padding(8.dp),
        ) {
            SonoraIcon(SonoraIcons.More, tint = theme.textSub, size = 18.dp)
        }
    }
}

@Composable
fun HomeScreen(
    repo: LibraryRepo,
    player: PlayerController,
    sheets: SheetsStore,
    onPickAudioFiles: () -> Unit,
    onPickSafFolder: () -> Unit,
    onNavigateToLibrary: () -> Unit,
) {
    val theme = LocalTheme.current
    val tracks by repo.tracks.flow.collectAsState()
    val userData by repo.userData.flow.collectAsState()
    val settings by Graph.settings.flow.collectAsState()
    val ui by player.state.collectAsState()

    val resumeLast = userData.resume.last
    val resumeTrack = remember(resumeLast, tracks) { repo.trackById(resumeLast?.trackId) }

    val recentlyAdded = remember(tracks) { tracks.sortedByDescending { it.addedAt }.take(12) }
    val mostPlayed = remember(tracks) {
        tracks.filter { it.playCount > 0 }.sortedByDescending { it.playCount }.take(10)
    }
    val favorites = remember(tracks, settings.likedIds) {
        val set = settings.likedIds.toSet()
        tracks.filter { set.contains(it.id) }
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(theme.bgSurface)
                    .border(1.dp, theme.borderStrong, RoundedCornerShape(18.dp))
                    .padding(16.dp),
            ) {
                SectionKicker("Sonora Offline Studio Player")
                Spacer(Modifier.height(4.dp))
                SonoraText(
                    text = if (tracks.isEmpty()) "Welcome to Sonora" else "${tracks.size} Tracks Ready",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = SonoraFonts.Manrope,
                )
                Spacer(Modifier.height(4.dp))
                val totalHours = tracks.sumOf { it.duration } / 3600.0
                val artistsCount = tracks.map { it.artist.lowercase() }.distinct().size
                SonoraText(
                    text = "$artistsCount Artists • ${String.format(java.util.Locale.US, "%.1f", totalHours)} Hours • ${favorites.size} Favorites",
                    color = theme.textSub,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SonoraButton("Import Files", icon = SonoraIcons.Upload, primary = tracks.isEmpty(), compact = true, onClick = onPickAudioFiles)
                    SonoraButton("Link Folder", icon = SonoraIcons.Folder, compact = true, onClick = onPickSafFolder)
                    if (tracks.isNotEmpty()) {
                        SonoraButton("Shuffle All", icon = SonoraIcons.Shuffle, primary = true, compact = true, onClick = {
                            player.playShuffled(tracks.indices.toList())
                        })
                    }
                }
            }
        }

        if (resumeTrack != null && resumeLast != null) {
            item {
                val posSec = resumeLast.ms / 1000.0
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(theme.accentSoft)
                        .border(1.dp, theme.accent, RoundedCornerShape(16.dp))
                        .clickable {
                            val idx = repo.indexOfTrackId(resumeTrack.id)
                            if (idx >= 0) {
                                player.playNow(idx)
                                player.seekTo(resumeLast.ms)
                            }
                        }
                        .padding(14.dp),
                ) {
                    SectionKicker("Continue Listening", color = theme.accent)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoverArt(track = resumeTrack, repo = repo, size = 52.dp, small = true)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            SonoraText(resumeTrack.title.ifEmpty { resumeTrack.fileName }, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            SonoraText(resumeTrack.artist, color = theme.textSub, fontSize = 12.sp, maxLines = 1)
                            Spacer(Modifier.height(2.dp))
                            SonoraText("Resume at ${fmtSec(posSec)} / ${fmtSec(resumeTrack.duration)}", color = theme.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        SonoraIcon(SonoraIcons.Play, tint = theme.accent, size = 24.dp)
                    }
                }
            }
        }

        if (recentlyAdded.isNotEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SectionKicker("Recently Added")
                    SonoraText(
                        text = "See All",
                        color = theme.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onNavigateToLibrary),
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recentlyAdded, key = { it.id }) { t ->
                        Column(
                            modifier = Modifier
                                .width(124.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(theme.bgSurface)
                                .border(1.dp, theme.border, RoundedCornerShape(14.dp))
                                .clickable {
                                    val listIndices = recentlyAdded.mapNotNull { repo.indexOfTrackId(it.id).takeIf { i -> i >= 0 } }
                                    val targetIdx = repo.indexOfTrackId(t.id)
                                    player.playFromList(listIndices, targetIdx)
                                }
                                .padding(10.dp),
                        ) {
                            CoverArt(track = t, repo = repo, size = 104.dp, small = false)
                            Spacer(Modifier.height(8.dp))
                            SonoraText(t.title.ifEmpty { t.fileName }, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            SonoraText(t.artist, color = theme.textSub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (favorites.isNotEmpty()) {
            item { SectionKicker("Favorites (${favorites.size})") }
            items(favorites.take(8), key = { it.id }) { t ->
                TrackRowItem(
                    track = t,
                    repo = repo,
                    isCurrent = ui.mediaId == t.id,
                    isPlaying = ui.isPlaying,
                    isFav = true,
                    onPlay = {
                        val listIndices = favorites.mapNotNull { repo.indexOfTrackId(it.id).takeIf { i -> i >= 0 } }
                        player.playFromList(listIndices, repo.indexOfTrackId(t.id))
                    },
                    onToggleFav = { Graph.toggleLikeTrack(t.id) },
                    onMore = { sheets.openTrackMenu(t.id) },
                )
            }
        }

        if (mostPlayed.isNotEmpty()) {
            item { SectionKicker("Most Played") }
            items(mostPlayed, key = { it.id }) { t ->
                TrackRowItem(
                    track = t,
                    repo = repo,
                    isCurrent = ui.mediaId == t.id,
                    isPlaying = ui.isPlaying,
                    isFav = settings.likedIds.contains(t.id),
                    onPlay = {
                        val listIndices = mostPlayed.mapNotNull { repo.indexOfTrackId(it.id).takeIf { i -> i >= 0 } }
                        player.playFromList(listIndices, repo.indexOfTrackId(t.id))
                    },
                    onToggleFav = { Graph.toggleLikeTrack(t.id) },
                    onMore = { sheets.openTrackMenu(t.id) },
                )
            }
        }
    }
}

@Composable
fun LibraryScreen(
    repo: LibraryRepo,
    importer: LibraryImporter,
    player: PlayerController,
    sheets: SheetsStore,
    subview: String,
    sortBy: String,
    viewMode: String,
    onSubviewChange: (String) -> Unit,
    onSortByChange: (String) -> Unit,
    onViewModeChange: (String) -> Unit,
    onPickAudioFiles: () -> Unit,
    onPickSafFolder: () -> Unit,
    onExportM3U: (Playlist) -> Unit,
    onImportM3U: () -> Unit,
) {
    val theme = LocalTheme.current
    val tracks by repo.tracks.flow.collectAsState()
    val userData by repo.userData.flow.collectAsState()
    val trees by repo.trees.flow.collectAsState()
    val settings by Graph.settings.flow.collectAsState()
    val ui by player.state.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedGroupFilter by remember(subview) { mutableStateOf<String?>(null) }
    var selectedPlaylistId by remember(subview) { mutableStateOf<String?>(null) }

    val sortedTracks = remember(tracks, searchQuery, sortBy) {
        val q = searchQuery.trim()
        val filtered = if (q.isEmpty()) tracks else tracks.filter {
            SonoraUtil.fuzzyMatch("${it.title} ${it.artist} ${it.album} ${it.fileName}", q)
        }
        when (sortBy) {
            "title" -> filtered.sortedWith { a, b -> SonoraUtil.naturalCompare(a.title.ifEmpty { a.fileName }, b.title.ifEmpty { b.fileName }) }
            "artist" -> filtered.sortedWith { a, b -> SonoraUtil.naturalCompare(a.artist, b.artist) }
            "album" -> filtered.sortedWith { a, b -> SonoraUtil.naturalCompare(a.album, b.album) }
            "duration" -> filtered.sortedByDescending { it.duration }
            "plays" -> filtered.sortedByDescending { it.playCount }
            else -> filtered.sortedByDescending { it.addedAt }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tabs = listOf(
                "tracks" to "Tracks (${tracks.size})",
                "albums" to "Albums",
                "artists" to "Artists",
                "playlists" to "Playlists (${userData.playlists.size})",
                "folders" to "Folders (${trees.size})",
            )
            items(tabs) { (id, label) ->
                SonoraChip(
                    label = label,
                    selected = subview == id,
                    onClick = { onSubviewChange(id) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SonoraTextField(
                label = "",
                value = searchQuery,
                modifier = Modifier.weight(1f),
                onValueChange = { searchQuery = it },
            )
            val sortCycle = listOf("added", "title", "artist", "album", "plays")
            SonoraButton(
                text = sortBy.uppercase(),
                icon = SonoraIcons.Sort,
                compact = true,
                onClick = {
                    val next = sortCycle[(sortCycle.indexOf(sortBy) + 1) % sortCycle.size]
                    onSortByChange(next)
                },
            )
            SonoraButton(
                text = "",
                icon = if (viewMode == "grid") SonoraIcons.List else SonoraIcons.Grid,
                compact = true,
                onClick = { onViewModeChange(if (viewMode == "grid") "list" else "grid") },
            )
        }

        Spacer(Modifier.height(10.dp))

        when (subview) {
            "tracks" -> {
                if (sortedTracks.isEmpty()) {
                    EmptyLibraryCard(onPickAudioFiles, onPickSafFolder)
                } else if (viewMode == "grid") {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 148.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(sortedTracks, key = { it.id }) { t ->
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(theme.bgSurface)
                                    .border(1.dp, theme.border, RoundedCornerShape(14.dp))
                                    .clickable {
                                        val listIndices = sortedTracks.mapNotNull { repo.indexOfTrackId(it.id).takeIf { i -> i >= 0 } }
                                        player.playFromList(listIndices, repo.indexOfTrackId(t.id))
                                    }
                                    .padding(10.dp),
                            ) {
                                CoverArt(track = t, repo = repo, size = 128.dp, small = false, modifier = Modifier.align(Alignment.CenterHorizontally))
                                Spacer(Modifier.height(8.dp))
                                SonoraText(t.title.ifEmpty { t.fileName }, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                SonoraText(t.artist, color = theme.textSub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(sortedTracks, key = { it.id }) { t ->
                            TrackRowItem(
                                track = t,
                                repo = repo,
                                isCurrent = ui.mediaId == t.id,
                                isPlaying = ui.isPlaying,
                                isFav = settings.likedIds.contains(t.id),
                                onPlay = {
                                    val listIndices = sortedTracks.mapNotNull { repo.indexOfTrackId(it.id).takeIf { i -> i >= 0 } }
                                    player.playFromList(listIndices, repo.indexOfTrackId(t.id))
                                },
                                onToggleFav = { Graph.toggleLikeTrack(t.id) },
                                onMore = { sheets.openTrackMenu(t.id) },
                            )
                        }
                    }
                }
            }

            "albums", "artists" -> {
                val isAlbums = subview == "albums"
                val groups = remember(sortedTracks, isAlbums) {
                    sortedTracks.groupBy { if (isAlbums) it.album.ifEmpty { "Unknown album" } else it.artist.ifEmpty { "Unknown artist" } }
                }
                if (selectedGroupFilter != null) {
                    val grpName = selectedGroupFilter!!
                    val grpTracks = groups[grpName] ?: emptyList()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SonoraButton("Back", compact = true, onClick = { selectedGroupFilter = null })
                        SonoraText("$grpName (${grpTracks.size})", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1)
                        SonoraButton("Play All", icon = SonoraIcons.Play, primary = true, compact = true, onClick = {
                            val listIndices = grpTracks.mapNotNull { repo.indexOfTrackId(it.id).takeIf { i -> i >= 0 } }
                            player.playIndices(listIndices, 0)
                        })
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                        items(grpTracks, key = { it.id }) { t ->
                            TrackRowItem(
                                track = t,
                                repo = repo,
                                isCurrent = ui.mediaId == t.id,
                                isPlaying = ui.isPlaying,
                                isFav = settings.likedIds.contains(t.id),
                                onPlay = {
                                    val listIndices = grpTracks.mapNotNull { repo.indexOfTrackId(it.id).takeIf { i -> i >= 0 } }
                                    player.playFromList(listIndices, repo.indexOfTrackId(t.id))
                                },
                                onToggleFav = { Graph.toggleLikeTrack(t.id) },
                                onMore = { sheets.openTrackMenu(t.id) },
                            )
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                        items(groups.entries.toList(), key = { it.key }) { (name, list) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(theme.bgSurface)
                                    .border(1.dp, theme.border, RoundedCornerShape(12.dp))
                                    .clickable { selectedGroupFilter = name }
                                    .padding(10.dp),
                            ) {
                                CoverArt(track = list.firstOrNull(), repo = repo, size = 46.dp, small = true)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    SonoraText(name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    SonoraText("${list.size} track(s)", color = theme.textSub, fontSize = 12.sp)
                                }
                                SonoraIcon(SonoraIcons.ChevRight, tint = theme.textMuted, size = 18.dp)
                            }
                        }
                    }
                }
            }

            "playlists" -> {
                val activePl = userData.playlists.firstOrNull { it.id == selectedPlaylistId }
                if (activePl != null) {
                    val plTracks = activePl.trackIds.mapNotNull { repo.trackById(it) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SonoraButton("Back", compact = true, onClick = { selectedPlaylistId = null })
                        SonoraText(activePl.name, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        SonoraButton("Add Songs", icon = SonoraIcons.Plus, compact = true, onClick = {
                            sheets.openSongPicker(activePl.id)
                        })
                        SonoraButton("Export M3U", icon = SonoraIcons.Download, compact = true, onClick = {
                            onExportM3U(activePl)
                        })
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                        items(plTracks, key = { it.id }) { t ->
                            TrackRowItem(
                                track = t,
                                repo = repo,
                                isCurrent = ui.mediaId == t.id,
                                isPlaying = ui.isPlaying,
                                isFav = settings.likedIds.contains(t.id),
                                onPlay = {
                                    val listIndices = plTracks.mapNotNull { repo.indexOfTrackId(it.id).takeIf { i -> i >= 0 } }
                                    player.playFromList(listIndices, repo.indexOfTrackId(t.id))
                                },
                                onToggleFav = { Graph.toggleLikeTrack(t.id) },
                                onMore = { sheets.openTrackMenu(t.id) },
                            )
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SonoraButton("New Playlist", icon = SonoraIcons.Plus, primary = true, compact = true, onClick = {
                            sheets.askPrompt("New Playlist", placeholder = "Late Night Vocaloid") { name ->
                                repo.createPlaylist(name)
                            }
                        })
                        SonoraButton("Import M3U", icon = SonoraIcons.Upload, compact = true, onClick = onImportM3U)
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                        items(userData.playlists, key = { it.id }) { pl ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(theme.bgSurface)
                                    .border(1.dp, theme.border, RoundedCornerShape(12.dp))
                                    .clickable { selectedPlaylistId = pl.id }
                                    .padding(12.dp),
                            ) {
                                SonoraIcon(SonoraIcons.Playlist, tint = theme.accent, size = 22.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    SonoraText(pl.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    SonoraText("${pl.trackIds.size} tracks", color = theme.textSub, fontSize = 12.sp)
                                }
                                Box(Modifier.clickable {
                                    sheets.askPrompt("Rename Playlist", initialValue = pl.name) { newName ->
                                        repo.renamePlaylist(pl.id, newName)
                                    }
                                }.padding(6.dp)) {
                                    SonoraIcon(SonoraIcons.Edit, tint = theme.textSub, size = 16.dp)
                                }
                                Box(Modifier.clickable {
                                    sheets.askConfirm("Delete Playlist", "Delete playlist \"${pl.name}\"?") {
                                        repo.deletePlaylist(pl.id)
                                    }
                                }.padding(6.dp)) {
                                    SonoraIcon(SonoraIcons.Trash, tint = theme.danger, size = 16.dp)
                                }
                            }
                        }
                    }
                }
            }

            "folders" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SonoraButton("Link SAF Folder", icon = SonoraIcons.Folder, primary = true, compact = true, onClick = onPickSafFolder)
                    SonoraButton("Rescan All", icon = SonoraIcons.Refresh, compact = true, onClick = { importer.rescanAllTrees() })
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    items(trees, key = { it.uri }) { tree ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(theme.bgSurface)
                                .border(1.dp, theme.border, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                        ) {
                            SonoraIcon(SonoraIcons.Folder, tint = theme.accent, size = 22.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                SonoraText(tree.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                SonoraText("${tree.count} tracks linked", color = theme.textSub, fontSize = 12.sp)
                            }
                            Box(Modifier.clickable {
                                sheets.askConfirm("Unlink Folder", "Remove linked folder \"${tree.name}\" from library?") {
                                    repo.removeTree(tree.uri)
                                }
                            }.padding(6.dp)) {
                                SonoraIcon(SonoraIcons.Trash, tint = theme.danger, size = 17.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryCard(
    onPickAudioFiles: () -> Unit,
    onPickSafFolder: () -> Unit,
) {
    val theme = LocalTheme.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 42.dp),
    ) {
        SonoraIcon(SonoraIcons.Music, tint = theme.accent, size = 44.dp)
        Spacer(Modifier.height(12.dp))
        SonoraText("Your library is empty", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, fontFamily = SonoraFonts.Manrope)
        Spacer(Modifier.height(4.dp))
        SonoraText("Import audio files or link a music folder on your phone.", color = theme.textSub, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SonoraButton("Import Files", icon = SonoraIcons.Upload, primary = true, onClick = onPickAudioFiles)
            SonoraButton("Link Folder", icon = SonoraIcons.Folder, onClick = onPickSafFolder)
        }
    }
}
