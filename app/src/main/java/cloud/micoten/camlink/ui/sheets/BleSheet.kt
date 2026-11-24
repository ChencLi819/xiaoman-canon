package cloud.micoten.camlink.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.micoten.camlink.ble.CanonBle
import cloud.micoten.camlink.ui.BleHotspot
import cloud.micoten.camlink.ui.CameraViewModel
import cloud.micoten.camlink.ui.Link
import cloud.micoten.camlink.ui.components.CommandButton
import cloud.micoten.camlink.ui.components.ControlSheet
import cloud.micoten.camlink.ui.components.DetailRow
import cloud.micoten.camlink.ui.components.PageHeading
import cloud.micoten.camlink.ui.components.SelectableTile
import cloud.micoten.camlink.ui.components.Symbol
import cloud.micoten.camlink.ui.components.ToolKey
import cloud.micoten.camlink.ui.components.UiTone
import cloud.micoten.camlink.ui.theme.Xm

/**
 * 佳能手机模式的蓝牙遥控面板。
 *
 * 这条通道没有取景：佳能把实时画面锁在 Wi-Fi 的 PTP/IP 上，蓝牙只回事件、只能按。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleSheet(vm: CameraViewModel, onDismiss: () -> Unit) {
    val phase by vm.blePhase.collectAsState()
    val devices by vm.bleDevices.collectAsState()
    val message by vm.bleMessage.collectAsState()
    val log by vm.bleLog.collectAsState()
    val name by vm.bleName.collectAsState()
    val hotspot by vm.bleHotspot.collectAsState()
    val profile by vm.profile.collectAsState()
    val link by vm.link.collectAsState()

    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ready = phase == CanonBle.Phase.READY
    val blocker = vm.bleUnavailable()

    ControlSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 18.dp)
        ) {
            PageHeading(
                "蓝牙遥控",
                when (phase) {
                    CanonBle.Phase.OFF -> "未连接"
                    CanonBle.Phase.SCANNING -> "正在搜索"
                    CanonBle.Phase.HANDSHAKING -> "正在配对"
                    CanonBle.Phase.WAITING_CONFIRM -> "等待机身确认"
                    CanonBle.Phase.READY -> null
                    CanonBle.Phase.ERROR -> "连接异常"
                },
            )

            if (!ready) {
                Text(
                    "在相机上选择「连接至智能手机」并添加设备",
                    fontSize = 12.sp,
                    color = UiTone.ink2,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            if (blocker != null) {
                Text(
                    blocker,
                    fontSize = 11.sp,
                    color = Xm.accent,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            if (ready) {
                BluetoothConsole(
                    name = name,
                    message = message,
                    hotspot = hotspot,
                    wifiLinked = link == Link.ONLINE,
                    driveHint = profile.bleDriveHint,
                    onShooting = { vm.bleSetMode(true) },
                    onPlayback = { vm.bleSetMode(false) },
                    onFocus = { vm.bleFocus() },
                    onShutter = { vm.bleShoot() },
                    onRecord = { start -> vm.bleRecord(start) },
                    onWake = { vm.bleWake() },
                    onSleep = { vm.bleSleep() },
                    onReadWifi = { vm.bleReadWifi() },
                    onUseWifi = { vm.connectViaBleHotspot() },
                    onDisconnect = { vm.bleDisconnect() },
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CommandButton(
                        label = if (phase == CanonBle.Phase.SCANNING) "搜索中" else "搜索",
                        enabled = blocker == null && phase != CanonBle.Phase.SCANNING,
                    ) {
                        vm.bleScan()
                    }
                    if (phase == CanonBle.Phase.SCANNING) {
                        CommandButton("停止", accent = Xm.blue) { vm.bleStopScan() }
                    }
                    Spacer(Modifier.weight(1f))
                    if (phase != CanonBle.Phase.OFF) {
                        CommandButton("断开", accent = Xm.rec) { vm.bleDisconnect() }
                    }
                }

                if (message.isNotBlank()) {
                    Text(
                        message,
                        fontSize = 11.sp,
                        color = Xm.accent,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                if (
                    devices.isNotEmpty() &&
                        (phase == CanonBle.Phase.OFF ||
                            phase == CanonBle.Phase.SCANNING ||
                            phase == CanonBle.Phase.ERROR)
                ) {
                    Spacer(Modifier.height(6.dp))
                    devices.forEach { d ->
                        DeviceRow(d.name, d.address, d.rssi, d.looksLikeCamera) { vm.bleConnect(d) }
                    }
                }
            }

            if (log.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(UiTone.panel2)
                        .padding(10.dp)
                ) {
                    log.takeLast(8).forEach { line ->
                        Text(
                            line,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = UiTone.ink3,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BluetoothConsole(
    name: String,
    message: String,
    hotspot: BleHotspot?,
    wifiLinked: Boolean,
    driveHint: String,
    onShooting: () -> Unit,
    onPlayback: () -> Unit,
    onFocus: () -> Unit,
    onShutter: () -> Unit,
    onRecord: (Boolean) -> Unit,
    onWake: () -> Unit,
    onSleep: () -> Unit,
    onReadWifi: () -> Unit,
    onUseWifi: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name.ifBlank { "Canon" },
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = UiTone.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        CommandButton("断开", accent = Xm.rec) { onDisconnect() }
    }
    if (message.isNotBlank()) {
        Text(message, fontSize = 11.sp, color = Xm.accent, modifier = Modifier.padding(top = 4.dp))
    }

    Spacer(Modifier.height(10.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CommandButton("拍摄", accent = Xm.accent) { onShooting() }
        CommandButton("回放", accent = UiTone.ink2) { onPlayback() }
        Spacer(Modifier.weight(1f))
        CommandButton("唤醒") { onWake() }
        CommandButton("休眠") { onSleep() }
    }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolKey(Symbol.Focus, "对焦", Modifier.weight(1f), tone = Xm.blueInk) { onFocus() }
        ToolKey(Symbol.Aperture, "快门", Modifier.weight(1f), active = true) { onShutter() }
        ToolKey(Symbol.Live, "录像", Modifier.weight(1f), tone = Xm.rec) { onRecord(true) }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CommandButton("停止录像", accent = Xm.rec) { onRecord(false) }
        Spacer(Modifier.weight(1f))
        Text("驱动 $driveHint", fontSize = 9.sp, color = UiTone.ink3)
    }

    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(UiTone.line))

    if (hotspot?.ssid.isNullOrBlank()) {
        DetailRow(
            label = "相机热点",
            value = null,
            divider = false,
            trailing = { CommandButton("读取", accent = Xm.gold) { onReadWifi() } },
        )
    } else {
        DetailRow(
            label = hotspot.ssid.orEmpty(),
            value = if (hotspot.pass.isNullOrEmpty()) "开放" else "已读取密码",
            divider = false,
            trailing = {
                if (wifiLinked) {
                    Text("已接入", fontSize = 10.sp, color = Xm.ok)
                } else {
                    CommandButton("接入", accent = Xm.gold) { onUseWifi() }
                }
            },
        )
    }
}

@Composable
private fun DeviceRow(
    name: String,
    address: String,
    rssi: Int,
    looksLikeCamera: Boolean,
    onClick: () -> Unit,
) {
    val label = name.ifBlank { if (looksLikeCamera) "佳能相机" else "未命名设备" }
    SelectableTile(
        label,
        "$address  ·  $rssi dBm",
        Symbol.Bluetooth,
        selected = false,
        modifier = Modifier.padding(vertical = 3.dp),
        onClick = onClick,
    )
}
