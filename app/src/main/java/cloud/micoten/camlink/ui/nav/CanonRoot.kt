package cloud.micoten.camlink.ui.nav

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import cloud.micoten.camlink.camera.CameraFile
import cloud.micoten.camlink.ui.CameraViewModel
import cloud.micoten.camlink.ui.components.Mark
import cloud.micoten.camlink.ui.components.Symbol
import cloud.micoten.camlink.ui.components.UiTone
import cloud.micoten.camlink.ui.screens.CameraInfoScreen
import cloud.micoten.camlink.ui.screens.GalleryScreen
import cloud.micoten.camlink.ui.screens.MeScreen
import cloud.micoten.camlink.ui.screens.ShootScreen
import cloud.micoten.camlink.ui.theme.Xm
import cloud.micoten.camlink.ui.theme.nightAppearance
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private class NavTab(val label: String, val icon: Symbol)

/*
 * 四项。曝光四要素仍在取景台上，精调走弹窗；机身信息与损耗归「相机」页。
 *
 * 第四项原名「我」、图标人像，与页内实际内容（Wi-Fi/蓝牙连接、链路状态、排查）对不上，
 * 改为「连接」+ 相扣链环。
 */
private val tabs =
    listOf(
        NavTab("取景", Symbol.Aperture),
        NavTab("照片", Symbol.Wall),
        NavTab("相机", Symbol.Camera),
        NavTab("连接", Symbol.Link),
    )

@Composable
fun CanonRoot(vm: CameraViewModel) {
    val pager = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val page = pager.settledPage
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    var navigation by remember { mutableStateOf<Job?>(null) }
    var openPhoto by remember { mutableStateOf<CameraFile?>(null) }
    val viewfinderVisible = foreground && pager.currentPage == 0
    LaunchedEffect(vm, viewfinderVisible) { vm.setViewfinderVisible(viewfinderVisible) }
    val navigate: (Int) -> Unit = { destination ->
        navigation?.cancel()
        navigation = scope.launch {
            pager.animateScrollToPage(
                destination,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
            )
        }
    }

    BarTint(light = !nightAppearance)

    Scaffold(
        containerColor = Xm.bg,
        contentWindowInsets = WindowInsets.systemBars,
        bottomBar = {
            NavBar(pager, navigate)
        },
    ) { inner ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().padding(inner),
            /* 左右各预载一页：翻到图库时取景不至于被拆掉重建 */
            beyondViewportPageCount = 1,
            contentPadding = PaddingValues(0.dp),
        ) { p ->
            when (p) {
                0 ->
                    ShootScreen(
                        vm,
                        active = viewfinderVisible,
                        onOpenPhotos = { file ->
                            openPhoto = file
                            navigate(1)
                        },
                        onOpenConnection = { navigate(3) },
                    )
                1 ->
                    GalleryScreen(
                        vm,
                        active = foreground && page == 1,
                        openTarget = openPhoto,
                        onTargetHandled = { openPhoto = null },
                        onOpenConnection = { navigate(3) },
                    )
                2 -> CameraInfoScreen(vm, active = foreground && page == 2)
                else -> MeScreen(vm)
            }
        }
    }
}

@Composable
private fun NavBar(pager: PagerState, onPick: (Int) -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val selected = pager.currentPage
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val capsule = Xm.accentSoft
    val accent = Xm.accent
    Box(
        Modifier.fillMaxWidth()
            .background(Xm.bg)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .background(Xm.navbg)
            .border(1.dp, Xm.line, shape)
            .padding(4.dp)
            .selectableGroup()
    ) {
        Canvas(Modifier.matchParentSize()) {
            val width = size.width / tabs.size
            val page =
                (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(
                    0f,
                    tabs.lastIndex.toFloat(),
                )
            val center = width * ((if (rtl) tabs.lastIndex - page else page) + .5f)
            val travel = abs(pager.currentPageOffsetFraction) * 2f
            val capsuleWidth = width * (.88f + travel * .13f)
            drawRoundRect(
                capsule,
                Offset(center - capsuleWidth / 2, 1.dp.toPx()),
                Size(capsuleWidth, (size.height - 2.dp.toPx()).coerceAtLeast(0f)),
                CornerRadius(12.dp.toPx()),
            )
            val indexWidth = (18.dp + 10.dp * travel).toPx()
            drawRoundRect(
                accent,
                Offset(center - indexWidth / 2, size.height - 5.dp.toPx()),
                Size(indexWidth, 2.dp.toPx()),
                CornerRadius(1.dp.toPx()),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, tab ->
                val tint by
                    animateColorAsState(
                        if (selected == i) Xm.accentDeep else UiTone.ink2,
                        label = "destinationTint",
                    )
                Column(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(selected = selected == i, role = Role.Tab) { onPick(i) }
                        .heightIn(min = 51.dp)
                        .padding(bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.graphicsLayer {
                            val distance =
                                abs(i - pager.currentPage - pager.currentPageOffsetFraction)
                                    .coerceAtMost(1f)
                            scaleX = 1f + (1f - distance) * .12f
                            scaleY = scaleX
                            translationY = -(1f - distance) * 1.dp.toPx()
                        }
                    ) {
                        Mark(tab.icon, tint, 19.dp)
                    }
                    Text(
                        tab.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = tint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 状态栏/导航栏图标明暗跟随当前页，否则深色取景页上会出现黑图标。 */
@Composable
private fun BarTint(light: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
