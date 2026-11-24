package cloud.micoten.camlink.ui.screens

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.micoten.camlink.camera.ExposureMode
import cloud.micoten.camlink.camera.PreviewFocusArea
import cloud.micoten.camlink.ui.AfState
import cloud.micoten.camlink.ui.Link
import cloud.micoten.camlink.ui.LiveFrame
import cloud.micoten.camlink.ui.ViewfinderPhase
import cloud.micoten.camlink.ui.ViewfinderState
import cloud.micoten.camlink.ui.components.ActivityMeter
import cloud.micoten.camlink.ui.components.CommandButton
import cloud.micoten.camlink.ui.components.Mark
import cloud.micoten.camlink.ui.components.Symbol
import cloud.micoten.camlink.ui.components.pressFeedback
import cloud.micoten.camlink.ui.theme.DarkXm
import cloud.micoten.camlink.util.AppLog
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ViewfinderDisplay(
    modifier: Modifier,
    frames: StateFlow<LiveFrame?>,
    lowLatency: Boolean,
    active: Boolean,
    feed: ViewfinderState,
    link: Link,
    linkMessage: String,
    bleReady: Boolean,
    grid: Boolean,
    mode: ExposureMode,
    focusModeLabel: String,
    timer: String?,
    captured: Int?,
    focus: Offset?,
    af: AfState,
    afSeq: Long,
    canFocus: Boolean,
    onFocus: (Offset, Int, Int) -> Unit,
    onPrimary: () -> Unit,
    onStop: () -> Unit,
    onGrid: () -> Unit,
    expanded: Boolean,
    onExpand: () -> Unit,
) {
    val phase = feed.phase
    val stream = link == Link.ONLINE && phase == ViewfinderPhase.STREAMING
    val displayFrames = active && stream
    val bitmap = remember(frames, displayFrames) { mutableStateOf<DecodedPreview?>(null) }
    val metrics = remember(bitmap) { PreviewRenderMetrics() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(bitmap, lowLatency) {
        if (!displayFrames) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            var lastPublished = 0L
            frames.collect { frame ->
                if (frame == null) {
                    bitmap.value = null
                    lastPublished = 0L
                    return@collect
                }
                val jpeg = frame.jpeg
                val start = SystemClock.elapsedRealtimeNanos()
                val image =
                    BitmapFactory.decodeByteArray(jpeg.bytes, jpeg.offset, jpeg.length)
                        ?.asImageBitmap() ?: return@collect
                coroutineContext.ensureActive()
                val newest = frames.value ?: return@collect
                val now = SystemClock.elapsedRealtimeNanos()
                if (
                    lowLatency &&
                        lastPublished != 0L &&
                        shouldSkipDecodedFrame(
                            newest !== frame,
                            now - frame.receivedAtNanos,
                            now - lastPublished,
                        )
                ) {
                    metrics.skipped.incrementAndGet()
                    return@collect
                }
                bitmap.value =
                    DecodedPreview(
                        image,
                        frame.receivedAtNanos,
                        now - start,
                        jpeg.focusWidth.takeIf { it > 0 } ?: image.width,
                        jpeg.focusHeight.takeIf { it > 0 } ?: image.height,
                        jpeg.focusArea,
                    )
                lastPublished = now
            }
        }
    }
    val hasFrame by remember(bitmap) { derivedStateOf { bitmap.value != null } }
    val reportFocus = rememberUpdatedState(onFocus)
    val focusEnabled = rememberUpdatedState(canFocus)
    val focusScale = remember { Animatable(1f) }
    val shutterFlash = remember { Animatable(0f) }
    var previousCapture by remember { mutableStateOf(captured) }
    LaunchedEffect(focus, afSeq) {
        if (focus != null) {
            focusScale.snapTo(1.35f)
            focusScale.animateTo(1f, spring())
        }
    }
    LaunchedEffect(captured) {
        val isNew = captured != null && previousCapture != captured
        previousCapture = captured
        if (isNew && active) {
            shutterFlash.snapTo(.16f)
            shutterFlash.animateTo(0f, tween(230))
        } else shutterFlash.snapTo(0f)
    }
    val focusColor by
        animateColorAsState(
            when (af) {
                AfState.OK -> DarkXm.ok
                AfState.FAIL -> DarkXm.rec
                else -> DarkXm.accent
            },
            label = "focusReticle",
        )
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(DarkXm.frame).border(1.dp, DarkXm.line2, shape)) {
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(bitmap) {
                detectTapGestures { point ->
                    val preview = bitmap.value ?: return@detectTapGestures
                    val image = preview.image
                    if (!focusEnabled.value) return@detectTapGestures
                    val rect =
                        fittedFrame(
                            Size(size.width.toFloat(), size.height.toFloat()),
                            image.width,
                            image.height,
                        )
                    if (!rect.contains(point)) return@detectTapGestures
                    val normalized =
                        Offset(
                            (point.x - rect.left) / rect.width,
                            (point.y - rect.top) / rect.height,
                        )
                    reportFocus.value(
                        normalized,
                        (normalized.x * preview.focusWidth)
                            .toInt()
                            .coerceIn(0, preview.focusWidth - 1),
                        (normalized.y * preview.focusHeight)
                            .toInt()
                            .coerceIn(0, preview.focusHeight - 1),
                    )
                }
            }
        ) {
            if (hasFrame) {
                Canvas(Modifier.fillMaxSize()) {
                    // Read each frame in the draw phase so it does not recompose the controls.
                    val preview = bitmap.value ?: return@Canvas
                    val image = preview.image
                    val rect = fittedFrame(size, image.width, image.height)
                    if (rect.isEmpty) return@Canvas
                    drawImage(
                        image,
                        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                        dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                        filterQuality = FilterQuality.Low,
                    )
                    metrics.rendered(preview)?.let { report ->
                        scope.launch(Dispatchers.Default) { AppLog.d("LivePreview", report) }
                    }
                    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                        if (grid) {
                            val color = Color.White.copy(alpha = .24f)
                            for (i in 1..2) {
                                val x = rect.left + rect.width * i / 3f
                                val y = rect.top + rect.height * i / 3f
                                drawLine(
                                    color,
                                    Offset(x, rect.top),
                                    Offset(x, rect.bottom),
                                    1.dp.toPx(),
                                )
                                drawLine(
                                    color,
                                    Offset(rect.left, y),
                                    Offset(rect.right, y),
                                    1.dp.toPx(),
                                )
                            }
                        }
                        val cameraFocus = preview.focusArea
                        if (cameraFocus != null) {
                            val scaleX = rect.width / preview.focusWidth
                            val scaleY = rect.height / preview.focusHeight
                            val color =
                                when (cameraFocus.status) {
                                    0x31 -> DarkXm.ok
                                    0x32 -> DarkXm.rec
                                    else -> DarkXm.accent
                                }
                            drawRoundRect(
                                color = color,
                                topLeft =
                                    Offset(
                                        rect.left + cameraFocus.x * scaleX,
                                        rect.top + cameraFocus.y * scaleY,
                                    ),
                                size =
                                    Size(cameraFocus.width * scaleX, cameraFocus.height * scaleY),
                                cornerRadius = CornerRadius(3.dp.toPx()),
                                style = Stroke(1.5.dp.toPx()),
                            )
                        } else if (focus != null) {
                            val point =
                                Offset(
                                    rect.left + focus.x * rect.width,
                                    rect.top + focus.y * rect.height,
                                )
                            val half = 16.dp.toPx() * focusScale.value
                            val tick = half * .53f
                            for (x in -1..1 step 2) for (y in -1..1 step 2) {
                                val corner = point + Offset(x * half, y * half)
                                drawLine(
                                    focusColor,
                                    corner,
                                    corner - Offset(x * tick, 0f),
                                    2.dp.toPx(),
                                    StrokeCap.Round,
                                )
                                drawLine(
                                    focusColor,
                                    corner,
                                    corner - Offset(0f, y * tick),
                                    2.dp.toPx(),
                                    StrokeCap.Round,
                                )
                            }
                        }
                        if (shutterFlash.value > 0f)
                            drawRect(
                                Color.White.copy(alpha = shutterFlash.value),
                                rect.topLeft,
                                rect.size,
                            )
                    }
                }
            } else {
                FinderEmptyState(
                    link,
                    linkMessage,
                    bleReady,
                    feed,
                    active,
                    if (feed.retryStop) onStop else onPrimary,
                )
            }
        }
        if (link == Link.ONLINE) {
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 49.dp)
                    .background(DarkXm.card)
                    .padding(start = 10.dp, end = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when (mode) {
                            ExposureMode.MOVIE -> "短片模式"
                            ExposureMode.UNKNOWN -> "机身模式未读取"
                            else -> "拍照 · ${mode.label}"
                        },
                        color = if (mode == ExposureMode.MOVIE) DarkXm.rec else DarkXm.ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(focusModeLabel, color = DarkXm.ink2, fontSize = 11.sp, maxLines = 1)
                    if (timer != null)
                        Text(timer, color = DarkXm.rec, fontSize = 12.sp, maxLines = 1)
                }
                FinderKey(
                    Symbol.Grid,
                    if (grid) "关闭构图网格" else "显示构图网格",
                    selected = grid,
                    enabled = stream,
                    onClick = onGrid,
                )
                FinderKey(
                    if (expanded) Symbol.Collapse else Symbol.Expand,
                    if (expanded) "收起取景画面" else "扩大取景画面",
                    selected = expanded,
                    onClick = onExpand,
                )
                if (
                    stream || phase == ViewfinderPhase.STARTING || phase == ViewfinderPhase.STOPPING
                ) {
                    FinderKey(
                        Symbol.Power,
                        "停止实时取景",
                        enabled = phase != ViewfinderPhase.STOPPING,
                        visibleLabel = "关闭",
                        onClick = onStop,
                    )
                }
            }
        }
    }
}

private class DecodedPreview(
    val image: ImageBitmap,
    val receivedAt: Long,
    val decodeNanos: Long,
    val focusWidth: Int,
    val focusHeight: Int,
    val focusArea: PreviewFocusArea?,
)

internal fun shouldSkipDecodedFrame(
    hasNewer: Boolean,
    frameAgeNanos: Long,
    sincePublishedNanos: Long,
): Boolean = hasNewer && frameAgeNanos >= 50_000_000L && sincePublishedNanos < 120_000_000L

/** 只统计首次进入绘制的帧，构图线或 AF 动画的重绘不计入帧率。 */
private class PreviewRenderMetrics {
    val skipped = AtomicInteger()
    private var lastFrameAt = 0L
    private var windowStart = 0L
    private var frames = 0
    private var decodeNanos = 0L
    private var phoneNanos = 0L

    fun rendered(frame: DecodedPreview): String? {
        if (frame.receivedAt == lastFrameAt) return null
        lastFrameAt = frame.receivedAt
        val now = SystemClock.elapsedRealtimeNanos()
        if (windowStart == 0L) windowStart = now
        frames++
        decodeNanos += frame.decodeNanos
        phoneNanos += now - frame.receivedAt
        val period = now - windowStart
        if (period < 5_000_000_000L) return null
        val report =
            "手机取景：${frames * 1_000_000_000L / period} 帧/秒，" +
                "平均解码 ${decodeNanos / frames / 1_000_000}ms，" +
                "收帧到绘制 ${phoneNanos / frames / 1_000_000}ms，" +
                "${frame.image.width}×${frame.image.height}，跳过过期帧 ${skipped.getAndSet(0)}"
        windowStart = now
        frames = 0
        decodeNanos = 0
        phoneNanos = 0
        return report
    }
}

@Composable
private fun FinderKey(
    icon: Symbol,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    visibleLabel: String? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .semantics { contentDescription = label }
            .pressFeedback(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        val ink =
            if (enabled && selected) DarkXm.accentDeep else if (enabled) DarkXm.ink else DarkXm.ink3
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Mark(icon, ink, 20.dp)
            if (visibleLabel != null)
                Text(visibleLabel, color = ink, fontSize = 10.sp, maxLines = 1)
        }
        if (enabled && selected) {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .size(14.dp, 2.dp)
                    .background(DarkXm.accent)
            )
        }
    }
}

@Composable
private fun FinderEmptyState(
    link: Link,
    message: String,
    bleReady: Boolean,
    feed: ViewfinderState,
    active: Boolean,
    onPrimary: () -> Unit,
) {
    val changing =
        link == Link.WORKING ||
            (link == Link.ONLINE &&
                feed.phase in
                    setOf(
                        ViewfinderPhase.STARTING,
                        ViewfinderPhase.STREAMING,
                        ViewfinderPhase.STOPPING,
                    ))
    val label =
        when {
            link == Link.WORKING -> message.ifBlank { "正在连接相机" }
            link == Link.OFFLINE && bleReady -> "蓝牙已就绪"
            link == Link.OFFLINE ->
                message.takeUnless { it.isBlank() || it == "未连接" } ?: "连接相机以开始取景"
            feed.phase == ViewfinderPhase.FAILED -> feed.error ?: "取景中断"
            feed.phase == ViewfinderPhase.STOPPING -> "正在关闭取景"
            changing -> "正在接收画面"
            else -> "实时取景已关闭"
        }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(11.dp, Alignment.CenterVertically),
    ) {
        val plate = RoundedCornerShape(12.dp)
        Box(
            Modifier.size(54.dp)
                .clip(plate)
                .background(DarkXm.card)
                .border(1.dp, DarkXm.line2, plate),
            contentAlignment = Alignment.Center,
        ) {
            if (changing && active) ActivityMeter(Modifier.size(23.dp), color = DarkXm.accent)
            else Mark(if (link == Link.ONLINE) Symbol.Live else Symbol.Camera, DarkXm.accent, 27.dp)
        }
        Text(
            label,
            color = if (feed.phase == ViewfinderPhase.FAILED) DarkXm.rec else DarkXm.ink2,
            fontSize = 12.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (!changing) {
            CommandButton(
                label =
                    when {
                        link == Link.OFFLINE && bleReady -> "连接 Wi-Fi"
                        link == Link.OFFLINE -> "连接相机"
                        feed.retryStop -> "重试关闭取景"
                        feed.phase == ViewfinderPhase.FAILED -> "重试取景"
                        else -> "开启取景"
                    },
                primary = true,
                accent = DarkXm.accent,
                onClick = onPrimary,
            )
        }
    }
}

internal fun fittedFrame(view: Size, width: Int, height: Int): Rect {
    if (width <= 0 || height <= 0) return Rect.Zero
    val scale = minOf(view.width / width, view.height / height)
    val frame = Size(width * scale, height * scale)
    return Rect(Offset((view.width - frame.width) / 2, (view.height - frame.height) / 2), frame)
}
