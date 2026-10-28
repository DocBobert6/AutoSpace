package app.autospace.car.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import app.autospace.notify.CarMotion

/**
 * An on/off switch in the current theme's finish: the track is pressed in while on, the knob is
 * raised like a button. The whole switch is one tap target, as big as a nav bar button.
 *
 * r26 (Pit Lane only): the track becomes a recessed well (gsSurface well = inverted bevel +
 * inner top shadow) and the knob becomes a metal bat lever on a pivot, leaning toward the
 * active side. Travel, hit area, and animation are unchanged — only the drawing changes.
 * Every other style keeps its exact current track-and-knob rendering.
 * r115 (Pitlane zoned rework): the bat lever is SUPERSEDED by the rocker paddle
 * (see drawRocker) — frame + chamfer kept, fill split light top / dark bottom,
 * on = top half faint primary tint + bolts primary. Travel, hit area, and
 * animation are unchanged from the bat; the well goes away with the bat
 * (well = batToggle, now false under Pitlane), so the track is a plain
 * chamfered container. The bat drawing stays below, unselected by any
 * built-in look, like checkerStrip.
 */
@Composable
fun GsSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val knob = 26.dp
    val travel = 64.dp - knob - 8.dp
    val look = gsLook()
    // r48: under a spring-motion style (Verve only) the knob travel
    // uses the state-change spec (spring parked, snap moving); every
    // other style keeps the exact 140ms tween.
    val x by animateDpAsState(
        if (checked) travel else 0.dp,
        if (look.springMotion) stateChangeMotionSpec(CarMotion.moving) else tween(140),
        label = "switch",
    )
    // r48: Verve morph — the track rounds from half-corner (off) to
    // the full style corner (on) on the same motion spec, so shape, not
    // just color, changes with state. Morph endpoints follow the style
    // corner, so a square style would morph between square endpoints;
    // every other style keeps the pill track exactly.
    val trackShape = if (look.springMotion) {
        val corner by animateDpAsState(
            if (checked) look.corner else look.corner * 0.5f,
            stateChangeMotionSpec(CarMotion.moving),
            label = "verve-track-morph",
        )
        RoundedCornerShape(corner)
    } else gsButtonShape(CircleShape)
    GsIconBox(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier.size(width = 64.dp, height = 36.dp),
        colors = if (checked) GsColors(scheme.primary, scheme.onPrimary) else GsColors(scheme.surfaceContainerHighest, scheme.onSurfaceVariant),
        shape = trackShape,
        latched = checked,
        well = look.batToggle,
    ) {
        Box(Modifier.matchParentSize().padding(4.dp), contentAlignment = Alignment.CenterStart) {
            if (look.rockerToggle) {
                Box(Modifier.offset(x = x).size(knob)) {
                    Canvas(Modifier.matchParentSize()) {
                        drawRocker(
                            checked,
                            if (checked) scheme.onPrimary else scheme.outline,
                            scheme.primary,
                        )
                    }
                }
            } else if (look.batToggle) {
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
 * r115 (Pitlane only): the rocker paddle, replacing the r26 bat lever.
 * The paddle fills the 26dp knob box as a chamfered rect (2dp cut, in
 * proportion to the box — the pick fixes 4dp for full-size controls,
 * not for the knob), framed by a 1dp outline stroke. The fill splits
 * light top / dark bottom (base lerped 0.25 toward white above, toward
 * black below — the classic rocker face); when on, the top half also
 * takes the faint 0.12 primary tint, and the four 4dp hex corner bolts
 * light the scheme primary (steel by base luminance when off). The
 * bolts reuse the zoned frame's hex geometry (pitlaneBoltHexPoints)
 * with an 8dp inset grid, so the centers sit 4dp in from each corner
 * and each 4dp bolt spans 2..6dp — inside the paddle on every side.
 * Base is the knob's existing color (onPrimary when on, outline when
 * off); travel, hit area, and animation are unchanged.
 */
private fun DrawScope.drawRocker(checked: Boolean, base: Color, primary: Color) {
    val paddlePath = (ChamferShape(2.dp).createOutline(size, layoutDirection, this) as Outline.Generic).path
    val thread = if (base.luminance() > 0.5f) Color.Black else Color.White
    val top = lerp(base, Color.White, 0.25f)
    val bottom = lerp(base, Color.Black, 0.25f)
    clipPath(paddlePath) {
        drawRect(top, topLeft = Offset(0f, 0f), size = Size(size.width, size.height / 2f))
        drawRect(bottom, topLeft = Offset(0f, size.height / 2f), size = Size(size.width, size.height / 2f))
        if (checked) {
            drawRect(primary.copy(alpha = 0.12f), topLeft = Offset(0f, 0f), size = Size(size.width, size.height / 2f))
        }
    }
    drawOutline(
        ChamferShape(2.dp).createOutline(size, layoutDirection, this),
        thread.copy(alpha = 0.60f),
        style = Stroke(width = 1.dp.toPx()),
    )
    val boltPx = 4.dp.toPx()
    val boltFill = if (checked) primary else thread.copy(alpha = 0.50f)
    val boltStroke = if (checked) primary else thread.copy(alpha = 0.85f)
    val hexStroke = Stroke(width = 1.dp.toPx())
    for (c in pitlaneBoltCenters(size.width, size.height, 8.dp.toPx())) {
        val hex = Path().apply {
            val pts = pitlaneBoltHexPoints(c, boltPx / 2f)
            moveTo(pts[0].x, pts[0].y)
            for (i in 1..5) lineTo(pts[i].x, pts[i].y)
            close()
        }
        drawPath(hex, boltFill)
        drawPath(hex, boltStroke, style = hexStroke)
    }
}

/**
 * r26 (Pit Lane only): a steel bat lever on a pivot — SUPERSEDED by
 * drawRocker in r115; no built-in look selects it anymore. Kept, like
 * checkerStrip, so the retirement is visible rather than silent.
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
