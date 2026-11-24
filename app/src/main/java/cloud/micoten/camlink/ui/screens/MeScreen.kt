package cloud.micoten.camlink.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cloud.micoten.camlink.BuildConfig
import cloud.micoten.camlink.ble.CanonBle
import cloud.micoten.camlink.camera.CameraCatalog
import cloud.micoten.camlink.net.CameraWifi
import cloud.micoten.camlink.ui.CameraViewModel
import cloud.micoten.camlink.ui.Link
import cloud.micoten.camlink.ui.components.*
import cloud.micoten.camlink.ui.sheets.BleSheet
import cloud.micoten.camlink.ui.sheets.ConnectSheet
import cloud.micoten.camlink.ui.sheets.ModelSheet
import cloud.micoten.camlink.ui.theme.AppAppearance
import cloud.micoten.camlink.ui.theme.Xm
import cloud.micoten.camlink.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MeScreen(vm: CameraViewModel) {
    val link by vm.link.collectAsState()
    val status by vm.status.collectAsState()
    val name by vm.cameraName.collectAsState()
    val appearance by vm.appearance.collectAsState()
    val autoTransfer by vm.autoTransfer.collectAsState()
    val lowLatencyPreview by vm.lowLatencyPreview.collectAsState()
    val busy by vm.busy.collectAsState()
    val blePhase by vm.blePhase.collectAsState()
    val bleName by vm.bleName.collectAsState()
    val profile by vm.profile.collectAsState()
    val modelId by vm.modelId.collectAsState()
    val logs by AppLog.lines.collectAsState()

    var showWifi by rememberSaveable { mutableStateOf(false) }
    var showBle by rememberSaveable { mutableStateOf(false) }
    var showModel by rememberSaveable { mutableStateOf(false) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    var showLan by rememberSaveable { mutableStateOf(false) }
    var directHost by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    var scanNotice by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val online = link == Link.ONLINE
    val offline = link == Link.OFFLINE
    val bleReady = blePhase == CanonBle.Phase.READY

    LaunchedEffect(online) { if (online) showWifi = false }

    LazyColumn(
        Modifier.fillMaxSize().background(Xm.bg),
        contentPadding = PaddingValues(start = 15.dp, end = 15.dp, bottom = 16.dp),
    ) {
        item {
            PageHeading(
                "连接",
                if (online) name.ifBlank { profile.model } else status.takeIf { it.isNotBlank() },
            )
        }
        item {
            Column(
                Modifier.padding(bottom = 15.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinkPanel(
                    Symbol.Wifi,
                    "相机 Wi-Fi",
                    when (link) {
                        Link.ONLINE -> "已连接 · 可取景与传输"
                        Link.WORKING -> "正在连接"
                        Link.OFFLINE -> "未连接"
                    },
                    tone = if (online) Xm.ok else if (offline) Xm.accentDeep else Xm.goldInk,
                    surface = if (online) Xm.okSoft else Xm.accentSoft,
                    action = if (online) "断开" else if (offline) "连接" else "连接中",
                    enabled = (online && !busy) || offline,
                ) {
                    if (online) vm.disconnect() else showWifi = true
                }
                LinkPanel(
                    Symbol.Bluetooth,
                    "蓝牙遥控",
                    if (bleReady) bleName.ifBlank { "已连接" }
                    else
                        when (blePhase) {
                            CanonBle.Phase.SCANNING -> "正在搜索"
                            CanonBle.Phase.HANDSHAKING -> "正在配对"
                            CanonBle.Phase.WAITING_CONFIRM -> "等待机身确认"
                            CanonBle.Phase.ERROR -> "连接异常"
                            else -> "未连接"
                        },
                    tone = Xm.blueInk,
                    surface = Xm.blueSoft,
                    action = if (bleReady) "控制" else "配对",
                    enabled = true,
                ) {
                    showBle = true
                }
            }
        }
        item {
            SectionPanel("设置") {
                DetailRow("相机机型", if (modelId == null) "自动识别" else profile.model) {
                    showModel = true
                }
                SettingToggle("拍摄后自动回传", autoTransfer, onChange = vm::setAutoTransfer)
                SettingToggle("低延迟取景", lowLatencyPreview, onChange = vm::setLowLatencyPreview)
                if (lowLatencyPreview && autoTransfer)
                    Text(
                        "取景时暂缓回传，离开取景页或关闭取景后继续。",
                        color = UiTone.ink2,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 7.dp),
                    )
                Text(
                    "外观",
                    color = UiTone.ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 7.dp),
                )
                Box(Modifier.padding(bottom = 7.dp)) {
                    ChoiceRail(AppAppearance.entries.map { it.label }, appearance.ordinal) {
                        vm.setAppearance(AppAppearance.entries[it])
                    }
                }
            }
        }
        item {
            SectionPanel("排查") {
                DetailRow("PTP 流水", if (logs.isEmpty()) "暂无记录" else "${logs.size} 条") {
                    showLog = true
                }
                DetailRow("局域网直连", if (showLan) "收起" else "通过相机 IP 或扫描连接", divider = false) {
                    showLan = !showLan
                }
                AnimatedVisibility(showLan) {
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FormField("相机 IP", directHost, "查看相机屏幕", enabled = offline) {
                            directHost = it
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            CommandButton(
                                if (scanning) "扫描中" else "扫描本网",
                                Modifier.weight(1f),
                                accent = Xm.blue,
                                enabled = offline && !scanning,
                            ) {
                                scanning = true
                                scope.launch {
                                    try {
                                        scanNotice = null
                                        results = CameraWifi.scanHosts(context)
                                        if (results.isEmpty()) scanNotice = "本网未发现相机"
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        results = emptyList()
                                        scanNotice = e.message ?: "扫描失败"
                                    } finally {
                                        scanning = false
                                    }
                                }
                            }
                            CommandButton(
                                "直连",
                                Modifier.weight(1f),
                                primary = true,
                                enabled = offline && directHost.isNotBlank(),
                            ) {
                                vm.connectDirect(directHost)
                            }
                        }
                        scanNotice?.let { Text(it, fontSize = 12.sp, color = UiTone.ink2) }
                        if (offline)
                            results.forEach { ip ->
                                SelectableTile(ip, "连接此地址", Symbol.Link, directHost == ip) {
                                    directHost = ip
                                    vm.connectDirect(ip)
                                }
                            }
                    }
                }
            }
        }
        item { Text("小满·卡农  ${BuildConfig.VERSION_NAME}", color = UiTone.ink2, fontSize = 11.sp) }
    }

    if (showWifi)
        ConnectSheet(
            vm,
            onDismiss = { showWifi = false },
            onOpenBle = {
                showWifi = false
                showBle = true
            },
        )
    if (showBle) BleSheet(vm) { showBle = false }
    if (showModel)
        ModelSheet(
            CameraCatalog.ALL,
            selectedId = modelId,
            reported = name,
            onPick = {
                vm.selectModel(it)
                showModel = false
            },
            onDismiss = { showModel = false },
        )
    if (showLog)
        Dialog(
            onDismissRequest = { showLog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            LogScreen { showLog = false }
        }
}

@Composable
private fun LinkPanel(
    icon: Symbol,
    title: String,
    status: String,
    tone: Color,
    surface: Color,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(Xm.card)
            .border(1.dp, Xm.line, shape)
            .padding(11.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(43.dp).clip(RoundedCornerShape(8.dp)).background(surface),
            contentAlignment = Alignment.Center,
        ) {
            Mark(icon, tone, 23.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = UiTone.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            SignalTag(status, tone, surface = Xm.card)
        }
        CommandButton(action, enabled = enabled, accent = tone, onClick = onClick)
    }
}
