package cloud.micoten.camlink.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.micoten.camlink.camera.CameraBattery
import cloud.micoten.camlink.ui.theme.LightXm
import cloud.micoten.camlink.ui.theme.Xm
import cloud.micoten.camlink.ui.theme.nightAppearance
import kotlin.math.sin

@Immutable
internal data class InterfaceColors(
    val bg: Color,
    val bgDeep: Color,
    val panel: Color,
    val panel2: Color,
    val line: Color,
    val line2: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
)

private val night =
    InterfaceColors(
        Color(0xFF1C1A21),
        Color(0xFF131117),
        Color(0xFF23202A),
        Color(0xFF2C2832),
        Color(0x12FFFFFF),
        Color(0x26FFFFFF),
        Color(0xFFF5F2F6),
        Color(0xFF9E98A6),
        Color(0xFF6B6574),
    )
private val day =
    InterfaceColors(
        LightXm.bg,
        LightXm.tagBg,
        LightXm.card,
        LightXm.tagBg,
        LightXm.line,
        LightXm.line2,
        LightXm.ink,
        LightXm.qInk,
        LightXm.ink2,
    )

internal val UiTone
    @Composable get() = if (nightAppearance) night else day

internal fun contrastInk(bg: Color): Color {
    val dark = Color(0xFF3A2E33)
    val luminance = bg.luminance() + .05f
    return if (luminance / (dark.luminance() + .05f) >= 1.05f / luminance) dark else Color.White
}

@Composable
internal fun Modifier.pressFeedback(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val amount =
        animateFloatAsState(
            if (pressed) 1f else 0f,
            spring(stiffness = Spring.StiffnessMedium),
            label = "controlPress",
        )
    return graphicsLayer {
            scaleX = 1f - amount.value * .027f
            scaleY = scaleX
            translationY = 1.5.dp.toPx() * amount.value
        }
        .clickable(
            interactionSource = interactions,
            indication = LocalIndication.current,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
}

@Composable
internal fun IconControl(
    icon: Symbol,
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    tint: Color = if (selected) Xm.accentDeep else UiTone.ink,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(11.dp)
    val background by
        animateColorAsState(if (selected) Xm.accentSoft else Xm.card, label = "iconSurface")
    Box(
        modifier
            .size(48.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, if (selected) Xm.accent.copy(alpha = .55f) else UiTone.line2, shape)
            .semantics { contentDescription = label }
            .pressFeedback(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Mark(icon, if (enabled) tint else UiTone.ink3, 21.dp)
    }
}

@Composable
internal fun CommandButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: Symbol? = null,
    enabled: Boolean = true,
    primary: Boolean = false,
    accent: Color = Xm.accent,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    val filled = primary && enabled
    val foreground =
        when {
            !enabled -> UiTone.ink3
            filled -> contrastInk(accent)
            else -> UiTone.ink
        }
    val edge = if (enabled) accent.copy(alpha = .52f) else UiTone.line
    Box(
        modifier
            .clip(shape)
            .background(if (filled) accent else Xm.card)
            .border(1.dp, if (filled) Color.Transparent else edge, shape)
            .pressFeedback(enabled, onClick)
    ) {
        Row(
            Modifier.align(Alignment.Center)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        ) {
            icon?.let { Mark(it, foreground, 17.dp) }
            Text(label, fontSize = 13.sp, color = foreground, fontWeight = FontWeight.SemiBold)
        }
        if (filled)
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(foreground.copy(alpha = .15f))
            )
    }
}

@Composable
internal fun ToolKey(
    icon: Symbol,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    tone: Color = if (active) Xm.accentDeep else UiTone.ink,
    onClick: () -> Unit,
) {
    val foreground = if (enabled || active) tone else UiTone.ink3
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .pressFeedback(enabled, onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 3.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        val keyShape =
            RoundedCornerShape(
                topStart = 13.dp,
                topEnd = 13.dp,
                bottomStart = 5.dp,
                bottomEnd = 5.dp,
            )
        Box(
            Modifier.size(width = 44.dp, height = 35.dp)
                .clip(keyShape)
                .background(if (active) Xm.accentSoft else Xm.tagBg)
                .border(1.dp, if (active) Xm.accent.copy(alpha = .45f) else UiTone.line, keyShape),
            contentAlignment = Alignment.Center,
        ) {
            Mark(icon, foreground, 19.dp)
        }
        Text(
            label,
            fontSize = 11.sp,
            color = foreground,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
internal fun SignalTag(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    surface: Color = Xm.tagBg,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        Text(
            text,
            color = UiTone.ink2,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun BatteryGauge(battery: CameraBattery, compact: Boolean = false) {
    val paint =
        when (battery) {
            CameraBattery.CRITICAL -> Xm.rec
            CameraBattery.LOW -> Xm.goldInk
            CameraBattery.UNKNOWN -> UiTone.ink3
            else -> Xm.ok
        }
    val title = if (compact && battery.bars >= 2) null else battery.label
    Row(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = "相机电量"
            stateDescription = battery.label
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            repeat(4) { index ->
                val color by
                    animateColorAsState(
                        if (
                            index < battery.bars ||
                                (index == 0 && battery == CameraBattery.CRITICAL)
                        )
                            paint
                        else UiTone.line2,
                        label = "batteryBar",
                    )
                Box(
                    Modifier.size(4.dp, (6 + index * 3).dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(color)
                )
            }
        }
        title?.let { Text(it, color = UiTone.ink, fontSize = 12.sp, maxLines = 1) }
    }
}

@Composable
internal fun PageHeading(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 65.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                Modifier.semantics { heading() },
                fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold,
                color = UiTone.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    color = UiTone.ink2,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

@Composable
internal fun ActivityMeter(modifier: Modifier = Modifier, color: Color = Xm.accentDeep) {
    val transition = rememberInfiniteTransition(label = "activityMeter")
    val time =
        transition.animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "activityPhase",
        )
    Canvas(modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        val width = size.width / 6f
        repeat(3) { i ->
            val wave = (sin((time.value - i * .18) * 2 * Math.PI).toFloat() + 1f) / 2f
            val height = size.height * (.30f + wave * .65f)
            drawRoundRect(
                color,
                Offset((2 * i + .5f) * width, (size.height - height) / 2f),
                Size(width, height),
                CornerRadius(width / 2f),
            )
        }
    }
}
