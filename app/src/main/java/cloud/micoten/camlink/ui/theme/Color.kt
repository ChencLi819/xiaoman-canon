package cloud.micoten.camlink.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/*
 * 小满·卡农 双色身份系统（沿用 xiaoman 视觉语言）：
 * 暖白底 #FBF7F4 / 珊瑚强调 #E86A7C / 雾蓝 #6E93C8 / 暖金 #D99A2B。
 * 取景框是这台 App 唯一允许出现的“深色面”，用 frame 单列一个 token。
 */

@Immutable
data class XmColors(
    val bg: Color,
    val card: Color,
    val line: Color,
    val line2: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentDeep: Color,
    val blue: Color,
    val blueSoft: Color,
    val blueInk: Color,
    val gold: Color,
    val goldSoft: Color,
    val goldInk: Color,
    val ok: Color,
    val okSoft: Color,
    val q: Color,
    val qSoft: Color,
    val qInk: Color,
    val rec: Color,
    val navbg: Color,
    val tagBg: Color,
    /** 取景画布底色（永远偏暗，画面未到时当噪点底） */
    val frame: Color,
)

val LightXm =
    XmColors(
        bg = Color(0xFFFBF7F4),
        card = Color(0xFFFFFFFF),
        line = Color(0xFFF0EAE5),
        line2 = Color(0xFFE5DCD8),
        ink = Color(0xFF2C2733),
        ink2 = Color(0xFF8A8494),
        ink3 = Color(0xFFB4AEB9),
        accent = Color(0xFFE86A7C),
        accentSoft = Color(0xFFFDE7E4),
        accentDeep = Color(0xFFC9526A),
        blue = Color(0xFF6E93C8),
        blueSoft = Color(0xFFEBF2FB),
        blueInk = Color(0xFF5A80B8),
        gold = Color(0xFFD99A2B),
        goldSoft = Color(0xFFFFF6E8),
        goldInk = Color(0xFFB98A38),
        ok = Color(0xFF4FA383),
        okSoft = Color(0xFFE7F5EF),
        q = Color(0xFF9B8AA6),
        qSoft = Color(0xFFF3EFF7),
        qInk = Color(0xFF7A6B85),
        rec = Color(0xFFD8455C),
        navbg = Color(0xFFFCFAFB),
        tagBg = Color(0xFFF5F0EE),
        frame = Color(0xFF23202A),
    )

val DarkXm =
    XmColors(
        bg = Color(0xFF141316),
        card = Color(0xFF1E1D22),
        line = Color(0xFF2E2C33),
        line2 = Color(0xFF3B3943),
        ink = Color(0xFFE6E1E5),
        ink2 = Color(0xFFA9A3AE),
        ink3 = Color(0xFF78727F),
        accent = Color(0xFFEFB8C8),
        accentSoft = Color(0xFF4A3038),
        accentDeep = Color(0xFFF7CAD7),
        blue = Color(0xFFA8C4EC),
        blueSoft = Color(0xFF242C3C),
        blueInk = Color(0xFF9FBADF),
        gold = Color(0xFFE3B860),
        goldSoft = Color(0xFF3A2F1C),
        goldInk = Color(0xFFD6AC5A),
        ok = Color(0xFF7FCCAB),
        okSoft = Color(0xFF1E3229),
        q = Color(0xFFC0AECB),
        qSoft = Color(0xFF2E2733),
        qInk = Color(0xFFB9A6C6),
        rec = Color(0xFFF07A8C),
        navbg = Color(0xFF1A191E),
        tagBg = Color(0xFF2A2830),
        frame = Color(0xFF0C0B0F),
    )

val LightScheme: ColorScheme =
    lightColorScheme(
        primary = Color(0xFFE86A7C),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFDE7E4),
        onPrimaryContainer = Color(0xFFC9526A),
        secondary = Color(0xFF6E93C8),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFEBF2FB),
        onSecondaryContainer = Color(0xFF5A80B8),
        tertiary = Color(0xFF4FA383),
        onTertiary = Color.White,
        background = Color(0xFFFBF7F4),
        onBackground = Color(0xFF2C2733),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF2C2733),
        surfaceVariant = Color(0xFFF5F0EE),
        onSurfaceVariant = Color(0xFF8A8494),
        outline = Color(0xFFB4AEB9),
        outlineVariant = Color(0xFFF0EAE5),
        surfaceContainer = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFCFAFB),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerHigh = Color(0xFFFFFFFF),
        surfaceContainerHighest = Color(0xFFFFFFFF),
    )

val DarkScheme: ColorScheme =
    darkColorScheme(
        primary = Color(0xFFEFB8C8),
        onPrimary = Color(0xFF3A2228),
        primaryContainer = Color(0xFF4A3038),
        onPrimaryContainer = Color(0xFFF7CAD7),
        secondary = Color(0xFFA8C4EC),
        onSecondary = Color(0xFF1A2434),
        secondaryContainer = Color(0xFF242C3C),
        onSecondaryContainer = Color(0xFF9FBADF),
        tertiary = Color(0xFF7FCCAB),
        onTertiary = Color(0xFF12281F),
        background = Color(0xFF141316),
        onBackground = Color(0xFFE6E1E5),
        surface = Color(0xFF1E1D22),
        onSurface = Color(0xFFE6E1E5),
        surfaceVariant = Color(0xFF2A2830),
        onSurfaceVariant = Color(0xFFA9A3AE),
        outline = Color(0xFF78727F),
        outlineVariant = Color(0xFF2E2C33),
        surfaceContainer = Color(0xFF1E1D22),
        surfaceContainerLow = Color(0xFF1A191E),
        surfaceContainerLowest = Color(0xFF141316),
        surfaceContainerHigh = Color(0xFF232228),
        surfaceContainerHighest = Color(0xFF282730),
    )
