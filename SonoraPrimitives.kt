// Sonora Native — SonoraPrimitives.kt
// Custom Compose design system matching legacy CSS themes, fonts, icons, and backgrounds (Zero Material3).

package app.sonora.player.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonora.player.R
import app.sonora.player.data.TrackRecord
import app.sonora.player.library.LibraryRepo
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalTheme = staticCompositionLocalOf { SonoraThemes.miku }

// Extension bridges on ThemeTokens & SonoraThemes so UI components have ergonomic access to all tokens
val SonoraThemes.Miku: ThemeTokens get() = miku
val SonoraThemes.All: List<ThemeTokens> get() = all
fun SonoraThemes.byId(id: String): ThemeTokens = byId(id, systemDark = true)

val ThemeTokens.name: String
    get() = when (id) {
        "miku" -> "Miku 01"
        "teto" -> "Teto 0401"
        "neru" -> "Neru DEN2"
        "vinyl" -> "Vinyl"
        "light" -> "Light"
        else -> "Dark"
    }
val ThemeTokens.bgSurface: Color get() = panel
val ThemeTokens.bgElevated: Color get() = input
val ThemeTokens.bgHover: Color get() = hover
val ThemeTokens.borderStrong: Color get() = border
val ThemeTokens.textSub: Color get() = muted
val ThemeTokens.textMuted: Color get() = faint
val ThemeTokens.accentGlow: Color get() = accentSoft
val ThemeTokens.danger: Color get() = Color(0xFFFF5C77)
val ThemeTokens.warn: Color get() = Color(0xFFFFB84D)

data class IconSpec(
    val key: String,
    val pathD: String,
    val filled: Boolean = false,
)

private fun iconFromTable(key: String, fallbackPath: String = "", forceFilled: Boolean? = null): IconSpec {
    val d = SonoraIcons.paths[key] ?: fallbackPath
    val isFilled = forceFilled ?: SonoraIcons.filled.contains(key)
    return IconSpec(key = key, pathD = d, filled = isFilled)
}

val SonoraIcons.Play: IconSpec get() = iconFromTable("play")
val SonoraIcons.Pause: IconSpec get() = iconFromTable("pause")
val SonoraIcons.SkipFwd: IconSpec get() = iconFromTable("next")
val SonoraIcons.SkipBack: IconSpec get() = iconFromTable("prev")
val SonoraIcons.Shuffle: IconSpec get() = iconFromTable("shuffle")
val SonoraIcons.Repeat: IconSpec get() = iconFromTable("repeat")
val SonoraIcons.RepeatOne: IconSpec get() = iconFromTable("loop")
val SonoraIcons.Folder: IconSpec get() = iconFromTable("folder")
val SonoraIcons.Music: IconSpec get() = iconFromTable("music")
val SonoraIcons.Playlist: IconSpec get() = iconFromTable("list-music")
val SonoraIcons.Queue: IconSpec get() = iconFromTable("list-music")
val SonoraIcons.Library: IconSpec get() = iconFromTable("folder-open")
val SonoraIcons.Home: IconSpec get() = iconFromTable("home")
val SonoraIcons.Lyrics: IconSpec get() = iconFromTable("lyrics")
val SonoraIcons.Sliders: IconSpec get() = iconFromTable("sliders")
val SonoraIcons.Wave: IconSpec get() = iconFromTable("wave")
val SonoraIcons.Settings: IconSpec get() = iconFromTable("gear")
val SonoraIcons.Plus: IconSpec get() = iconFromTable("plus")
val SonoraIcons.Close: IconSpec get() = iconFromTable("x")
val SonoraIcons.Check: IconSpec get() = iconFromTable("check")
val SonoraIcons.Clock: IconSpec get() = iconFromTable("clock")
val SonoraIcons.Download: IconSpec get() = iconFromTable("download")
val SonoraIcons.Upload: IconSpec get() = iconFromTable("upload")
val SonoraIcons.Search: IconSpec get() = iconFromTable("search")
val SonoraIcons.Heart: IconSpec get() = iconFromTable("heart")
val SonoraIcons.HeartFill: IconSpec get() = iconFromTable("heart", forceFilled = true)
val SonoraIcons.More: IconSpec get() = iconFromTable("more")
val SonoraIcons.ChevDown: IconSpec get() = iconFromTable("down")
val SonoraIcons.ChevRight: IconSpec get() = iconFromTable("right")
val SonoraIcons.Grid: IconSpec get() = iconFromTable("widget")
val SonoraIcons.List: IconSpec get() = iconFromTable("lyrics")
val SonoraIcons.Sort: IconSpec get() = iconFromTable("sliders")
val SonoraIcons.Refresh: IconSpec get() = iconFromTable("loop")
val SonoraIcons.Disc: IconSpec get() = iconFromTable(
    "disc",
    "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 7a3 3 0 1 1 0 6 3 3 0 0 1 0-6z",
)
val SonoraIcons.Edit: IconSpec get() = iconFromTable(
    "edit",
    "M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z",
)
val SonoraIcons.Trash: IconSpec get() = iconFromTable(
    "trash",
    "M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6h14",
)
val SonoraIcons.Share: IconSpec get() = iconFromTable(
    "share",
    "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8M16 6l-4-4-4 4M12 2v13",
)
val SonoraIcons.Image: IconSpec get() = iconFromTable(
    "image",
    "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zm1.5 6a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3zM21 15l-5-5L5 21",
)
val SonoraIcons.Bookmark: IconSpec get() = iconFromTable(
    "bookmark",
    "M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z",
)
val SonoraIcons.Copy: IconSpec get() = iconFromTable("file")

object SonoraFonts {
    val DmSans = FontFamily(
        Font(R.font.dm_sans_regular, FontWeight.Normal),
        Font(R.font.dm_sans_medium, FontWeight.Medium),
        Font(R.font.dm_sans_semibold, FontWeight.SemiBold),
        Font(R.font.dm_sans_bold, FontWeight.Bold),
    )

    val Manrope = FontFamily(
        Font(R.font.manrope_regular, FontWeight.Medium),
        Font(R.font.manrope_semibold, FontWeight.SemiBold),
        Font(R.font.manrope_bold, FontWeight.Bold),
        Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
    )
}

object CoverBitmapCache {
    private val cache = object : LruCache<String, ImageBitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            (value.width * value.height * 4) / 1024
    }

    suspend fun loadFile(file: File): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = file.absolutePath
        cache.get(key)?.let { return@withContext it }
        runCatching {
            val bmp = BitmapFactory.decodeFile(key) ?: return@withContext null
            val img = bmp.asImageBitmap()
            cache.put(key, img)
            img
        }.getOrNull()
    }

    suspend fun loadVinylBlur(file: File): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "${file.absolutePath}#blur24"
        cache.get(key)?.let { return@withContext it }
        runCatching {
            val src = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
            val tiny = Bitmap.createScaledBitmap(src, 24, 24, true)
            val pixels = IntArray(24 * 24)
            val out = IntArray(24 * 24)
            tiny.getPixels(pixels, 0, 24, 0, 0, 24, 24)
            for (y in 0 until 24) {
                for (x in 0 until 24) {
                    var r = 0; var g = 0; var b = 0; var c = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = (x + dx).coerceIn(0, 23)
                            val ny = (y + dy).coerceIn(0, 23)
                            val p = pixels[ny * 24 + nx]
                            r += (p shr 16) and 0xFF
                            g += (p shr 8) and 0xFF
                            b += p and 0xFF
                            c++
                        }
                    }
                    out[y * 24 + x] = (0xFF shl 24) or ((r / c) shl 16) or ((g / c) shl 8) or (b / c)
                }
            }
            val blurred = Bitmap.createBitmap(out, 24, 24, Bitmap.Config.ARGB_8888)
            val img = blurred.asImageBitmap()
            cache.put(key, img)
            img
        }.getOrNull()
    }
}

private val vectorCache = HashMap<String, ImageVector>()

@Composable
fun SonoraIcon(
    spec: IconSpec,
    tint: Color = LocalTheme.current.text,
    size: Dp = 20.dp,
    modifier: Modifier = Modifier,
) {
    val vector = remember(spec) {
        val cacheKey = "${spec.key}:${spec.filled}"
        vectorCache.getOrPut(cacheKey) {
            val builder = ImageVector.Builder(
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            )
            if (spec.pathD.isNotEmpty()) {
                val nodes = PathParser().parsePathString(spec.pathD).toNodes()
                builder.addPath(
                    pathData = nodes,
                    fill = if (spec.filled) SolidColor(Color.White) else null,
                    stroke = if (!spec.filled) SolidColor(Color.White) else null,
                    strokeLineWidth = 2f,
                )
            }
            builder.build()
        }
    }
    Image(
        imageVector = vector,
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

@Composable
fun SonoraText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalTheme.current.text,
    fontSize: TextUnit = 14.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    fontFamily: FontFamily = SonoraFonts.DmSans,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Start,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            textAlign = textAlign,
            letterSpacing = letterSpacing,
            lineHeight = lineHeight,
        ),
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
fun SectionKicker(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalTheme.current.textMuted,
) {
    SonoraText(
        text = text.uppercase(),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        fontFamily = SonoraFonts.Manrope,
        letterSpacing = 1.4.sp,
        modifier = modifier,
    )
}

@Composable
fun SonoraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: IconSpec? = null,
    primary: Boolean = false,
    danger: Boolean = false,
    compact: Boolean = false,
) {
    val theme = LocalTheme.current
    val bg = when {
        danger -> theme.danger.copy(alpha = 0.16f)
        primary -> theme.accent
        else -> theme.bgElevated
    }
    val fg = when {
        danger -> theme.danger
        primary -> theme.accentInk
        else -> theme.text
    }
    val borderCol = when {
        danger -> theme.danger.copy(alpha = 0.45f)
        primary -> theme.accent
        else -> theme.borderStrong
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(if (compact) 10.dp else 12.dp))
            .background(bg)
            .border(1.dp, borderCol, RoundedCornerShape(if (compact) 10.dp else 12.dp))
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (compact) 11.dp else 15.dp,
                vertical = if (compact) 7.dp else 10.dp,
            ),
    ) {
        if (icon != null) {
            SonoraIcon(icon, tint = fg, size = if (compact) 15.dp else 17.dp)
            if (text.isNotEmpty()) Spacer(Modifier.width(6.dp))
        }
        if (text.isNotEmpty()) {
            SonoraText(
                text = text,
                color = fg,
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = SonoraFonts.Manrope,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun SonoraChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalTheme.current
    val bg = if (selected) theme.accentSoft else theme.bgElevated
    val border = if (selected) theme.accent else theme.border
    val fg = if (selected) theme.accent else theme.textSub
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
    ) {
        SonoraText(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            fontFamily = SonoraFonts.Manrope,
            maxLines = 1,
        )
    }
}

@Composable
fun SonoraSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalTheme.current
    val bg = if (checked) theme.accent else theme.bgHover
    val border = if (checked) theme.accent else theme.borderStrong
    Box(
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = modifier
            .width(44.dp)
            .height(25.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(3.dp),
    ) {
        Box(
            modifier = Modifier
                .size(19.dp)
                .clip(CircleShape)
                .background(if (checked) theme.accentInk else theme.textSub)
        )
    }
}

@Composable
fun SonoraSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalTheme.current
    var widthPx by remember { mutableIntStateOf(1) }
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
    val frac = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { widthPx = max(1, it.width) }
            .pointerInput(valueRange) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun emitAt(x: Float) {
                        val f = (x / widthPx.toFloat()).coerceIn(0f, 1f)
                        onValueChange(valueRange.start + f * span)
                    }
                    emitAt(down.position.x)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        emitAt(change.position.x)
                        change.consume()
                    }
                }
            }
    ) {
        val trackH = 5.dp.toPx()
        val cy = size.height / 2f
        drawRoundRect(
            color = theme.bgHover,
            topLeft = Offset(0f, cy - trackH / 2f),
            size = Size(size.width, trackH),
            cornerRadius = CornerRadius(trackH / 2f, trackH / 2f),
        )
        val fillW = (size.width * frac).coerceIn(0f, size.width)
        drawRoundRect(
            color = theme.accent,
            topLeft = Offset(0f, cy - trackH / 2f),
            size = Size(fillW, trackH),
            cornerRadius = CornerRadius(trackH / 2f, trackH / 2f),
        )
        drawCircle(
            color = theme.text,
            radius = 7.dp.toPx(),
            center = Offset(fillW, cy),
        )
    }
}

@Composable
fun SonoraVerticalEqSlider(
    freqLabel: String,
    dbValue: Double,
    enabled: Boolean,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalTheme.current
    var heightPx by remember { mutableIntStateOf(1) }
    val frac = ((dbValue.toFloat() + 12f) / 24f).coerceIn(0f, 1f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.width(32.dp),
    ) {
        SonoraText(
            text = (if (dbValue >= 0) "+" else "") + String.format(java.util.Locale.US, "%.1f", dbValue),
            color = if (enabled) theme.textSub else theme.textMuted,
            fontSize = 9.sp,
            fontFamily = SonoraFonts.Manrope,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .width(26.dp)
                .height(136.dp)
                .onSizeChanged { heightPx = max(1, it.height) }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun emitAt(y: Float) {
                            val inv = 1f - (y / heightPx.toFloat()).coerceIn(0f, 1f)
                            val db = (-12.0 + inv * 24.0).coerceIn(-12.0, 12.0)
                            onValueChange((db * 2.0).toInt() / 2.0)
                        }
                        emitAt(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            emitAt(change.position.y)
                            change.consume()
                        }
                    }
                }
        ) {
            val cx = size.width / 2f
            val midY = size.height / 2f
            val trackW = 5.dp.toPx()
            drawRoundRect(
                color = theme.bgHover,
                topLeft = Offset(cx - trackW / 2f, 0f),
                size = Size(trackW, size.height),
                cornerRadius = CornerRadius(trackW / 2f, trackW / 2f),
            )
            drawLine(
                color = theme.borderStrong,
                start = Offset(cx - 7.dp.toPx(), midY),
                end = Offset(cx + 7.dp.toPx(), midY),
                strokeWidth = 1.dp.toPx(),
            )
            val thumbY = size.height * (1f - frac)
            val topY = min(midY, thumbY)
            val barH = kotlin.math.abs(thumbY - midY)
            drawRoundRect(
                color = if (enabled) theme.accent else theme.textMuted,
                topLeft = Offset(cx - trackW / 2f, topY),
                size = Size(trackW, barH.coerceAtLeast(2f)),
                cornerRadius = CornerRadius(trackW / 2f, trackW / 2f),
            )
            drawCircle(
                color = if (enabled) theme.text else theme.textMuted,
                radius = 6.dp.toPx(),
                center = Offset(cx, thumbY),
            )
        }
        Spacer(Modifier.height(4.dp))
        SonoraText(
            text = freqLabel,
            color = theme.textMuted,
            fontSize = 9.sp,
            fontFamily = SonoraFonts.Manrope,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun CoverArt(
    track: TrackRecord?,
    repo: LibraryRepo,
    size: Dp,
    small: Boolean = true,
    isPlaying: Boolean = false,
    vinylSpin: Boolean = false,
    cornerRadius: Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    val theme = LocalTheme.current
    var bitmap by remember(track?.id, track?.coverName, small) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(track?.id, track?.coverName, small) {
        if (track == null) {
            bitmap = null
        } else {
            val f = repo.resolveCoverFile(track, small)
            bitmap = if (f != null) CoverBitmapCache.loadFile(f) else null
        }
    }

    val isVinylStyle = vinylSpin || theme.id == "vinyl"
    val transition = rememberInfiniteTransition(label = "vinylSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "deg",
    )
    val rot = if (vinylSpin && isPlaying) angle else 0f

    val shape = if (vinylSpin) CircleShape else RoundedCornerShape(cornerRadius)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .rotate(rot)
            .clip(shape)
            .background(theme.bgElevated)
            .border(1.dp, theme.borderStrong, shape),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = track?.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(theme.accent.copy(alpha = 0.25f), theme.accent2.copy(alpha = 0.18f)),
                    )
                )
            }
            SonoraIcon(
                spec = if (isVinylStyle) SonoraIcons.Disc else SonoraIcons.Music,
                tint = theme.accent,
                size = size * 0.42f,
            )
        }

        if (vinylSpin) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = this.size.minDimension / 2f
                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                drawCircle(
                    color = Color.Black.copy(alpha = 0.25f),
                    radius = r * 0.88f,
                    center = c,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.25f),
                    radius = r * 0.72f,
                    center = c,
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawCircle(color = theme.bg, radius = r * 0.14f, center = c)
                drawCircle(
                    color = theme.accent,
                    radius = r * 0.14f,
                    center = c,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}

@Composable
fun SonoraBackgroundBox(
    currentTrack: TrackRecord?,
    repo: LibraryRepo,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val theme = LocalTheme.current
    var vinylGlow by remember(currentTrack?.id, theme.id) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(currentTrack?.id, theme.id) {
        if (theme.id == "vinyl" && currentTrack != null) {
            val f = repo.resolveCoverFile(currentTrack, small = true)
            vinylGlow = if (f != null) CoverBitmapCache.loadVinylBlur(f) else null
        } else {
            vinylGlow = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.bg),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(theme.accentGlow, Color.Transparent),
                    center = Offset(size.width * 0.18f, size.height * 0.08f),
                    radius = size.width * 0.75f,
                ),
                radius = size.width * 0.75f,
                center = Offset(size.width * 0.18f, size.height * 0.08f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(theme.accent2.copy(alpha = 0.11f), Color.Transparent),
                    center = Offset(size.width * 0.85f, size.height * 0.16f),
                    radius = size.width * 0.65f,
                ),
                radius = size.width * 0.65f,
                center = Offset(size.width * 0.85f, size.height * 0.16f),
            )

            when (theme.id) {
                "miku" -> {
                    val step = 28.dp.toPx()
                    val gridCol = theme.accent.copy(alpha = 0.03f)
                    var x = 0f
                    while (x < size.width) {
                        drawLine(gridCol, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                        x += step
                    }
                    var y = 0f
                    while (y < size.height) {
                        drawLine(gridCol, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        y += step
                    }
                }
                "teto" -> {
                    val step = 36.dp.toPx()
                    val stripeCol = theme.accent.copy(alpha = 0.025f)
                    var d = -size.height
                    while (d < size.width + size.height) {
                        drawLine(stripeCol, Offset(d, 0f), Offset(d + size.height, size.height), strokeWidth = 2f)
                        d += step
                    }
                }
                "neru" -> {
                    val step = 24.dp.toPx()
                    val dotCol = theme.accent.copy(alpha = 0.04f)
                    var y = step / 2f
                    while (y < size.height) {
                        var x = step / 2f
                        while (x < size.width) {
                            drawCircle(dotCol, radius = 1.2f, center = Offset(x, y))
                            x += step
                        }
                        y += step
                    }
                }
            }
        }

        if (vinylGlow != null) {
            Image(
                bitmap = vinylGlow!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.26f,
                modifier = Modifier.fillMaxSize(),
            )
        }

        content()
    }
}
