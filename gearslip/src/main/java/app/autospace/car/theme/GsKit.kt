package app.autospace.car.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

    /** The floating-bar gradient: near-solid, lighter at the top. */
    @Composable
    fun brush(): Brush = Brush.verticalGradient(
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

/** A grouped card in the kit's card material and radius. */
@Composable
fun GsGroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = GsKit.CardShape,
        colors = CardDefaults.cardColors(containerColor = GsKit.cardColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

/**
 * A panel card in the kit's panel material: the tinted container colour at
 * the card radius, for cards sitting directly on the plain page background.
 */
@Composable
fun GsPanelCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = GsKit.CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

/** A bottom sheet in the kit's sheet material and shape. */
@Composable
fun GsSheetSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        color = GsKit.sheetColor(),
        shape = GsKit.SheetShape,
        content = { Column { content() } }
    )
}

/** A section header in the grouped-kit language: bold, on-surface, above its card. */
@Composable
fun GsSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(bottom = 4.dp)
    )
}
