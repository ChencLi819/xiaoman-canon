package cloud.micoten.camlink.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class Symbol {
    Aperture,
    Camera,
    Chevron,
    Close,
    Collapse,
    Download,
    Expand,
    Focus,
    Grid,
    Link,
    Live,
    Power,
    Refresh,
    Timer,
    Wall,
    Wifi,
    Bluetooth,
    Check,
    Terminal,
    Copy,
}

@Composable
internal fun Mark(
    symbol: Symbol,
    color: Color = UiTone.ink2,
    size: Dp = 19.dp,
    stroke: Dp = 1.65.dp,
) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val weight = stroke.toPx()
        val outline = Stroke(weight, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun point(x: Float, y: Float) = Offset(x * u, y * u)
        fun trace(vararg coords: Int) {
            val path =
                Path().apply {
                    for (i in coords.indices step 2) {
                        val x = coords[i] * u
                        val y = coords[i + 1] * u
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
            drawPath(path, color, style = outline)
        }
        fun ring(x: Float, y: Float, r: Float) =
            drawCircle(color, r * u, point(x, y), style = outline)
        fun dot(x: Float, y: Float, r: Float = 1.2f) = drawCircle(color, r * u, point(x, y))
        fun box(x: Float, y: Float, width: Float, height: Float, radius: Float = 2f) {
            drawRoundRect(
                color,
                point(x, y),
                Size(width * u, height * u),
                CornerRadius(radius * u),
                style = outline,
            )
        }
        fun arc(x: Float, y: Float, diameter: Float, start: Float, sweep: Float) {
            drawArc(
                color,
                start,
                sweep,
                false,
                point(x, y),
                Size(diameter * u, diameter * u),
                style = outline,
            )
        }
        when (symbol) {
            Symbol.Aperture -> {
                ring(12f, 12f, 8.6f)
                trace(8, 4, 15, 15)
                trace(20, 8, 8, 8)
                trace(16, 20, 10, 8)
                trace(4, 16, 16, 16)
            }
            Symbol.Camera -> {
                trace(4, 20, 4, 7, 8, 7, 10, 4, 16, 4, 18, 7, 21, 7, 21, 20, 4, 20)
                ring(12.5f, 13f, 3.3f)
                dot(18.2f, 10f, .9f)
            }
            Symbol.Chevron -> trace(10, 6, 16, 12, 10, 18)
            Symbol.Close -> {
                trace(6, 6, 18, 18)
                trace(18, 6, 6, 18)
            }
            Symbol.Collapse -> {
                trace(3, 8, 8, 8, 8, 3)
                trace(16, 3, 16, 8, 21, 8)
                trace(3, 16, 8, 16, 8, 21)
                trace(16, 21, 16, 16, 21, 16)
            }
            Symbol.Download -> {
                trace(12, 3, 12, 15)
                trace(8, 11, 12, 15, 16, 11)
                trace(4, 16, 4, 21, 20, 21, 20, 16)
            }
            Symbol.Expand -> {
                trace(3, 8, 3, 3, 8, 3)
                trace(16, 3, 21, 3, 21, 8)
                trace(3, 16, 3, 21, 8, 21)
                trace(16, 21, 21, 21, 21, 16)
            }
            Symbol.Focus -> {
                trace(3, 8, 3, 3, 8, 3)
                trace(16, 3, 21, 3, 21, 8)
                trace(3, 16, 3, 21, 8, 21)
                trace(16, 21, 21, 21, 21, 16)
                ring(12f, 12f, 3.7f)
            }
            Symbol.Grid -> {
                box(3f, 3f, 18f, 18f)
                trace(9, 4, 9, 20)
                trace(15, 4, 15, 20)
                trace(4, 9, 20, 9)
                trace(4, 15, 20, 15)
            }
            Symbol.Link -> {
                trace(10, 15, 7, 18, 4, 18, 2, 15, 3, 12, 7, 8, 10, 8)
                trace(14, 9, 17, 6, 20, 6, 22, 9, 21, 12, 17, 16, 14, 16)
                trace(8, 16, 16, 8)
            }
            Symbol.Live -> {
                box(3f, 4f, 18f, 13f)
                trace(7, 21, 17, 21)
                dot(12f, 10.5f, 2.3f)
            }
            Symbol.Power -> {
                arc(4f, 4f, 16f, -48f, 276f)
                trace(12, 2, 12, 11)
            }
            Symbol.Refresh -> {
                arc(4f, 4f, 16f, -158f, 254f)
                trace(20, 4, 20, 10, 14, 10)
            }
            Symbol.Timer -> {
                ring(12f, 13f, 8f)
                trace(9, 2, 15, 2)
                trace(12, 8, 12, 13, 15, 15)
            }
            Symbol.Wall -> {
                box(3f, 4f, 18f, 16f)
                trace(4, 16, 9, 11, 13, 15, 16, 12, 20, 16)
                dot(15.8f, 8.2f)
            }
            Symbol.Wifi -> {
                val path =
                    Path().apply {
                        moveTo(3f * u, 8f * u)
                        quadraticTo(12f * u, .5f * u, 21f * u, 8f * u)
                        moveTo(6f * u, 12f * u)
                        quadraticTo(12f * u, 6.8f * u, 18f * u, 12f * u)
                        moveTo(9f * u, 16f * u)
                        quadraticTo(12f * u, 13.5f * u, 15f * u, 16f * u)
                    }
                drawPath(path, color, style = outline)
                dot(12f, 20f, 1.5f)
            }
            Symbol.Bluetooth -> trace(6, 6, 17, 17, 12, 22, 12, 2, 17, 7, 6, 18)
            Symbol.Check -> trace(5, 12, 10, 17, 20, 7)
            Symbol.Terminal -> {
                box(3f, 4f, 18f, 16f)
                trace(7, 8, 11, 12, 7, 16)
                trace(13, 16, 17, 16)
            }
            Symbol.Copy -> {
                box(7f, 7f, 13f, 14f)
                trace(4, 16, 4, 3, 16, 3)
            }
        }
    }
}
