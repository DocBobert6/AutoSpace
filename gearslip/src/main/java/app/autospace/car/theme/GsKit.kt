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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

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
 */
val LocalOnPitlanePanel = compositionLocalOf { false }

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
        modifier = modifier.pitLanePanelBorder(cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = GsKit.cardColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (LocalOledDark.current) BorderStroke(0.5.dp, GsKit.hairline()) else null,
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
        modifier = modifier.pitLanePanelBorder(cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (LocalOledDark.current) BorderStroke(0.5.dp, GsKit.hairline()) else null,
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
        modifier = modifier.pitLanePanelBorder(sheetShape),
        color = GsKit.sheetColor(),
        shape = sheetShape,
        border = if (LocalOledDark.current) BorderStroke(0.5.dp, GsKit.hairline()) else null,
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
    Surface(
        modifier = modifier.pitLanePanelBorder(shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = fillAlpha),
        shadowElevation = elevation,
        border = if (LocalOledDark.current) BorderStroke(0.5.dp, GsKit.hairline()) else null,
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
    val grain = if (dark) 0.04f else 0.03f
    val thread = if (dark) Color.White else Color.Black
    val screws = look.rivets
    return this.drawWithCache {
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
            drawPath(even, thread.copy(alpha = weave), style = weaveStroke)
            drawPath(odd, thread.copy(alpha = weave * 0.6f), style = weaveStroke)
            // r32: grain was a 1px stroke while weave is 1dp — on a
            // high-density car display grain rendered ~3x thinner than
            // weave and fell below visibility. Same 1dp stroke now;
            // alphas unchanged.
            drawPath(streaks, thread.copy(alpha = grain), style = Stroke(width = 1.dp.toPx()))
            if (screws && size.width >= 64.dp.toPx() && size.height >= 40.dp.toPx()) {
                val r = 2.5.dp.toPx()
                val inset = 6.dp.toPx()
                val corners = listOf(
                    Offset(inset + r, inset + r),
                    Offset(size.width - inset - r, inset + r),
                    Offset(inset + r, size.height - inset - r),
                    Offset(size.width - inset - r, size.height - inset - r),
                )
                for (c in corners) {
                    drawCircle(Color.Black.copy(alpha = 0.50f), r, c)
                    drawLine(
                        Color.Black.copy(alpha = 0.65f),
                        start = Offset(c.x - r * 0.7f, c.y + r * 0.7f),
                        end = Offset(c.x + r * 0.7f, c.y - r * 0.7f),
                        strokeWidth = 1.dp.toPx(),
                    )
                    drawCircle(Color.White.copy(alpha = 0.30f), 1.dp.toPx(), Offset(c.x - r * 0.35f, c.y - r * 0.35f))
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
