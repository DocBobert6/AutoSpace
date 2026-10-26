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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import app.autospace.car.theme.GsIconButton
import kotlin.math.roundToInt
import app.autospace.car.theme.GsKit
import app.autospace.car.theme.LocalOledDark
import app.autospace.car.theme.gsButtonShape
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
 */
enum class WidgetKind { WEATHER, DIGEST, EVENT }

/**
 * Whether the widget column shows. Pure so it is unit-testable. Every gate
 * matters: the master switch, the floating full-bleed base, the column not
 * dismissed (persisted, not per-session — see [CarSettings]), actually on
 * Home, never while the lyrics column owns the side (lyrics falls back
 * to the classic split, which has no background to overlay), and the nav
 * app's current template leaving the column's corner free (see
 * [navTemplateAllowsWidgets]).
 */
fun shouldShowWidgetColumn(
    widgetsEnabled: Boolean,
    floatingOn: Boolean,
    dismissed: Boolean,
    onHome: Boolean,
    sidePanel: SidePanel,
    navTemplateAllowsWidgets: Boolean,
): Boolean = widgetsEnabled && floatingOn && !dismissed && onHome &&
    sidePanel != SidePanel.LYRICS && navTemplateAllowsWidgets

/**
 * r39 widget/nav-panel gate: whether the nav app's current template leaves
 * the driver-side column's corner free. Pure so it is unit-testable.
 *
 * Why (r37 screenshot): Vela's search/categories panel is host-rendered
 * TemplateChrome, and MapPaneLayout always draws its left-side container
 * (TopStart, 12dp padding, 42% of the width) for any map-backed template
 * when not panning — the widget column (TopStart, same 12dp padding, 30%
 * width) sits inside that footprint. NavigationChrome's turn card is
 * TopStart too, so the same collision applies during guidance. This
 * predicate mirrors TemplateChrome's own card condition exactly: the card
 * shows iff not panning and the navigation info is a RoutingInfo that is
 * loading or carries a current step.
 *
 * Show only when: no template (bare map), or a NavigationTemplate with no
 * turn card showing — its navigation info is not a RoutingInfo, the
 * RoutingInfo is neither loading nor carrying a current step, or pan mode
 * is on (pan hides the card, and MapPaneLayout hides its container too).
 * A non-loading RoutingInfo without a step cannot be built via its Builder
 * (build requires a step and distance unless loading), so that arm is
 * defensive only — a real app cannot send it either. A
 * destinationTravelEstimate alone never hides the column: its bar is
 * BottomStart and does not collide, and this predicate never reads it.
 *
 * Hide for every other non-null template: any MapTemplate (the container
 * always renders, pane or list or neither), MapWithContent, place lists,
 * route preview, search, list, pane, and any other full-screen content
 * template.
 *
 * Hiding here is NOT a dismissal: it touches no persisted flag, so the
 * column returns on its own when the template clears.
 */
fun navTemplateAllowsWidgets(template: Template?, panMode: Boolean): Boolean {
    if (template == null) return true
    if (template is NavigationTemplate) {
        if (panMode) return true
        val info = template.navigationInfo as? RoutingInfo ?: return true
        return !info.isLoading && info.currentStep == null
    }
    return false
}

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
): List<WidgetKind> {
    if (frameWidthDp < WIDGET_NARROW_WIDTH_DP && moving) return emptyList()
    if (frameWidthDp < WIDGET_NARROW_WIDTH_DP) {
        // Parked narrow frame: the column shows, degrade thresholds skipped.
        return listOfNotNull(
            WidgetKind.WEATHER.takeIf { weatherReady },
            WidgetKind.DIGEST.takeIf { digestAvailable },
            WidgetKind.EVENT.takeIf { eventAvailable },
        )
    }
    return listOfNotNull(
        WidgetKind.WEATHER.takeIf { weatherReady },
        WidgetKind.DIGEST.takeIf { digestAvailable && frameWidthDp >= WIDGET_DIGEST_MIN_WIDTH_DP },
        WidgetKind.EVENT.takeIf { eventAvailable && frameWidthDp >= WIDGET_EVENT_MIN_WIDTH_DP },
    )
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
 * gaps. Renders nothing at all when no widget is visible, so an empty
 * column (and its dismiss button) never sits over the map.
 */
@Composable
internal fun WidgetColumn(
    frameWidthDp: Float,
    moving: Boolean,
    modifier: Modifier = Modifier,
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
    val visible = visibleWidgets(frameWidthDp, moving, weatherReady, digestOk, event != null)
    if (visible.isEmpty()) return

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Column-level dismiss, REMEMBERED across drives (persisted pref):
        // re-enable from the widget settings row, never by toggle-cycling.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
            GsIconButton(
                Icons.Filled.Close, "Hide widgets", { CarSettings.setWidgetsDismissed(true) },
                size = 44.dp, iconSize = 24.dp,
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
private fun WidgetCard(onClick: () -> Unit, content: @Composable () -> Unit) {
    val cardShape = gsButtonShape(GsKit.CardShape)
    Card(
        modifier = Modifier.fillMaxWidth().pitLanePanelBorder(cardShape).clickable { onClick() },
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        border = if (LocalOledDark.current) BorderStroke(0.5.dp, GsKit.hairline()) else null,
    ) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).pitLanePanelTexture().padding(12.dp),
            contentAlignment = Alignment.CenterStart,
        ) { content() }
    }
}
