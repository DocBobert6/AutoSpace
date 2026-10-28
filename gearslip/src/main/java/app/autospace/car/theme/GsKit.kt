package app.autospace.car.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.autospace.car.CarSettings

/**
 * AutoSpace's port of Notscape Mariner's FrostKit (Phase 7), car-adapted: the
 * same recipe, the same radii, but every control stays car-sized — nothing here
 * shrinks a touch target to fit a phone kit onto a landscape car screen.
 *
 *  - Floating bars and panels ([frostBar]): shadow, clip, a near-solid vertical
 *    gradient (surface @0.97 -> @0.92, lighter top edge), and a 0.5dp hairline
 *    (outline @0.45), in that order. Plain compositing only — no blur, which
 *    cannot capture video/mirror surfaces and which Robert already rejected in
 *    Notscape 0.0.63. Browser pages stay unthemed; video/mirror surfaces keep
 *    scrims, never blur.
 *  - Sheets ([GsSheetSurface]): SOLID surfaceContainerLow with 28dp corners.
 *    Never an alpha container colour — that breaks Material's content-colour
 *    lookup and produced dark text on dark sheets (Notscape's 0.0.59 lesson).
 *  - Grouped cards ([GsGroupedCard]): surfaceBright, 20dp, sitting on a soft
 *    tinted backdrop (surfaceContainerLow) — the settings-page look, which
 *    also fixes white-on-white in light mode (bright white cards on the
 *    #F2F2F7 tint, instead of tinted cards on a white page).
 *  - Panel cards ([GsPanelCard]): surfaceContainerLow, 20dp, for cards sitting
 *    directly on the plain page background — one step off the backdrop, the
 *    mirror image of a grouped card on a sheet.
 *  - Radii follow the concentric ladder: cards 20, sheets/dialogs 28, pills full.
 */
object GsKit {
    val SheetShape = RoundedCornerShape(28.dp)
    val CardShape = RoundedCornerShape(20.dp)
    val PillShape = RoundedCornerShape(50)
    val DialogShape = RoundedCornerShape(28.dp)

    /**
     * The floating-bar gradient: near-solid, lighter at the top.
     * Phase 10 OLED: the surface IS black there, so a surface-color
     * gradient would be invisible on a black background — OLED frost
     * is instead a white-alpha gradient over black (0.16 -> 0.06 since
     * the R2 true-black refinement, Robert 2026-10-25; was 0.10 -> 0.04),
     * lighter at the top, plus the 0.5dp hairline. Same frost language,
     * visible on black; non-OLED is unchanged. The stronger branch is
     * gated on LocalOledDark, so Regular dark and light never see it;
     * Night provides LocalOledDark too (Night is black), so Night bars
     * use the same stronger gradient.
     */
    @Composable
    fun brush(): Brush = if (LocalOledDark.current) Brush.verticalGradient(
        listOf(
            androidx.compose.ui.graphics.Color.White.copy(alpha = 0.16f),
            androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f)
        )
    ) else Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        )
    )

    @Composable
    fun hairline(): Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

    /** The solid sheet colour — never an alpha colour (see above). */
    @Composable
    fun sheetColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow

    /** The grouped-card colour used on sheets and grouped lists. */
    @Composable
    fun cardColor(): Color = MaterialTheme.colorScheme.surfaceBright

    /** The soft tinted backdrop grouped cards sit on. */
    @Composable
    fun backdropColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow
}

/**
 * The floating frost material as a modifier: shadow, clip, gradient,
 * hairline — in that order, exactly as Notscape's address bar has worn it
 * since 0.0.63. Use for floating bars and panels that are NOT Gs controls
 * (Gs controls get the same cues from their theme's ControlLook).
 */
@Composable
fun Modifier.frostBar(shape: Shape = GsKit.PillShape, elevation: Dp = 6.dp): Modifier =
    this.shadow(elevation, shape)
        .clip(shape)
        .background(GsKit.brush())
        .border(0.5.dp, GsKit.hairline(), shape)

/**
 * True when the current scheme is OLED black (provided by CarUi):
 * Phase 10, every surface token is pure #000000, so a card on its
 * backdrop is invisible unless it draws an edge. Kit cards and sheets
 * then get a 0.5dp hairline outline (outline @0.45, the kit hairline)
 * instead of a grey fill — that outline, plus the white-alpha frost
 * gradient (see brush()), plus text, is what separates panels in
 * OLED mode. Off, cards are exactly as before (no border at all).
 */
val LocalOledDark = compositionLocalOf { false }

/**
 * r32: true while composing inside a panel that draws its own Pitlane
 * material (GsGroupedCard / GsPanelCard / GsSheetSurface under a look with
 * weave or rivets). Controls inside such a panel skip their own weave +
 * grain (see shouldDrawControlTexture) so the material reads once per
 * surface instead of two misaligned grids compounding into a moiré. The
 * provider value is the panel's own texture gate, so under any other style
 * it is false and nothing changes.
 * r115: a zoned control (chamferCut) on a panel is exempt from the skip
 * (see shouldDrawControlTexture) — band-confined weave cannot moiré,
 * because bands are disjoint by construction.
 */
val LocalOnPitlanePanel = compositionLocalOf { false }

/**
 * r110 (Blueprint only): the card/sheet ring — the same crisp 1px
 * onSurfaceVariant ring gsSurface draws on controls, applied to kit
 * card/sheet surfaces ([GsGroupedCard], [GsPanelCard],
 * [GsSheetSurface], [FrostedPanelSurface]) and the Meridian pill, so
 * cards get the ring instead of fill contrast. No-op under every
 * other style. Drawn with drawWithContent AFTER the surface fill:
 * a Card/Surface draws its own background, so a drawBehind ring
 * would hide under it. The 1px stroke sits inset 0.5px inside the
 * edge, following the surface's own shape, so it never clips
 * outside rounded corners and never touches inner content, which
 * sits padded inside every card. Under OLED the kit's forced
 * hairline border is skipped wherever this ring draws (see the
 * call sites) — the ring IS the edge there, and the hairline
 * would double it.
 */
@Composable
fun Modifier.blueprintCardRing(shape: Shape): Modifier {
    val look = gsLook()
    if (look.ringAlpha <= 0f) return this
    val ring = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = look.ringAlpha)
    return this.drawWithContent {
        drawContent()
        val insetPx = 0.5f
        val ringSize = Size(
            (size.width - 1f).coerceAtLeast(0f),
            (size.height - 1f).coerceAtLeast(0f),
        )
        translate(insetPx, insetPx) {
            drawOutline(
                shape.createOutline(ringSize, layoutDirection, this),
                ring,
                style = Stroke(width = 1f),
            )
        }
    }
}

/**
 * r113 (Silhouette only): the card/sheet halo — the FULL
 * faint halo (buttons draw half; see gsSurface), so cards
 * and sheets glow slightly more than the buttons on them.
 * Neutral white always, never primary; tight falloff;
 * top + sides only, the bottom edge kept clean (the
 * clipRect cuts everything below the bottom edge, so the
 * glow never reads as a drop shadow). Scales by
 * [haloBrightnessFactor], so the halo fades out at
 * minimum brightness and the card degrades to pure
 * Contour. No-op under every other style. Drawn with
 * drawBehind on the card's own modifier: the Card's fill
 * covers the interior part of the nested fills, and the
 * exterior part shows past the edge — the same reason
 * the soft shadow in gsSurface draws before the clip.
 * Applied at the same four call sites as
 * [blueprintCardRing] ([GsGroupedCard], [GsPanelCard],
 * [GsSheetSurface], [FrostedPanelSurface]); the Meridian
 * pill is a bar surface, not a card, and keeps no halo.
 * Dense lists carry no halo of their own: list rows are
 * not card surfaces, and no halo is added to row
 * containers — the only halo on a list screen is the
 * sheet's own (this tier) and the faintest button tier
 * on its buttons, governed by the acceptance rule (if
 * the halo is noticeable at a glance on a dense screen,
 * it is too strong).
 */
@Composable
fun Modifier.silhouetteCardHalo(shape: Shape): Modifier {
    val look = gsLook()
    if (look.haloAlpha <= 0f || look.haloFalloff <= 0.dp) return this
    val brightness by CarSettings.brightness.collectAsState()
    val haloTotal = look.haloAlpha * haloBrightnessFactor(brightness)
    if (haloTotal <= 0f) return this
    return this.drawBehind {
        val falloffPx = look.haloFalloff.toPx()
        val steps = 4
        clipRect(
            left = -falloffPx,
            top = -falloffPx,
            right = size.width + falloffPx,
            bottom = size.height,
        ) {
            for (i in steps downTo 1) {
                val grow = falloffPx * i / steps
                val outline = shape.createOutline(
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

/**
 * r114 (Morph only): the card/sheet wash — the same Chroma
 * blend at half strength, applied to the card's container
 * color (not an overlay: a Card/Surface draws its own
 * background, so an overlay would tint the text too).
 * No-op under every other style. Alpha preserved, so
 * frosted fills keep their pinned alpha and the art-backed
 * media card (fill alpha 0) stays unwashd.
 */
@Composable
fun morphCardColor(color: Color): Color {
    val look = gsLook()
    if (look.washCardAlpha <= 0f) return color
    return morphWashBlend(color, MaterialTheme.colorScheme.primary, look.washCardAlpha)
}

/** A grouped card in the kit's card material and radius. r29: the card
 * shape follows the r24 button-shape rule — squared under Pit Lane,
 * today's 20dp everywhere else — so dialog/sheet cards go rectangular
 * under Pit Lane without any other style changing. r48: Verve takes
 * its own 24dp corner instead of the 20dp fallback. */
@Composable
fun GsGroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val look = gsLook()
    val cardShape = cardShapeForLook(look, GsKit.CardShape)
    val panelTextured = look.weaveAlpha > 0f || look.rivets
    Card(
        modifier = modifier.pitLanePanelBorder(cardShape).blueprintCardRing(cardShape).silhouetteCardHalo(cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = morphCardColor(GsKit.cardColor())),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (LocalOledDark.current && look.ringAlpha <= 0f) BorderStroke(0.5.dp, GsKit.hairline()) else null,
        content = {
            CompositionLocalProvider(LocalOnPitlanePanel provides panelTextured) {
                Column(Modifier.fillMaxWidth().pitLanePanelTexture()) { content() }
            }
        }
    )
}

/**
 * A panel card in the kit's panel material: the tinted container colour at
 * the card radius, for cards sitting directly on the plain page background.
 * r29: same shape rule as [GsGroupedCard] (squared under Pit Lane only).
 * r48: Verve takes its own 24dp corner, same as the grouped card.
 */
@Composable
fun GsPanelCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val look = gsLook()
    val cardShape = cardShapeForLook(look, GsKit.CardShape)
    val panelTextured = look.weaveAlpha > 0f || look.rivets
    Card(
        modifier = modifier.pitLanePanelBorder(cardShape).blueprintCardRing(cardShape).silhouetteCardHalo(cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = morphCardColor(MaterialTheme.colorScheme.surfaceContainerLow)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (LocalOledDark.current && look.ringAlpha <= 0f) BorderStroke(0.5.dp, GsKit.hairline()) else null,
        content = {
            CompositionLocalProvider(LocalOnPitlanePanel provides panelTextured) {
                Column(Modifier.fillMaxWidth().pitLanePanelTexture()) { content() }
            }
        }
    )
}

/** A bottom sheet in the kit's sheet material and shape. r29: the sheet
 * shape follows the same rule as the cards — squared under Pit Lane,
 * today's 28dp everywhere else. r48: Verve takes its own 24dp corner. */
@Composable
fun GsSheetSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val look = gsLook()
    val sheetShape = cardShapeForLook(look, GsKit.SheetShape)
    val panelTextured = look.weaveAlpha > 0f || look.rivets
    Surface(
        modifier = modifier.pitLanePanelBorder(sheetShape).blueprintCardRing(sheetShape).silhouetteCardHalo(sheetShape),
        color = morphCardColor(GsKit.sheetColor()),
        shape = sheetShape,
        border = if (LocalOledDark.current && look.ringAlpha <= 0f) BorderStroke(0.5.dp, GsKit.hairline()) else null,
        content = {
            CompositionLocalProvider(LocalOnPitlanePanel provides panelTextured) {
                Column(Modifier.fillMaxWidth().pitLanePanelTexture()) { content() }
            }
        }
    )
}

/**
 * r46: the frosted map-chrome treatment, shared by the floating media
 * card and the map chrome so the two cannot drift. Frosted
 * approximation, NOT live blur: live blur over a SurfaceView is
 * impossible (a SurfaceFlinger layer cannot be sampled by an overlay),
 * so the fill is translucent and the map reads through faintly — at
 * exactly the card's level, whatever the style or scheme.
 *
 * The see-through level is PINNED: [FrostedChromeAlpha] (0.88) is the
 * base fill alpha on every style and every scheme. Corners, depth and
 * texture still follow the style (panel corners through [gsButtonShape],
 * Pitlane weave/bevel/rivets only under Pitlane, each surface keeping
 * its elevation role); colors still follow the scheme (the fill is the
 * scheme's surfaceContainerLow, so dark schemes frost dark and light
 * schemes frost light). The Pitch hairline stays Pitch-gated, as on
 * the card.
 *
 * The fill is drawn exactly once per pane: a frosted surface provides
 * [LocalInsideFrostedChrome] to its content, and any ChromeSurface
 * inside it renders as a pass-through ([ChromeSurfaceMode.PASSTHROUGH])
 * instead of stacking a second translucent fill on top (two 0.88 fills
 * would read ~0.986 opaque, not 0.88). [chromeSurfaceMode] is that
 * rule, pure so it is unit-testable.
 */
const val FrostedChromeAlpha = 0.88f

/**
 * r56: nav-bar item fill alpha — the bar's frost level plus a small fixed offset, so
 * buttons, tiles, and other items on the nav bar always read slightly less transparent
 * than the bar beneath them. Derived from [FrostedChromeAlpha], never a second pinned
 * value, so the relationship holds at any future float level; coerced at 1.0 so a
 * future level near solid cannot push an item alpha past opaque. Only the fill goes
 * translucent — glyph and text colors stay full-strength at every call site.
 */
val NavBarItemAlpha: Float = (FrostedChromeAlpha + 0.05f).coerceAtMost(1f)

/** Which surface a ChromeSurface draws: opaque as today, frosted, or nothing but its content. */
enum class ChromeSurfaceMode { OPAQUE, FROSTED, PASSTHROUGH }

/**
 * r46: the pure surface-selection rule behind ChromeSurface, kept
 * free of Composition so it is unit-testable. Inside a frosted pane the
 * fill already exists, so any further surface passes through; a map
 * surface with the toggle on frosts; everything else stays opaque.
 */
fun chromeSurfaceMode(toggleOn: Boolean, frosted: Boolean, insideFrosted: Boolean): ChromeSurfaceMode =
    if (insideFrosted) ChromeSurfaceMode.PASSTHROUGH
    else if (toggleOn && frosted) ChromeSurfaceMode.FROSTED
    else ChromeSurfaceMode.OPAQUE

/** True while composing inside a frosted panel surface (see above). */
val LocalInsideFrostedChrome = compositionLocalOf { false }

/** r46: the frosted fill — the scheme's surfaceContainerLow at the pinned alpha. */
@Composable
fun frostedChromeColor(): Color =
    MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = FrostedChromeAlpha)

/**
 * r46: the one frosted panel surface. The floating media card and the
 * frosted map chrome both read this, with their own shape and elevation
 * — the card keeps its gsButtonShape radius and 4dp elevation, map
 * panels keep their call-site shape (routed through gsButtonShape) and
 * elevation. Pitlane border + texture are no-ops under every other
 * style, and the hairline stays Pitch-gated, exactly as the card has it.
 */
@Composable
fun FrostedPanelSurface(
    shape: Shape,
    elevation: Dp,
    modifier: Modifier = Modifier,
    // r88: the media card passes a lower fill when album art sits behind it so the art
    // reads clearly; every other caller keeps the default, so their fill is unchanged.
    // This sits BEFORE content on purpose: content is the trailing-lambda parameter, and
    // a parameter after it would steal every call site's trailing lambda block.
    fillAlpha: Float = FrostedChromeAlpha,
    content: @Composable () -> Unit,
) {
    val look = gsLook()
    Surface(
        modifier = modifier.pitLanePanelBorder(shape).blueprintCardRing(shape).silhouetteCardHalo(shape),
        shape = shape,
        color = morphCardColor(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = fillAlpha)),
        shadowElevation = elevation,
        border = if (LocalOledDark.current && look.ringAlpha <= 0f) BorderStroke(0.5.dp, GsKit.hairline()) else null,
    ) {
        Box(Modifier.pitLanePanelTexture()) { content() }
    }
}

/**
 * r26: Pit Lane panel treatment, in two halves. [pitLanePanelBorder] is the
 * raised-panel edge: a hard 1dp bevel (light top, dark bottom) on the card /
 * sheet outline, Pit Lane only — other styles get no extra border, so their
 * panels are byte-identical. [pitLanePanelTexture] is the material itself,
 * drawn behind the panel's content (never over text): the same 8dp twill
 * weave as gsSurface but stronger (panels are where the material reads —
 * weave 0.08 dark / 0.06 light, grain 0.04 / 0.03, vs controls' 0.05 / 0.03),
 * plus corner screws (5dp dots inset 6dp, dark circle + diagonal slot +
 * top-left highlight) on panels at least 64dp wide and 40dp tall, so small
 * surfaces never get crowded. Both halves are no-ops for every other style
 * (the look carries no weave and no rivets), and the texture is built once
 * per size (drawWithCache), never per frame. Screws sit on panels only —
 * buttons and tiles never get them, so they never read as scattered
 * decoration. Browser sheets are chrome, not pages: they follow the panel
 * treatment like every other sheet, while WebView page content is untouched.
 * r115 (zoned rework): the panel texture follows the same zones as
 * controls — weave confined to the frame band (same tier table: L 12dp
 * band / 8dp cell / 8dp bolts, M 8dp band / 6dp cell / 6dp bolts, S
 * nothing), corner fasteners as hex bolts (not screw dots), no grain
 * (the fill zone is clean, the frame zone carries weave + bolts only).
 * The old 64×40 screw floor is superseded by the tier rule: a panel
 * under 48dp on its smaller side is S and gets no bolts. The border half
 * is unchanged (see pitLanePanelBorder) — it cannot know the size, so
 * small panels keep the thick bevel edge; panels are L/M in practice.
 */
/**
 * r32: the panel bevel, aligned to the control bevel. r26 set panels weaker
 * (0.30/0.45 dark, 0.25/0.35 light) and the audit found the panel edge
 * indiscernible — panels are the surfaces that must read raised at a
 * glance, so they now carry the same edge as controls (0.50/0.60 dark,
 * 0.45/0.55 light). Pure so it is unit-testable. (top, bottom) alphas.
 */
fun pitlanePanelBevel(dark: Boolean): Pair<Float, Float> =
    if (dark) 0.50f to 0.60f else 0.45f to 0.55f

@Composable
fun Modifier.pitLanePanelBorder(shape: Shape): Modifier {
    val look = gsLook()
    if (look.weaveAlpha <= 0f && !look.rivets) return this
    val dark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val (top, bottom) = pitlanePanelBevel(dark)
    return this.border(
        1.dp,
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = top),
            0.3f to Color.Transparent,
            0.7f to Color.Transparent,
            1f to Color.Black.copy(alpha = bottom),
        ),
        shape,
    )
}

@Composable
fun Modifier.pitLanePanelTexture(): Modifier {
    val look = gsLook()
    if (look.weaveAlpha <= 0f && !look.rivets) return this
    val dark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val weave = if (dark) 0.08f else 0.06f
    val thread = if (dark) Color.White else Color.Black
    return this.drawWithCache {
        // r115: same zones as gsSurface, same tier table. The panel's
        // band is a rectangular ring (the texture Column itself is
        // rectangular; the card's own outer clip trims the corners to
        // the chamfer). Panels are never latched, so bolts are steel.
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
        val bandPath = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addRoundRect(
                RoundRect(
                    Rect(bandPx, bandPx, size.width - bandPx, size.height - bandPx),
                    CornerRadius(0f, 0f),
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
                val boltFill = thread.copy(alpha = 0.50f)
                val boltStroke = thread.copy(alpha = 0.85f)
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
}

/** A section header in the grouped-kit language: bold, on-surface, above its card. */
@Composable
fun GsSectionHeader(text: String, modifier: Modifier = Modifier) {
    // r26: under Pit Lane, headers are engraved panel labels — small, plain,
    // letterspaced, with a subtle inset: a contrasting copy offset 1dp below
    // (light on dark panels, dark on light ones) behind the main text, so the
    // letters read cut into the panel. Headers only; body/button/tile labels
    // are untouched, and every other style keeps the bold titleMedium header.
    if (gsLook().engravedHeader) {
        // The below-copy is the lit lower edge of the cut: white on both
        // backgrounds (stronger on light panels, subtler on dark ones),
        // peeking 1dp below the muted main letters.
        val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        val edge = Color.White.copy(alpha = if (light) 0.45f else 0.28f)
        val style = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.12.em,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.Box(modifier.padding(bottom = 4.dp)) {
            Text(text, style = style.copy(color = edge), modifier = Modifier.padding(top = 1.dp))
            Text(text, style = style)
        }
    } else {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier.padding(bottom = 4.dp)
        )
    }
}
