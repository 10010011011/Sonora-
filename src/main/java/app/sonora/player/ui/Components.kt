package app.sonora.player.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.sonora.player.Track
import app.sonora.player.fmt
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.math.roundToInt

data class DialogState(val title: String, val message: String? = null, val value: String? = null, val okLabel: String = "OK", val onOk: (String) -> Unit)

/** Navigation and sheet state (the native counterpart of sheets.ts + App.tsx view state). */
object Ui {
    val view = MutableStateFlow("room") // room | library | lyrics | sound | visual
    val player = MutableStateFlow(false)
    val settings = MutableStateFlow(false)
    val menu = MutableStateFlow<Pair<String, String?>?>(null)
    val tags = MutableStateFlow<String?>(null)
    val picker = MutableStateFlow<List<String>?>(null)
    val songPicker = MutableStateFlow<String?>(null)
    val queue = MutableStateFlow(false)
    val bookmarks = MutableStateFlow(false)
    val dialog = MutableStateFlow<DialogState?>(null)
    val calibrate = MutableStateFlow(false)
    val sleep = MutableStateFlow(false)

    fun closeSheets() {
        menu.value = null; tags.value = null; picker.value = null; songPicker.value = null; queue.value = false; bookmarks.value = false
    }

    fun go(v: String) {
        view.value = v
        player.value = false
    }

    fun askText(title: String, initial: String, okLabel: String = "OK", onOk: (String) -> Unit) { dialog.value = DialogState(title, null, initial, okLabel, onOk) }
    fun askConfirm(title: String, message: String, okLabel: String = "OK", onOk: () -> Unit) { dialog.value = DialogState(title, message, null, okLabel) { onOk() } }
}

/** System pickers and permission prompts, provided once by the activity. */
class Actions(
    val addFiles: () -> Unit,
    val addFolder: () -> Unit,
    val saveText: (name: String, text: String) -> Unit,
    val pickText: (onText: (name: String, text: String) -> Unit) -> Unit,
    val askBluetooth: () -> Unit,
    val askNotifications: (openSettings: Boolean) -> Unit,
    val copy: (text: String, done: String) -> Unit,
)

val LocalActions = staticCompositionLocalOf<Actions> { error("Actions not provided") }
val Mono = FontFamily.Monospace

@Composable
fun Cover(path: String?, size: Dp, modifier: Modifier = Modifier, radius: Dp = 10.dp) {
    val p = LocalPal.current
    Box(
        modifier.size(size).clip(RoundedCornerShape(radius)).background(Brush.linearGradient(listOf(p.input, p.accentSoft))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Ic.Music, null, tint = p.faint, modifier = Modifier.size(size * 0.42f))
        if (path != null) AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    val p = LocalPal.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(p.accent))
        Spacer(Modifier.width(8.dp))
        Text(text, color = p.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
    }
}

@Composable
fun Heading(text: String, accentWord: String = "", modifier: Modifier = Modifier) {
    val p = LocalPal.current
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(text, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, color = p.text, letterSpacing = (-0.8).sp)
        if (accentWord.isNotEmpty()) Text(" $accentWord", fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, color = p.accent, letterSpacing = (-0.8).sp)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, pad: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPal.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.panel).border(1.dp, p.border, RoundedCornerShape(18.dp)).padding(pad), content = content)
}

@Composable
fun Chip(text: String, active: Boolean = false, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (active) p.accentSoft else p.input).border(1.dp, if (active) p.accent else p.border, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 13.dp, vertical = 8.dp).graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (active) p.accent else p.muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = if (active) p.accent else p.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun Btn(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, accent: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPal.current
    val fg = if (accent) p.accentInk else p.text
    Row(
        modifier.defaultMinSize(minHeight = 40.dp).clip(RoundedCornerShape(50)).background(if (accent) p.accent else Color.Transparent)
            .then(if (accent) Modifier else Modifier.border(BorderStroke(1.dp, p.border), RoundedCornerShape(50)))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = fg, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun IconBtn(icon: ImageVector, desc: String, modifier: Modifier = Modifier, tint: Color = LocalPal.current.muted, size: Dp = 20.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.size(size + 22.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(size))
    }
}

@Composable
fun SSlider(label: String, value: Double, min: Double, max: Double, step: Double, fmt: (Double) -> String = { "${if (it > 0) "+" else ""}${"%.1f".format(it)} dB" }, onChange: (Double) -> Unit) {
    val p = LocalPal.current
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(fmt(value), color = p.accent, fontSize = 12.sp, fontFamily = Mono)
        }
        Slider(
            value = value.toFloat(), onValueChange = { v -> onChange(((v / step).roundToInt() * step).coerceIn(min, max).let { (it * 1000).roundToInt() / 1000.0 }) },
            valueRange = min.toFloat()..max.toFloat(), modifier = Modifier.height(34.dp),
            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.border),
        )
    }
}

@Composable
fun SettingRow(title: String, desc: String = "", control: @Composable RowScope.() -> Unit) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (desc.isNotEmpty()) Text(desc, color = p.muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        control()
    }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPal.current
    Switch(
        checked = on, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(checkedThumbColor = p.accentInk, checkedTrackColor = p.accent, uncheckedThumbColor = p.muted, uncheckedTrackColor = p.input, uncheckedBorderColor = p.border),
    )
}

@Composable
fun ToggleRow(title: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit) = SettingRow(title, desc) { Toggle(on, onChange) }

@Composable
fun <T> Select(value: T, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    val p = LocalPal.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(p.input).border(1.dp, p.border, RoundedCornerShape(10.dp)).clickable { open = true }.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == value }?.second ?: options.firstOrNull()?.second.orEmpty(), color = p.text, fontSize = 13.sp, maxLines = 1)
            Icon(Ic.Down, null, tint = p.muted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, label) ->
                DropdownMenuItem(text = { Text(label, color = if (v == value) p.accent else p.text, fontSize = 14.sp) }, onClick = { open = false; onPick(v) })
            }
        }
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, singleLine: Boolean = true, mono: Boolean = false, number: Boolean = false, leading: ImageVector? = null, minHeight: Dp = 42.dp) {
    val p = LocalPal.current
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = singleLine, cursorBrush = SolidColor(p.accent),
        textStyle = TextStyle(color = p.text, fontSize = 14.sp, fontFamily = if (mono) Mono else DmSans),
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = modifier.defaultMinSize(minHeight = minHeight).clip(RoundedCornerShape(12.dp)).background(p.input).border(1.dp, p.border, RoundedCornerShape(12.dp)),
        decorationBox = { inner ->
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top) {
                if (leading != null) {
                    Icon(leading, null, tint = p.muted, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = p.faint, fontSize = 14.sp, fontFamily = if (mono) Mono else DmSans)
                    inner()
                }
                if (singleLine && value.isNotEmpty() && leading != null) Icon(Ic.Close, "Clear", tint = p.muted, modifier = Modifier.size(16.dp).clickable { onChange("") })
            }
        },
    )
}

/** A draggable scroll thumb for long lists. It appears while the list moves, and fades out afterwards. */
@Composable
fun FastScrollbar(state: LazyListState, modifier: Modifier = Modifier, minItems: Int = 40) {
    val p = LocalPal.current
    val scope = rememberCoroutineScope()
    // Scroll position is read where it is used (layout and input), so scrolling never recomposes this.
    val total by remember(state) { derivedStateOf { state.layoutInfo.totalItemsCount } }
    if (total < minItems) return
    var dragging by remember { mutableStateOf(false) }
    var trackPx by remember { mutableIntStateOf(1) }
    val show = state.isScrollInProgress || dragging
    val alpha by animateFloatAsState(if (show) 1f else 0f, tween(if (show) 120 else 700, delayMillis = if (show) 0 else 900), label = "scrollbar")
    val thumb = 52.dp
    // Touches are only taken while the thumb is showing, so it never blocks the buttons underneath a resting list.
    val grab = if (alpha < 0.4f) Modifier else Modifier.pointerInput(total) {
        detectVerticalDragGestures(onDragStart = { dragging = true }, onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { ch, _ ->
            ch.consume()
            val f = ((ch.position.y - thumb.toPx() / 2) / (size.height - thumb.toPx()).coerceAtLeast(1f)).coerceIn(0f, 1f)
            scope.launch { state.scrollToItem((f * (total - 1)).roundToInt()) }
        }
    }
    Box(modifier.fillMaxHeight().width(26.dp).padding(vertical = 6.dp).onSizeChanged { trackPx = it.height }.then(grab).graphicsLayer { this.alpha = alpha }) {
        Box(
            Modifier.align(Alignment.TopEnd).offset {
                val visible = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                val frac = (state.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)).coerceIn(0f, 1f)
                IntOffset(0, (frac * (trackPx - thumb.toPx())).roundToInt())
            }.padding(end = 3.dp)
                .size(if (dragging) 7.dp else 5.dp, thumb).clip(RoundedCornerShape(50)).background(if (dragging) p.accent else p.muted),
        )
    }
}

private var keepOnCount = 0

/** Stops the screen from sleeping while `on` (reading live lyrics, tap-syncing). */
@Composable
fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        if (on) {
            keepOnCount++
            view.keepScreenOn = true
        }
        onDispose {
            if (on) {
                keepOnCount = (keepOnCount - 1).coerceAtLeast(0)
                view.keepScreenOn = keepOnCount > 0
            }
        }
    }
}

/* ------------------------------------------------------------------ press-and-hold drag reordering */

class DragState {
    var from by mutableIntStateOf(-1)
    var dy by mutableFloatStateOf(0f)
    var rowPx = 1f
    var fingerY = 0f
    var top = 0f
    var bottom = 0f
    fun target(count: Int) = if (from < 0) -1 else (from + (dy / rowPx).roundToInt()).coerceIn(0, count - 1)

    /** Visual offset: the held row follows the finger, the rows it passes slide aside to open a gap. */
    fun shift(i: Int, count: Int): Float {
        if (from < 0) return 0f
        if (i == from) return dy.coerceIn(-from * rowPx, (count - 1 - from) * rowPx)
        val t = target(count)
        return if (from < t && i in (from + 1)..t) -rowPx else if (from > t && i in t until from) rowPx else 0f
    }
}

/** Holding a row near the top or bottom edge scrolls the list while dragging. */
@Composable
fun DragAutoScroll(ds: DragState, list: LazyListState) {
    val dragging = ds.from >= 0
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        while (true) {
            val edge = 150f
            val d = when {
                ds.fingerY < ds.top + edge -> (ds.fingerY - (ds.top + edge)) / 5f
                ds.fingerY > ds.bottom - edge -> (ds.fingerY - (ds.bottom - edge)) / 5f
                else -> 0f
            }.coerceIn(-30f, 30f)
            if (d != 0f) ds.dy += list.scrollBy(d)
            delay(16)
        }
    }
}

/**
 * Wraps one row of a reorderable list. Without `handleOnly` the whole row is picked up by press-and-hold;
 * with it, only the element given the `handle` modifier starts a drag (the queue's grip).
 */
@Composable
fun ReorderItem(ds: DragState, index: Int, count: Int, enabled: Boolean, onMove: (Int, Int) -> Unit, handleOnly: Boolean = false, content: @Composable (handle: Modifier, dragging: Boolean) -> Unit) {
    val pin = LocalPinnableContainer.current
    val haptic = LocalHapticFeedback.current
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val move by rememberUpdatedState(onMove)
    val cnt by rememberUpdatedState(count)
    val idx by rememberUpdatedState(index)
    val handle = if (!enabled) Modifier else Modifier.onGloballyPositioned { holder[0] = it }.pointerInput(handleOnly) {
        var pinned: PinnableContainer.PinnedHandle? = null
        val start: (Offset) -> Unit = { o ->
            pinned = pin?.pin() // keeps this row alive while the list scrolls under it
            ds.from = idx
            ds.dy = 0f
            ds.fingerY = holder[0]?.takeIf { it.isAttached }?.localToRoot(o)?.y ?: 0f
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        val drag: (PointerInputChange, Offset) -> Unit = { ch, d ->
            ch.consume()
            ds.dy += d.y
            holder[0]?.takeIf { it.isAttached }?.let { ds.fingerY = it.localToRoot(ch.position).y }
        }
        val finish: (Boolean) -> Unit = { ok ->
            val f = ds.from
            val t = ds.target(cnt)
            ds.from = -1
            ds.dy = 0f
            pinned?.release()
            pinned = null
            if (ok && f >= 0 && t != f) move(f, t)
        }
        if (handleOnly) detectDragGestures(onDragStart = start, onDragEnd = { finish(true) }, onDragCancel = { finish(false) }, onDrag = drag)
        else detectDragGesturesAfterLongPress(onDragStart = start, onDragEnd = { finish(true) }, onDragCancel = { finish(false) }, onDrag = drag)
    }
    val dragging = ds.from == index
    Box(
        Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer {
            translationY = ds.shift(index, count)
            if (dragging) { shadowElevation = 14f; scaleX = 1.02f; scaleY = 1.02f }
        }.then(if (handleOnly) Modifier else handle),
    ) { content(if (handleOnly) handle else Modifier, dragging) }
}

/** One song row: cover, title, "artist · album · LRC · RG · 3x", like, more, and duration (or live bars when playing). */
@Composable
fun TrackRow(t: Track, current: Boolean, playing: Boolean, liked: Boolean, lrc: Boolean, dragging: Boolean = false, onClick: () -> Unit, onLike: () -> Unit, onMore: () -> Unit) {
    val p = LocalPal.current
    val meta = buildList {
        add(t.artist)
        if (t.album.isNotEmpty() && t.album != t.artist && t.album != "Local audio" && t.album != "Unknown album") add(t.album)
        if (lrc) add("LRC")
        if (t.rgTrack != null) add("RG")
        if (t.playCount > 0) add("${t.playCount}×")
    }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(12.dp)).background(if (dragging) p.hover else if (current) p.accentSoft else Color.Transparent)
            .clickable(onClick = onClick).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(t.thumb ?: t.cover, 44.dp, radius = 8.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(t.title, color = if (current) p.accent else p.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, color = p.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (current && playing) Icon(Ic.Wave, "Playing", tint = p.accent, modifier = Modifier.size(16.dp))
        else Text(if (t.duration > 0) fmt(t.duration) else "--:--", color = p.faint, fontSize = 11.5.sp, fontFamily = Mono)
        IconBtn(if (liked) Ic.Heart else Ic.HeartOutline, if (liked) "Unlike song" else "Like song", tint = if (liked) p.accent2 else p.faint, size = 17.dp, onClick = onLike)
        IconBtn(Ic.More, "More options", size = 18.dp, onClick = onMore)
    }
}
