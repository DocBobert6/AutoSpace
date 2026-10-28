package app.autospace.car.theme

import android.os.SystemClock
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import app.autospace.car.CarSettings
import app.autospace.notify.CarMotion
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
 * r115 (Pitlane only): a chamfered rectangle — flat diagonal cuts at
 * the four corners instead of rounded arcs. The cut is coerced to at
 * most half the smaller dimension, so a tiny surface degrades to a
 * narrow octagon rather than an inverted shape. A data class so tests
 * can pin the shape by equality.
 */
data class ChamferShape(val cut: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        // density is a parameter here, not a receiver — toPx() needs
        // the with() scope (unlike draw scopes, where Density IS the
        // receiver and bare toPx() resolves).
        val cutPx = with(density) {
            minOf(cut.toPx(), minOf(size.width, size.height) / 2f).coerceAtLeast(0f)
        }
        val path = Path().apply {
            moveTo(cutPx, 0f)
            lineTo(size.width - cutPx, 0f)
            lineTo(size.width, cutPx)
            lineTo(size.width, size.height - cutPx)
            lineTo(size.width - cutPx, size.height)
            lineTo(cutPx, size.height)
            lineTo(0f, size.height - cutPx)
            lineTo(0f, cutPx)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * r79: the pure button-shape rule behind [gsShape], kept free of
 * Composition so it is unit-testable: a pillButtons style (Horizon
 * only) takes the full pill; every other style takes the style's
 * corner exactly, as before.
 * r115: a chamferCut style (Pitlane only) takes the chamfer instead
 * of its corner — the cut IS the corner treatment under the zoned
 * rework, so buttons, tiles and keys render cut-cornered, never
 * rounded, under Pitlane.
 */
fun buttonShapeForLook(look: ControlLook): Shape =
    if (look.chamferCut > 0.dp) ChamferShape(look.chamferCut)
    else if (look.pillButtons) GsKit.PillShape else RoundedCornerShape(look.corner)

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
 * r115: under a chamferCut style (Pitlane only) the container takes
 * the chamfer (4dp cut) instead of the corner — the r24 squaring rule
 * with the corner treatment replaced by the cut.
 */
@Composable
fun gsButtonShape(fallback: Shape): Shape {
    val look = gsLook()
    return if (look.chamferCut > 0.dp) ChamferShape(look.chamferCut)
    else cardShapeForLook(look.rectangularButtons, look.corner, fallback)
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
 * r115: a chamferCut style (Pitlane only) takes the chamfer instead of
 * the corner — cards and sheets render cut-cornered under Pitlane.
 */
fun cardShapeForLook(look: ControlLook, fallback: Shape): Shape =
    if (look.chamferCut > 0.dp) ChamferShape(look.chamferCut)
    else if (look.rectangularButtons || look.cardsUseStyleCorner) RoundedCornerShape(look.corner) else fallback

/**
 * r115 (Pitlane only): the zoned frame tier, driven by the surface's
 * smaller dimension. The thresholds are the kit's own size facts: keys
 * bottom out at a 44dp floor (CarKeyboard) while standard buttons sit
 * at 48dp and up, so 48dp is the S/M boundary; cards and sheets sit
 * at 96dp and up, so 96dp is the M/L boundary. A surface exactly on a
 * boundary takes the larger tier. Pure so it is unit-testable.
 */
enum class PitlaneFrameTier { S, M, L }

fun pitlaneFrameTier(minDim: Dp): PitlaneFrameTier = when {
    minDim < 48.dp -> PitlaneFrameTier.S
    minDim < 96.dp -> PitlaneFrameTier.M
    else -> PitlaneFrameTier.L
}

/**
 * r115: the frame band width per tier. L keeps a thick 12dp band;
 * M a medium 8dp band; S has no band at all — the border thins to
 * the standard hairline-only edge (shedding step 3). Pure so it is
 * unit-testable.
 */
fun pitlaneFrameBand(tier: PitlaneFrameTier): Dp = when (tier) {
    PitlaneFrameTier.L -> 12.dp
    PitlaneFrameTier.M -> 8.dp
    PitlaneFrameTier.S -> 0.dp
}

/**
 * r115: the twill cell per tier. L keeps the kit's 8dp cell; M takes
 * a finer 6dp cell so the weave reads denser on the narrower band;
 * S draws no weave (shedding step 1). Pure so it is unit-testable.
 */
fun pitlaneWeaveCell(tier: PitlaneFrameTier): Dp = when (tier) {
    PitlaneFrameTier.L -> 8.dp
    PitlaneFrameTier.M -> 6.dp
    PitlaneFrameTier.S -> 0.dp
}

/**
 * r115: the hex bolt size per tier — ~8dp at L (the pick), 6dp at M,
 * none at S (shedding step 2). Pure so it is unit-testable.
 */
fun pitlaneBoltSize(tier: PitlaneFrameTier): Dp = when (tier) {
    PitlaneFrameTier.L -> 8.dp
    PitlaneFrameTier.M -> 6.dp
    PitlaneFrameTier.S -> 0.dp
}

/**
 * r115: the four bolt centers for a surface, set INTO the frame on a
 * strict grid: each center sits half a band in from its corner, so an
 * 8dp bolt in a 12dp band spans 2..10dp and a 6dp bolt in an 8dp band
 * spans 1..7dp — inside the band on every side, never on the edge.
 * Pure floats so it is unit-testable.
 */
fun pitlaneBoltCenters(widthPx: Float, heightPx: Float, bandPx: Float): List<Offset> {
    val c = bandPx / 2f
    return listOf(
        Offset(c, c),
        Offset(widthPx - c, c),
        Offset(c, heightPx - c),
        Offset(widthPx - c, heightPx - c),
    )
}

/**
 * r115: the six vertices of a flat-top hexagon centered at [center]
 * with circumradius [radiusPx] — a bolt head reads as a nut with flat
 * top and bottom edges (vertices at 0°, 60°, 120°, 180°, 240°, 300°).
 * Pure so it is unit-testable.
 */
fun pitlaneBoltHexPoints(center: Offset, radiusPx: Float): List<Offset> {
    val angles = listOf(0f, 60f, 120f, 180f, 240f, 300f)
    return angles.map { deg ->
        val rad = Math.toRadians(deg.toDouble()).toFloat()
        Offset(center.x + radiusPx * cos(rad), center.y + radiusPx * sin(rad))
    }
}

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
 * r114 (Morph only): the Chroma wash blend — the fill IS the
 * wash: the container lerped toward the scheme primary at
 * [wash], alpha preserved (a plain lerp would pull the
 * alpha toward the primary's 1.0, which would un-pin
 * frosted fills). Pure so it is unit-testable.
 */
fun morphWashBlend(container: Color, primary: Color, wash: Float): Color =
    lerp(container, primary, wash).copy(alpha = container.alpha)

/** r114: the WCAG contrast ratio between two colors (opaque RGB; translucent fills are judged on their RGB, the standard approximation over an unknown backdrop). Pure so it is unit-testable. */
fun morphContrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

/** r114: the latched text-contrast bar (WCAG AA for normal text). */
const val MorphLatchedContrastMin = 4.5f

/**
 * r114 (Morph only): whether latched text stays readable over
 * the full-strength wash — the guard GsIconBox applies before
 * handing a latched control to gsSurface. False means the
 * tint-only fallback (a faint 0.12 primary overlay on the
 * unwashed fill, the same strength as Verve's latched tint)
 * instead of the full wash. Pure so it is unit-testable;
 * the runtime colors are whatever the device's dynamic
 * scheme yields, so the guard — not a fixed table — is
 * what keeps text readable on every seed, in both modes.
 */
fun morphLatchedWashPasses(content: Color, container: Color, primary: Color, wash: Float): Boolean =
    morphContrastRatio(content, morphWashBlend(container, primary, wash)) >= MorphLatchedContrastMin

/**
 * r114 (Morph only): the uniform Dp corner of a rounded
 * shape, or null when the shape must never morph (percent
 * corners — pills; mixed corners; non-rounded shapes; a zero
 * corner, which stays square rather than opening to 2dp on
 * latch). DpCornerSize is internal to foundation, so the
 * corner is read through the public [CornerSize.toPx]: a
 * Dp corner's pixel size is independent of the shape size,
 * while a percent corner scales with it — comparing at two
 * sizes tells them apart. Pure so it is unit-testable.
 */
fun roundedCornerDp(shape: Shape, density: Density): Dp? {
    val r = shape as? RoundedCornerShape ?: return null
    val corners = listOf(r.topStart, r.topEnd, r.bottomEnd, r.bottomStart)
    val small = Size(100f, 100f)
    val large = Size(200f, 200f)
    val pxSmall = corners.map { it.toPx(small, density) }
    val pxLarge = corners.map { it.toPx(large, density) }
    if (pxSmall != pxLarge) return null
    val first = pxSmall[0]
    if (first == 0f || pxSmall.any { it != first }) return null
    return (first / density.density).dp
}

/**
 * r114 (Morph only): the corner a morphing control animates
 * toward. [base] is the shape's own corner: the style
 * corner on the default path, the explicit fallback on
 * keys/tabs. Full range on the style corner (rest →
 * pressedCorner pressed, latchedCorner latched); a smaller
 * ±2dp range on any other rounded shape (keys' 12dp
 * fallback, tabs' explicit corners). Pressed wins the
 * corner over latched: pressing a latched button tightens
 * it, and releasing returns it to the latched corner.
 * Pure so it is unit-testable.
 */
fun morphCornerTarget(base: Dp, look: ControlLook, pressed: Boolean, latched: Boolean): Dp =
    if (base == look.corner) {
        when {
            pressed -> look.pressedCorner
            latched -> look.latchedCorner
            else -> base
        }
    } else {
        when {
            pressed -> (base - 2.dp).coerceAtLeast(0.dp)
            latched -> base + 2.dp
            else -> base
        }
    }

/**
 * r32: texture reads once per surface. A control sitting on a Pitlane panel
 * (which already draws its own stronger weave + grain) skips its own
 * weave/grain — two independent 8dp grids at different phases compounded
 * into a busy moiré on the audited Appearance page. Bevel, hairline, LED
 * and rim still draw; only the material pattern is suppressed. Pure so it
 * is unit-testable; [gsSurface] feeds it the current look + panel flag.
 * r115: the zoned frame is exempt — band-confined weave cannot moiré,
 * because bands are disjoint by construction (the control's band sits
 * at the control's edge, the panel's at the panel's, separated by
 * padding), so a zoned control on a panel still draws its own frame.
 * A non-zoned weave (no built-in look carries one from r115 on) keeps
 * the old suppression.
 */
fun shouldDrawControlTexture(look: ControlLook, onTexturedPanel: Boolean): Boolean =
    (look.weaveAlpha > 0f || look.grainAlpha > 0f) && (!onTexturedPanel || look.chamferCut > 0.dp)

/** The current theme's control look, swapped for its light-mode variant on a light UI. */
@Composable
fun gsLook(): ControlLook {
    val look = LocalGsTheme.current.control
    val light = look.light ?: return look
    return if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) light else look
}

/**
 * r113: the minimum of the car screen's brightness setting
 * (CarSettings clamps brightness to 0.05..1 on load and on
 * set — see CarSettings.kt). The halo fade is measured
 * against this floor, so the halo reaches exactly zero at
 * minimum brightness instead of merely dimming with the
 * brightness scrim.
 */
const val HaloBrightnessMin = 0.05f

/**
 * r113: the halo's brightness factor, pure so it is
 * unit-testable. 1 at full brightness, 0 at minimum
 * brightness ([HaloBrightnessMin]), linear between — so the
 * halo fades out as the panel dims and the blend degrades
 * to pure Contour at the floor. The brightness scrim dims
 * everything uniformly; this factor fades the halo FASTER,
 * to nothing, which is what the pick asks for.
 */
fun haloBrightnessFactor(brightness: Float): Float =
    ((brightness - HaloBrightnessMin) / (1f - HaloBrightnessMin)).coerceIn(0f, 1f)

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
    /** r114: latched tint-only fallback (see the guard in GsIconBox) — the fill stays unwashed and carries only the faint tint, because the full-strength wash would leave latched text below 4.5:1. */
    latchedWashFallback: Boolean = false,
): Modifier {
    val look = gsLook()
    // r114 (Morph only): the Chroma wash — the fill IS the
    // wash, blended here so every downstream read (lip base,
    // shade choice, weave thread, stitch color, background)
    // sees the washed fill. Wash precedence, stated plainly:
    // latched wins over pressed (the persistent state keeps
    // its strength under the press dim); pressed wins over
    // rest. Latched + fallback means no wash at all — the
    // tint-only block below carries the latched read instead.
    // Transparent containers stay transparent: the wash is
    // over a surface tone, and a transparent container has
    // none (Outline buttons keep their transparent fill).
    val washStrength = when {
        latched -> if (latchedWashFallback) 0f else look.washLatchedAlpha
        pressed -> look.washPressedAlpha
        else -> look.washAlpha
    }
    val rawContainer = container.copy(alpha = scaledContainerAlpha(container.alpha, look.containerAlpha, keepAlpha))
    val effContainer = if (rawContainer.alpha > 0f && washStrength > 0f) {
        morphWashBlend(rawContainer, MaterialTheme.colorScheme.primary, washStrength)
    } else {
        rawContainer
    }
    val down = pressed || latched
    // The keycap's face sinks onto its base while pressed, and stays down while latched: the base
    // shrinks to nothing and the face moves down by the same amount, so the control's overall size
    // never changes.
    // r48: under a spring-motion style (Verve; Horizon since r79) press
    // animations use the state-change spec (spring parked, snap moving); every
    // other style keeps its exact tween durations. The moving flag is
    // read at the state change, which is when the decision matters.
    val moving = CarMotion.moving
    // r113: the panel brightness for the halo fade (Silhouette
    // only in practice — every other look has haloAlpha 0, so
    // the value is read but never used). Read unconditionally:
    // a conditional collectAsState would change the hook count
    // when the style changes.
    val brightness by CarSettings.brightness.collectAsState()
    val lipNow by animateDpAsState(if (down) 0.dp else look.lip, if (look.springMotion) stateChangeMotionSpec(moving, look.springDamping) else tween(if (pressed) 50 else 110), label = "keycap")
    // Tactile: the drop shadow collapses and the face drops by [ControlLook.sink].
    val lift by animateFloatAsState(if (down) 0f else 1f, if (look.springMotion) stateChangeMotionSpec(moving, look.springDamping) else tween(if (pressed) 50 else 140), label = "lift")
    val sinkNow by animateDpAsState(if (down) look.sink else 0.dp, if (look.springMotion) stateChangeMotionSpec(moving, look.springDamping) else tween(if (pressed) 50 else 140), label = "sink")
    // r114 (Morph only): the morphing corner. The base is the
    // style corner on the default path (shape == the look's
    // own shape) and the shape's own uniform Dp corner
    // otherwise (keys' 12dp fallback, tabs' explicit
    // corners); percent corners (pills) and non-rounded
    // shapes (circles) never morph — pills are wash-only,
    // per the pick. The animation rides the shared
    // state-change spec (spring parked, snap moving), so
    // motion is parked-only exactly as the pick asks.
    // Called unconditionally (like lip/lift/sink above) so
    // the hook count never changes with the style; when
    // morph is off the value is simply unused. Key shedding
    // order, FIXED per the pick: if the keyboard ever
    // looks busy, the wash drops FIRST — the wash is the
    // independently gated layer (zeroing washAlpha leaves
    // every corner exactly as picked), never the corner
    // range. No busy condition triggers shedding today:
    // keys ship with both layers, and the order governs
    // any future simplification.
    val morphBase: Dp? = if (!look.morphCorners) null
        else if (shape == buttonShapeForLook(look)) look.corner
        else roundedCornerDp(shape, LocalDensity.current)
    val morphTarget = morphBase?.let { morphCornerTarget(it, look, pressed, latched) } ?: 0.dp
    val cornerNow by animateDpAsState(morphTarget, if (look.springMotion) stateChangeMotionSpec(moving, look.springDamping) else tween(110), label = "morph")
    val drawShape: Shape = if (morphBase != null) RoundedCornerShape(cornerNow) else shape
    var m = this
    if (effContainer.alpha > 0f && look.shadow > 0.dp) {
        m = m.drawBehind { drawSoftShadow(drawShape, look.shadow.toPx(), look.shadowAlpha * lift, lift) }
    }
    // r113 (Silhouette only): the faint halo, buttons at half
    // strength — neutral white, never primary, top + sides only.
    // Drawn here, BEFORE clip(drawShape), like the soft shadow: the
    // glow must reach past the edge, and the clip would cut it.
    // The clipRect cuts everything below the bottom edge, so
    // the bottom stays clean (no drop-shadow read). Cards draw
    // the full halo (see silhouetteCardHalo); buttons draw half
    // (the faintest tier), both scaled by the brightness factor
    // so the halo fades out at minimum brightness. The nested
    // translucent fills are the same faked-blur idiom as
    // drawSoftShadow: each larger fill adds one layer of alpha
    // at the rim, so the glow falls off over haloFalloff
    // instead of stopping hard. Transparent containers skip it
    // with the shadow.
    if (effContainer.alpha > 0f && look.haloAlpha > 0f && look.haloFalloff > 0.dp) {
        val haloTotal = look.haloAlpha * 0.5f * haloBrightnessFactor(brightness)
        if (haloTotal > 0f) {
            val falloff = look.haloFalloff
            m = m.drawBehind {
                val falloffPx = falloff.toPx()
                val steps = 4
                clipRect(
                    left = -falloffPx,
                    top = -falloffPx,
                    right = size.width + falloffPx,
                    bottom = size.height,
                ) {
                    for (i in steps downTo 1) {
                        val grow = falloffPx * i / steps
                        val outline = drawShape.createOutline(
                            Size((size.width + grow * 2).coerceAtLeast(0f), (size.height + grow * 2).coerceAtLeast(0f)),
                            layoutDirection,
                            this,
                        )
                        translate(-grow, -grow) {
                            drawOutline(outline, Color.White.copy(alpha = haloTotal / steps))
                        }
                    }
                }
            }
        }
    }
    if (look.sink > 0.dp) m = m.offset(y = sinkNow)
    if (effContainer.alpha > 0f && look.lip > 0.dp) {
        val base = lerp(effContainer, Color.Black, 0.35f)
        m = m
            .padding(top = look.lip - lipNow, bottom = lipNow)
            .drawBehind {
                translate(top = lipNow.toPx()) {
                    drawOutline(drawShape.createOutline(size, layoutDirection, this), base)
                }
            }
    }
    m = m.clip(drawShape)
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
        // r115 (Pitlane only): the smallest tier's latched read — no
        // bolts to light, so the fill takes the faint primary tint
        // instead (same 0.12 strength as Verve's latched tint).
        // Tier needs the size, so this lives in a drawBehind, not
        // the body.
        if (latched && look.chamferCut > 0.dp) {
            val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            m = m.drawBehind {
                val minDimDp = (minOf(size.width, size.height) / density).dp
                if (pitlaneFrameTier(minDimDp) == PitlaneFrameTier.S) {
                    drawRect(tint)
                }
            }
        }
        // r114 (Morph only): the latched tint-only fallback —
        // when the full-strength wash would leave latched text
        // below 4.5:1 (the guard in GsIconBox sets
        // latchedWashFallback), the fill stays unwashed and
        // carries only this faint 0.12 primary overlay, the
        // same strength as Verve's latched tint — so the text
        // stays readable on every dynamic seed, in both modes.
        // Transparent containers skip it with the rest of
        // this block, like the tint above.
        if (latched && latchedWashFallback) {
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
        // r115 (Pitlane only): the zoned frame. The weave is confined
        // to the frame band by an EvenOdd clip (outer outline minus
        // the inset inner rect), so the fill stays clean solid
        // blocking; hex bolts sit INTO the band at the four corners
        // on a strict grid (see pitlaneBoltCenters). No grain: the
        // fill zone is clean and the frame zone carries weave +
        // bolts only, so grain has no zone left. Tier comes from the
        // size at draw time, so the shedding order is size-driven:
        // S draws nothing here (clean fill, standard border, chamfer
        // + corner only — the tint for latched S controls is drawn
        // separately below). The twill construction is the r26 one
        // (one diagonal per cell, direction alternating by parity,
        // even cells full alpha, odd cells 60% — the over-under read),
        // with the tier's cell size. Drawn under content and borders,
        // like the old full-face weave. Latched: the bolts light the
        // scheme primary; unlatched they are steel by container
        // luminance. The primary is read in the composable body — a
        // draw scope cannot call MaterialTheme. The gate is the
        // zoned exemption itself (see shouldDrawControlTexture): a
        // chamfered look draws its frame whether or not it sits on a
        // panel, and it draws when it carries weave OR bolts — the
        // bolts must not vanish just because the weave value is 0.
        if (look.chamferCut > 0.dp && (look.weaveAlpha > 0f || look.rivets)) {
            val weave = look.weaveAlpha
            val thread = if (effContainer.luminance() > 0.5f) Color.Black else Color.White
            val boltPrimary = MaterialTheme.colorScheme.primary
            m = m.drawWithCache {
                val minDimDp = (minOf(size.width, size.height) / density).dp
                val tier = pitlaneFrameTier(minDimDp)
                val bandPx = pitlaneFrameBand(tier).toPx()
                val cell = pitlaneWeaveCell(tier).toPx()
                val boltPx = pitlaneBoltSize(tier).toPx()
                val even = Path()
                val odd = Path()
                if (tier != PitlaneFrameTier.S) {
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
                }
                val outerOutline = drawShape.createOutline(size, layoutDirection, this)
                // toPx() needs a Density receiver: compute here, in the
                // cache scope, not inside the Path.apply below (whose
                // receiver is the Path, not the scope).
                val innerCornerPx = (look.corner - pitlaneFrameBand(tier)).coerceAtLeast(0.dp).toPx()
                val bandPath = Path().apply {
                    fillType = PathFillType.EvenOdd
                    when (val o = outerOutline) {
                        is Outline.Generic -> addPath(o.path)
                        is Outline.Rounded -> addRoundRect(RoundRect(o.rect, o.radius))
                        is Outline.Rectangle -> addRect(o.rect)
                    }
                    addRoundRect(
                        RoundRect(
                            Rect(bandPx, bandPx, size.width - bandPx, size.height - bandPx),
                            CornerRadius(innerCornerPx, innerCornerPx),
                        ),
                    )
                }
                val weaveStroke = Stroke(width = 1.dp.toPx())
                onDrawBehind {
                    if (tier != PitlaneFrameTier.S && weave > 0f) {
                        clipPath(bandPath) {
                            drawPath(even, thread.copy(alpha = weave), style = weaveStroke)
                            drawPath(odd, thread.copy(alpha = weave * 0.6f), style = weaveStroke)
                        }
                    }
                    if (tier != PitlaneFrameTier.S && boltPx > 0f) {
                        val boltFill = if (latched) boltPrimary else thread.copy(alpha = 0.50f)
                        val boltStroke = if (latched) boltPrimary else thread.copy(alpha = 0.85f)
                        val hexStroke = Stroke(width = 1.dp.toPx())
                        for (c in pitlaneBoltCenters(size.width, size.height, bandPx)) {
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
                }
            }
        } else if (shouldDrawControlTexture(look, LocalOnPitlanePanel.current)) {
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
            m = m.border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = look.rimAlpha), Color.Transparent)), drawShape)
        }
        // Notscape FrostKit's hairline: a true 0.5dp outline at 0.45 alpha, on top of the
        // rim fade, so the edge reads on any background — not just on dark ones.
        if (look.hairlineAlpha > 0f) {
            m = m.border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = look.hairlineAlpha), drawShape)
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
                        drawShape.createOutline(ringSize, layoutDirection, this),
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
            m = m.border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), drawShape)
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
                    drawShape,
                )
            } else if (look.chamferCut > 0.dp) {
                // r115: the zoned frame's edge — the bevel draws as an
                // outline stroke in a drawBehind (not a border) so the
                // tier, known only at draw time from the size, can thin
                // it: M/L keep the thick bevel, S thins to the standard
                // hairline-only edge (the hairline block above already
                // drew it). Drawn under content, unlike the border it
                // replaces — a 1dp edge stroke with inset content, so
                // the difference is invisible in practice.
                m.drawBehind {
                    val minDimDp = (minOf(size.width, size.height) / density).dp
                    if (pitlaneFrameTier(minDimDp) != PitlaneFrameTier.S) {
                        drawOutline(
                            drawShape.createOutline(size, layoutDirection, this),
                            Brush.verticalGradient(
                                0f to Color.White.copy(alpha = look.bevelTop),
                                0.3f to Color.Transparent,
                                0.7f to Color.Transparent,
                                1f to Color.Black.copy(alpha = look.bevelBottom),
                            ),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                }
            } else {
                m.border(
                    1.dp,
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = look.bevelTop),
                        0.3f to Color.Transparent,
                        0.7f to Color.Transparent,
                        1f to Color.Black.copy(alpha = look.bevelBottom),
                    ),
                    drawShape,
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
                        drawShape.createOutline(insetSize, layoutDirection, this),
                        stitch.copy(alpha = look.stitchAlpha),
                        style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
                    )
                }
            }
        }
        // r113 (Silhouette only): the Contour bottom line — ONE
        // soft light line along the BOTTOM edge only, drawn as
        // part of the button background (under content, like the
        // stitch and ring), never as an overlay widget. A 1dp
        // line in white at bottomLineAlpha, clipped to the shape
        // (the clip above) so it follows the bottom corners;
        // latched, the line takes the scheme primary (amber
        // under Nocturne). The pressed dim at the end of
        // gsSurface overlays the whole face, so a pressed
        // control dims its line with the rest of the face —
        // unlike Sector's trio, which is notched around, the
        // pick WANTS the line dimmed on press. The color is
        // read here, in the composable body — a draw scope
        // cannot call MaterialTheme.
        if (look.bottomLineAlpha > 0f) {
            val line = if (latched) MaterialTheme.colorScheme.primary else Color.White
            m = m.drawBehind {
                val thicknessPx = 1.dp.toPx()
                drawRect(
                    line.copy(alpha = look.bottomLineAlpha),
                    topLeft = Offset(0f, size.height - thicknessPx),
                    size = Size(size.width, thicknessPx),
                )
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
    if (border.isSpecified) m = m.border(BorderStroke(1.dp, border), drawShape)
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
    val look = gsLook()
    // r114 (Morph only): the latched contrast guard — before
    // a latched control reaches gsSurface, check its actual
    // content color against the full-strength washed fill;
    // below 4.5:1 the control takes the tint-only fallback
    // instead (see gsSurface). GsIconBox is the only
    // latched path into gsSurface (GsButton takes no
    // latched flag; direct gsSurface callers never pass
    // latched), so this guard covers every latched surface
    // — including template actions whose colors come from
    // the nav app, which no fixed table could promise.
    // Computed on opaque RGB (see morphContrastRatio).
    val latchedWashFallback = latched && look.washLatchedAlpha > 0f &&
        !morphLatchedWashPasses(c.content, c.container, MaterialTheme.colorScheme.primary, look.washLatchedAlpha)
    val source = remember { MutableInteractionSource() }
    val pressed = rememberPressed(source)
    Box(
        modifier
            .gsPressable(source, pressed, enabled, onClick, onLongClick, shape = shape)
            .gsSurface(c.container, shape, c.border, pressed, latched, well, keepAlpha, latchedWashFallback),
        contentAlignment = Alignment.Center,
    ) {
        if (onLongClick != null && enabled) GsHoldMarker(gsLook().holdMarker, c.container, shape, source)
        CompositionLocalProvider(LocalContentColor provides c.content) { content() }
    }
}
