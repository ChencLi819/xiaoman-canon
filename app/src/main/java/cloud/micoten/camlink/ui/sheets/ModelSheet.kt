package cloud.micoten.camlink.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.micoten.camlink.camera.CameraProfile
import cloud.micoten.camlink.ui.components.ControlSheet
import cloud.micoten.camlink.ui.components.PageHeading
import cloud.micoten.camlink.ui.components.SelectableTile
import cloud.micoten.camlink.ui.components.Symbol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    models: List<CameraProfile>,
    selectedId: String?,
    reported: String,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ControlSheet(onDismiss, rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .selectableGroup()
                .padding(start = 17.dp, end = 17.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PageHeading("相机机型", null)
            SelectableTile(
                "自动识别",
                reported.takeIf { it.isNotBlank() },
                Symbol.Refresh,
                selectedId == null,
            ) {
                onPick(null)
            }
            models.forEach { model ->
                SelectableTile(model.model, null, Symbol.Camera, selectedId == model.id) {
                    onPick(model.id)
                }
            }
        }
    }
}
