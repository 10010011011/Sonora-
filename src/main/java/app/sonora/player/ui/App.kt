package app.sonora.player.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonora.player.Engine
import app.sonora.player.Importer
import app.sonora.player.S
import app.sonora.player.Track
import kotlin.math.abs

private val THEME_ICON = mapOf("miku" to "Miku", "teto" to "Teto", "neru" to "Neru")
private val ICON_ALIASES = listOf("Default", "General", "Miku", "Teto", "Neru")

/** Enables exactly one launcher alias (.IconDefault / .IconGeneral / .IconMiku / .IconTeto / .IconNeru). */
fun applyAppIcon(ctx: Context, name: String) = runCatching {
    val pm = ctx.packageManager
    for (n in ICON_ALIASES.sortedByDescending { it == name }) {
        val cn = ComponentName(ctx.packageName, "app.sonora.player.Icon$n")
        val on = when (pm.getComponentEnabledSetting(cn)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> n == "Default"
        }
        if (on != (n == name)) pm.setComponentEnabledSetting(cn, if (n == name) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}

@Composable
fun currentTrack(): Track? {
    val ps by S.player.collectAsState()
    val tracks by S.tracks.collectAsState()
    val q by S.queue.collectAsState()
    return remember(ps.trackId, tracks, q.qpos, q.queue) { S.current() }
}

private fun granted(ctx: Context, perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
private fun openAppSettings(ctx: Context) = runCatching {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val pending = remember { arrayOfNulls<Any>(2) } // [0] text waiting to be saved, [1] callback waiting for a picked text file
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> Importer.importUris(uris) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) Importer.addFolder(uri) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val text = pending[0] as? String
        pending[0] = null
        if (uri != null && text != null) {
            runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
                .onSuccess { S.toast("Saved") }.onFailure { S.toast("Could not save the file") }
        }
    }
    val textPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        @Suppress("UNCHECKED_CAST") val cb = pending[1] as? (String, String) -> Unit
        pending[1] = null
        if (uri != null && cb != null) runCatching {
            var name = uri.lastPathSegment ?: "file"
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { name = it } }
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            cb(name, text)
        }.onFailure { S.toast("Could not read that file") }
    }
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Engine.devicesChanged()
        if (ok) S.toast(if (S.outs.value.isNotEmpty()) "Headset names refreshed" else "Allowed. No named headset is connected right now")
        else {
            S.toast("Turn on “Nearby devices” in App info → Permissions")
            openAppSettings(ctx)
        }
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (pending.size > 1 && !ok && notifAsked[0]) openAppSettings(ctx)
        if (ok && notifAsked[0]) S.toast("Notification controls are allowed")
    }
    val actions = remember {
        Actions(
            addFiles = { files.launch(arrayOf("audio/*", "application/ogg", "application/x-flac", "text/*", "application/octet-stream", "application/x-subrip")) },
            addFolder = { folder.launch(null) },
            saveText = { name, text ->
                pending[0] = text
                saver.launch(name)
            },
            pickText = { cb ->
                pending[1] = cb
                textPicker.launch(arrayOf("*/*"))
            },
            askBluetooth = {
                if (Build.VERSION.SDK_INT < 31 || granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)) {
                    Engine.devicesChanged()
                    S.toast("Headset names refreshed")
                } else btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT)
            },
            askNotifications = { open ->
                notifAsked[0] = open
                if (Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) {
                    if (open) S.toast("Notification controls are allowed")
                } else notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            copy = { text, done ->
                runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Sonora", text)) }
                    .onSuccess { S.toast(done) }.onFailure { S.toast("Could not copy") }
            },
        )
    }
    // Asked on each launch until granted: the lock-screen and notification controls need it.
    LaunchedEffect(Unit) { actions.askNotifications(false) }

    val tabs = rememberSaveableStateHolder()
    val view by Ui.view.collectAsState()
    val playerOpen by Ui.player.collectAsState()
    val settingsOpen by Ui.settings.collectAsState()
    val s by S.settings.collectAsState()
    val imp by S.imp.collectAsState()
    val simple = s.uiMode == "simple"
    val cur = currentTrack()
    LaunchedEffect(simple, view) { if (simple && (view == "sound" || view == "visual")) Ui.view.value = "room" }
    LaunchedEffect(s.theme, s.themeIcon) { applyAppIcon(ctx, if (s.themeIcon) THEME_ICON[s.theme] ?: "General" else "Default") }
    LaunchedEffect(view, playerOpen) { Engine.uiVisible = true }

    BackHandler(enabled = !playerOpen && !settingsOpen && view != "room") { Ui.view.value = if (view == "visual") "sound" else "room" }

    CompositionLocalProvider(LocalActions provides actions) {
        Box(Modifier.fillMaxSize()) {
            ThemedBackground(cur?.cover)
            BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
                val wide = maxWidth > 840.dp
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight().statusBarsPadding()) {
                        TopBar(view, imp.phase == "running" || imp.phase == "hidden")
                        // Each tab keeps its scroll position, search text and open group while you are on another tab.
                        Crossfade(view, Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(140), label = "tab") { v ->
                            tabs.SaveableStateProvider(v) {
                                when (v) {
                                    "library" -> LibraryScreen()
                                    "lyrics" -> LyricsStudio()
                                    "sound" -> SoundScreen()
                                    "visual" -> VisualScreen()
                                    else -> HomeScreen()
                                }
                            }
                        }
                        MiniPlayer(cur)
                        BottomNav(view, simple)
                    }
                    if (wide) RightLyricsPanel(cur)
                }
            }
            AnimatedVisibility(playerOpen, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) { NowPlayingScreen() }
            AnimatedVisibility(settingsOpen, enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut()) { SettingsScreen() }
            SheetsHost()
            ToastHost()
            // Declared after the screens so they take priority over a screen's own Back handling.
            BackHandler(enabled = playerOpen && !settingsOpen) { Ui.player.value = false }
            BackHandler(enabled = settingsOpen) { Ui.settings.value = false }
        }
    }
}

private val notifAsked = booleanArrayOf(false)

private val TITLES = mapOf("room" to "Listening room", "library" to "Your library", "lyrics" to "Lyrics studio", "sound" to "Sound lab", "visual" to "Visual analyzer")

@Composable
private fun TopBar(view: String, scanning: Boolean) {
    val p = LocalPal.current
    val actions = LocalActions.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(p.accentSoft).border(1.dp, p.border, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(Ic.Wave, null, tint = p.accent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("sonora", fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = p.text, letterSpacing = (-0.4).sp)
                Text(".", fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = p.accent)
                if (p.tag.isNotEmpty()) {
                    Spacer(Modifier.width(7.dp))
                    Text(p.tag, color = p.accent2, fontSize = 10.sp, fontFamily = Mono, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, p.accent2.copy(alpha = 0.5f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            Text("YOUR SPACE  ›  ${TITLES[view] ?: ""}${if (scanning) "  · Scanning…" else ""}", color = p.muted, fontSize = 10.sp, letterSpacing = 0.8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconBtn(Ic.Queue, "Queue", onClick = { Ui.queue.value = true })
        IconBtn(Ic.Gear, "Settings", onClick = { Ui.settings.value = true })
        Box(Modifier.padding(start = 2.dp, end = 6.dp).size(36.dp).clip(CircleShape).background(p.accent).clickable { actions.addFiles() }, contentAlignment = Alignment.Center) {
            Icon(Ic.Plus, "Import music", tint = p.accentInk, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun BottomNav(view: String, simple: Boolean) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().background(p.bar).navigationBarsPadding().height(62.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        @Composable
        fun item(id: String, label: String, icon: ImageVector, active: Boolean) {
            Column(Modifier.weight(1f).fillMaxHeight().clickable { Ui.go(id) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (active) p.accentSoft else Color.Transparent).padding(horizontal = 18.dp, vertical = 4.dp)) {
                    Icon(icon, label, tint = if (active) p.accent else p.muted, modifier = Modifier.size(21.dp))
                }
                Text(label, color = if (active) p.accent else p.muted, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
            }
        }
        item("room", "Home", Ic.Home, view == "room")
        item("library", "Library", Ic.Queue, view == "library")
        item("lyrics", "Lyrics", Ic.Lyrics, view == "lyrics")
        if (!simple) item("sound", "Sound", Ic.Sliders, view == "sound" || view == "visual")
    }
}

/** The persistent player bar: tap to open Now Playing. */
@Composable
private fun MiniPlayer(track: Track?) {
    if (track == null) return
    val p = LocalPal.current
    val ps by S.player.collectAsState()
    val t by S.clock.collectAsState()
    val s by S.settings.collectAsState()
    val liked = s.likedIds.contains(track.id)
    val dur = if (ps.duration > 0) ps.duration else track.duration
    var drag by remember { mutableFloatStateOf(0f) }
    // Swipe left / right to skip, swipe up (or tap) to open the player.
    val swipe = Modifier.pointerInput(Unit) {
        var dx = 0f
        var dy = 0f
        detectDragGestures(
            onDragStart = { dx = 0f; dy = 0f },
            onDragCancel = { drag = 0f },
            onDragEnd = {
                val far = 64.dp.toPx()
                if (abs(dx) > abs(dy)) { if (dx < -far) Engine.next() else if (dx > far) Engine.prev() } else if (dy < -far / 2) Ui.player.value = true
                drag = 0f
            },
        ) { ch, d ->
            ch.consume()
            dx += d.x
            dy += d.y
            drag = if (abs(dx) > abs(dy)) dx else 0f
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(p.bar).border(1.dp, p.border, RoundedCornerShape(16.dp)).then(swipe).clickable { Ui.player.value = true }) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(p.border)) {
            Box(Modifier.fillMaxWidth(if (dur > 0) (t / dur).toFloat().coerceIn(0f, 1f) else 0f).height(2.dp).background(p.accent))
        }
        Row(Modifier.padding(start = 8.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(track.thumb ?: track.cover, 44.dp, radius = 9.dp)
            Column(Modifier.weight(1f).padding(horizontal = 11.dp).graphicsLayer { translationX = drag * 0.45f; alpha = 1f - (abs(drag) / 500f).coerceAtMost(0.6f) }) {
                Text(track.title, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = p.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconBtn(if (liked) Ic.Heart else Ic.HeartOutline, "Like current song", tint = if (liked) p.accent2 else p.muted, size = 18.dp, onClick = { S.toggleLike(track.id) })
            Box(Modifier.size(40.dp).clip(CircleShape).background(p.accent).clickable { Engine.toggle() }, contentAlignment = Alignment.Center) {
                Icon(if (ps.playing) Ic.Pause else Ic.Play, if (ps.playing) "Pause" else "Play", tint = p.accentInk, modifier = Modifier.size(22.dp))
            }
            IconBtn(Ic.Next, "Next", tint = p.text, size = 22.dp, onClick = { Engine.next() })
        }
    }
}

/** Wide screens (tablets, landscape, foldables): the live lyrics panel sits beside the content, like the desktop layout. */
@Composable
private fun RightLyricsPanel(track: Track?) {
    val p = LocalPal.current
    Column(Modifier.width(340.dp).fillMaxHeight().background(p.side).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Text("THE WORDS BEHIND THE MUSIC", color = p.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Text("Live lyrics", fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = p.text)
        Text(track?.title ?: "No track", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
        LyricsScroller(Modifier.weight(1f).fillMaxWidth(), track) { Ui.go("lyrics") }
        Btn("Lyrics studio", Modifier.fillMaxWidth().padding(top = 8.dp), icon = Ic.Lyrics, accent = false) { Ui.go("lyrics") }
    }
}

@Composable
private fun ToastHost() {
    val msg by S.toastMsg.collectAsState()
    val p = LocalPal.current
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 150.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(msg.isNotEmpty(), enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
            Row(Modifier.clip(RoundedCornerShape(50)).background(p.text).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Check, null, tint = p.bg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(msg, color = p.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
