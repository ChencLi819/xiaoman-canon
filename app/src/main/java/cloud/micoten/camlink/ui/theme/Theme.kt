package cloud.micoten.camlink.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class AppAppearance(val label: String) {
    LIGHT("浅色"),
    NIGHT("夜间"),
}

private val LocalXm = staticCompositionLocalOf { DarkXm }
private val LocalAppearance = staticCompositionLocalOf { AppAppearance.NIGHT }

/* 字阶（四级）：Title 20 SemiBold / Body 14 / Caption 12 / Label 11 Medium */
val XmTypography =
    Typography(
        headlineMedium =
            TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
            ),
        titleMedium =
            TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
        labelSmall =
            TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
    )

val Xm: XmColors
    @Composable get() = LocalXm.current
val T: Typography
    @Composable get() = MaterialTheme.typography
val nightAppearance: Boolean
    @Composable get() = LocalAppearance.current == AppAppearance.NIGHT

@Composable
fun XiaomanCanonTheme(appearance: AppAppearance, content: @Composable () -> Unit) {
    val night = appearance == AppAppearance.NIGHT
    CompositionLocalProvider(
        LocalAppearance provides appearance,
        LocalXm provides if (night) DarkXm else LightXm,
    ) {
        MaterialTheme(
            colorScheme = if (night) DarkScheme else LightScheme,
            typography = XmTypography,
            content = content,
        )
    }
}
