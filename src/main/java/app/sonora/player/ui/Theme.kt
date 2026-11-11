package app.sonora.player.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.sonora.player.R
import app.sonora.player.S
import coil.compose.AsyncImage
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One palette per theme: the same tokens as the web app's CSS variables (--bg, --panel, --accent, ...). */
data class Pal(
    val id: String, val light: Boolean, val bg: Color, val side: Color, val panel: Color, val bar: Color, val border: Color,
    val text: Color, val muted: Color, val faint: Color, val accent: Color, val accent2: Color, val accentInk: Color,
    val hover: Color, val input: Color, val accentSoft: Color, val tag: String,
)

val PALETTES: Map<String, Pal> = listOf(
    Pal("dark", false, Color(0xFF111415), Color(0xFF151819), Color(0xFF171B1C), Color(0xFF171A1B), Color(0xFF2A3030), Color(0xFFF2F4F1), Color(0xFF929B99), Color(0xFF858D8A), Color(0xFFCBEF79), Color(0xFF8FE388), Color(0xFF18200D), Color(0xFF202627), Color(0xFF202526), Color(0x1CCBEF79), ""),
    Pal("light", true, Color(0xFFF5F5EF), Color(0xFFEBEEE7), Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFFD9DED6), Color(0xFF17211F), Color(0xFF5C6863), Color(0xFF5D6864), Color(0xFF556E19), Color(0xFF0A7268), Color(0xFFFFFFFF), Color(0xFFDFE6D9), Color(0xFFE7EBE3), Color(0x1F6C8D20), ""),
    Pal("miku", false, Color(0xFF0B1517), Color(0xFF0F1D20), Color(0xFF132528), Color(0xFF112023), Color(0xFF234548), Color(0xFFEBFFFD), Color(0xFF8ABDB8), Color(0xFF719D9A), Color(0xFF39C5BB), Color(0xFFEA68A9), Color(0xFF052220), Color(0xFF1A3236), Color(0xFF193033), Color(0x2439C5BB), "01"),
    Pal("teto", false, Color(0xFF170E12), Color(0xFF1E1317), Color(0xFF25181D), Color(0xFF1E1317), Color(0xFF472C35), Color(0xFFFFF0F2), Color(0xFFC498A2), Color(0xFFA6848C), Color(0xFFF0546C), Color(0xFFE9B56F), Color(0xFF29070D), Color(0xFF322027), Color(0xFF301F26), Color(0x24F0546C), "0401"),
    Pal("neru", false, Color(0xFF15130B), Color(0xFF1C1A10), Color(0xFF242115), Color(0xFF1C1A10), Color(0xFF464026), Color(0xFFFFF9DF), Color(0xFFC2B788), Color(0xFF9F9670), Color(0xFFFFD60A), Color(0xFFFF8A1F), Color(0xFF241E00), Color(0xFF332F1E), Color(0xFF302C1C), Color(0x24FFD60A), "DEN2"),
    Pal("vinyl", false, Color(0xFF0B0A14), Color(0x8C100E20), Color(0xB817142B), Color(0xD9221D40), Color(0x1AFFFFFF), Color(0xFFF3F0FF), Color(0xFFA8A3C9), Color(0xFF928DB4), Color(0xFFB4A0FF), Color(0xFFFF8FB8), Color(0xFF140C33), Color(0x12FFFFFF), Color(0x14FFFFFF), Color(0x29B4A0FF), ""),
).associateBy { it.id }

val LocalPal = staticCompositionLocalOf { PALETTES.getValue("dark") }

private fun family(res: Int) = FontFamily(listOf(400, 500, 600, 700, 800).map { Font(res, FontWeight(it), variationSettings = FontVariation.Settings(FontVariation.weight(it))) })

/** Manrope for headings, DM Sans for body - the same pairing as the web app. */
val Manrope = family(R.font.manrope)
val DmSans = family(R.font.dmsans)

@Composable
fun resolvePal(): Pal {
    val theme by S.settings.collectAsState()
    val sysDark = isSystemInDarkTheme()
    return PALETTES[if (theme.theme == "system") (if (sysDark) "dark" else "light") else theme.theme] ?: PALETTES.getValue("dark")
}

@Composable
fun SonoraTheme(content: @Composable () -> Unit) {
    val p = resolvePal()
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = p.light
                isAppearanceLightNavigationBars = p.light
            }
        }
    }
    val solidPanel = if (p.id == "vinyl") Color(0xFF17142B) else p.panel
    val scheme = (if (p.light) lightColorScheme() else darkColorScheme()).copy(
        primary = p.accent, onPrimary = p.accentInk, secondary = p.accent2, onSecondary = p.accentInk, background = p.bg, onBackground = p.text,
        surface = solidPanel, onSurface = p.text, surfaceVariant = p.input, onSurfaceVariant = p.muted, outline = p.border, outlineVariant = p.border,
        surfaceContainer = solidPanel, surfaceContainerLow = solidPanel, surfaceContainerLowest = p.bg, surfaceContainerHigh = solidPanel,
        surfaceContainerHighest = p.input, primaryContainer = p.accentSoft, onPrimaryContainer = p.accent, secondaryContainer = p.accentSoft,
        onSecondaryContainer = p.text, inverseSurface = p.text, inverseOnSurface = p.bg, scrim = Color.Black,
    )
    val body = TextStyle(fontFamily = DmSans, color = p.text)
    val type = Typography().let {
        it.copy(
            bodyLarge = it.bodyLarge.merge(body), bodyMedium = it.bodyMedium.merge(body), bodySmall = it.bodySmall.merge(body),
            labelLarge = it.labelLarge.merge(body), labelMedium = it.labelMedium.merge(body), labelSmall = it.labelSmall.merge(body),
            titleLarge = it.titleLarge.merge(TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold)),
            titleMedium = it.titleMedium.merge(TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold)),
            headlineMedium = it.headlineMedium.merge(TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)),
        )
    }
    CompositionLocalProvider(LocalPal provides p) { MaterialTheme(colorScheme = scheme, typography = type, content = content) }
}

private fun DrawScope.glow(color: Color, center: Offset, radius: Float, alpha: Float) =
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center, radius), radius, center)

/** An Archimedean spiral: Teto's drill twin-tails. */
private fun DrawScope.spiral(center: Offset, maxR: Float, turns: Float, color: Color, width: Float) {
    val path = Path()
    val steps = (turns * 48).toInt()
    for (i in 0..steps) {
        val a = i / 48f * 2 * PI.toFloat()
        val r = maxR * i / steps
        val x = center.x + r * cos(a)
        val y = center.y + r * sin(a) * 1.25f
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
}

/**
 * The themed backdrop behind the whole app.
 *  - Miku: synth-style waveform grid with two ribbon-like twin-tail curves (teal into pink) and a "01" mark.
 *  - Teto: twin drill spirals in rose and warm gold, "0401".
 *  - Neru: LCD dot matrix with amber glow and phone signal bars, "DEN2".
 *  - Vinyl: frosted glass over a blurred glow of the current cover art.
 */
@Composable
fun ThemedBackground(cover: String?) {
    val p = LocalPal.current
    Box(Modifier.fillMaxSize().background(p.bg)) {
        if (p.id == "vinyl") {
            if (cover != null && Build.VERSION.SDK_INT >= 31) {
                AsyncImage(model = File(cover), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(90.dp).alpha(0.5f))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x660B0A14), Color(0xE60B0A14)))))
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            when (p.id) {
                "miku" -> {
                    glow(p.accent, Offset(w * 0.85f, 0f), w * 0.9f, 0.16f)
                    glow(p.accent2, Offset(0f, h * 0.9f), w * 0.7f, 0.07f)
                    val step = 26.dp.toPx()
                    val line = p.accent.copy(alpha = 0.05f)
                    var x = 0f
                    while (x < w) { drawLine(line, Offset(x, 0f), Offset(x, h), 1f); x += step }
                    var y = 0f
                    while (y < h) { drawLine(line, Offset(0f, y), Offset(w, y), 1f); y += step }
                    val ribbon = Brush.linearGradient(listOf(p.accent.copy(alpha = 0.28f), Color(0xFFE12885).copy(alpha = 0.16f), Color.Transparent), Offset(w, 0f), Offset(w * 0.3f, h * 0.55f))
                    for (k in 0..1) {
                        val path = Path().apply {
                            moveTo(w * (0.98f - k * 0.16f), -10f)
                            cubicTo(w * (1.05f - k * 0.2f), h * 0.18f, w * (0.62f - k * 0.1f), h * 0.2f, w * (0.74f - k * 0.22f), h * (0.46f + k * 0.06f))
                        }
                        drawPath(path, ribbon, style = Stroke((14 - k * 5).dp.toPx(), cap = StrokeCap.Round))
                    }
                }
                "teto" -> {
                    glow(p.accent, Offset(0f, 0f), w * 0.9f, 0.15f)
                    glow(p.accent2, Offset(w, h), w * 0.8f, 0.08f)
                    spiral(Offset(w * 0.1f, h * 0.2f), w * 0.34f, 5f, p.accent.copy(alpha = 0.10f), 5.dp.toPx())
                    spiral(Offset(w * 0.92f, h * 0.74f), w * 0.3f, 5f, p.accent2.copy(alpha = 0.09f), 5.dp.toPx())
                }
                "neru" -> {
                    glow(p.accent, Offset(w, 0f), w * 0.85f, 0.13f)
                    glow(p.accent2, Offset(0f, h), w * 0.7f, 0.07f)
                    val step = 18.dp.toPx()
                    val dot = p.accent.copy(alpha = 0.075f)
                    var y = step / 2
                    while (y < h) {
                        var x = step / 2
                        while (x < w) { drawCircle(dot, 1.3.dp.toPx(), Offset(x, y)); x += step }
                        y += step
                    }
                    // Phone signal bars: Neru is never without her clamshell.
                    for (i in 0..3) {
                        val bw = 5.dp.toPx()
                        val bh = (6 + i * 5).dp.toPx()
                        drawRect(p.accent.copy(alpha = 0.16f), Offset(w - 54.dp.toPx() + i * 9.dp.toPx(), 64.dp.toPx() - bh), androidx.compose.ui.geometry.Size(bw, bh))
                    }
                }
                "vinyl" -> {
                    glow(p.accent, Offset(w * 0.2f, h * 0.1f), w * 0.8f, 0.14f)
                    glow(p.accent2, Offset(w * 0.9f, h * 0.8f), w * 0.8f, 0.10f)
                }
            }
        }
    }
}

/** Theme swatch gradients for the Appearance picker. */
fun swatch(id: String): Brush = when (id) {
    "light" -> Brush.linearGradient(listOf(Color(0xFFF5F5EF), Color(0xFF556E19)))
    "system" -> Brush.linearGradient(listOf(Color(0xFF111415), Color(0xFF111415), Color(0xFFF5F5EF), Color(0xFFF5F5EF)))
    "miku" -> Brush.linearGradient(listOf(Color(0xFF39C5BB), Color(0xFFE12885)))
    "teto" -> Brush.linearGradient(listOf(Color(0xFFF0546C), Color(0xFFE9B56F)))
    "neru" -> Brush.linearGradient(listOf(Color(0xFFFFD60A), Color(0xFFFF8A1F)))
    "vinyl" -> Brush.linearGradient(listOf(Color(0xFFB4A0FF), Color(0xFFFF8FB8)))
    else -> Brush.linearGradient(listOf(Color(0xFF111415), Color(0xFFCBEF79)))
}
