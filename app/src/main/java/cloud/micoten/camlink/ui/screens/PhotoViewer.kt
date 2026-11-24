package cloud.micoten.camlink.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cloud.micoten.camlink.camera.CameraFile
import cloud.micoten.camlink.camera.EosValues
import cloud.micoten.camlink.ui.CameraViewModel
import cloud.micoten.camlink.ui.FileTransfer
import cloud.micoten.camlink.ui.TransferPhase
import cloud.micoten.camlink.ui.components.CommandButton
import cloud.micoten.camlink.ui.components.ControlSheet
import cloud.micoten.camlink.ui.components.IconControl
import cloud.micoten.camlink.ui.components.Mark
import cloud.micoten.camlink.ui.components.PageHeading
import cloud.micoten.camlink.ui.components.Symbol
import cloud.micoten.camlink.ui.components.UiTone
import cloud.micoten.camlink.ui.theme.DarkXm
import cloud.micoten.camlink.ui.theme.Xm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun PhotoReview(
    vm: CameraViewModel,
    file: CameraFile,
    online: Boolean,
    transfer: FileTransfer?,
    onClose: () -> Unit,
) {
    var image by remember(file) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(file) { mutableStateOf(false) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var loadedFull by remember(file) { mutableStateOf(false) }
    var request by remember(file) { mutableIntStateOf(0) }
    var pct by remember(file) { mutableIntStateOf(-1) }

    LaunchedEffect(file, online, request) {
        if (!online) {
            loading = false
            error = "相机已断开"
            return@LaunchedEffect
        }
        loading = true
        pct = -1
        error = null
        val full = request > 0
        try {
            val bmp =
                withTimeoutOrNull(if (full) 50_000L else 15_000L) {
                    vm.loadPreview(file, full) { pct = it }
                }
            if (bmp != null) {
                image = bmp
                loadedFull = full
            } else error = if (full) "高清预览暂不可用，可重试" else "缩略图暂不可用"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "预览读取失败"
        } finally {
            loading = false
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(DarkXm.frame)
                .padding(horizontal = 15.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(DarkXm.card)
                    .padding(start = 11.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        file.name,
                        color = DarkXm.ink,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val type = if (file.isRaw) "RAW" else if (file.isMovie) "视频" else "JPEG"
                    Text(
                        "$type · ${EosValues.bytesText(file.size)}",
                        color = DarkXm.ink2,
                        fontSize = 12.sp,
                    )
                }
                IconControl(Symbol.Close, "关闭预览", onClick = onClose)
            }
            val imageShape = RoundedCornerShape(9.dp)
            Box(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .clip(imageShape)
                    .border(1.dp, DarkXm.line2, imageShape),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = image
                if (bmp != null) ZoomPhoto(bmp, Modifier.fillMaxSize())
                else
                    Text(
                        error ?: "正在读取预览",
                        color = DarkXm.ink2,
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                    )
            }
            if (loading) {
                ProgressStrip(pct.takeIf { it in 0..100 }, DarkXm.accent, DarkXm.line2)
            }
            val line =
                transfer?.let { it.error ?: transferLabel(it) } ?: error?.takeIf { image != null }
            if (line != null)
                Text(
                    line,
                    color =
                        if (transfer?.phase == TransferPhase.FAILED || error != null) DarkXm.rec
                        else DarkXm.ink2,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(DarkXm.card)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (vm.canPreviewFull(file))
                    CommandButton(
                        if (loadedFull) "高清已加载" else "高清预览",
                        Modifier.weight(1f),
                        enabled = online && !loading && !loadedFull,
                    ) {
                        request++
                    }
                CommandButton(
                    if (transfer?.phase == TransferPhase.FAILED) "重试保存" else "保存到手机",
                    Modifier.weight(1f),
                    icon = Symbol.Download,
                    primary = true,
                    accent = DarkXm.accent,
                    enabled =
                        online && (transfer == null || transfer.phase == TransferPhase.FAILED),
                ) {
                    vm.enqueueTransfers(listOf(file))
                }
            }
        }
    }
}

@Composable
private fun ZoomPhoto(bitmap: Bitmap, modifier: Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var position by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTransformGestures { center, pan, zoom, _ ->
                    val previous = scale
                    scale = (previous * zoom).coerceIn(1f, 4f)
                    val mid = Offset(size.width / 2f, size.height / 2f)
                    val adjusted =
                        (position - (center - mid)) * (scale / previous) + (center - mid) + pan
                    val x = size.width * (scale - 1f) / 2
                    val y = size.height * (scale - 1f) / 2
                    position = Offset(adjusted.x.coerceIn(-x, x), adjusted.y.coerceIn(-y, y))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        position = Offset.Zero
                    }
                )
            }
    ) {
        Image(
            bitmap.asImageBitmap(),
            null,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = position.x
                translationY = position.y
            },
            contentScale = ContentScale.Fit,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransferPanel(
    items: List<FileTransfer>,
    online: Boolean,
    onRetry: (CameraFile) -> Unit,
    onDismiss: () -> Unit,
) {
    ControlSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 20.dp)) {
            PageHeading("传输", items.takeIf { it.isNotEmpty() }?.let { "${it.size} 个文件" })
            if (items.isEmpty())
                Text("暂无传输记录", color = UiTone.ink2, modifier = Modifier.padding(vertical = 21.dp))
            else
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    contentPadding = PaddingValues(top = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(items, key = { it.file.handle }) { record ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(Xm.tagBg)
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Mark(
                                Symbol.Download,
                                if (record.phase == TransferPhase.FAILED) Xm.rec else Xm.accentDeep,
                                21.dp,
                            )
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text(
                                    record.file.name,
                                    color = UiTone.ink,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    record.error ?: transferLabel(record),
                                    color =
                                        if (record.phase == TransferPhase.FAILED) Xm.rec
                                        else UiTone.ink2,
                                    fontSize = 11.sp,
                                )
                                if (record.phase == TransferPhase.COPYING) {
                                    ProgressStrip(
                                        record.percent?.takeIf { it >= 0 },
                                        Xm.accent,
                                        UiTone.line2,
                                    )
                                }
                            }
                            if (record.phase == TransferPhase.FAILED && online) {
                                CommandButton("重试") { onRetry(record.file) }
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun ProgressStrip(percent: Int?, fill: Color, track: Color) {
    val range =
        if (percent == null) ProgressBarRangeInfo.Indeterminate
        else ProgressBarRangeInfo(percent.toFloat().coerceIn(0f, 100f), 0f..100f)
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(track)
            .semantics { progressBarRangeInfo = range }
    ) {
        if (percent == null) {
            val motion = rememberInfiniteTransition(label = "transferWaiting")
            val position by
                motion.animateFloat(
                    -.24f,
                    1f,
                    infiniteRepeatable(tween(1100)),
                    label = "transferPosition",
                )
            Spacer(
                Modifier.offset(x = maxWidth * position)
                    .width(maxWidth * .24f)
                    .fillMaxHeight()
                    .background(fill)
            )
        } else {
            val complete by
                animateFloatAsState(percent.coerceIn(0, 100) / 100f, label = "transferProgress")
            Spacer(Modifier.fillMaxWidth(complete).fillMaxHeight().background(fill))
        }
    }
}

private fun transferLabel(item: FileTransfer): String =
    when (item.phase) {
        TransferPhase.QUEUED -> "等待传输"
        TransferPhase.COPYING ->
            item.percent
                ?.takeIf { it >= 0 }
                ?.let {
                    if (it == 100) "正在保存" else "传输中 $it%"
                } ?: "正在传输"
        TransferPhase.SAVED -> "已保存到手机"
        TransferPhase.FAILED -> "传输失败"
    }
