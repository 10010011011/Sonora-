package app.sonora.player.ui

import androidx.compose.ui.graphics.Color

/** GENERATED from index.css theme blocks. Colors are exact; gradients/patterns are kept as raw CSS in [raw] for the executor to port (see PLAN). */
class ThemeTokens(
    val id: String,
    val isDark: Boolean,
    val bg: Color,
    val side: Color,
    val panel: Color,
    val right: Color,
    val bar: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val accent2: Color,
    val accentInk: Color,
    val hover: Color,
    val input: Color,
    val accentSoft: Color,
    val brandTag: String,
    val raw: Map<String, String>,
)

object SonoraThemes {
    val dark = ThemeTokens(
        id = "dark", isDark = true,
        bg = Color(0xFF111415),
        side = Color(0xFF151819),
        panel = Color(0xFF171B1C),
        right = Color(0xFF151819),
        bar = Color(0xFF171A1B),
        border = Color(0xFF2A3030),
        text = Color(0xFFF2F4F1),
        muted = Color(0xFF929B99),
        faint = Color(0xFF858D8A),
        accent = Color(0xFFCBEF79),
        accent2 = Color(0xFF8FE388),
        accentInk = Color(0xFF18200D),
        hover = Color(0xFF202627),
        input = Color(0xFF202526),
        accentSoft = Color(0x1CCBEF79),
        brandTag = "",
        raw = mapOf(
            "bg-pattern" to """none""",
            "bg-pattern-size" to """auto""",
            "hero-top-line" to """linear-gradient(90deg, var(--accent), transparent)""",
        ),
    )
    val light = ThemeTokens(
        id = "light", isDark = false,
        bg = Color(0xFFF5F5EF),
        side = Color(0xFFEBEEE7),
        panel = Color(0xFFFFFFFF),
        right = Color(0xFFECF0E8),
        bar = Color(0xFFFFFFFF),
        border = Color(0xFFD9DED6),
        text = Color(0xFF17211F),
        muted = Color(0xFF5C6863),
        faint = Color(0xFF5D6864),
        accent = Color(0xFF556E19),
        accent2 = Color(0xFF0A7268),
        accentInk = Color(0xFFFFFFFF),
        hover = Color(0xFFDFE6D9),
        input = Color(0xFFE7EBE3),
        accentSoft = Color(0x1F6C8D20),
        brandTag = "",
        raw = mapOf(
            "bg-pattern" to """none""",
            "bg-pattern-size" to """auto""",
            "hero-top-line" to """linear-gradient(90deg, var(--accent), transparent)""",
        ),
    )
    val miku = ThemeTokens(
        id = "miku", isDark = true,
        bg = Color(0xFF0B1517),
        side = Color(0xFF0F1D20),
        panel = Color(0xFF132528),
        right = Color(0xFF0F1D20),
        bar = Color(0xFF112023),
        border = Color(0xFF234548),
        text = Color(0xFFEBFFFD),
        muted = Color(0xFF8ABDB8),
        faint = Color(0xFF719D9A),
        accent = Color(0xFF39C5BB),
        accent2 = Color(0xFFEA68A9),
        accentInk = Color(0xFF052220),
        hover = Color(0xFF1A3236),
        input = Color(0xFF193033),
        accentSoft = Color(0x2439C5BB),
        brandTag = "01",
        raw = mapOf(
            "bg-pattern" to """radial-gradient(circle at 88% 12%, rgba(225, 40, 133, 0.08), transparent 36%),
    linear-gradient(rgba(57, 197, 187, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(57, 197, 187, 0.045) 1px, transparent 1px)""",
            "bg-pattern-size" to """100% 100%, 26px 26px, 26px 26px""",
            "hero-top-line" to """linear-gradient(90deg, #39c5bb 0%, #e12885 70%, transparent 100%)""",
        ),
    )
    val teto = ThemeTokens(
        id = "teto", isDark = true,
        bg = Color(0xFF170E12),
        side = Color(0xFF1E1317),
        panel = Color(0xFF25181D),
        right = Color(0xFF1E1317),
        bar = Color(0xFF1E1317),
        border = Color(0xFF472C35),
        text = Color(0xFFFFF0F2),
        muted = Color(0xFFC498A2),
        faint = Color(0xFFA6848C),
        accent = Color(0xFFF0546C),
        accent2 = Color(0xFFE9B56F),
        accentInk = Color(0xFF29070D),
        hover = Color(0xFF322027),
        input = Color(0xFF301F26),
        accentSoft = Color(0x24F0546C),
        brandTag = "0401",
        raw = mapOf(
            "bg-pattern" to """radial-gradient(circle at 85% 14%, rgba(233, 181, 111, 0.08), transparent 38%),
    repeating-linear-gradient(135deg, rgba(240, 84, 108, 0.038) 0 11px, transparent 11px 22px)""",
            "bg-pattern-size" to """100% 100%, auto""",
            "hero-top-line" to """linear-gradient(90deg, #f0546c 0%, #e9b56f 75%, transparent 100%)""",
        ),
    )
    val neru = ThemeTokens(
        id = "neru", isDark = true,
        bg = Color(0xFF15130B),
        side = Color(0xFF1C1A10),
        panel = Color(0xFF242115),
        right = Color(0xFF1C1A10),
        bar = Color(0xFF1C1A10),
        border = Color(0xFF464026),
        text = Color(0xFFFFF9DF),
        muted = Color(0xFFC2B788),
        faint = Color(0xFF9F9670),
        accent = Color(0xFFFFD60A),
        accent2 = Color(0xFFFF8A1F),
        accentInk = Color(0xFF241E00),
        hover = Color(0xFF332F1E),
        input = Color(0xFF302C1C),
        accentSoft = Color(0x24FFD60A),
        brandTag = "DEN2",
        raw = mapOf(
            "bg-pattern" to """radial-gradient(circle at 86% 12%, rgba(255, 138, 31, 0.085), transparent 35%),
    radial-gradient(rgba(255, 214, 10, 0.075) 1.15px, transparent 1.25px)""",
            "bg-pattern-size" to """100% 100%, 18px 18px""",
            "hero-top-line" to """linear-gradient(90deg, #ffd60a 0%, #ff8a1f 75%, transparent 100%)""",
        ),
    )
    val vinyl = ThemeTokens(
        id = "vinyl", isDark = true,
        bg = Color(0xFF0B0A14),
        side = Color(0x8C100E20),
        panel = Color(0xFF17142B),
        right = Color(0x8C100E20),
        bar = Color(0x9E221D40),
        border = Color(0x1AFFFFFF),
        text = Color(0xFFF3F0FF),
        muted = Color(0xFFA8A3C9),
        faint = Color(0xFF928DB4),
        accent = Color(0xFFB4A0FF),
        accent2 = Color(0xFFFF8FB8),
        accentInk = Color(0xFF140C33),
        hover = Color(0x12FFFFFF),
        input = Color(0x14FFFFFF),
        accentSoft = Color(0x29B4A0FF),
        brandTag = "",
        raw = mapOf(
            "bg-pattern" to """radial-gradient(circle at 86% 6%, rgba(180, 160, 255, 0.22), transparent 42%),
    radial-gradient(circle at 8% 92%, rgba(255, 143, 184, 0.13), transparent 40%)""",
            "bg-pattern-size" to """100% 100%, 100% 100%""",
            "hero-top-line" to """linear-gradient(90deg, #b4a0ff, #ff8fb8 70%, transparent)""",
        ),
    )

    val all: List<ThemeTokens> = listOf(dark, light, miku, teto, neru, vinyl)

    /** theme setting value ("dark","light","system","miku","teto","neru","vinyl") -> tokens. */
    fun byId(id: String, systemDark: Boolean): ThemeTokens = when (id) {
        "light" -> light
        "miku" -> miku
        "teto" -> teto
        "neru" -> neru
        "vinyl" -> vinyl
        "system" -> if (systemDark) dark else light
        else -> dark
    }
}
