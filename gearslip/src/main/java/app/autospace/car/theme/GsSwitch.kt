package app.autospace.car.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/**
 * An on/off switch in the current theme's finish: the track is pressed in while on, the knob is
 * raised like a button. The whole switch is one tap target, as big as a nav bar button.
 *
 * r26 (Pit Lane only): the track becomes a recessed well (gsSurface well = inverted bevel +
 * inner top shadow) and the knob becomes a metal bat lever on a pivot, leaning toward the
 * active side. Travel, hit area, and animation are unchanged — only the drawing changes.
 * Every other style keeps its exact current track-and-knob rendering.
 */
@Composable
fun GsSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val knob = 26.dp
    val travel = 64.dp - knob - 8.dp
    val x by animateDpAsState(if (checked) travel else 0.dp, tween(140), label = "switch")
    val look = gsLook()
    GsIconBox(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier.size(width = 64.dp, height = 36.dp),
        colors = if (checked) GsColors(scheme.primary, scheme.onPrimary) else GsColors(scheme.surfaceContainerHighest, scheme.onSurfaceVariant),
        shape = gsButtonShape(CircleShape),
        latched = checked,
        well = look.batToggle,
    ) {
        Box(Modifier.matchParentSize().padding(4.dp), contentAlignment = Alignment.CenterStart) {
            if (look.batToggle) {
                Box(Modifier.offset(x = x).size(knob)) {
                    Canvas(Modifier.matchParentSize()) { drawBat(checked) }
                }
            } else {
                Box(
                    Modifier
                        .offset(x = x)
                        .size(knob)
                        .gsSurface(if (checked) scheme.onPrimary else scheme.outline, gsButtonShape(CircleShape)),
                )
            }
        }
    }
}

/**
 * A steel bat lever on a pivot, drawn in the 26dp knob box. The handle leans 14 degrees
 * toward the active side (right when on, left when off) around its base pivot; a bright
 * stripe on the upper-left edge and a dark stripe on the lower-right edge make it read
 * as round metal, and a slightly wider tip cap finishes it. Fixed steel tones — they read
 * on the dark well, the light well, and the primary (checked) track alike.
 */
private fun DrawScope.drawBat(checked: Boolean) {
    val w = size.width
    val h = size.height
    val pivot = Offset(w / 2, h - 4.dp.toPx())
    val lean = if (checked) 14f else -14f
    val hw = 11.dp.toPx()
    val hh = 20.dp.toPx()
    rotate(lean, pivot) {
        drawRoundRect(
            color = Color(0xFFB8B8B8),
            topLeft = Offset(pivot.x - hw / 2, pivot.y - hh),
            size = Size(hw, hh),
            cornerRadius = CornerRadius(hw / 2, hw / 2),
        )
        drawRoundRect(
            color = Color(0xFFD0D0D0),
            topLeft = Offset(pivot.x - hw / 2 - 1.dp.toPx(), pivot.y - hh - 2.dp.toPx()),
            size = Size(hw + 2.dp.toPx(), 4.dp.toPx()),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.55f),
            start = Offset(pivot.x - hw / 2 + 2.dp.toPx(), pivot.y - hh + 3.dp.toPx()),
            end = Offset(pivot.x - hw / 2 + 2.dp.toPx(), pivot.y - 3.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.35f),
            start = Offset(pivot.x + hw / 2 - 2.dp.toPx(), pivot.y - hh + 3.dp.toPx()),
            end = Offset(pivot.x + hw / 2 - 2.dp.toPx(), pivot.y - 3.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
    }
    drawCircle(color = Color(0xFF7A7A7A), radius = 4.dp.toPx(), center = pivot)
    drawCircle(
        color = Color.Black.copy(alpha = 0.35f),
        radius = 4.dp.toPx(),
        center = pivot,
        style = Stroke(width = 1.dp.toPx()),
    )
}
