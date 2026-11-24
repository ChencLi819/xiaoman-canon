package cloud.micoten.camlink.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.micoten.camlink.camera.CameraFile
import cloud.micoten.camlink.ui.CameraViewModel
import cloud.micoten.camlink.ui.Link
import cloud.micoten.camlink.ui.TransferPhase
import cloud.micoten.camlink.ui.components.ActivityMeter
import cloud.micoten.camlink.ui.components.ChoiceRail
import cloud.micoten.camlink.ui.components.CommandButton
import cloud.micoten.camlink.ui.components.IconControl
import cloud.micoten.camlink.ui.components.Mark
import cloud.micoten.camlink.ui.components.PageHeading
import cloud.micoten.camlink.ui.components.Symbol
import cloud.micoten.camlink.ui.components.UiTone
import cloud.micoten.camlink.ui.components.contrastInk
import cloud.micoten.camlink.ui.theme.Xm
import cloud.micoten.camlink.util.Bitmaps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun GalleryScreen(
    vm: CameraViewModel,
    active: Boolean,
    openTarget: CameraFile?,
    onTargetHandled: () -> Unit,
    onOpenConnection: () -> Unit,
) {
    val files by vm.files.collectAsState()
    val loading by vm.filesLoading.collectAsState()
    val error by vm.filesError.collectAsState()
    val transfers by vm.transfers.collectAsState()
    val link by vm.link.collectAsState()
    val online = link == Link.ONLINE
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<CameraFile?>(null) }
    var showTransfers by rememberSaveable { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<Int>()) }
    val shown =
        remember(files, filter) {
            when (filter) {
                1 -> files.filter { !it.isRaw && !it.isMovie }
                2 -> files.filter { it.isRaw }
                3 -> files.filter { it.isMovie }
                else -> files
            }
        }

    LaunchedEffect(active, online) {
        if (active && online) vm.reloadFiles(onlyIfNeeded = true)
        if (!online) {
            preview = null
            selecting = false
            selection = emptySet()
        }
    }
    LaunchedEffect(active, online, openTarget) {
        if (active && openTarget != null) {
            if (online) {
                preview =
                    files.firstOrNull { it.handle == openTarget.handle }
                        ?: openTarget.takeIf { it === vm.recentPhoto.value }
            }
            onTargetHandled()
        }
    }
    LaunchedEffect(files, selecting) {
        if (selecting && selection.isNotEmpty()) {
            selection = files.mapNotNullTo(HashSet()) { it.handle.takeIf(selection::contains) }
        }
    }
    val pending = transfers.count {
        it.phase == TransferPhase.QUEUED || it.phase == TransferPhase.COPYING
    }
    val failed = transfers.count { it.phase == TransferPhase.FAILED }

    Column(
        Modifier.fillMaxSize().background(Xm.bg).padding(horizontal = 15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PageHeading(
            "照片",
            when {
                !online -> "相机未连接"
                loading -> "正在读取存储卡"
                selecting -> "已选 ${selection.size} 项"
                error != null -> "读取存储卡失败"
                else -> "${shown.size} 个文件"
            },
        ) {
            IconControl(Symbol.Refresh, "刷新照片", enabled = online && !loading) { vm.reloadFiles() }
            IconControl(Symbol.Download, "传输记录", selected = pending > 0 || failed > 0) {
                showTransfers = true
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                ChoiceRail(listOf("全部", "JPEG", "RAW", "视频"), filter) {
                    filter = it
                    selection = emptySet()
                }
            }
            CommandButton(if (selecting) "完成" else "多选", enabled = online && files.isNotEmpty()) {
                selecting = !selecting
                selection = emptySet()
            }
        }

        if (shown.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 23.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (loading) ActivityMeter(Modifier.size(27.dp), color = Xm.accent)
                else Mark(Symbol.Wall, UiTone.ink2, 38.dp)
                Text(
                    when {
                        !online -> "连接相机后查看存储卡"
                        loading -> "正在读取照片"
                        error != null -> error.orEmpty()
                        files.isEmpty() -> "存储卡暂无文件"
                        else -> "没有此格式的文件"
                    },
                    color = UiTone.ink2,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                )
                if (!online) CommandButton("连接相机", primary = true, onClick = onOpenConnection)
                else if (!loading && error != null)
                    CommandButton("重试", icon = Symbol.Refresh) { vm.reloadFiles() }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(102.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.handle }) { file ->
                    MediaContactTile(
                        vm,
                        file,
                        active,
                        selecting,
                        file.handle in selection,
                        modifier =
                            Modifier.animateItem(fadeInSpec = tween(160), fadeOutSpec = tween(110)),
                        onSelect = {
                            selecting = true
                            selection =
                                if (file.handle in selection) selection - file.handle
                                else selection + file.handle
                        },
                    ) {
                        if (online) preview = file
                    }
                }
            }
        }
        AnimatedVisibility(
            selecting,
            enter = fadeIn(tween(160)) + expandVertically(),
            exit = fadeOut(tween(100)) + shrinkVertically(),
        ) {
            CommandButton(
                "回传 ${selection.size} 个文件",
                modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp),
                icon = Symbol.Download,
                enabled = online && selection.isNotEmpty(),
                primary = true,
            ) {
                vm.enqueueTransfers(shown.filter { it.handle in selection })
                selection = emptySet()
                selecting = false
                showTransfers = true
            }
        }
    }
    preview?.let { file ->
        val record = transfers.firstOrNull { it.file.handle == file.handle }
        PhotoReview(vm, file, online, record, onClose = { preview = null })
    }
    if (showTransfers)
        TransferPanel(transfers, online, onRetry = { vm.enqueueTransfers(listOf(it)) }) {
            showTransfers = false
        }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaContactTile(
    vm: CameraViewModel,
    file: CameraFile,
    active: Boolean,
    selecting: Boolean,
    chosen: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file, active) {
        if (!active || bitmap != null) return@LaunchedEffect
        bitmap =
            try {
                vm.thumb(file)?.let { withContext(Dispatchers.Default) { Bitmaps.decode(it, 360) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
    }
    val shape = RoundedCornerShape(9.dp)
    val surface by
        animateColorAsState(if (chosen) Xm.accentSoft else Xm.card, label = "contactSurface")
    val stroke by animateColorAsState(if (chosen) Xm.accent else Xm.line, label = "contactBorder")
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(surface)
            .border(1.dp, stroke, shape)
            .semantics {
                selected = chosen
                contentDescription = file.name
            }
            .combinedClickable(
                role = Role.Button,
                onLongClick = onSelect,
                onClick = if (selecting) onSelect else onOpen,
            )
    ) {
        Box(
            Modifier.fillMaxWidth()
                .padding(4.dp)
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(Xm.tagBg),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(bitmap, label = "contactThumbnail") { image ->
                if (image != null)
                    Image(
                        image.asImageBitmap(),
                        null,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                else
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Mark(Symbol.Wall, UiTone.ink2, 24.dp)
                    }
            }
            SelectionBadge(selecting, chosen, Modifier.align(Alignment.TopEnd).padding(5.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                file.name,
                Modifier.weight(1f),
                color = UiTone.ink,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file.isRaw || file.isMovie) {
                Text(
                    if (file.isRaw) "RAW" else "视频",
                    color = Xm.blueInk,
                    fontSize = 9.sp,
                    modifier =
                        Modifier.clip(RoundedCornerShape(3.dp))
                            .background(Xm.blueSoft)
                            .padding(horizontal = 3.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SelectionBadge(visible: Boolean, chosen: Boolean, modifier: Modifier) {
    AnimatedVisibility(
        visible,
        modifier,
        enter = fadeIn() + scaleIn(initialScale = .72f),
        exit = fadeOut() + scaleOut(targetScale = .72f),
    ) {
        val shape = RoundedCornerShape(5.dp)
        Box(
            Modifier.size(25.dp)
                .clip(shape)
                .background(if (chosen) Xm.accent else Color.Black.copy(alpha = .60f))
                .border(1.dp, if (chosen) Xm.accent else Color.White.copy(alpha = .72f), shape),
            contentAlignment = Alignment.Center,
        ) {
            if (chosen) Mark(Symbol.Check, contrastInk(Xm.accent), 16.dp)
        }
    }
}
