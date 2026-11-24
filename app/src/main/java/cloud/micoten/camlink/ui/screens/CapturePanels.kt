package cloud.micoten.camlink.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.micoten.camlink.camera.CameraFile
import cloud.micoten.camlink.camera.ExposureMode
import cloud.micoten.camlink.camera.ParamKind
import cloud.micoten.camlink.ui.CameraViewModel
import cloud.micoten.camlink.ui.Params
import cloud.micoten.camlink.ui.components.ChoiceRail
import cloud.micoten.camlink.ui.components.CommandButton
import cloud.micoten.camlink.ui.components.ControlSheet
import cloud.micoten.camlink.ui.components.FormField
import cloud.micoten.camlink.ui.components.Mark
import cloud.micoten.camlink.ui.components.PageHeading
import cloud.micoten.camlink.ui.components.SettingToggle
import cloud.micoten.camlink.ui.components.Symbol
import cloud.micoten.camlink.ui.components.UiTone
import cloud.micoten.camlink.ui.components.contrastInk
import cloud.micoten.camlink.ui.components.pressFeedback
import cloud.micoten.camlink.ui.theme.Xm
import cloud.micoten.camlink.util.Bitmaps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val primaryControls = listOf(ParamKind.SHUTTER, ParamKind.APERTURE, ParamKind.ISO)
private val secondaryControls = listOf(ParamKind.EXPOSURE_COMP, ParamKind.WHITE_BALANCE)

@Composable
internal fun ExposureConsole(
    params: Params,
    mode: ExposureMode,
    lensFocus: String?,
    online: Boolean,
    compact: Boolean,
    enabled: Boolean,
    editable: (ParamKind) -> Boolean,
    onChoose: (ParamKind) -> Unit,
) {
    val shape = RoundedCornerShape(15.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Xm.card).border(1.dp, Xm.line2, shape)) {
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = if (compact) 4.dp else 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "曝光参数",
                color = UiTone.ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (online && lensFocus != null)
                Text(
                    lensFocus,
                    color = Xm.blueInk,
                    fontSize = 11.sp,
                    modifier =
                        Modifier.clip(RoundedCornerShape(5.dp))
                            .background(Xm.blueSoft)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            if (online && params.shots != "—")
                Text("可拍 ${params.shots} 张", color = UiTone.ink2, fontSize = 11.sp)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            for (kind in primaryControls) {
                val writable =
                    online && editable(kind) && params.options[kind]?.isNotEmpty() == true
                val value =
                    if (kind == ParamKind.SHUTTER) mode.shutterPlaceholder() ?: params.shutter
                    else params.parameterText(kind)
                ExposureCell(Modifier.weight(1f), kind, value, writable, enabled, compact) {
                    onChoose(kind)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (kind in secondaryControls) {
                val writable =
                    online && editable(kind) && params.options[kind]?.isNotEmpty() == true
                Row(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .pressFeedback(writable && enabled) { onChoose(kind) }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        kind.label,
                        color = UiTone.ink2,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        params.parameterText(kind),
                        Modifier.weight(1f),
                        color = UiTone.ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (writable && enabled) Mark(Symbol.Chevron, Xm.accentDeep, 13.dp)
                }
            }
        }
    }
}

@Composable
private fun ExposureCell(
    modifier: Modifier,
    kind: ParamKind,
    value: String,
    writable: Boolean,
    enabled: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val available = writable && enabled
    val line by
        animateColorAsState(
            if (available) Xm.accent.copy(alpha = .45f) else Xm.line,
            label = "exposureBorder",
        )
    Column(
        modifier
            .clip(shape)
            .background(Xm.tagBg)
            .border(1.dp, line, shape)
            .pressFeedback(available, onClick)
            .heightIn(min = if (compact) 48.dp else 63.dp)
            .padding(horizontal = 8.dp, vertical = if (compact) 4.dp else 7.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (kind == ParamKind.ISO) "ISO" else kind.label,
                Modifier.weight(1f),
                fontSize = 10.sp,
                color = UiTone.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (available) Mark(Symbol.Chevron, Xm.accentDeep, 13.dp)
        }
        if (writable) {
            AnimatedContent(
                value,
                transitionSpec = {
                    (fadeIn(tween(150)) + slideInVertically(tween(200)) { it / 3 }) togetherWith
                        (fadeOut(tween(85)) + slideOutVertically(tween(150)) { -it / 3 })
                },
                label = "exposureValue",
            ) {
                ExposureValue(it, compact, if (available) UiTone.ink else UiTone.ink2)
            }
        } else {
            ExposureValue(value, compact, UiTone.ink2)
        }
    }
}

@Composable
private fun ExposureValue(value: String, compact: Boolean, tint: Color) {
    Text(
        value,
        fontSize = if (compact) 17.sp else 22.sp,
        color = tint,
        fontWeight = FontWeight.SemiBold,
        style = TextStyle(fontFeatureSettings = "tnum"),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun RecentShotKey(
    vm: CameraViewModel,
    file: CameraFile?,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var bmp by remember(file, active) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file, active) {
        if (!active || file == null) return@LaunchedEffect
        bmp =
            try {
                vm.thumb(file)?.let { withContext(Dispatchers.Default) { Bitmaps.decode(it, 180) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
    }
    Column(
        modifier
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(8.dp))
            .semantics { contentDescription = if (file == null) "查看照片" else "查看最近拍摄" }
            .pressFeedback(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        val shape = RoundedCornerShape(6.dp)
        Box(
            Modifier.size(42.dp, 34.dp)
                .clip(shape)
                .background(Xm.tagBg)
                .border(1.dp, Xm.line2, shape),
            contentAlignment = Alignment.Center,
        ) {
            val image = bmp
            if (image == null) Mark(Symbol.Wall, UiTone.ink, 20.dp)
            else
                Image(
                    image.asImageBitmap(),
                    null,
                    Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
        }
        Text(if (file == null) "照片" else "最近拍摄", fontSize = 11.sp, color = UiTone.ink, maxLines = 1)
    }
}

@Composable
internal fun ShutterControl(
    label: String,
    enabled: Boolean,
    active: Boolean,
    movie: Boolean,
    busy: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val tint =
        animateColorAsState(
            when {
                !enabled && !busy -> UiTone.line2
                movie || active -> Xm.rec
                else -> Xm.accent
            },
            label = "shutterColor",
        )
    val keySize = if (compact) 64.dp else 76.dp
    val housing = RoundedCornerShape(21.dp)
    val stop =
        animateFloatAsState(
            if (active) 1f else 0f,
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
            label = "shutterShape",
        )
    val rotation =
        if (busy) {
            rememberInfiniteTransition(label = "shutterPending")
                .animateFloat(
                    0f,
                    360f,
                    infiniteRepeatable(tween(900, easing = LinearEasing)),
                    label = "shutterRing",
                )
        } else null
    val rim = Xm.line2
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(keySize + 13.dp, keySize)
                .clip(housing)
                .background(Xm.card)
                .border(1.dp, rim, housing)
                .semantics {
                    contentDescription = label
                    if (busy) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                }
                .pressFeedback(enabled, onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(keySize - 8.dp)) {
                val edge = 2.dp.toPx()
                val track = Size(size.width - 2 * edge, size.height - 2 * edge)
                drawCircle(rim, radius = track.width / 2, style = Stroke(edge))
                if (rotation != null) {
                    drawArc(
                        tint.value,
                        rotation.value - 90f,
                        108f,
                        useCenter = false,
                        topLeft = Offset(edge, edge),
                        size = track,
                        style = Stroke(edge, cap = StrokeCap.Round),
                    )
                } else if (enabled) {
                    drawCircle(
                        tint.value.copy(alpha = .58f),
                        radius = track.width / 2,
                        style = Stroke(edge),
                    )
                }
                val morph = stop.value.coerceIn(0f, 1f)
                val diameter = size.minDimension * (.78f - morph * .35f)
                val corner = diameter * (.5f - morph * .28f)
                drawRoundRect(
                    tint.value,
                    center - Offset(diameter / 2, diameter / 2),
                    Size(diameter, diameter),
                    CornerRadius(corner),
                )
            }
            if (enabled && !active)
                Mark(if (movie) Symbol.Live else Symbol.Aperture, contrastInk(tint.value), 24.dp)
        }
        Text(label, fontSize = 11.sp, color = UiTone.ink, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureTimerSheet(onDismiss: () -> Unit, onStart: (Int, Int, Int, Boolean) -> Unit) {
    var type by rememberSaveable { mutableIntStateOf(0) }
    var selfieSeconds by rememberSaveable { mutableIntStateOf(2) }
    var countText by rememberSaveable { mutableStateOf("10") }
    var gapText by rememberSaveable { mutableStateOf("3") }
    var delayText by rememberSaveable { mutableStateOf("0") }
    var focus by rememberSaveable { mutableStateOf(true) }
    val count = if (type == 0) 1 else countText.toIntOrNull()?.takeIf { it in 2..999 }
    val gap = if (type == 0) 1 else gapText.toIntOrNull()?.takeIf { it in 1..600 }
    val firstDelay =
        if (type == 0) selfieSeconds else delayText.toIntOrNull()?.takeIf { it in 0..600 }

    ControlSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PageHeading("定时拍摄", null)
            ChoiceRail(listOf("自拍", "间隔拍摄"), type) { type = it }
            if (type == 0) {
                val times = listOf(2, 5, 10)
                ChoiceRail(times.map { "$it 秒" }, times.indexOf(selfieSeconds)) {
                    selfieSeconds = times[it]
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    TimerField("张数", countText, count != null, Modifier.weight(1f)) {
                        countText = it
                    }
                    TimerField("间隔（秒）", gapText, gap != null, Modifier.weight(1f)) { gapText = it }
                    TimerField("延时（秒）", delayText, firstDelay != null, Modifier.weight(1f)) {
                        delayText = it
                    }
                }
                if (count != null && gap != null && firstDelay != null) {
                    Text(
                        "最后一张约在 ${cameraClock((count - 1) * gap + firstDelay)} 后开始",
                        color = UiTone.ink2,
                        fontSize = 12.sp,
                    )
                } else {
                    Text("张数 2–999，间隔 1–600 秒，延时 0–600 秒", color = Xm.rec, fontSize = 12.sp)
                }
            }
            SettingToggle(if (type == 0) "拍摄前自动对焦" else "每张重新对焦", focus) { focus = it }
            CommandButton(
                label = if (type == 0) "开始自拍" else "开始间隔拍摄",
                modifier = Modifier.fillMaxWidth(),
                icon = Symbol.Timer,
                primary = true,
                enabled = count != null && gap != null && firstDelay != null,
            ) {
                if (count != null && gap != null && firstDelay != null)
                    onStart(count, gap, firstDelay, focus)
            }
        }
    }
}

@Composable
private fun TimerField(
    label: String,
    value: String,
    valid: Boolean,
    modifier: Modifier,
    onValue: (String) -> Unit,
) {
    FormField(
        label = label,
        value = value,
        hint = "0",
        modifier = modifier,
        error = !valid && value.isNotEmpty(),
        keyboard = KeyboardType.Number,
    ) { text ->
        onValue(text.filter { it in '0'..'9' }.take(3))
    }
}
