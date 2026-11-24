package cloud.micoten.camlink.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.micoten.camlink.ui.components.*
import cloud.micoten.camlink.ui.theme.Xm
import cloud.micoten.camlink.util.AppLog

private data class LogEntry(val value: String) {
    val time: String
        get() = value.substringBefore(' ')

    val level: Char
        get() = value.getOrNull(value.indexOf(' ') + 1) ?: 'D'

    val message: String
        get() = value.substringAfter('/', value)
}

@Composable
fun LogScreen(onClose: () -> Unit) {
    val all by AppLog.lines.collectAsState()
    val state = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var follow by rememberSaveable { mutableStateOf(true) }
    val levels = listOf(null, 'E', 'W', 'I', 'D')
    val entries =
        remember(all, filter) {
            val level = levels.getOrNull(filter)
            all.map(::LogEntry).filter { level == null || it.level == level }
        }
    LaunchedEffect(entries.lastOrNull(), filter, follow) {
        if (follow && entries.isNotEmpty()) state.scrollToItem(entries.lastIndex)
    }
    Column(
        Modifier.fillMaxSize().background(Xm.bg).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageHeading("PTP 流水", "${entries.size} 条记录") {
            IconControl(Symbol.Copy, "复制当前日志", enabled = entries.isNotEmpty()) {
                clipboard.setText(AnnotatedString(entries.joinToString("\n") { it.value }))
            }
            IconControl(Symbol.Close, "关闭日志", onClick = onClose)
        }
        ChoiceRail(listOf("全部", "错误", "警告", "信息", "调试"), filter) { filter = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { SettingToggle("跟随最新", follow) { follow = it } }
            CommandButton("清空", enabled = all.isNotEmpty(), accent = Xm.rec) { AppLog.clear() }
        }
        val shape = RoundedCornerShape(11.dp)
        Box(
            Modifier.weight(1f)
                .fillMaxWidth()
                .clip(shape)
                .background(Xm.card)
                .border(1.dp, Xm.line, shape)
        ) {
            if (entries.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Mark(Symbol.Terminal, UiTone.ink2, 30.dp)
                    Text("暂无记录", color = UiTone.ink2, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    state = state,
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    items(entries) { entry -> DiagnosticRecord(entry) }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRecord(entry: LogEntry) {
    val foreground =
        when (entry.level) {
            'E' -> Xm.rec
            'W' -> Xm.goldInk
            'I' -> Xm.blueInk
            else -> UiTone.ink2
        }
    val surface =
        when (entry.level) {
            'E' -> Xm.accentSoft
            'W' -> Xm.goldSoft
            'I' -> Xm.blueSoft
            else -> Xm.tagBg
        }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(surface).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(foreground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                entry.level.toString(),
                color = contrastInk(foreground),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                entry.time,
                color = foreground,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            SelectionContainer {
                Text(
                    entry.message,
                    color = UiTone.ink,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
