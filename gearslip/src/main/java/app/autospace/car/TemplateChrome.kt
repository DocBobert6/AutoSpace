package app.autospace.car

import app.autospace.car.theme.*
import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.MapTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import app.autospace.host.clockTime
import app.autospace.host.color
import app.autospace.host.image
import app.autospace.host.display
import app.autospace.host.remainingTime
import app.autospace.host.text

/**
 * Draws the template an app handed us, over the surface it is drawing its map into.
 *
 * A templated app never draws its own chrome: it describes a screen in the Car App Library's
 * model types and every host renders those to suit its own screen. This is Gearslip's
 * rendering. It is deliberately laid out for a wide, short car display - controls to the
 * right where a hand reaches from the driver's seat, information to the left.
 *
 * Templates split into two kinds. The six map-backed ones here leave room for the app's own
 * drawing and lay chrome around it; everything else fills the screen and is drawn by
 * [ContentTemplate].
 */
@Composable
fun TemplateChrome(template: Template?, modifier: Modifier = Modifier) {
    when (template) {
        null -> Unit
        is NavigationTemplate -> NavigationChrome(template, modifier)
        is MapWithContentTemplate -> MapWithContentChrome(template, modifier)
        is MapTemplate -> MapChrome(template, modifier)
        is PlaceListNavigationTemplate -> PlaceListNavigationChrome(template, modifier)
        is RoutePreviewNavigationTemplate -> RoutePreviewChrome(template, modifier)
        is PlaceListMapTemplate -> PlaceListMapChrome(template, modifier)
        else -> ContentTemplate(template, modifier)
    }
}

/**
 * How much of the surface Gearslip's chrome covers, so the app can keep what matters clear of
 * it. Expressed as the visible rectangle within a [width] x [height] surface.
 *
 * A template that isn't map-backed covers the surface completely, and reporting an empty
 * rectangle is how the app is told to stop bothering.
 */
fun visibleAreaFor(template: Template?, width: Int, height: Int): Rect = when (template) {
    is NavigationTemplate -> Rect(0, 0, width, (height * (1f - ESTIMATE_FRACTION)).toInt())
    is MapWithContentTemplate, is MapTemplate, is PlaceListNavigationTemplate,
    is RoutePreviewNavigationTemplate, is PlaceListMapTemplate,
    -> Rect((width * CONTENT_PANE_FRACTION).toInt(), 0, width, height)
    null -> Rect(0, 0, width, height)
    else -> Rect(0, 0, 0, 0)
}

// --- navigation ------------------------------------------------------------------------------

@Composable
private fun NavigationChrome(template: NavigationTemplate, modifier: Modifier) {
    val pan by CarServices.nav.panMode.collectAsState()
    Box(modifier.fillMaxWidth()) {
        if (!pan) (template.navigationInfo as? RoutingInfo)?.let {
            RoutingCard(it, Modifier.align(Alignment.TopStart).padding(12.dp).width(320.dp))
        }
        RightEdgeControls(
            if (pan) null else template.actionStrip,
            template.mapActionStrip,
            Modifier.align(Alignment.TopEnd).padding(12.dp),
        )
        if (!pan) template.destinationTravelEstimate?.let {
            TravelEstimateBar(it, Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
    }
}

/**
 * r61: the guidance cards' surface. In Google-recreation mode the card is a
 * solid, fixed-color surface with fixed rounded corners, whatever the style
 * or scheme says; in Themed mode it is exactly today's frosted scheme
 * surface, with the call site's shape untouched.
 */
@Composable
private fun GuidanceSurface(
    modifier: Modifier,
    googleColor: Color,
    google: Boolean,
    themedShape: Shape,
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    if (google) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            color = googleColor,
            contentColor = Color.White,
            shadowElevation = shadowElevation,
            content = content,
        )
    } else {
        ChromeSurface(
            modifier,
            shape = themedShape,
            shadowElevation = shadowElevation,
            frosted = true,
            content = content,
        )
    }
}

/** The maneuver card: what to do next, how far away it is, and onto what road. */
@Composable
private fun RoutingCard(info: RoutingInfo, modifier: Modifier = Modifier) {
    val mapChrome by CarSettings.mapChrome.collectAsState()
    val google = mapChrome == MapChrome.GOOGLE
    if (info.isLoading) {
        // r54 (D2): outer pane shape through the style rule, so the frosted
        // toggle cannot change corners; inner 0dp fills below stay untouched.
        GuidanceSurface(
            modifier,
            googleColor = Color(MapChromeColors.GOOGLE_CARD),
            google = google,
            themedShape = cardShapeForLook(gsLook(), RoundedCornerShape(16.dp)),
        ) {
            Text("Finding a route…", Modifier.padding(16.dp), style = ChromeType.title)
        }
        return
    }

    val step = info.currentStep ?: return
    val next = info.nextStep
    // r54 (D2): outer pane shape through the style rule, as above.
    GuidanceSurface(
        modifier,
        googleColor = Color(MapChromeColors.GOOGLE_CARD),
        google = google,
        themedShape = cardShapeForLook(gsLook(), RoundedCornerShape(16.dp)),
    ) {
        Column {
            // r61: Google's top strip replaces the "then" row below: the
            // current maneuver glyph solid, the next one muted, and the
            // next step's short cue in small text. Themed mode keeps the
            // "then" row exactly as it was.
            if (google && next != null) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Color(MapChromeColors.GOOGLE_STRIP))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CarGlyph(step.maneuver?.icon, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    CarGlyph(next.maneuver?.icon, Modifier.size(20.dp).alpha(0.6f))
                    nextStripText(next.cue.text(), next.road.text())?.let { cue ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            cue,
                            style = ChromeType.label,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CarGlyph(step.maneuver?.icon, Modifier.size(44.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val distance = info.currentDistance.display()
                        if (distance.isNotEmpty()) {
                            Text(distance, style = ChromeType.headline)
                        }
                        Text(step.cue.text(), style = ChromeType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                val road = step.road.text()
                if (road.isNotEmpty()) {
                    Text(
                        road,
                        style = ChromeType.body,
                        color = if (google) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Neither is sent by every app - most say only "turn left" and trust the driver to
                // find the lane themselves - so both are drawn only when there is something to draw.
                info.junctionImage?.let { junction ->
                    Spacer(Modifier.height(10.dp))
                    JunctionImage(junction)
                }
                LaneGuidance(step, Modifier.padding(top = 10.dp), google)
                if (!google) {
                    info.nextStep?.let { nextStep ->
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("then", style = ChromeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            CarGlyph(nextStep.maneuver?.icon, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(nextStep.cue.text(), style = ChromeType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** The zoomed-in intersection an app draws for a junction too complex for an arrow to explain. */
@Composable
private fun JunctionImage(icon: CarIcon, modifier: Modifier = Modifier) {
    val bitmap = icon.image(LocalContext.current) ?: return
    Image(
        bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.fillMaxWidth().heightIn(max = 120.dp),
    )
}

/**
 * Which lane to be in before the manoeuvre, either as the app's own picture ([Step.getLanesImage])
 * or as a row of arrows built from its structured lane data - whichever it sent. The recommended
 * lane, or lanes, stand out in the theme's own colour; the rest sit on the same muted, solid tile
 * everything else uses, never dimmed by transparency.
 */
@Composable
private fun LaneGuidance(step: Step, modifier: Modifier = Modifier, google: Boolean = false) {
    step.lanesImage?.let {
        JunctionImage(it, modifier)
        return
    }
    val lanes = step.lanes.orEmpty()
    if (lanes.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        lanes.forEach { lane -> LaneTile(lane, google) }
    }
}

@Composable
private fun LaneTile(lane: Lane, google: Boolean = false) {
    val directions = lane.directions.orEmpty()
    val shown = directions.firstOrNull { it.isRecommended } ?: directions.firstOrNull()
    val recommended = shown?.isRecommended == true
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                // r61: on the green card the recommended lane stands out in white and the
                // rest sit on the darker strip green, so no scheme color fights the card.
                if (google) {
                    if (recommended) Color.White else Color(MapChromeColors.GOOGLE_STRIP)
                } else if (recommended) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowUp,
            contentDescription = if (recommended) "Recommended lane" else "Lane",
            tint = if (google) {
                if (recommended) Color(MapChromeColors.GOOGLE_CARD) else Color.White
            } else if (recommended) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp).rotate(shown.turnDegrees()),
        )
    }
}

/** The one arrow glyph this app draws, turned to stand in for every shape a lane can permit. */
private fun LaneDirection?.turnDegrees(): Float = when (this?.shape) {
    LaneDirection.SHAPE_SLIGHT_RIGHT -> 30f
    LaneDirection.SHAPE_NORMAL_RIGHT -> 90f
    LaneDirection.SHAPE_SHARP_RIGHT -> 135f
    LaneDirection.SHAPE_U_TURN_RIGHT -> 170f
    LaneDirection.SHAPE_SLIGHT_LEFT -> -30f
    LaneDirection.SHAPE_NORMAL_LEFT -> -90f
    LaneDirection.SHAPE_SHARP_LEFT -> -135f
    LaneDirection.SHAPE_U_TURN_LEFT -> -170f
    else -> 0f
}

/**
 * The trip at a glance: time and distance left, the clock time it ends at, and - beside them,
 * where a thumb reaches - the app's own way to stop navigating. A floating card rather than a
 * full-width bar, so it reads as one thing beside the manoeuvre card above it instead of a strip
 * that happens to hold three numbers. The close button is Gearslip's own, not the app's: an
 * actionStrip's actions carry no flag saying which one (if any) means "stop navigating" - the
 * first button here turned out to be a mute toggle, not an exit - so this leaves the app the way
 * going Home already does, rather than guess at the app's own controls and risk getting it wrong.
 */
@Composable
private fun TravelEstimateBar(estimate: TravelEstimate, modifier: Modifier = Modifier) {
    val mapChrome by CarSettings.mapChrome.collectAsState()
    val google = mapChrome == MapChrome.GOOGLE
    val time = remainingTime(estimate.remainingTimeSeconds)
    val distance = estimate.remainingDistance.display()
    val arrival = estimate.arrivalTimeAtDestination.clockTime()
    val trip = estimate.tripText.text()
    if (time.isEmpty() && distance.isEmpty() && arrival.isEmpty() && trip.isEmpty()) return

    val detail = listOfNotNull(
        arrival.takeIf { it.isNotEmpty() },
        distance.takeIf { it.isNotEmpty() },
        trip.takeIf { it.isNotEmpty() },
    ).joinToString("  •  ")

    // The same solid card the manoeuvre card above it uses - one surface for the whole map
    // chrome, rather than this one spot styled on its own as a bar over the map.
    // r54 (D2): outer pane shape through the style rule, as above.
    // r61: Google mode swaps the frosted scheme surface for the solid dark pill.
    GuidanceSurface(
        modifier,
        googleColor = Color(MapChromeColors.GOOGLE_ETA),
        google = google,
        themedShape = cardShapeForLook(gsLook(), RoundedCornerShape(16.dp)),
        shadowElevation = 8.dp,
    ) {
        val dark = MaterialTheme.colorScheme.surface.luminanceIsDark()
        Row(
            Modifier.padding(start = 8.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Not a disconnect: the connection stays up and the app decides its own next screen.
            // Ending navigation is its own call, separate from a back press - a back press only
            // pops the on-screen card, leaving the app still believing it is navigating (so a
            // freshly picked destination silently does nothing); this is the call that actually
            // clears that flag on the app's side.
            GsIconButton(
                Icons.Filled.Close,
                "Stop navigating",
                { CarServices.nav.stopNavigating() },
                colors = if (google) GsColors(Color(MapChromeColors.GOOGLE_STOP_BACKGROUND), Color(MapChromeColors.GOOGLE_STOP_CONTENT)) else GsColors(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface),
                size = 40.dp,
                iconSize = 20.dp,
                shape = CircleShape,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                if (time.isNotEmpty()) {
                    Text(
                        time,
                        style = ChromeType.headline,
                        color = if (google) Color(MapChromeColors.GOOGLE_MINUTES) else estimate.remainingTimeColor.color(dark, MaterialTheme.colorScheme.onSurface),
                    )
                }
                if (detail.isNotEmpty()) {
                    Text(detail, style = ChromeType.body, color = if (google) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// --- map with a side pane ----------------------------------------------------------------------

/**
 * The shape every remaining map-backed template shares: the app's map across the whole surface,
 * the template's content in a card floating over its left side, and both action strips stacked at
 * the right edge. The card is as tall as its content, up to the height of the screen, so a
 * handful of buttons no longer claim a whole column of the map. [fullHeight] is for content that
 * needs the room, such as a search field with its keyboard.
 */
@Composable
private fun MapPaneLayout(
    modifier: Modifier,
    actionStrip: androidx.car.app.model.ActionStrip?,
    mapActionStrip: androidx.car.app.model.ActionStrip?,
    fullHeight: Boolean = false,
    pane: @Composable () -> Unit,
) {
    // Pan mode hands the whole screen to the map, leaving only its own controls.
    val pan by CarServices.nav.panMode.collectAsState()
    val edgeActions = (actionStrip?.actions.orEmpty() + mapActionStrip?.actions.orEmpty()).map { it.identity() }.toSet()
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        if (!pan) {
            ChromeSurface(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .width(maxOf(maxWidth * CONTENT_PANE_FRACTION - 12.dp, 232.dp))
                    .let { if (fullHeight) it.height(maxHeight - 24.dp) else it.heightIn(max = maxHeight - 24.dp) },
                // r54 (D2): the outer pane shape routes through the style rule,
                // so Pitlane squares it (6dp) in BOTH branches; the frosted branch
                // re-routes idempotently, and the inner 0dp fills below stay 0dp
                // flush in opaque mode and passthrough when frosted.
                shape = cardShapeForLook(gsLook(), androidx.compose.foundation.shape.RoundedCornerShape(20.dp)),
                shadowElevation = 8.dp,
                frosted = true,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(LocalMapEdgeActions provides edgeActions) { pane() }
            }
        }
        RightEdgeControls(
            if (pan) null else actionStrip,
            mapActionStrip,
            Modifier.align(Alignment.TopEnd).padding(12.dp),
        )
    }
}

@Composable
private fun MapWithContentChrome(template: MapWithContentTemplate, modifier: Modifier) {
    MapPaneLayout(
        modifier,
        template.actionStrip,
        template.mapController?.mapActionStrip,
        fullHeight = template.contentTemplate.needsFullHeight(),
    ) {
        // A pane floating over the map is a place or a route the app is proposing, so its one
        // action - Start - counts itself down rather than waiting to be pressed; the same pane
        // filling a whole screen elsewhere gets no such push.
        ContentTemplate(template.contentTemplate, Modifier, autoStartPane = true)
    }
}

@Composable
private fun MapChrome(template: MapTemplate, modifier: Modifier) {
    MapPaneLayout(
        modifier,
        template.actionStrip,
        template.mapController?.mapActionStrip,
    ) {
        ChromeSurface(Modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp), frosted = true) {
            Column {
                HeaderBar(
                    headerTitle(template.header, ""),
                    template.header?.startHeaderAction,
                    template.header?.endHeaderActions.orEmpty(),
                )
                if (template.pane != null) PaneRows(template.pane, autoStart = true)
                else ItemListColumn(template.itemList)
            }
        }
    }
}

@Composable
private fun PlaceListNavigationChrome(template: PlaceListNavigationTemplate, modifier: Modifier) {
    MapPaneLayout(modifier, template.actionStrip, template.mapActionStrip) {
        ListPane(
            headerTitle(template.header, template.title.text()),
            template.header?.startHeaderAction ?: template.headerAction,
            template.isLoading,
            template.itemList,
            Modifier,
        )
    }
}

@Composable
private fun PlaceListMapChrome(template: PlaceListMapTemplate, modifier: Modifier) {
    MapPaneLayout(modifier, template.actionStrip, null) {
        ListPane(
            template.title.text(),
            template.headerAction,
            template.isLoading,
            template.itemList,
            Modifier,
        )
    }
}

/** Route choices down the side, with the app's "start" action pinned under them. */
@Composable
private fun RoutePreviewChrome(template: RoutePreviewNavigationTemplate, modifier: Modifier) {
    MapPaneLayout(modifier, template.actionStrip, template.mapActionStrip) {
        ChromeSurface(Modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp), frosted = true) {
            Column {
                HeaderBar(
                    headerTitle(template.header, template.title.text()),
                    template.header?.startHeaderAction ?: template.headerAction,
                    template.header?.endHeaderActions.orEmpty(),
                )
                Box(Modifier.weight(1f, fill = false)) {
                    if (template.isLoading) {
                        Text("Finding routes…", Modifier.padding(14.dp), style = ChromeType.title)
                    } else {
                        ItemListColumn(template.itemList)
                    }
                }
                template.navigateAction?.let {
                    AutoStartButton(it, Modifier.padding(12.dp))
                }
            }
        }
    }
}

@Composable
private fun ListPane(
    title: String,
    startAction: androidx.car.app.model.Action?,
    loading: Boolean,
    itemList: androidx.car.app.model.ItemList?,
    modifier: Modifier,
) {
    ChromeSurface(modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp), frosted = true) {
        Column {
            HeaderBar(title, startAction)
            if (loading) Text("Loading…", Modifier.padding(14.dp), style = ChromeType.title)
            else ItemListColumn(itemList)
        }
    }
}

/** Fraction of the surface the content pane covers beside a map. */
private const val CONTENT_PANE_FRACTION = 0.42f

/** Fraction of the surface the travel-estimate bar covers along the bottom. */
private const val ESTIMATE_FRACTION = 0.14f

/** Search, sign-in and the like are laid out for a whole column, so they keep one. */
private fun Template?.needsFullHeight() =
    this is androidx.car.app.model.SearchTemplate || this is androidx.car.app.model.signin.SignInTemplate ||
        this is androidx.car.app.model.TabTemplate || this is androidx.car.app.model.LongMessageTemplate
