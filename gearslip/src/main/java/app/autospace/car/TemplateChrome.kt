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
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.core.graphics.drawable.toBitmap
import app.autospace.R
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
fun TemplateChrome(
    template: Template?,
    modifier: Modifier = Modifier,
    /** r78: the connected app's package, for the collapsed pane's logo; null draws the fallback glyph. */
    appPackage: String? = null,
) {
    when (template) {
        null -> Unit
        is NavigationTemplate -> NavigationChrome(template, modifier)
        is MapWithContentTemplate -> MapWithContentChrome(template, modifier, appPackage)
        is MapTemplate -> MapChrome(template, modifier, appPackage)
        is PlaceListNavigationTemplate -> PlaceListNavigationChrome(template, modifier, appPackage)
        is RoutePreviewNavigationTemplate -> RoutePreviewChrome(template, modifier, appPackage)
        is PlaceListMapTemplate -> PlaceListMapChrome(template, modifier, appPackage)
        else -> ContentTemplate(template, modifier)
    }
}

/**
 * How much of the surface Gearslip's chrome covers, so the app can keep what matters clear of
 * it. Expressed as the visible rectangle within a [width] x [height] surface.
 *
 * A template that isn't map-backed covers the surface completely, and reporting an empty
 * rectangle is how the app is told to stop bothering.
 *
 * r97: [coveredStartPx] is the start-side strip the steering-side rail
 * covers when the lent surface extends under it (see CarAppStage) —
 * the visible area is the uncovered remainder, so the app keeps
 * centering in it exactly as when the surface itself was inset.
 * r99: [coveredBottomPx] is the bottom analog — the strip the
 * Meridian bottom pill covers when the lent surface extends under
 * it (see CarAppStage). The visible area ends where the first
 * bottom obstruction begins.
 */
fun visibleAreaFor(template: Template?, width: Int, height: Int, coveredStartPx: Int = 0, coveredBottomPx: Int = 0): Rect {
    val visible = when (template) {
        is NavigationTemplate -> Rect(coveredStartPx, 0, width, (height * (1f - ESTIMATE_FRACTION)).toInt())
        is MapWithContentTemplate, is MapTemplate, is PlaceListNavigationTemplate,
        is RoutePreviewNavigationTemplate, is PlaceListMapTemplate,
        -> Rect(((width * CONTENT_PANE_FRACTION) + (1f - CONTENT_PANE_FRACTION) * coveredStartPx).toInt(), 0, width, height)
        null -> Rect(coveredStartPx, 0, width, height)
        else -> Rect(0, 0, 0, 0)
    }
    // r99: the bottom strip the Meridian pill covers when the lent
    // surface extends under it (see CarAppStage) — the visible area
    // ends where the first bottom obstruction begins, so the app
    // keeps centering in the uncovered remainder. Empty areas stay
    // empty, and the rect can never invert.
    if (coveredBottomPx <= 0 || visible.height() <= 0) return visible
    return Rect(
        visible.left, visible.top, visible.right,
        maxOf(visible.top, minOf(visible.bottom, height - coveredBottomPx)),
    )
}

// --- navigation ------------------------------------------------------------------------------

/**
 * r97 graduation: the single Meridian computation. Every Meridian site
 * in this file — the turn card, the trip pill, the panes, the edge
 * controls — calls this one helper, so they can never disagree about
 * which look they wear. Meridian renders exactly when the regular
 * map-theme choice says Meridian; Themed is the default.
 */
@Composable
private fun meridianChromeNow(): Boolean {
    val mapTheme by CarSettings.mapTheme.collectAsState()
    return mapTheme == MapChrome.MERIDIAN
}

@Composable
private fun NavigationChrome(template: NavigationTemplate, modifier: Modifier) {
    val pan by CarServices.nav.panMode.collectAsState()
    val meridian = meridianChromeNow()
    Box(modifier.fillMaxWidth()) {
        if (!pan) (template.navigationInfo as? RoutingInfo)?.let {
            RoutingCard(it, Modifier.align(Alignment.TopStart).padding(12.dp).width(320.dp))
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalMeridianChrome provides meridian) {
            RightEdgeControls(
                if (pan) null else template.actionStrip,
                template.mapActionStrip,
                Modifier.align(Alignment.TopEnd).padding(12.dp),
            )
        }
        if (!pan) template.destinationTravelEstimate?.let {
            TravelEstimateBar(it, Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
    }
}

/**
 * r61: the guidance cards' surface. In Meridian mode the card is a
 * solid, fixed-color surface with fixed rounded corners, whatever the style
 * or scheme says; in Themed mode it is exactly today's frosted scheme
 * surface, with the call site's shape untouched.
 */
@Composable
private fun GuidanceSurface(
    modifier: Modifier,
    meridianColor: Color,
    meridian: Boolean,
    themedShape: Shape,
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    if (meridian) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            color = meridianColor,
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
    val meridian = meridianChromeNow()
    if (info.isLoading) {
        // r54 (D2): outer pane shape through the style rule, so the frosted
        // toggle cannot change corners; inner 0dp fills below stay untouched.
        GuidanceSurface(
            modifier,
            meridianColor = Color(MapChromeColors.MERIDIAN_CARD),
            meridian = meridian,
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
        meridianColor = Color(MapChromeColors.MERIDIAN_CARD),
        meridian = meridian,
        themedShape = cardShapeForLook(gsLook(), RoundedCornerShape(16.dp)),
    ) {
        Column {
            // r61: Meridian's top strip replaces the "then" row below: the
            // current maneuver glyph solid, the next one muted, and the
            // next step's short cue in small text. Themed mode keeps the
            // "then" row exactly as it was.
            if (meridian && next != null) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Color(MapChromeColors.MERIDIAN_STRIP))
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
                // r63: Meridian stacks Google-exact — distance directly over
                // the street name beside the arrow, no maneuver-wording
                // line. Themed keeps exactly today's rows (distance, cue,
                // road) in the else branch below.
                if (meridian) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CarGlyph(step.maneuver?.icon, Modifier.size(44.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            val distance = info.currentDistance.display()
                            if (distance.isNotEmpty()) {
                                Text(distance, style = ChromeType.headline)
                            }
                            val road = step.road.text()
                            if (road.isNotEmpty()) {
                                Text(
                                    road,
                                    style = ChromeType.title,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } else {
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Neither is sent by every app - most say only "turn left" and trust the driver to
                // find the lane themselves - so both are drawn only when there is something to draw.
                info.junctionImage?.let { junction ->
                    Spacer(Modifier.height(10.dp))
                    JunctionImage(junction)
                }
                LaneGuidance(step, Modifier.padding(top = 10.dp), meridian)
                if (!meridian) {
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
private fun LaneGuidance(step: Step, modifier: Modifier = Modifier, meridian: Boolean = false) {
    step.lanesImage?.let {
        JunctionImage(it, modifier)
        return
    }
    val lanes = step.lanes.orEmpty()
    if (lanes.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        lanes.forEach { lane -> LaneTile(lane, meridian) }
    }
}

@Composable
private fun LaneTile(lane: Lane, meridian: Boolean = false) {
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
                if (meridian) {
                    if (recommended) Color.White else Color(MapChromeColors.MERIDIAN_STRIP)
                } else if (recommended) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowUp,
            contentDescription = if (recommended) "Recommended lane" else "Lane",
            tint = if (meridian) {
                if (recommended) Color(MapChromeColors.MERIDIAN_CARD) else Color.White
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
    val meridian = meridianChromeNow()
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
    // r63: Meridian swaps the frosted scheme surface for the solid dark pill.
    GuidanceSurface(
        modifier,
        meridianColor = Color(MapChromeColors.MERIDIAN_ETA),
        meridian = meridian,
        themedShape = cardShapeForLook(gsLook(), RoundedCornerShape(16.dp)),
        shadowElevation = 6.dp,
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
                colors = if (meridian) GsColors(Color(MapChromeColors.MERIDIAN_STOP_BACKGROUND), Color(MapChromeColors.MERIDIAN_STOP_CONTENT)) else GsColors(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface),
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
                        color = if (meridian) Color(MapChromeColors.MERIDIAN_MINUTES) else estimate.remainingTimeColor.color(dark, MaterialTheme.colorScheme.onSurface),
                    )
                }
                if (detail.isNotEmpty()) {
                    Text(detail, style = ChromeType.body, color = if (meridian) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
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
    /** r78: the connected app's package, for the collapsed pane's logo. */
    appPackage: String? = null,
    pane: @Composable () -> Unit,
) {
    // Pan mode hands the whole screen to the map, leaving only its own controls.
    val pan by CarServices.nav.panMode.collectAsState()
    val meridian = meridianChromeNow()
    val edgeActions = (actionStrip?.actions.orEmpty() + mapActionStrip?.actions.orEmpty()).map { it.identity() }.toSet()
    // r78: pane-collapse session state (r97: always on). The collapse lives in
    // a remember keyed on the connected app's package: it survives
    // pan excursions (this layout stays composed; pan only hides
    // the pane content) and content refreshes of the same pane, and
    // resets when the app changes, when the pane leaves composition
    // (template change, app stop), or on restart — session-only,
    // never persisted. The five call sites are five distinct
    // composition locations, so the site needs no key of its own.
    var collapsed by remember(appPackage) { mutableStateOf(false) }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        if (!pan) {
            if (collapsed) {
                // Collapsed: the pane is just its app's logo, tappable
                // to re-expand. Same surface treatment as the pane
                // (frosted/Meridian, 6dp) at the pane's own corner, so
                // it reads as the pane folded, not a new control. The
                // edge controls below are outside this branch and
                // stay drawn — collapsing never hides template chrome.
                ChromeSurface(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .clickable(onClickLabel = "Expand pane") { collapsed = false },
                    shape = cardShapeForLook(gsLook(), androidx.compose.foundation.shape.RoundedCornerShape(20.dp)),
                    shadowElevation = 6.dp,
                    frosted = true,
                    meridian = meridian,
                ) {
                    Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                        PaneCollapseLogo(appPackage)
                    }
                }
            } else {
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
                    shadowElevation = 6.dp,
                    frosted = true,
                    meridian = meridian,
                ) {
                    // Collapse affordance: one row above the app's
                    // content, right-aligned, so it never covers a
                    // header action. r97 graduation: it always renders.
                    // In Meridian the button wears the pane-header
                    // treatment (strip-green disc, white glyph).
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(end = 4.dp, top = 4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            GsIconButton(
                                Icons.Filled.KeyboardArrowUp,
                                "Collapse pane",
                                { collapsed = true },
                                colors = if (meridian) GsColors(
                                    Color(MapChromeColors.MERIDIAN_STRIP),
                                    Color.White,
                                ) else gsColors(GsTone.Neutral),
                            )
                        }
                        androidx.compose.runtime.CompositionLocalProvider(LocalMapEdgeActions provides edgeActions) { pane() }
                    }
                }
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalMeridianChrome provides meridian) {
            RightEdgeControls(
                if (pan) null else actionStrip,
                mapActionStrip,
                Modifier.align(Alignment.TopEnd).padding(12.dp),
            )
        }
    }
}

/**
 * r78: the collapsed pane's logo — the connected app's own launcher
 * icon, the same source AppIconBadge uses (and the icon NavHomeButton
 * wears while a map runs). A package with no loadable icon falls
 * back to the navigation glyph, so the collapsed button is never
 * blank.
 */
@Composable
private fun PaneCollapseLogo(appPackage: String?) {
    val context = LocalContext.current
    val icon = remember(appPackage) {
        appPackage?.let {
            runCatching {
                context.packageManager.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }
    if (icon != null) {
        Image(icon, null, Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
    } else {
        Icon(
            ImageVector.vectorResource(R.drawable.navigation_24),
            null,
            Modifier.size(32.dp),
        )
    }
}

@Composable
private fun MapWithContentChrome(template: MapWithContentTemplate, modifier: Modifier, appPackage: String? = null) {
    MapPaneLayout(
        modifier,
        template.actionStrip,
        template.mapController?.mapActionStrip,
        fullHeight = template.contentTemplate.needsFullHeight(),
        appPackage = appPackage,
    ) {
        // A pane floating over the map is a place or a route the app is proposing, so its one
        // action - Start - counts itself down rather than waiting to be pressed; the same pane
        // filling a whole screen elsewhere gets no such push.
        ContentTemplate(template.contentTemplate, Modifier, autoStartPane = true)
    }
}

@Composable
private fun MapChrome(template: MapTemplate, modifier: Modifier, appPackage: String? = null) {
    MapPaneLayout(
        modifier,
        template.actionStrip,
        template.mapController?.mapActionStrip,
        appPackage = appPackage,
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
private fun PlaceListNavigationChrome(template: PlaceListNavigationTemplate, modifier: Modifier, appPackage: String? = null) {
    MapPaneLayout(modifier, template.actionStrip, template.mapActionStrip, appPackage = appPackage) {
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
private fun PlaceListMapChrome(template: PlaceListMapTemplate, modifier: Modifier, appPackage: String? = null) {
    MapPaneLayout(modifier, template.actionStrip, null, appPackage = appPackage) {
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
private fun RoutePreviewChrome(template: RoutePreviewNavigationTemplate, modifier: Modifier, appPackage: String? = null) {
    MapPaneLayout(modifier, template.actionStrip, template.mapActionStrip, appPackage = appPackage) {
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
