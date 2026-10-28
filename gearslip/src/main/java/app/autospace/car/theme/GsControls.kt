package app.autospace.car.theme

import android.os.SystemClock
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import app.autospace.notify.CarMotion
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** What a control is for, which picks its colours from the Material scheme. */
enum class GsTone { Primary, Tonal, Neutral, Outline, Danger }

data class GsColors(val container: Color, val content: Color, val border: Color = Color.Unspecified)

@Composable
fun gsColors(tone: GsTone): GsColors {
    val s = MaterialTheme.colorScheme
    return when (tone) {
        GsTone.Primary -> GsColors(s.primary, s.onPrimary)
        GsTone.Tonal -> GsColors(s.secondaryContainer, s.onSecondaryContainer)
        GsTone.Neutral -> GsColors(s.surfaceContainerHighest, s.onSurfaceVariant)
        GsTone.Outline -> GsColors(Color.Transparent, s.primary, s.outline)
        GsTone.Danger -> GsColors(s.error, s.onError)
    }
}

/**
 * r79: the pure button-shape rule behind [gsShape], kept free of
 * Composition so it is unit-testable: a pillButtons style (Horizon
 * only) takes the full pill; every other style takes the style's
 * corner exactly, as before.
 */
fun buttonShapeForLook(look: ControlLook): Shape =
    if (look.pillButtons) GsKit.PillShape else RoundedCornerShape(look.corner)

@Composable
fun gsShape(): Shape = buttonShapeForLook(gsLook())

/**
 * r24: a capsule or explicitly round button/tile container's shape.
 * Under a style with [ControlLook.rectangularButtons] (Pitlane only) the
 * container takes the style's corner (6dp) instead of its capsule/round
 * fallback, so no button or tile renders as a pill under Pitlane. Every
 * other style gets the fallback itself — which IS today's shape at every
 * call site — so no other style's shapes change by construction.
 * r29: the same rule now also squares dialog/sheet cards, browser shells,
 * and the nav media pill under Pit Lane (see the r29 spec); the
 * non-Pit Lane styles still keep their fallback exactly, which is what
 * keeps their look unchanged in this round.
 */
@Composable
fun gsButtonShape(fallback: Shape): Shape {
    val look = gsLook()
    return cardShapeForLook(look.rectangularButtons, look.corner, fallback)
}

/**
 * r29: the pure shape rule behind [gsButtonShape], kept free of
 * Composition so it is unit-testable: a rectangularButtons style (Pit
 * Lane only) takes its own corner; every other style keeps the fallback
 * exactly. [gsButtonShape] is this function fed by the current look.
 */
fun cardShapeForLook(rectangularButtons: Boolean, corner: Dp, fallback: Shape): Shape =
    if (rectangularButtons) RoundedCornerShape(corner) else fallback

/**
 * r48: the look-fed shape rule behind the card surfaces ([GsGroupedCard],
 * [GsPanelCard], [GsSheetSurface], the floating media card, widget cards,
 * frosted map chrome): a rectangularButtons style (Pitlane) or a
 * cardsUseStyleCorner style (Verve) takes its own corner; every other
 * style keeps the fallback exactly. The 3-arg form above is unchanged
 * (existing tests pin it); card call sites use this form.
 */
fun cardShapeForLook(look: ControlLook, fallback: Shape): Shape =
    if (look.rectangularButtons || look.cardsUseStyleCorner) RoundedCornerShape(look.corner) else fallback

/**
 * r48 (Verve motion): the state-change animation spec, pure so it is
 * unit-testable. Parked: the approved spring (medium stiffness;
 * dampingRatio defaults to 0.7) on press, toggle, morph and expand.
 * Moving: an instant snap — no spring while driving. Never continuous
 * or looping: this spec is only ever fed to state-change animations,
 * and it is only used by looks with [ControlLook.springMotion]
 * (Verve; Horizon since r79).
 * r79: [dampingRatio] defaults to 0.7, so existing callers are
 * unchanged; Horizon passes 0.65. r100: Verve passes 1.0 (critically
 * damped, no overshoot).
 */
fun <T> stateChangeMotionSpec(moving: Boolean, dampingRatio: Float = 0.7f): AnimationSpec<T> =
    if (moving) snap() else spring(dampingRatio = dampingRatio, stiffness = Spring.StiffnessMedium)

/**
 * r32: texture reads once per surface. A control sitting on a Pitlane panel
 * (which already draws its own stronger weave + grain) skips its own
 * weave/grain — two independent 8dp grids at different phases compounded
 * into a busy moiré on the audited Appearance page. Bevel, hairline, LED
 * and rim still draw; only the material pattern is suppressed. Pure so it
 * is unit-testable; [gsSurface] feeds it the current look + panel flag.
 */
fun shouldDrawControlTexture(look: ControlLook, onTexturedPanel: Boolean): Boolean =
    !onTexturedPanel && (look.weaveAlpha > 0f || look.grainAlpha > 0f)

/** The current theme's control look, swapped for its light-mode variant on a light UI. */
@Composable
fun gsLook(): ControlLook {
    val look = LocalGsTheme.current.control
    val light = look.light ?: return look
    return if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) light else look
}

/**
 * r46: the pure container-alpha rule behind [gsSurface], kept free of
 * Composition so it is unit-testable. Frosted fills pin their alpha
 * (see [FrostedChromeAlpha]) — the style's containerAlpha must not
 * dilute them, so Frost (0.97) would otherwise read 0.88 × 0.97 =
 * 0.8536. [keepAlpha] skips the scaling; everything else keeps
 * today's multiplication exactly.
 */
fun scaledContainerAlpha(inputAlpha: Float, lookAlpha: Float, keepAlpha: Boolean): Float =
    if (keepAlpha) inputAlpha else inputAlpha * lookAlpha

/**
 * Paints a control's container in the current theme's finish. Transparent containers stay
 * transparent in every theme. Use directly for custom-shaped tappables; [GsButton] and
 * [GsIconButton] already apply it. [latched] holds a control down - a selected option, the current
 * screen's nav button - in themes that have a pressed-in look.
 */
@Composable
fun Modifier.gsSurface(
    container: Color,
    shape: Shape = gsShape(),
    border: Color = Color.Unspecified,
    pressed: Boolean = false,
    latched: Boolean = false,
    /** r26: a recessed well (switch track under Pit Lane) — inverted bevel + inner top shadow. */
    well: Boolean = false,
    /** r46: keep the container's alpha exactly (frosted fills pin theirs — see [scaledContainerAlpha]). */
    keepAlpha: Boolean = false,
): Modifier {
    val look = gsLook()
    val effContainer = container.copy(alpha = scaledContainerAlpha(container.alpha, look.containerAlpha, keepAlpha))
    val down = pressed || latched
    // The keycap's face sinks onto its base while pressed, and stays down while latched: the base
    // shrinks to nothing and the face moves down by the same amount, so the control's overall size
    // never changes.
    // r48: under a spring-motion style (Verve; Horizon since r79) press
    // animations use the state-change spec (spring parked, snap moving); every
    // other style keeps its exact tween durations. The moving flag is
    // read at the state change, which is when the decision matters.
    val moving = CarMotion.moving
    val lipNow by animateDpAsState(if (down) 0.dp else look.lip, if (look.springMotion) stateChangeMotionSpec(moving, look.springDamping) else tween(if (pressed) 50 else 110), label = "keycap")
    // Tactile: the drop shadow collapses and the face drops by [ControlLook.sink].
    val lift by animateFloatAsState(if (down) 0f else 1f, if (look.springMotion) stateChangeMotionSpec(moving, look.springDamping) else tween(if (pressed) 50 else 140), label = "lift")
    val sinkNow by animateDpAsState(if (down) look.sink else 0.dp, if (look.springMotion) stateChangeMotionSpec(moving, look.springDamping) else tween(if (pressed) 50 else 140), label = "sink")
    var m = this
    if (effContainer.alpha > 0f && look.shadow > 0.dp) {
        m = m.drawBehind { drawSoftShadow(shape, look.shadow.toPx(), look.shadowAlpha * lift, lift) }
    }
    if (look.sink > 0.dp) m = m.offset(y = sinkNow)
    if (effContainer.alpha > 0f && look.lip > 0.dp) {
        val base = lerp(effContainer, Color.Black, 0.35f)
        m = m
            .padding(top = look.lip - lipNow, bottom = lipNow)
            .drawBehind {
                translate(top = lipNow.toPx()) {
                    drawOutline(shape.createOutline(size, layoutDirection, this), base)
                }
            }
    }
    m = m.clip(shape)
    if (effContainer.alpha > 0f) {
        val shade = if (effContainer.luminance() > 0.5f) look.bottomShadeOnLight else look.bottomShade
        m = if (look.topLight > 0f || shade > 0f) {
            m.background(
                Brush.verticalGradient(
                    0f to lerp(effContainer, Color.White, look.topLight),
                    0.5f to effContainer,
                    1f to lerp(effContainer, Color.Black, shade),
                ),
            )
        } else {
            m.background(effContainer)
        }
        if (latched && look.insetAlpha > 0f) {
            val inset = look.insetAlpha
            m = m.drawBehind {
                drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = inset), 0.3f to Color.Transparent))
            }
        }
        // r100 (Verve only): the latched primary tint — a faint primary
        // wash over the container, under the content and borders, so a
        // latched control reads selected on any tone. 0.12 keeps it a
        // tint, never a fill change; transparent containers skip it
        // with the rest of this block. The color is read here, in the
        // composable body — a draw scope cannot call MaterialTheme.
        if (latched && look.latchedPrimaryTint) {
            val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            m = m.drawBehind {
                drawRect(tint)
            }
        }
        // r24: the checker strip is gone entirely (Robert disliked it
        // under Pitlane and under Circuit). No strip is drawn for any
        // style; ControlLook.checkerStrip is retired and reads false
        // everywhere. Selected controls read through their primary
        // fill and bold label alone.
        // r26: Pit Lane material — carbon weave + brushed grain, built
        // once per size (drawWithCache) and drawn UNDER content and
        // borders, never over text. Twill suggestion: one diagonal per
        // 8dp cell, direction alternating by (row + col) parity, even
        // cells at full alpha, odd cells at 60% — the over-under read.
        // Grain is horizontal 1px streaks every 3dp. Lines are white on
        // dark faces, black on light ones. Faint on controls by design
        // (weave 0.05 / grain 0.03 dark); panels draw it stronger via
        // pitLanePanel. Suppressed wherever the container is transparent
        // (this whole block is inside the alpha > 0 gate), and the LED,
        // rivets, and borders all draw over it.
        // r32: on a textured panel the panel's own weave + grain already
        // texture this surface (see shouldDrawControlTexture) — a control
        // drawing its own second grid on top is what made the audited page
        // read busy instead of subtle.
        if (shouldDrawControlTexture(look, LocalOnPitlanePanel.current)) {
            val weave = look.weaveAlpha
            val grain = look.grainAlpha
            val thread = if (effContainer.luminance() > 0.5f) Color.Black else Color.White
            m = m.drawWithCache {
                val cell = 8.dp.toPx()
                val even = Path()
                val odd = Path()
                var row = 0
                var y = 0f
                while (y < size.height + cell) {
                    var col = 0
                    var x = 0f
                    while (x < size.width + cell) {
                        if ((row + col) % 2 == 0) {
                            even.moveTo(x, y + cell)
                            even.lineTo(x + cell, y)
                        } else {
                            odd.moveTo(x, y)
                            odd.lineTo(x + cell, y + cell)
                        }
                        col++
                        x += cell
                    }
                    row++
                    y += cell
                }
                val streaks = Path()
                val step = 3.dp.toPx()
                var sy = 0f
                while (sy < size.height + step) {
                    streaks.moveTo(0f, sy)
                    streaks.lineTo(size.width, sy)
                    sy += step
                }
                val weaveStroke = Stroke(width = 1.dp.toPx())
                onDrawBehind {
                    if (weave > 0f) {
                        drawPath(even, thread.copy(alpha = weave), style = weaveStroke)
                        drawPath(odd, thread.copy(alpha = weave * 0.6f), style = weaveStroke)
                    }
                    if (grain > 0f) {
                        // r32: grain was a 1px stroke while weave is 1dp —
                        // same 1dp stroke now (see pitLanePanelTexture);
                        // alphas unchanged.
                        drawPath(streaks, thread.copy(alpha = grain), style = Stroke(width = 1.dp.toPx()))
                    }
                }
            }
        }
        if (look.rimAlpha > 0f) {
            m = m.border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = look.rimAlpha), Color.Transparent)), shape)
        }
        // Notscape FrostKit's hairline: a true 0.5dp outline at 0.45 alpha, on top of the
        // rim fade, so the edge reads on any background — not just on dark ones.
        if (look.hairlineAlpha > 0f) {
            m = m.border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = look.hairlineAlpha), shape)
        }
        // r110 (Blueprint only): the crisp 1px ring in
        // onSurfaceVariant around the control, drawn as part of
        // the button background (under content, like the stitch).
        // A literal pixel, not 1dp — the pick says 1px — inset
        // 0.5px so the full pixel lands inside the edge (the
        // clip would halve a stroke centered on the outline).
        // Latched: the ring takes the scheme primary, plus the
        // faint primary tint drawn above (latchedPrimaryTint).
        // Pressed: the dim at the end of gsSurface overlays
        // everything, so a pressed control dims its ring with
        // the rest of the face but never drops it.
        if (look.ringAlpha > 0f) {
            val ring = if (latched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            m = m.drawBehind {
                val insetPx = 0.5f
                val ringSize = Size(
                    (size.width - 1f).coerceAtLeast(0f),
                    (size.height - 1f).coerceAtLeast(0f),
                )
                translate(insetPx, insetPx) {
                    drawOutline(
                        shape.createOutline(ringSize, layoutDirection, this),
                        ring.copy(alpha = look.ringAlpha),
                        style = Stroke(width = 1f),
                    )
                }
            }
        }
        // Phase 8 OLED: every surface token is pure black, so a control whose
        // look has no hairline (Flat, Tactile, Keycap) would be a black shape
        // on a black backdrop, separated by nothing but its icon and label.
        // Force the same 0.5dp kit hairline on every Gs control in OLED mode,
        // whatever the style — for Frost it duplicates its own identical
        // hairline, which is visually a no-op.
        // r110: under a ring style the ring IS the edge, so the forced
        // hairline would double it — skipped when the ring draws.
        if (LocalOledDark.current && look.ringAlpha <= 0f) {
            m = m.border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), shape)
        }
        // r26: the bevel is the panel edge. Raised (default): light top,
        // dark bottom. A well (switch track under Pit Lane) inverts it —
        // dark top, light bottom — so the track reads milled into the
        // panel, plus a soft inner top shadow. Pit Lane draws its bevel
        // on latched controls too (bevelOnLatched: the bevel is the panel
        // edge, not a selection cue); other styles keep the old latched
        // suppression, so their pressed-in read stays clean.
        if ((!latched || look.bevelOnLatched || well) && (look.bevelTop > 0f || look.bevelBottom > 0f)) {
            m = if (well) {
                m.drawBehind {
                    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.3f to Color.Transparent))
                }.border(
                    1.dp,
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = look.bevelBottom),
                        0.3f to Color.Transparent,
                        0.7f to Color.Transparent,
                        1f to Color.White.copy(alpha = look.bevelTop),
                    ),
                    shape,
                )
            } else {
                m.border(
                    1.dp,
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = look.bevelTop),
                        0.3f to Color.Transparent,
                        0.7f to Color.Transparent,
                        1f to Color.Black.copy(alpha = look.bevelBottom),
                    ),
                    shape,
                )
            }
        }
        // r112 (Sector only): the trio accent segment — three equal
        // parallelogram blocks along the top edge, start-aligned,
        // about a third of the button wide, 3dp thick, sharing
        // slanted 45° seams that lean forward (the top of each seam
        // shifted toward the reading direction by the thickness).
        // The colors are the scheme's accents in order (primary,
        // secondary, tertiary) — under Circuit exactly blue
        // #007AC2, red #E4002B, yellow #FFD659 — read here, in the
        // composable body, because a draw scope cannot call
        // MaterialTheme. Drawn under content, like the stitch and
        // ring, and clipped to the shape, so the start end follows
        // the corner arc. Latched: the trio brightens (each accent
        // lifted 20% toward white) plus the faint primary tint
        // drawn above (latchedPrimaryTint). Pressed: the fill dims
        // but the trio stays full — the pressed dim below is
        // notched around the segment's footprint, so the trio is
        // never dimmed or dropped. Seams lean forward by default
        // and flip only on Robert's word; in RTL the whole segment
        // mirrors, so blue still starts at the start edge.
        if (look.segmentAlpha > 0f && look.segmentThickness > 0.dp) {
            val trio = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.secondary,
                MaterialTheme.colorScheme.tertiary,
            )
            val lift = if (latched) 0.20f else 0f
            val segColors = trio.map { lerp(it, Color.White, lift).copy(alpha = look.segmentAlpha) }
            val segThickness = look.segmentThickness
            m = m.drawBehind {
                val thicknessPx = segThickness.toPx()
                val segW = size.width / 3f
                val slant = thicknessPx
                val blockW = segW / 3f
                fun mx(x: Float) = if (layoutDirection == LayoutDirection.Rtl) size.width - x else x
                for (i in 0..2) {
                    val x0b = i * blockW
                    val x1b = (i + 1) * blockW
                    val x0t = x0b + slant
                    val x1t = x1b + slant
                    val block = Path().apply {
                        moveTo(mx(x0b), thicknessPx)
                        lineTo(mx(x1b), thicknessPx)
                        lineTo(mx(x1t), 0f)
                        lineTo(mx(x0t), 0f)
                        close()
                    }
                    drawPath(block, segColors[i])
                }
            }
        }
        // r108 (Heirloom only): the leather stitch — a dashed
        // hairline inset from the edge, drawn as part of the button
        // background (under content, like the weave), never as an
        // overlay widget. Warm cream on dark containers; on light
        // containers the same warm family in dark brass (the light
        // Heritage primary), so the stitch stays visible on cream
        // fills, where cream-on-cream would vanish — the weave
        // picks its thread the same way, by container luminance.
        // The pressed dim overlays everything at the end of
        // gsSurface, so a pressed control dims its stitch with the
        // rest of the face but never drops it.
        if (look.stitchAlpha > 0f && look.stitchInset > 0.dp) {
            val stitch = if (effContainer.luminance() > 0.5f) Color(0xFF6E5613) else Color(0xFFF5EEDD)
            m = m.drawBehind {
                val insetPx = look.stitchInset.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()), 0f)
                val insetSize = Size(
                    (size.width - insetPx * 2).coerceAtLeast(0f),
                    (size.height - insetPx * 2).coerceAtLeast(0f),
                )
                translate(insetPx, insetPx) {
                    drawOutline(
                        shape.createOutline(insetSize, layoutDirection, this),
                        stitch.copy(alpha = look.stitchAlpha),
                        style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
                    )
                }
            }
        }
        // r26: latched LED — a single lit yellow dot at the top-end
        // corner, an ADDITION to the primary fill + bold label, never a
        // replacement for it. One dot, no strip, no pattern: this is not
        // a checker and nothing checker-like returns with it. Drawn over
        // the texture and edge, clear of the centered content.
        if (latched && look.ledDot) {
            m = m.drawBehind {
                val d = 6.dp.toPx()
                val inset = 5.dp.toPx()
                val cx = if (layoutDirection == LayoutDirection.Ltr) size.width - inset - d / 2 else inset + d / 2
                val cy = inset + d / 2
                val led = Color(0xFFFFD200)
                drawCircle(led.copy(alpha = 0.30f), radius = d / 2 + 2.dp.toPx(), center = Offset(cx, cy))
                drawCircle(led, radius = d / 2, center = Offset(cx, cy))
                drawCircle(Color.Black.copy(alpha = 0.35f), radius = d / 2, center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
            }
        }
    }
    if (border.isSpecified) m = m.border(BorderStroke(1.dp, border), shape)
    if (pressed) m = m.drawWithContent {
        drawContent()
        if (look.segmentAlpha > 0f && look.segmentThickness > 0.dp && effContainer.alpha > 0f) {
            // r112: the trio segment stays the full trio while
            // pressed — the dim is drawn around the segment's
            // footprint instead of over it: the full face below the
            // strip, plus the face beside the strip on the top row.
            // Same two rects in RTL, mirrored with the segment.
            // (The segment only drew inside the alpha gate above,
            // so the notch applies only when it actually drew.)
            val thicknessPx = look.segmentThickness.toPx()
            val segRight = size.width / 3f + thicknessPx
            val dim = Color.Black.copy(alpha = look.pressedDim)
            drawRect(
                dim,
                topLeft = Offset(0f, thicknessPx),
                size = Size(size.width, (size.height - thicknessPx).coerceAtLeast(0f)),
            )
            if (layoutDirection == LayoutDirection.Ltr) {
                drawRect(
                    dim,
                    topLeft = Offset(segRight, 0f),
                    size = Size((size.width - segRight).coerceAtLeast(0f), thicknessPx),
                )
            } else {
                drawRect(
                    dim,
                    topLeft = Offset(0f, 0f),
                    size = Size((size.width - segRight).coerceAtLeast(0f), thicknessPx),
                )
            }
        } else {
            drawRect(Color.Black.copy(alpha = look.pressedDim))
        }
    }
    return m
}

/**
 * A blurred shadow falling [elevation] below the control, faked with a few stacked, growing
 * outlines: cheap, works on every API level, and already soft enough that the car's video
 * compression has nothing crisp to smear.
 */
private fun DrawScope.drawSoftShadow(shape: Shape, elevation: Float, alpha: Float, lift: Float) {
    if (alpha <= 0f) return
    val blur = elevation * 2f
    val steps = 6
    for (i in steps downTo 1) {
        val grow = blur * i / steps - blur * 0.35f
        val outline = shape.createOutline(
            Size((size.width + grow * 2).coerceAtLeast(0f), (size.height + grow * 2).coerceAtLeast(0f)),
            layoutDirection,
            this,
        )
        translate(-grow, elevation * lift - grow) {
            drawOutline(outline, Color.Black.copy(alpha = alpha / steps))
        }
    }
}

/**
 * Whether a control should look pressed. Held for at least [MIN_PRESS_MS] after the finger lifts:
 * a quick tap would otherwise come and go between two frames of the car's video stream.
 */
@Composable
private fun rememberPressed(source: MutableInteractionSource): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(source) {
        var since = 0L
        var release: Job? = null
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    release?.cancel()
                    since = SystemClock.uptimeMillis()
                    shown = true
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    release?.cancel()
                    release = launch {
                        delay((MIN_PRESS_MS - (SystemClock.uptimeMillis() - since)).coerceAtLeast(0))
                        shown = false
                    }
                }
            }
        }
    }
    return shown
}

private const val MIN_PRESS_MS = 140L

@Composable
private fun Modifier.gsPressable(
    source: MutableInteractionSource,
    pressed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    role: Role = Role.Button,
    shape: Shape = gsShape(),
): Modifier {
    val look = gsLook()
    // Reached with the car's knob or D-pad rather than a finger: a ring shows where focus is.
    val focused by source.collectIsFocusedAsState()
    val ring = MaterialTheme.colorScheme.primary
    return this
        .scale(if (pressed) look.pressedScale else 1f)
        .then(if (focused) Modifier.border(3.dp, ring, shape) else Modifier)
        .combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = role,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

private fun GsColors.disabled(enabled: Boolean, onSurface: Color): GsColors =
    if (enabled) this
    else GsColors(
        container = if (container.alpha > 0f) onSurface.copy(alpha = 0.12f) else container,
        content = onSurface.copy(alpha = 0.38f),
        border = if (border.isSpecified) onSurface.copy(alpha = 0.12f) else border,
    )

/** A themed text (or text-and-icon) button. */
@Composable
fun GsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: GsTone = GsTone.Primary,
    colors: GsColors = gsColors(tone),
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val c = colors.disabled(enabled, MaterialTheme.colorScheme.onSurface)
    val shape = gsShape()
    val source = remember { MutableInteractionSource() }
    val pressed = rememberPressed(source)
    Row(
        modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
            .gsPressable(source, pressed, enabled, onClick, shape = shape)
            .gsSurface(c.container, shape, c.border, pressed)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides c.content) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

/** A themed square icon button. Pass a [modifier] size to override the default [size]. */
@Composable
fun GsIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: GsTone = GsTone.Neutral,
    colors: GsColors = gsColors(tone),
    enabled: Boolean = true,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    shape: Shape = gsShape(),
) {
    GsIconBox(onClick, modifier.size(size), colors, enabled, shape) {
        Icon(icon, contentDescription, Modifier.size(iconSize))
    }
}

/** [GsIconButton] with arbitrary content, for glyphs drawn in code or app icons. */
@Composable
fun GsIconBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: GsColors = gsColors(GsTone.Neutral),
    enabled: Boolean = true,
    shape: Shape = gsShape(),
    /** Held down, as a keycap theme draws it - for a toggle or the current screen's nav button. */
    latched: Boolean = false,
    /** Also marks the control with the theme's hold marker, so the hold can be found. */
    onLongClick: (() -> Unit)? = null,
    /** r26: recessed well (switch track under Pit Lane) — inverted bevel + inner top shadow. */
    well: Boolean = false,
    /** r46: keep the container's alpha exactly (frosted fills pin theirs — see [scaledContainerAlpha]). */
    keepAlpha: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = colors.disabled(enabled, MaterialTheme.colorScheme.onSurface)
    val source = remember { MutableInteractionSource() }
    val pressed = rememberPressed(source)
    Box(
        modifier
            .gsPressable(source, pressed, enabled, onClick, onLongClick, shape = shape)
            .gsSurface(c.container, shape, c.border, pressed, latched, well, keepAlpha),
        contentAlignment = Alignment.Center,
    ) {
        if (onLongClick != null && enabled) GsHoldMarker(gsLook().holdMarker, c.container, shape, source)
        CompositionLocalProvider(LocalContentColor provides c.content) { content() }
    }
}
