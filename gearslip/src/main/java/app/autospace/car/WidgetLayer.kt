package app.autospace.car

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import app.autospace.car.theme.GsIconButton
import kotlin.math.roundToInt
import app.autospace.car.theme.GsKit
import app.autospace.car.theme.FrostedChromeAlpha
import app.autospace.car.theme.LocalOledDark
import app.autospace.car.theme.cardShapeForLook
import app.autospace.car.theme.gsLook
import app.autospace.car.theme.pitLanePanelBorder
import app.autospace.car.theme.pitLanePanelTexture
import app.autospace.notify.CarNotification
import app.autospace.notify.CarNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Widget layer (r37): v1 = exactly three widgets, fixed, no picker, behind
 * one master experimental switch (default OFF). They attach inside CarHome's
 * Box as ordinary Compose surfaces in the window layer above the map
 * SurfaceView (HomeFrameOverlay + floating-card mechanism) — no
 * map-pipeline, VirtualDisplay, or CarAppStage changes, so they survive
 * CarAppStage's deliberate SurfaceView recreation as independent nodes.
 *
 * Base rule (spec §1, one word reverses it): the column renders ONLY with
 * the floating Home's full-bleed map base (floating media ON). In classic
 * split mode the map is a pane, not a background, and a column over it
 * would eat the map the split exists to show — so classic mode hides the
 * column and stays byte-for-byte. The settings row states this plainly.
 *
 * While moving, widgets are glance only: taps open screens (Weather,
 * dashboard), never a communication or navigation action directly.
 *
 * r58 widget controls (experimental): per-widget hide toggles (B10),
 * nav-bar popup button (same dismissal flag as the X, never overrides
 * the r39 template gate), hide-while-driving with glance recall, and an
 * empty-state explainer when the gates pass but nothing renders.
 *
 * r59 restack (experimental): the r39 template gate is gone — the column
 * stays up during navigation, moving to the passenger side when the
 * template's own chrome occupies the driver side, and hiding only for
 * full-screen content templates, where no corner coexists (see
 * [widgetColumnPlacement]). The popup button's press now takes effect
 * at once even during a template; the driving rule is unchanged.
 */
enum class WidgetKind { WEATHER, DIGEST, EVENT }

/**
 * r59 restack: where the widget column sits while a nav template is up,
 * and whether it shows at all. Pure so it is unit-testable.
 *
 * The r39 occupancy analysis was right; only its response was wrong.
 * Hiding the column during guidance contradicted the stack-over-the-map
 * model, so restack MOVES instead of hiding: the column takes the corner
 * the template chrome does not occupy.
 *
 * Bare map: driver side, unchanged. A navigation template with no turn
 * card showing (no RoutingInfo, or a RoutingInfo with neither loading nor
 * a current step — the same card condition TemplateChrome draws by) leaves
 * the driver side free, so the column stays. A turn card showing (loading
 * or carrying a step) occupies the driver side, so the column moves to the
 * passenger side, mirroring the stack-over-the-map layout: turn card
 * driver side, widgets passenger side. Pan mode hides the turn card and
 * the map pane, so the driver side is free again even mid-template — the
 * TopEnd controls are what pan keeps, and the column avoids them by
 * staying driver side.
 *
 * The other five map-backed templates (Map, MapWithContent, place lists,
 * route preview) draw their pane on the driver side, so the column moves
 * passenger side there too, and back to the driver side while panning.
 * Full-screen content templates (search, lists, panes) ARE the screen — no
 * corner coexists with them, so the column hides there. That is the one
 * case restack still hides, and it is not a dismissal: no persisted flag
 * is touched, so the column returns on its own when the template clears.
 *
 * Compromise, stated plainly: on the passenger side the column can
 * overlap the template's right-edge controls at the top corner while they
 * are awake (they fade out when idle). The turn card, the map pane, and
 * the travel-estimate bar are never covered; the column's own dismiss
 * button faces the map center, not the corner, so it stays hittable and
 * never sits on a control.
 */
enum class WidgetColumnPlacement { DRIVER_SIDE, PASSENGER_SIDE, HIDDEN }

fun widgetColumnPlacement(template: Template?, panMode: Boolean): WidgetColumnPlacement {
    if (template == null) return WidgetColumnPlacement.DRIVER_SIDE
    if (template is NavigationTemplate) {
        if (panMode) return WidgetColumnPlacement.DRIVER_SIDE
        val info = template.navigationInfo as? RoutingInfo
        return if (info != null && (info.isLoading || info.currentStep != null)) {
            WidgetColumnPlacement.PASSENGER_SIDE
        } else {
            WidgetColumnPlacement.DRIVER_SIDE
        }
    }
    if (!isMapBackedTemplate(template)) return WidgetColumnPlacement.HIDDEN
    return if (panMode) WidgetColumnPlacement.DRIVER_SIDE else WidgetColumnPlacement.PASSENGER_SIDE
}

/**
 * The five non-navigation map-backed templates: their chrome is a pane on
 * the driver side (see MapPaneLayout in TemplateChrome), so the column
 * moves passenger side for them. NavigationTemplate is handled by
 * [widgetColumnPlacement] directly because its driver-side card comes and
 * goes with the routing state.
 */
fun isMapBackedTemplate(template: Template): Boolean = template is MapTemplate ||
    template is MapWithContentTemplate || template is PlaceListNavigationTemplate ||
    template is RoutePreviewNavigationTemplate || template is PlaceListMapTemplate

/**
 * Whether the widget column shows. Pure so it is unit-testable. Every gate
 * matters: the master switch, the floating full-bleed base, the column not
 * dismissed (persisted, not per-session — see [CarSettings]), actually on
 * Home, never while the lyrics column owns the side (lyrics falls back
 * to the classic split, which has no background to overlay), and the
 * placement rule leaving a corner for it (see [widgetColumnPlacement]).
 * A HIDDEN placement hides the column without dismissing it: no persisted
 * flag is touched, so it returns on its own when the template clears.
 */
fun shouldShowWidgetColumn(
    widgetsEnabled: Boolean,
    floatingOn: Boolean,
    dismissed: Boolean,
    onHome: Boolean,
    sidePanel: SidePanel,
    placement: WidgetColumnPlacement,
): Boolean = widgetsEnabled && floatingOn && !dismissed && onHome &&
    sidePanel != SidePanel.LYRICS && placement != WidgetColumnPlacement.HIDDEN

/**
 * Column width: ~30% of the frame, capped at 280dp (proposal §3) and floored
 * at 200dp so content still fits, never wider than the frame minus its
 * 12dp side margins. Rounded to whole dp: a raw 0.30f product is not exact
 * (800dp * 0.30f lands at 240.00002dp, which fails exact equality and sits
 * on a subpixel boundary — the r30 whole-dp lesson, applied here first).
 * Pure so it is unit-testable.
 */
fun widgetColumnWidth(frameWidth: Dp): Dp =
    (frameWidth.value * 0.30f).roundToInt().dp.coerceIn(200.dp, 280.dp)
        .coerceAtMost(frameWidth - 24.dp)

/** Below this frame width the column is narrow-frame territory. */
const val WIDGET_NARROW_WIDTH_DP = 320f

/** Digest needs at least this width; below it, weather alone survives. */
const val WIDGET_DIGEST_MIN_WIDTH_DP = 400f

/** Next-event needs at least this width; it degrades first. */
const val WIDGET_EVENT_MIN_WIDTH_DP = 560f

/**
 * Which widgets render, in fixed column order (weather, digest, event).
 * Pure so it is unit-testable. Degrade order (spec §3): next-event hides
 * first, then digest, weather last. Narrow frames hide the column entirely
 * while moving — EXCEPT parked, where the column shows its full available
 * set (a parked driver can read and tap; nothing here acts while moving).
 */
fun visibleWidgets(
    frameWidthDp: Float,
    moving: Boolean,
    weatherReady: Boolean,
    digestAvailable: Boolean,
    eventAvailable: Boolean,
    hidden: Set<WidgetKind> = emptySet(),
): List<WidgetKind> {
    val all = if (frameWidthDp < WIDGET_NARROW_WIDTH_DP && moving) {
        emptyList()
    } else if (frameWidthDp < WIDGET_NARROW_WIDTH_DP) {
        // Parked narrow frame: the column shows, degrade thresholds skipped.
        listOfNotNull(
            WidgetKind.WEATHER.takeIf { weatherReady },
            WidgetKind.DIGEST.takeIf { digestAvailable },
            WidgetKind.EVENT.takeIf { eventAvailable },
        )
    } else {
        listOfNotNull(
            WidgetKind.WEATHER.takeIf { weatherReady },
            WidgetKind.DIGEST.takeIf { digestAvailable && frameWidthDp >= WIDGET_DIGEST_MIN_WIDTH_DP },
            WidgetKind.EVENT.takeIf { eventAvailable && frameWidthDp >= WIDGET_EVENT_MIN_WIDTH_DP },
        )
    }
    // r58 B10: a widget the driver hid never renders, whatever its source says.
    return all.filter { it !in hidden }
}

/**
 * r58 hide-while-driving: the column hides while moving unless the driver
 * recalled it for a glance with the nav-bar button. Pure so it is
 * unit-testable. The floating media card is NOT covered by this rule — it
 * stays as it is; only the widget column hides.
 */
fun shouldHideWhileDriving(moving: Boolean, glanceRecalled: Boolean): Boolean =
    moving && !glanceRecalled

/**
 * r58 glance recall: in-memory only, never persisted — a glance lasts
 * until motion stops, Home is left, or the column is dismissed, and never
 * survives into the next drive. CarHome clears it; the nav-bar button
 * sets it.
 */
object WidgetDrivingGlance {
    var recalled by mutableStateOf(false)
        private set

    fun recall() { recalled = true }
    fun clear() { recalled = false }
}

/** r58: what one press of the nav-bar widget popup button does. Pure so it is unit-testable. */
enum class WidgetPopupPress { DISMISS, UNDISMISS, GLANCE, UNDISMISS_AND_GLANCE }

/**
 * r58 popup-button decision, r59 restack update. The button toggles the
 * SAME persisted dismissal flag as the column's "Hide widgets" X, and
 * since restack removed the template gate the press takes effect at once
 * even during a template — the column follows the flag immediately.
 * dismissed → undismiss (plus a glance when driving-hidden, so one tap
 * brings the column up even while moving); driving-hidden → glance
 * recall; otherwise → dismiss, exactly like the X.
 */
fun widgetPopupPress(dismissed: Boolean, drivingHidden: Boolean): WidgetPopupPress =
    when {
        dismissed && drivingHidden -> WidgetPopupPress.UNDISMISS_AND_GLANCE
        dismissed -> WidgetPopupPress.UNDISMISS
        drivingHidden -> WidgetPopupPress.GLANCE
        else -> WidgetPopupPress.DISMISS
    }

/**
 * r58 empty-state legibility: why nothing renders, in plain words, one
 * line per cause. Pure so it is unit-testable. Only called when the
 * column's gates pass but no widget is visible, so every line here is a
 * source or width cause — never a gate (a failed gate hides the column
 * entirely, explainer included, by design).
 */
fun widgetEmptyReasons(
    weatherReady: Boolean,
    digestListening: Boolean,
    digestUnread: Int,
    digestHistoryEmpty: Boolean,
    eventAvailable: Boolean,
    hidden: Set<WidgetKind>,
    frameWidthDp: Float,
): List<String> {
    val reasons = mutableListOf<String>()
    if (WidgetKind.WEATHER in hidden) {
        reasons += "Weather hidden"
    } else if (!weatherReady) {
        reasons += "Weather not ready yet"
    }
    if (WidgetKind.DIGEST in hidden) {
        reasons += "Digest hidden"
    } else if (!digestListening) {
        reasons += "Notifications off"
    } else if (digestUnread <= 0) {
        reasons += "No unread notifications"
    } else if (digestHistoryEmpty) {
        reasons += "No notifications to show"
    } else if (frameWidthDp < WIDGET_DIGEST_MIN_WIDTH_DP) {
        reasons += "Digest needs a wider screen"
    }
    if (WidgetKind.EVENT in hidden) {
        reasons += "Next event hidden"
    } else if (!eventAvailable) {
        reasons += "No upcoming events"
    } else if (frameWidthDp < WIDGET_EVENT_MIN_WIDTH_DP) {
        reasons += "Next event needs a wider screen"
    }
    // Defensive: visibleWidgets empty with no cause above means a width
    // corner the reasons missed — never show a blank card.
    if (reasons.isEmpty()) reasons += "Nothing to show right now"
    return reasons
}

/**
 * Whether the digest widget may show: notification access granted
 * ([CarNotifications.listening]), something actually unread, and a latest
 * item to name. Pure so it is unit-testable.
 */
fun digestAvailable(listening: Boolean, unread: Int, historyEmpty: Boolean): Boolean =
    listening && unread > 0 && !historyEmpty

/**
 * The digest's sender line. Latest non-owner chat line sender first (a
 * message thread names its sender); else the notification's title (for a
 * message that is usually the sender's name); else the app label. The
 * notification BODY text is never read here — the digest shows a sender
 * and a count, never message text, by construction (no `text` access).
 */
fun digestSender(notification: CarNotification): String {
    val lineSender = notification.lines.lastOrNull { it.sender != null }?.sender
    return lineSender ?: notification.title.ifBlank { notification.appLabel }
}

/**
 * The widget column: dismiss row + the visible widgets, stacked with 10dp
 * gaps. r58: when the gates pass but no widget is visible, it renders a
 * small explainer card saying why instead of vanishing silently — "on"
 * with all sources empty used to look exactly like broken.
 */
@Composable
internal fun WidgetColumn(
    frameWidthDp: Float,
    moving: Boolean,
    modifier: Modifier = Modifier,
    hidden: Set<WidgetKind> = emptySet(),
    // r59 restack: on the passenger side the dismiss button faces the map
    // center (start edge) instead of the screen corner, so it never sits
    // on the template's right-edge controls.
    dismissAtStart: Boolean = false,
) {
    val navigator = LocalCarNavigator.current
    val context = LocalContext.current
    val weatherState by Weather.state.collectAsState()
    val listening by CarNotifications.listening.collectAsState()
    val unread by CarNotifications.unread.collectAsState()
    val history by CarNotifications.history.collectAsState()
    // Agenda: the dashboard's cache when warm, else one IO load (same
    // provider query as fetchAgenda — the dashboard's own source, not a
    // second calendar read path). Empty means no permission or nothing
    // scheduled; either way the chip hides.
    val agenda by produceState<List<AgendaEvent>?>(cachedAgendaSnapshot()) {
        if (value == null) value = withContext(Dispatchers.IO) { fetchAgenda(context) }
    }
    val weatherReady = weatherState is WeatherState.Ready
    val digestOk = digestAvailable(listening, unread, history.isEmpty())
    val event = agenda?.firstOrNull()
    val visible = visibleWidgets(frameWidthDp, moving, weatherReady, digestOk, event != null, hidden)
    // r58: the gates passed but nothing is visible — say why, instead of
    // vanishing. The dismiss row stays, so the explainer hides exactly
    // like the column (same persisted flag, same settings row back).
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Column-level dismiss, REMEMBERED across drives (persisted pref):
        // re-enable from the widget settings row, never by toggle-cycling.
        Box(
            Modifier.fillMaxWidth(),
            contentAlignment = if (dismissAtStart) Alignment.TopStart else Alignment.TopEnd,
        ) {
            GsIconButton(
                Icons.Filled.Close, "Hide widgets", { CarSettings.setWidgetsDismissed(true) },
                size = 44.dp, iconSize = 24.dp,
            )
        }
        if (visible.isEmpty()) {
            WidgetEmptyCard(
                widgetEmptyReasons(
                    weatherReady, listening, unread, history.isEmpty(), event != null,
                    hidden, frameWidthDp,
                ),
            )
        }
        for (kind in visible) {
            when (kind) {
                WidgetKind.WEATHER -> {
                    val data = (weatherState as WeatherState.Ready).data
                    WidgetCard(onClick = navigator::weather) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            WeatherIcon(data.code, data.isDay, size = 32.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${data.temp}${data.tempUnit}  ·  ${Weather.describe(data.code)}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    data.place, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                WidgetKind.DIGEST -> {
                    val latest = history.firstOrNull()
                    if (latest != null) {
                        WidgetCard(onClick = navigator::dashboard) {
                            Column {
                                Text(
                                    "$unread unread",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    digestSender(latest), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                WidgetKind.EVENT -> {
                    val e = event
                    if (e != null) {
                        WidgetCard(onClick = navigator::dashboard) {
                            Column {
                                Text(
                                    e.title, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    eventWhen(e), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One widget card: the floating card's treatment (translucent fill + panel
 * texture + style corner + scheme tokens + Pitch hairline), so widgets read
 * as part of the same frosted layer. Tappable whole-card, 56dp floor.
 */
@Composable
private fun WidgetCard(onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    // r48: Verve takes its own 24dp corner, same as the floating card.
    val cardShape = cardShapeForLook(gsLook(), GsKit.CardShape)
    Card(
        modifier = Modifier.fillMaxWidth().pitLanePanelBorder(cardShape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = FrostedChromeAlpha),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = if (LocalOledDark.current) BorderStroke(0.5.dp, GsKit.hairline()) else null,
    ) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).pitLanePanelTexture().padding(12.dp),
            contentAlignment = Alignment.CenterStart,
        ) { content() }
    }
}

/**
 * r58 empty-state card: plain words for why no widget renders, same
 * frosted treatment as a widget card, deliberately not tappable — there
 * is nowhere useful for a tap to go.
 */
@Composable
private fun WidgetEmptyCard(reasons: List<String>) {
    WidgetCard(onClick = null) {
        Column {
            Text(
                "No widgets to show",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            for (reason in reasons) {
                Text(
                    reason, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
