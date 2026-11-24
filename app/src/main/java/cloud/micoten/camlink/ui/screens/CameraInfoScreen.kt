package cloud.micoten.camlink.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.micoten.camlink.ui.CameraViewModel
import cloud.micoten.camlink.ui.Link
import cloud.micoten.camlink.ui.components.*
import cloud.micoten.camlink.ui.theme.Xm

@Composable
fun CameraInfoScreen(vm: CameraViewModel, active: Boolean) {
    val link by vm.link.collectAsState()
    val p by vm.params.collectAsState()
    val profile by vm.profile.collectAsState()
    val mode by vm.exposureMode.collectAsState()
    val focus by vm.focusMode.collectAsState()
    val online = link == Link.ONLINE
    LaunchedEffect(active, online) { if (active && online) vm.refreshDeviceInfo() }

    LazyColumn(
        Modifier.fillMaxSize().background(Xm.bg),
        contentPadding = PaddingValues(start = 15.dp, end = 15.dp, bottom = 18.dp),
    ) {
        item {
            PageHeading("相机", if (online) profile.model else "未连接相机") {
                IconControl(
                    Symbol.Refresh,
                    "刷新相机信息",
                    enabled = online,
                    onClick = vm::refreshDeviceInfo,
                )
            }
        }
        if (!online) {
            item {
                Box(
                    Modifier.fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Xm.tagBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Mark(Symbol.Camera, UiTone.ink2, 30.dp)
                }
            }
            return@LazyColumn
        }
        item {
            BodyReadouts(mode.label, p.shutterCount?.let { "%,d".format(it) } ?: "—") {
                BatteryGauge(p.battery)
            }
        }
        item {
            Details(
                "机身",
                listOf(
                    "机型" to profile.model,
                    "昵称" to p.nickname,
                    "所有者" to p.owner,
                    "序列号" to p.serial,
                    "固件版本" to p.firmware,
                ),
            )
        }
        item {
            Details(
                "镜头",
                listOf(
                    "镜头" to p.lens,
                    "对焦模式" to
                        when (focus) {
                            0 -> "单次自动对焦"
                            1 -> "伺服自动对焦"
                            2 -> "智能自动对焦"
                            3 -> "手动对焦"
                            else -> "—"
                        },
                ),
            )
        }
        item {
            Details(
                "存储卡",
                listOf(
                    "卡类型" to p.storageType,
                    "卷标" to p.storageLabel,
                    "总容量" to p.storageTotal,
                    "剩余容量" to p.storageFree,
                    "剩余可拍" to
                        p.storageFreeImages.takeUnless { it == "—" }.orEmpty().ifBlank { p.shots },
                    "访问权限" to p.storageAccess,
                ),
            )
        }
        item {
            Details(
                "当前设置",
                listOf(
                    "快门速度" to p.shutter,
                    "光圈" to p.aperture,
                    "感光度" to p.iso,
                    "曝光补偿" to p.ec,
                    "白平衡" to p.wb,
                    "驱动模式" to p.drive,
                    "测光模式" to p.metering,
                    "画质" to p.imageFormat,
                    "色彩空间" to p.colorSpace,
                    "照片风格" to p.pictureStyle,
                ),
            )
        }
    }
}

@Composable
private fun BodyReadouts(mode: String, shutterCount: String, battery: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth()
            .padding(bottom = 15.dp)
            .clip(shape)
            .background(Xm.tagBg)
            .border(1.dp, Xm.line, shape)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Mark(Symbol.Camera, Xm.accentDeep, 19.dp)
            Text(
                "机身状态",
                color = UiTone.ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            battery()
        }
        Row(
            Modifier.fillMaxWidth()
                .background(Xm.card)
                .padding(horizontal = 13.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            listOf(mode to "拍摄模式", shutterCount to "快门计数").forEach { (value, label) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        value,
                        color = UiTone.ink,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(label, color = UiTone.ink2, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun Details(title: String, rows: List<Pair<String, String>>) {
    SectionPanel(title) {
        rows.forEachIndexed { index, (label, value) ->
            DetailRow(label, value.ifBlank { "—" }, divider = index < rows.lastIndex)
        }
    }
}
