package cloud.micoten.camlink.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.micoten.camlink.camera.Choice
import cloud.micoten.camlink.camera.ParamKind
import cloud.micoten.camlink.ui.components.CommandButton
import cloud.micoten.camlink.ui.components.ControlSheet
import cloud.micoten.camlink.ui.components.Mark
import cloud.micoten.camlink.ui.components.PageHeading
import cloud.micoten.camlink.ui.components.Symbol
import cloud.micoten.camlink.ui.components.UiTone
import cloud.micoten.camlink.ui.theme.Xm
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiceSheet(
    kind: ParamKind,
    choices: List<Choice>,
    selectedCode: Int?,
    currentLabel: String,
    onApply: suspend (Int) -> String?,
    onDismiss: () -> Unit,
) {
    val table = choices
    var picked by remember(kind) { mutableStateOf(selectedCode) }
    var error by remember(kind) { mutableStateOf<String?>(null) }
    var applying by remember(kind) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    val state =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !applying },
        )
    LaunchedEffect(table, kind) {
        val index = table.indexOfFirst { it.code == picked }
        if (index >= 0) grid.scrollToItem((index - 3).coerceAtLeast(0))
        if (!applying && index < 0 && picked != selectedCode) {
            picked = selectedCode
            error = "机身可用档位已更新，请重新选择"
        }
    }

    ControlSheet(
        onDismissRequest = { if (!applying) onDismiss() },
        sheetState = state,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PageHeading(kind.label, "当前 $currentLabel")
            if (table.isEmpty()) {
                Text(
                    "当前机身模式下暂无可用档位",
                    color = UiTone.ink2,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = grid,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 275.dp).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(table, key = { it.code }) { choice ->
                        ParameterKey(choice.label, picked == choice.code, !applying) {
                            picked = choice.code
                            error = null
                        }
                    }
                }
            }
            error?.let { Text(it, color = Xm.rec, fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommandButton("取消", Modifier.weight(1f), enabled = !applying, onClick = onDismiss)
                CommandButton(
                    if (applying) "正在应用" else "应用",
                    Modifier.weight(1f),
                    enabled =
                        !applying && picked != selectedCode && table.any { it.code == picked },
                    primary = true,
                ) {
                    val code = picked ?: return@CommandButton
                    if (applying) return@CommandButton
                    applying = true
                    scope.launch {
                        val failure =
                            try {
                                onApply(code)
                            } finally {
                                applying = false
                            }
                        if (failure == null) onDismiss() else error = failure
                    }
                }
            }
        }
    }
}

@Composable
private fun ParameterKey(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 51.dp)
            .clip(shape)
            .background(if (selected) Xm.accentSoft else Xm.tagBg)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Xm.accentDeep else UiTone.ink,
            maxLines = 1,
        )
        if (selected) Mark(Symbol.Check, Xm.accentDeep, 15.dp)
    }
}
