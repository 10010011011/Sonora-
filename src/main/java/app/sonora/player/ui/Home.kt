package app.sonora.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonora.player.Engine
import app.sonora.player.S
import app.sonora.player.hasLyrics
import app.sonora.player.sortTracks
import kotlinx.coroutines.flow.update

@Composable
fun HomeScreen() {
    val p = LocalPal.current
    val track = currentTrack()
    val ps by S.player.collectAsState()
    val tracks by S.tracks.collectAsState()
    val s by S.settings.collectAsState()
    val user by S.user.collectAsState()
    val lyr by S.lyrics.collectAsState()
    // Every song, in the chosen sort order; a saved manual order follows the tracks by id.
    val list = remember(tracks, s.librarySort, user.libraryOrder) { sortTracks(tracks, s.librarySort, user.libraryOrder) }
    val ids = remember(list) { list.map { it.id } }
    val liked = remember(s.likedIds) { s.likedIds.toHashSet() }
    val ds = remember { DragState() }
    ds.rowPx = with(LocalDensity.current) { 66.dp.toPx() }
    val state = rememberLazyListState()
    DragAutoScroll(ds, state)

    fun startMix(mode: String) {
        if (tracks.isEmpty()) return
        var picked = when (mode) {
            "liked" -> tracks.filter { it.id in liked }
            "mostPlayed" -> tracks.sortedByDescending { it.playCount }.take(25)
            else -> tracks.sortedBy { it.lastPlayedAt }.take(25)
        }
        if (picked.isEmpty()) picked = tracks
        if (mode == "mostPlayed") Engine.playIds(picked.map { it.id }, 0, false) else Engine.playShuffled(picked.map { it.id })
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            modifier = Modifier.fillMaxSize().onGloballyPositioned { val b = it.boundsInRoot(); ds.top = b.top; ds.bottom = b.bottom },
        ) {
            item("h") { Heading("Home", modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 10.dp)) }
            item("feature") {
                if (track != null) {
                    // The hero card: tap anywhere to open Now Playing.
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.panel).border(1.dp, p.border, RoundedCornerShape(22.dp)).clickable { Ui.player.value = true }) {
                        Box(Modifier.fillMaxWidth().height(3.dp).background(Brush.horizontalGradient(listOf(p.accent, p.accent2.copy(alpha = if (p.tag.isEmpty()) 0f else 0.9f), Color.Transparent))))
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(track.cover, 112.dp, radius = 16.dp)
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text(if (ps.playing) "PLAYING" else "PAUSED", color = p.accent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                                Text(track.title, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, lineHeight = 25.sp, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                Text(track.artist, color = p.muted, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(46.dp).clip(CircleShape).background(p.accent).clickable { Engine.toggle() }, contentAlignment = Alignment.Center) {
                                        Icon(if (ps.playing) Ic.Pause else Ic.Play, if (ps.playing) "Pause track" else "Play track", tint = p.accentInk, modifier = Modifier.size(24.dp))
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    val isLiked = track.id in liked
                                    IconBtn(if (isLiked) Ic.Heart else Ic.HeartOutline, "Like track", tint = if (isLiked) p.accent2 else p.muted, onClick = { S.toggleLike(track.id) })
                                }
                            }
                        }
                    }
                } else {
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) { Icon(Ic.Music, null, tint = p.accent, modifier = Modifier.size(27.dp)) }
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text("No songs yet", fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = p.text)
                                Text("Add music to start listening. Files are copied into Sonora, and playback keeps going with the screen off.", color = p.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
                            }
                        }
                        Btn("Add music", Modifier.padding(top = 14.dp), icon = Ic.Plus) { Ui.go("library") }
                    }
                }
            }
            if (tracks.isNotEmpty()) {
                item("chips") {
                    Row(Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Shuffle all", icon = Ic.Shuffle) { Engine.playShuffled(ids) }
                        Chip("Most played", icon = Ic.Sparkles) { startMix("mostPlayed") }
                        Chip("Rediscover", icon = Ic.Clock) { startMix("rediscover") }
                        if (s.likedIds.isNotEmpty()) Chip("Liked mix (${s.likedIds.size})", icon = Ic.Heart) { startMix("liked") }
                    }
                }
                item("all") {
                    Column(Modifier.padding(start = 6.dp, top = 20.dp, bottom = 6.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("All songs", fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = p.text)
                            Text("  ${tracks.size}", color = p.muted, fontSize = 13.sp, fontFamily = Mono)
                        }
                        Text("Press and hold a song, then drag it to set your manual order.", color = p.faint, fontSize = 12.sp)
                    }
                }
                itemsIndexed(list, key = { _, t -> t.id }) { i, t ->
                    ReorderItem(ds, i, list.size, true, onMove = { from, to ->
                        val next = ids.toMutableList().apply { add(to, removeAt(from)) }
                        S.user.update { it.copy(libraryOrder = next) }
                        S.set { it.copy(librarySort = "manual") }
                    }) { _, dragging ->
                        val isCur = t.id == ps.trackId
                        TrackRow(t, isCur, ps.playing, t.id in liked, hasLyrics(t, lyr), dragging,
                            // Tapping the song that is already playing pauses/resumes it instead of restarting the queue.
                            onClick = { if (ds.from < 0) { if (isCur) Engine.toggle() else Engine.playFromList(ids, t.id) } },
                            onLike = { S.toggleLike(t.id) }, onMore = { Ui.menu.value = t.id to null })
                    }
                }
            }
        }
        FastScrollbar(state, Modifier.align(Alignment.CenterEnd))
    }
}
