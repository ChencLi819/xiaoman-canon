package cloud.micoten.camlink.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.micoten.camlink.ui.theme.Xm

@Composable
internal fun SectionPanel(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape =
        RoundedCornerShape(topStart = 19.dp, topEnd = 19.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        title?.let {
            Text(
                it,
                Modifier.padding(start = 2.dp, bottom = 7.dp).semantics { heading() },
                fontSize = 14.sp,
                color = UiTone.ink,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Column(
            Modifier.fillMaxWidth()
                .clip(shape)
                .background(Xm.card)
                .border(1.dp, Xm.line, shape)
                .padding(horizontal = 13.dp, vertical = 3.dp),
            content = content,
        )
    }
}

@Composable
internal fun DetailRow(
    label: String,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    divider: Boolean = true,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val spoken = if (danger) Xm.rec else UiTone.ink
    val row = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
    Column(Modifier.fillMaxWidth()) {
        Row(
            (if (onClick != null) row.pressFeedback(onClick = onClick) else row)
                .heightIn(min = 49.dp)
                .padding(horizontal = 2.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, color = spoken, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (trailing != null || onClick != null) {
                    value
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            Text(
                                it,
                                color = UiTone.ink2,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                }
            }
            if (trailing == null && onClick == null && value != null) {
                Text(
                    value,
                    Modifier.weight(.9f, fill = false),
                    color = UiTone.ink2,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing?.invoke()
            if (onClick != null) Mark(Symbol.Chevron, Xm.accentDeep, 15.dp)
        }
        if (divider) Spacer(Modifier.fillMaxWidth().height(1.dp).background(Xm.line))
    }
}

@Composable
internal fun FormField(
    label: String,
    value: String,
    hint: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secret: Boolean = false,
    error: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    var shown by remember(secret) { mutableStateOf(false) }
    val shape =
        RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 3.dp, bottomEnd = 3.dp)
    Column(modifier.padding(vertical = 6.dp)) {
        Text(
            label,
            fontSize = 12.sp,
            color = UiTone.ink,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 1.dp, bottom = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth()
                .clip(shape)
                .heightIn(min = 48.dp)
                .background(Xm.tagBg)
                .padding(start = 10.dp, end = if (secret) 3.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                singleLine = true,
                textStyle =
                    TextStyle(color = if (enabled) UiTone.ink else UiTone.ink2, fontSize = 14.sp),
                cursorBrush = SolidColor(Xm.accentDeep),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = if (secret && !shown) KeyboardType.Password else keyboard
                    ),
                visualTransformation =
                    if (secret && !shown) PasswordVisualTransformation()
                    else VisualTransformation.None,
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty())
                            Text(hint, fontSize = 12.sp, color = UiTone.ink2, maxLines = 1)
                        inner()
                    }
                },
            )
            if (secret) {
                Box(
                    Modifier.heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .pressFeedback(enabled) { shown = !shown }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (shown) "隐藏" else "显示", fontSize = 12.sp, color = UiTone.ink)
                }
            }
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(if (error) Xm.rec else UiTone.line2))
    }
}

@Composable
internal fun SettingToggle(
    label: String,
    on: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val thumb by animateDpAsState(if (on) 22.dp else 3.dp, label = "switchThumb")
    val track by
        animateColorAsState(if (on && enabled) Xm.accentSoft else Xm.tagBg, label = "switchTrack")
    val thumbColor by
        animateColorAsState(if (on && enabled) Xm.accentDeep else UiTone.ink2, label = "switchInk")
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 52.dp)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            color = if (enabled) UiTone.ink else UiTone.ink2,
            fontSize = 13.sp,
        )
        val shape = RoundedCornerShape(6.dp)
        Box(
            Modifier.size(44.dp, 24.dp)
                .clip(shape)
                .background(track)
                .border(1.dp, UiTone.line2, shape),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier.offset { IntOffset(thumb.roundToPx(), 0) }
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(thumbColor)
            ) {
                if (on && enabled)
                    Box(Modifier.align(Alignment.Center)) {
                        Mark(Symbol.Check, contrastInk(thumbColor), 11.dp, 1.2.dp)
                    }
            }
        }
    }
}

@Composable
internal fun ChoiceRail(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    if (options.isEmpty()) return
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Xm.tagBg)
            .selectableGroup()
    ) {
        val itemWidth = maxWidth / options.size
        val position by
            animateDpAsState(itemWidth * selected.coerceIn(options.indices), label = "choiceMarker")
        if (selected in options.indices) {
            Spacer(
                Modifier.align(Alignment.BottomStart)
                    .offset { IntOffset((position + itemWidth * .19f).roundToPx(), 0) }
                    .width(itemWidth * .62f)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(Xm.accent)
            )
        }
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                val chosen = index == selected
                Box(
                    Modifier.weight(1f)
                        .heightIn(min = 47.dp)
                        .selectable(selected = chosen, role = Role.Tab) { onSelect(index) }
                        .padding(horizontal = 3.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option,
                        fontSize = 12.sp,
                        color = if (chosen) Xm.accentDeep else UiTone.ink2,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SelectableTile(
    label: String,
    detail: String?,
    icon: Symbol,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 53.dp)
            .clip(shape)
            .background(if (selected) Xm.accentSoft else Xm.tagBg)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Mark(icon, if (selected) Xm.accentDeep else UiTone.ink2, 19.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                label,
                fontSize = 13.sp,
                color = UiTone.ink,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    color = UiTone.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Mark(
            if (selected) Symbol.Check else Symbol.Chevron,
            if (selected) Xm.accentDeep else UiTone.ink2,
            15.dp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ControlSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 23.dp, topEnd = 23.dp),
        containerColor = Xm.card,
        contentColor = Xm.ink,
        dragHandle = {
            Row(
                Modifier.height(30.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) {
                    Spacer(Modifier.size(10.dp, 3.dp).clip(CircleShape).background(UiTone.line2))
                }
            }
        },
        content = content,
    )
}
