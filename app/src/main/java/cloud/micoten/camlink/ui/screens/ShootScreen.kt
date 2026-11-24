package cloud.micoten.camlink.ui.screens

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.micoten.camlink.R
import cloud.micoten.camlink.ble.CanonBle
import cloud.micoten.camlink.camera.CameraFile
import cloud.micoten.camlink.camera.EosCamera
import cloud.micoten.camlink.camera.ExposureMode
import cloud.micoten.camlink.camera.ParamKind
import cloud.micoten.camlink.ui.AfState
import cloud.micoten.camlink.ui.CameraViewModel
import cloud.micoten.camlink.ui.CapturePhase
import cloud.micoten.camlink.ui.Link
import cloud.micoten.camlink.ui.Params
import cloud.micoten.camlink.ui.ViewfinderPhase
import cloud.micoten.camlink.ui.components.BatteryGauge
import cloud.micoten.camlink.ui.components.IconControl
import cloud.micoten.camlink.ui.components.PageHeading
import cloud.micoten.camlink.ui.components.Symbol
import cloud.micoten.camlink.ui.components.ToolKey
import cloud.micoten.camlink.ui.components.UiTone
import cloud.micoten.camlink.ui.sheets.BleSheet
import cloud.micoten.camlink.ui.sheets.ChoiceSheet
import cloud.micoten.camlink.ui.sheets.ConnectSheet
import cloud.micoten.camlink.ui.theme.Xm
import kotlinx.coroutines.delay

@Composable
fun ShootScreen(
    vm: CameraViewModel,
    active: Boolean,
    onOpenPhotos: (CameraFile?) -> Unit,
    onOpenConnection: () -> Unit,
) {
    val link by vm.link.collectAsState()
    val status by vm.status.collectAsState()
    val name by vm.cameraName.collectAsState()
    val profile by vm.profile.collectAsState()
    val p by vm.params.collectAsState()
    val feed by vm.viewfinder.collectAsState()
    val lowLatency by vm.lowLatencyPreview.collectAsState()
    val capture by vm.captureFeedback.collectAsState()
    val recent by vm.recentPhoto.collectAsState()
    val cameraMode by vm.exposureMode.collectAsState()
    val recording by vm.recording.collectAsState()
    val bulb by vm.bulbOpen.collectAsState()
    val busy by vm.busy.collectAsState()
    val interval by vm.intervalRunning.collectAsState()
    val stoppingInterval by vm.intervalStopping.collectAsState()
    val progress by vm.intervalProgress.collectAsState()
    val af by vm.af.collectAsState()
    val afSeq by vm.afSeq.collectAsState()
    val focusMode by vm.focusMode.collectAsState()
    val blePhase by vm.blePhase.collectAsState()
    val bleName by vm.bleName.collectAsState()
    val bleMessage by vm.bleMessage.collectAsState()

    val online = link == Link.ONLINE
    val bleReady = blePhase == CanonBle.Phase.READY
    val streaming = feed.phase == ViewfinderPhase.STREAMING
    val movie = cameraMode == ExposureMode.MOVIE
    val bulbReady = cameraMode.usesBulb(p.shutterCode)
    val manualFocus = focusMode == 3
    val autoFocus = focusMode == 0 || focusMode == 1 || focusMode == 2
    val ready = !busy && !interval
    var grid by rememberSaveable { mutableStateOf(true) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var setting by remember { mutableStateOf<ParamKind?>(null) }
    var timerSheet by rememberSaveable { mutableStateOf(false) }
    var connectSheet by rememberSaveable { mutableStateOf(false) }
    var bleSheet by rememberSaveable { mutableStateOf(false) }
    var target by remember { mutableStateOf<Offset?>(null) }
    val shutterView = LocalView.current

    LaunchedEffect(online, manualFocus, streaming) {
        if (online) connectSheet = false
        else {
            setting = null
            timerSheet = false
        }
        if (!streaming || manualFocus) target = null
    }

    var visibleCapture by remember { mutableStateOf(capture.phase) }
    LaunchedEffect(capture) {
        visibleCapture = capture.phase
        if (capture.phase == CapturePhase.SAVED) {
            delay(2400)
            visibleCapture = CapturePhase.IDLE
        }
    }
    var bulbSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(bulb) {
        bulbSeconds = 0
        val started = SystemClock.elapsedRealtime()
        while (bulb) {
            bulbSeconds = ((SystemClock.elapsedRealtime() - started) / 1000).toInt()
            delay(250)
        }
    }
    val countdown = intervalCountdown(progress)
    val focusText =
        when (focusMode) {
            0 -> "单次 AF"
            1 -> "伺服 AF"
            2 -> "智能 AF"
            3 -> "MF"
            else -> null
        }
    val shutterLabel =
        when {
            recording -> "停止录像"
            movie -> "开始录像"
            bulb -> "结束曝光"
            bulbReady -> "开始曝光"
            else -> "拍照"
        }

    BoxWithConstraints(Modifier.fillMaxSize().background(Xm.bg)) {
        val fontScale = LocalDensity.current.fontScale
        val compact = maxHeight < 610.dp || maxWidth < 360.dp || fontScale >= 1.25f
        val scroll =
            maxHeight < 460.dp || fontScale >= 1.55f || (fontScale >= 1.25f && maxHeight < 555.dp)
        val workspaceScroll = rememberScrollState()
        val pageModifier = if (scroll) Modifier.verticalScroll(workspaceScroll) else Modifier
        val monitorModifier =
            if (scroll) {
                val monitorHeight by
                    animateDpAsState(
                        if (expanded) 330.dp else 254.dp,
                        spring(stiffness = Spring.StiffnessMediumLow),
                        label = "monitorHeight",
                    )
                Modifier.height(monitorHeight)
            } else Modifier
        Column(
            Modifier.fillMaxSize()
                .then(pageModifier)
                .padding(horizontal = if (compact) 12.dp else 16.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 9.dp),
        ) {
            val model = if (online) name else if (bleReady) bleName else ""
            PageHeading(
                model.ifBlank { profile.model },
                when {
                    online -> "Wi-Fi 已连接"
                    link == Link.WORKING -> "正在连接"
                    bleReady -> "蓝牙遥控"
                    else -> "相机未连接"
                },
            ) {
                if (online) BatteryGauge(p.battery, compact = true)
                val symbol = if (bleReady && !online) Symbol.Bluetooth else Symbol.Wifi
                IconControl(symbol, if (online) "连接设置" else "连接相机", selected = online || bleReady) {
                    if (online) onOpenConnection() else connectSheet = true
                }
            }

            ViewfinderDisplay(
                modifier = (if (scroll) monitorModifier else Modifier.weight(1f)).fillMaxWidth(),
                frames = vm.frame,
                lowLatency = lowLatency,
                active = active,
                feed = feed,
                link = link,
                linkMessage = status,
                bleReady = bleReady,
                grid = grid,
                mode = cameraMode,
                focusModeLabel = focusText ?: "对焦模式未读取",
                timer =
                    when {
                        recording -> "● ${cameraClock(p.recordingSec)}"
                        bulb -> "B 门 ${cameraClock(bulbSeconds)}"
                        else -> null
                    },
                captured = recent?.handle,
                focus = target,
                af = af,
                afSeq = afSeq,
                canFocus =
                    online && streaming && autoFocus && ready && !bulb && af != AfState.WORKING,
                onFocus = { point, cameraPoint ->
                    target = point.takeIf { cameraPoint != null }
                    vm.touchAf(cameraPoint)
                },
                onPrimary = { if (online) vm.startLive() else connectSheet = true },
                onStop = vm::stopLive,
                onGrid = { grid = !grid },
                expanded = expanded,
                onExpand = { expanded = !expanded },
            )

            AnimatedVisibility(
                visible = !expanded,
                enter =
                    fadeIn(tween(185, delayMillis = 35)) +
                        expandVertically(
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                        ),
                exit =
                    fadeOut(tween(130)) +
                        shrinkVertically(
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                        ),
            ) {
                ExposureConsole(
                    p,
                    cameraMode,
                    focusText,
                    online,
                    compact,
                    ready && !bulb,
                    editable = { profile.isEditable(it, cameraMode) },
                    onChoose = { setting = it },
                )
            }

            val timer = progress
            val activity =
                when {
                    interval && stoppingInterval -> "正在停止定时拍摄"
                    interval && timer?.shooting == true -> "拍摄中 · ${timer.done + 1}/${timer.total}"
                    interval && timer != null && countdown > 0 ->
                        "下一张 ${countdown} 秒 · ${timer.done}/${timer.total} 张"
                    interval -> "准备定时拍摄"
                    visibleCapture != CapturePhase.IDLE -> capture.message
                    autoFocus && af == AfState.WORKING -> "正在对焦"
                    autoFocus && af == AfState.POSITION_UNAVAILABLE -> "相机对焦坐标未就绪"
                    autoFocus && af == AfState.UNCONFIRMED ->
                        stringResource(R.string.msg_af_no_result)
                    autoFocus && af == AfState.FAIL -> "未合焦"
                    autoFocus && af == AfState.ERROR -> "对焦操作失败"
                    !online && bleReady -> bleMessage
                    else -> ""
                }
            AnimatedContent(
                targetState = activity,
                contentKey = { if (interval) "timer" else it },
                transitionSpec = {
                    (fadeIn(tween(170)) + slideInVertically(tween(210)) { it / 3 }) togetherWith
                        (fadeOut(tween(100)) + slideOutVertically(tween(160)) { -it / 3 })
                },
                label = "cameraActivity",
            ) { text ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 22.dp).semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color =
                            when (visibleCapture) {
                                CapturePhase.FAILED -> Xm.rec
                                CapturePhase.SAVED -> Xm.ok
                                else -> UiTone.ink2
                            },
                    )
                }
            }

            val dock =
                RoundedCornerShape(
                    topStart = 22.dp,
                    topEnd = 22.dp,
                    bottomStart = 15.dp,
                    bottomEnd = 15.dp,
                )
            Row(
                Modifier.fillMaxWidth()
                    .clip(dock)
                    .background(Xm.tagBg)
                    .border(1.dp, Xm.line2, dock)
                    .padding(horizontal = if (compact) 4.dp else 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RecentShotKey(vm, recent, active, Modifier.weight(.9f)) { onOpenPhotos(recent) }
                ToolKey(
                    Symbol.Focus,
                    when {
                        manualFocus -> "手动对焦"
                        online && !autoFocus -> "对焦未知"
                        af == AfState.OK -> "已合焦"
                        af == AfState.FAIL || af == AfState.UNCONFIRMED || af == AfState.ERROR ->
                            "重试 AF"
                        af == AfState.WORKING -> "对焦中"
                        else -> "自动对焦"
                    },
                    modifier = Modifier.weight(1f),
                    enabled =
                        ready &&
                            !bulb &&
                            af != AfState.WORKING &&
                            (if (online) autoFocus else bleReady),
                    active = af == AfState.WORKING,
                    tone =
                        when (af) {
                            AfState.OK -> Xm.ok
                            AfState.FAIL,
                            AfState.ERROR -> Xm.rec
                            else -> UiTone.ink
                        },
                ) {
                    if (online) vm.afDrive() else vm.bleFocus()
                }
                ShutterControl(
                    shutterLabel,
                    ready && link != Link.WORKING && (online || bleReady),
                    active = recording || bulb,
                    movie = movie,
                    busy = busy,
                    compact = compact,
                ) {
                    shutterView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    if (online) vm.pressShutter() else vm.bleShoot()
                }
                ToolKey(
                    Symbol.Timer,
                    if (stoppingInterval) "停止中" else if (interval) "停止" else "定时",
                    modifier = Modifier.weight(1f),
                    active = interval,
                    enabled =
                        online &&
                            !stoppingInterval &&
                            (interval || (ready && !movie && !bulbReady && !bulb)),
                ) {
                    if (interval) vm.stopInterval() else timerSheet = true
                }
            }
        }
    }

    setting?.let { kind ->
        ChoiceSheet(
            kind,
            p.options[kind].orEmpty(),
            p.code(kind),
            p.parameterText(kind),
            onApply = { vm.applyParameter(kind, it) },
            onDismiss = { setting = null },
        )
    }
    if (timerSheet)
        CaptureTimerSheet(onDismiss = { timerSheet = false }) { count, gap, first, focus ->
            timerSheet = false
            vm.startInterval(count, gap, first, focus)
        }
    if (connectSheet)
        ConnectSheet(
            vm,
            onDismiss = { connectSheet = false },
            onOpenBle = {
                connectSheet = false
                bleSheet = true
            },
        )
    if (bleSheet) BleSheet(vm) { bleSheet = false }
}

private fun Params.code(kind: ParamKind): Int? =
    when (kind) {
        ParamKind.SHUTTER -> shutterCode
        ParamKind.APERTURE -> apertureCode
        ParamKind.ISO -> isoCode
        ParamKind.EXPOSURE_COMP -> ecCode
        ParamKind.WHITE_BALANCE -> wbCode
    }

internal fun Params.parameterText(kind: ParamKind): String =
    when (kind) {
        ParamKind.SHUTTER -> shutter
        ParamKind.APERTURE -> aperture
        ParamKind.ISO -> iso
        ParamKind.EXPOSURE_COMP -> ec
        ParamKind.WHITE_BALANCE -> wb
    }

internal fun cameraClock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
private fun intervalCountdown(progress: EosCamera.IntervalProgress?): Int {
    var remaining by remember { mutableIntStateOf(0) }
    LaunchedEffect(progress) {
        val end = SystemClock.elapsedRealtime() + (progress?.waitMs ?: 0)
        do {
            val left = (end - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            remaining = ((left + 999) / 1000).toInt()
            if (left > 0) delay(minOf(100, left))
        } while (left > 0)
    }
    return remaining
}
