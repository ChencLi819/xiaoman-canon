package cloud.micoten.camlink.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.micoten.camlink.ble.CanonBle
import cloud.micoten.camlink.net.CameraWifi
import cloud.micoten.camlink.ui.CameraViewModel
import cloud.micoten.camlink.ui.Link
import cloud.micoten.camlink.ui.components.*
import cloud.micoten.camlink.ui.theme.Xm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectSheet(vm: CameraViewModel, onDismiss: () -> Unit, onOpenBle: () -> Unit) {
    val link by vm.link.collectAsState()
    val status by vm.status.collectAsState()
    val blePhase by vm.blePhase.collectAsState()
    val hotspot by vm.bleHotspot.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connectWithSystem = rememberSystemWifiConnection()
    var ssid by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf(emptyList<CameraWifi.Candidate>()) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val editable = link == Link.OFFLINE

    LaunchedEffect(hotspot) {
        val value = hotspot ?: return@LaunchedEffect
        if (!value.ssid.isNullOrBlank()) {
            if (ssid.isBlank()) ssid = value.ssid
            if (ssid == value.ssid && pass.isBlank()) pass = value.pass.orEmpty()
        }
    }
    LaunchedEffect(link) { if (link == Link.ONLINE) onDismiss() }

    ControlSheet(onDismiss, rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 17.dp, end = 17.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            PageHeading("连接 Wi-Fi", if (editable) "在机身打开「遥控（EOS Utility）」" else status)
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(Xm.card)
                    .padding(horizontal = 3.dp)
            ) {
                FormField("热点名称", ssid, "以相机屏幕为准", enabled = editable) { ssid = it }
                FormField("热点密码", pass, "开放热点留空", enabled = editable, secret = true) { pass = it }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                CommandButton(
                    if (editable) "连接相机" else "连接中",
                    Modifier.weight(1f),
                    icon = Symbol.Link,
                    primary = true,
                    enabled = editable,
                ) {
                    vm.connect(ssid.ifBlank { null }, pass)
                }
                CommandButton(
                    if (scanning) "扫描中" else "扫描热点",
                    enabled = editable && !scanning,
                    accent = Xm.blue,
                ) {
                    scanning = true
                    scope.launch {
                        try {
                            scanError = null
                            candidates = CameraWifi.scan(context)
                            if (candidates.isEmpty()) scanError = "未扫描到相机热点"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            candidates = emptyList()
                            scanError = e.message ?: "扫描失败"
                        } finally {
                            scanning = false
                        }
                    }
                }
            }
            scanError?.let { Text(it, color = UiTone.ink2, fontSize = 12.sp) }
            if (editable)
                candidates.forEach { candidate ->
                    val selected = candidate.ssid == ssid
                    val detail =
                        if (candidate.looksLikeCamera) "相机热点 · ${candidate.level} dBm"
                        else "${candidate.level} dBm"
                    SelectableTile(candidate.ssid, detail, Symbol.Wifi, selected) {
                        if (!selected) pass = ""
                        ssid = candidate.ssid
                    }
                }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                CommandButton("系统连接", Modifier.weight(1f), accent = Xm.blue, enabled = editable) {
                    connectWithSystem(ssid, pass)
                }
                CommandButton(
                    "手机已连 Wi-Fi",
                    Modifier.weight(1f),
                    accent = Xm.blue,
                    enabled = editable,
                ) {
                    vm.connectCurrentWifi()
                }
            }
            SectionPanel {
                DetailRow(
                    "蓝牙读取热点",
                    if (blePhase == CanonBle.Phase.READY) "已连接蓝牙" else "未连接蓝牙",
                    divider = false,
                    onClick = onOpenBle,
                )
            }
            if (status.isNotBlank() && editable && status != "未连接")
                Text(status, color = Xm.accentDeep, fontSize = 12.sp)
        }
    }
}
