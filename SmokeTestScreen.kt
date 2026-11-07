package app.sonora.player.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import app.sonora.player.audio.PlayerController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * PHASE 1 ENGINE HARNESS (not the final UI). Pick songs straight from the phone (no copying, no library), play them,
 * seek, change speed, and run the seek torture test. Gate G1 in the plan = this screen passes on the user's worst files.
 */
@Composable
fun SmokeTestScreen(controller: PlayerController) {
    val t = SonoraThemes.dark
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    val pos by controller.positionMs.collectAsState()
    val log by controller.log.collectAsState()
    var names by remember { mutableStateOf(listOf<String>()) }
    var items by remember { mutableStateOf(listOf<MediaItem>()) }
    var report by remember { mutableStateOf("") }
    var dragMs by remember { mutableStateOf<Long?>(null) }
    var fast by remember { mutableStateOf(false) }

    fun displayName(uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "track"

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val n = uris.map { displayName(it) }
            names = n
            items = uris.mapIndexed { i, u ->
                MediaItem.Builder()
                    .setMediaId(u.toString())
                    .setUri(u)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(n[i]).build())
                    .build()
            }
        }
    }

    @Composable
    fun Btn(label: String, onClick: () -> Unit) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(t.accent)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) { BasicText(label, style = TextStyle(color = t.accentInk, fontSize = 14.sp)) }
    }

    val txt = TextStyle(color = t.text, fontSize = 14.sp)
    val dim = TextStyle(color = t.muted, fontSize = 12.sp)

    Column(
        Modifier
            .fillMaxSize()
            .background(t.bg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText("Sonora native - engine test", style = TextStyle(color = t.accent, fontSize = 18.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Add songs") { picker.launch(arrayOf("audio/*")) }
            Btn("Prev") { controller.prev() }
            Btn(if (state.isPlaying) "Pause" else "Play") { controller.toggle() }
            Btn("Next") { controller.next() }
        }
        BasicText(
            (state.title.ifEmpty { "nothing playing" }) + "  [" + (state.index + 1) + "/" + state.count + "]",
            style = txt,
        )
        SeekBar(
            positionMs = pos,
            durationMs = state.durationMs,
            onSeek = { controller.seekTo(it) },
            trackColor = t.border,
            fillColor = t.accent,
            onDragTime = { dragMs = it },
        )
        BasicText(fmtTime(dragMs ?: pos) + " / " + fmtTime(state.durationMs) + "   state=" + state.playbackState, style = dim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(if (fast) "Speed 1.5x" else "Speed 1x") {
                fast = !fast
                controller.setSpeed(if (fast) 1.5f else 1f)
            }
            Btn("Seek torture") {
                scope.launch {
                    report = "running..."
                    var ok = 0
                    var bad = 0
                    val dur = controller.state.value.durationMs
                    if (dur < 6000L) {
                        report = "play a track longer than 6 s first"
                    } else {
                        repeat(40) {
                            val target = Random.nextLong(1000L, dur - 2000L)
                            controller.seekTo(target)
                            delay(Random.nextLong(150L, 600L))
                            val now = controller.currentPositionNow()
                            val s = controller.state.value
                            val alive = s.error == null && (s.isPlaying || s.playbackState == Player.STATE_BUFFERING)
                            if (alive && now >= target - 300L && now <= target + 4000L) ok++ else bad++
                        }
                        report = "seek torture: $ok ok / $bad failed (40 random seeks)"
                    }
                }
            }
        }
        if (report.isNotEmpty()) BasicText(report, style = txt)
        state.error?.let { BasicText("ERROR: $it", style = TextStyle(color = Color(0xFFFF6B6B), fontSize = 13.sp)) }
        BasicText(log.joinToString("\n"), style = dim)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(names) { i, n ->
                BasicText(
                    n,
                    style = if (i == state.index) TextStyle(color = t.accent, fontSize = 14.sp) else txt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { controller.setQueue(items, i) }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}
