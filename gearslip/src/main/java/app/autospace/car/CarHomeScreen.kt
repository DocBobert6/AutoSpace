package app.autospace.car

import app.autospace.car.theme.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import app.autospace.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.autospace.media.MediaArt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.autospace.host.CarAppConnection
import app.autospace.media.CarMedia
import app.autospace.media.NowPlaying
import app.autospace.notify.CarMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The home screen: the map on the left, the docked player on the right when asked for.
 *
 * The map is whatever navigation app was used last, brought back on its own. The player sits in
 * the nav bar's pill until its dock button moves it here; the column closes itself back to a
 * full-width map once nothing is playing.
 */
@Composable
fun CarHome() {
    val frame by CarEnvironment.frame.collectAsStateWithLifecycle()
    val navStatus by CarServices.nav.status.collectAsStateWithLifecycle()
    val phase by CarServices.media.phase.collectAsStateWithLifecycle()
    val now by CarServices.media.now.collectAsStateWithLifecycle()
    val floatingOn by CarSettings.floatingMediaEnabled.collectAsStateWithLifecycle()
    val widgetsOn by CarSettings.homeWidgetsEnabled.collectAsStateWithLifecycle()
    val widgetsDismissed by CarSettings.widgetsDismissed.collectAsStateWithLifecycle()
    val navTemplate by CarServices.nav.template.collectAsStateWithLifecycle()
    val navPanMode by CarServices.nav.panMode.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { CarServices.autostart(frame) }
    LaunchedEffect(phase) { if (phase == CarMedia.Phase.READY) CarServices.autoplayIfDue() }
    // Cycling the toggle back on re-enables a card dismissed earlier in the session.
    LaunchedEffect(floatingOn) { if (floatingOn) FloatingHomeState.reset() }
    // r36 restore: a playback-start transition (paused/stopped -> playing) re-enables a
    // card dismissed earlier in the session, so pressing play after a stop brings the
    // card back without reaching for Settings. Transition-gated, not level-gated:
    // dismiss-while-playing + track change (playing stays true) does NOT pop it back.
    // CarHome never leaves the composition (other screens cover it), so transitions
    // are observed across screens, including the music app. The previous value lives
    // in FloatingHomeState, not a remember, so it survives recomposition.
    LaunchedEffect(now.playing) {
        if (floatingOn && shouldResurfaceOnPlayTransition(FloatingHomeState.lastPlaying, now.playing)) FloatingHomeState.reset()
        FloatingHomeState.lastPlaying = now.playing
    }

    val navigator = LocalCarNavigator.current
    val panel = navigator.sidePanel

    // Coming back to the map from another screen: the map stayed loaded underneath, so its surface
    // is only replaced if it died meanwhile. The first composition is skipped, since the surface
    // is only just being lent.
    val onHome = navigator.current == CarScreen.Home
    val seenHome = remember { booleanArrayOf(false) }
    LaunchedEffect(onHome) {
        if (onHome && seenHome[0]) CarServices.nav.replaceDeadSurface()
        if (onHome) seenHome[0] = true
    }
    val splitScreen = phase == CarMedia.Phase.READY && now.isActive && panel != SidePanel.NONE

    // r34 floating Home: the toggle (default OFF) swaps the split for a
    // full-bleed map with a floating media card. The lyrics column is the
    // one exception — it keeps the classic split, since lyrics is a view
    // the driver explicitly opened, not a player placement. Everything
    // below in the else branch is the classic Home, verbatim.
    val useFloating = floatingOn && panel != SidePanel.LYRICS
    val showFloatingCard = shouldShowFloatingCard(
        floatingOn, phase == CarMedia.Phase.READY && now.isActive,
        FloatingHomeState.dismissed.value, panel, onHome,
    )
    // r37 widget layer: driver-side column over the full-bleed map base
    // only (floating ON). Classic split hides it and stays byte-for-byte.
    // r39: the column also steps aside whenever the nav app's current
    // template occupies its corner (search panel, turn card, any other
    // template chrome) — hiding by template, never a dismissal, so the
    // column returns on its own when the template clears.
    val showWidgetColumn = shouldShowWidgetColumn(
        widgetsOn, floatingOn, widgetsDismissed, onHome, panel,
        navTemplateAllowsWidgets(navTemplate, navPanMode),
    )

    // Phase 8: 12dp outer padding (so rounded corners actually show against
    // the backdrop), 12dp gap, map 1.75 / media 1.0 — the media pane is
    // ~36% of the width instead of ~32%, about 22px wider on an 800px
    // screen once padding and gap are counted. With nothing playing the
    // map still takes the full row (minus the padding), as before.
    // Phase 10: the audit video's full-width map showed NO padding or
    // rounded corners at all — a SurfaceView is its own SurfaceFlinger
    // layer: if that layer ever spans the full frame (it is lent at
    // frame size in places, and the app keeps drawing into it), it
    // paints straight over the Row's padding area. So the Home frame
    // is drawn here, in the window layer above the SurfaceView: an
    // opaque backdrop-coloured frame with a rounded inner cut, over
    // the whole Row. When the SurfaceView is correctly pane-sized
    // this frame paints backdrop over backdrop and changes nothing;
    // when it is full-frame, this frame is what restores the 12dp
    // padding and 20dp corners. It takes no pointer input, so map
    // gestures pass through untouched.
    // 2026-10-25 (map-corner dots): the pane-level corner masks that
    // used to sit here are GONE, deliberately. On a real head unit
    // they showed as four visible dots at the map's corners: when the
    // SurfaceView spans the full frame, the masks (painted at the
    // pane's inset corners, in backdrop colour) land in the middle of
    // map video instead of over its corners, so each one reads as a
    // dot punched into the map — and even when the surface IS
    // pane-sized, the frame overlay below already rounds those same
    // corners, so the masks were redundant there. One frame only, no
    // pane masks: the dots have nowhere left to come from. Cost,
    // stated plainly: in split mode the map pane's INNER corners (the
    // ones against the media pane, which the whole-row frame does not
    // cover) go square instead of rounded. Square inner corners beat
    // dots on the map.
    Box(Modifier.fillMaxSize()) {
        if (useFloating) {
            // Full-bleed map: no Row padding and no frame overlay — the map
            // IS the background here, so masking its edges would defeat the
            // point. The card is an ordinary Compose surface in the window
            // layer above the map SurfaceView (same mechanism as
            // HomeFrameOverlay), so it survives CarAppStage's deliberate
            // SurfaceView recreation as an independent node. Bottom-end
            // (passenger side) reserves the driver side for the later
            // widget column; r42 moves it to 24dp in from the frame edges
            // (left and up). r43: the nav bar overlays this Box's bottom
            // instead of sitting below it in-flow, so the card clears the
            // bar with bottom padding of 24dp plus the bar strip — and can
            // never cover the turn zone.
            Box(Modifier.fillMaxSize()) {
                MapPane(navStatus, frame, NAV_BAR_STRIP_HEIGHT)
            }
            if (showFloatingCard) {
                BoxWithConstraints(
                    Modifier.fillMaxSize().padding(
                        start = 24.dp, top = 24.dp, end = 24.dp,
                        bottom = 24.dp + NAV_BAR_STRIP_HEIGHT,
                    ),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    FloatingMediaCard(now, Modifier.width(floatingCardWidth(maxWidth)))
                }
            }
            if (showWidgetColumn) {
                // CarMotion.moving is a plain value, not a flow — poll it
                // like VideoPlayerScreen/CarWhatsNew do (1 s; GPS only
                // updates every 2 s). It gates only the narrow-frame
                // parked exception; the poll runs only while the column
                // could render, never on classic Home.
                val moving by produceState(CarMotion.moving) {
                    while (true) { value = CarMotion.moving; delay(1_000) }
                }
                // Driver side (start in LTR), top-anchored below the top
                // inset (proposal §3); the media card sits bottom-end, so
                // the two never collide. r43: the nav bar now overlays
                // this Box's bottom instead of sitting below it in-flow,
                // but the column is top-anchored so it is unaffected —
                // and on Home the bar carries no turn pill anyway. The
                // nav app's own turn UI at the top of its surface is
                // checked visually on device (test list) — no numeric
                // turn zone is claimed in code.
                BoxWithConstraints(
                    Modifier.fillMaxSize().padding(12.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    WidgetColumn(maxWidth.value, moving, Modifier.width(widgetColumnWidth(maxWidth)))
                }
            }
        } else {
        Row(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Audit (second video): the pane's clip is gone. It cannot
            // round a SurfaceView (a separate window layer). Corner
            // rounding comes from HomeFrameOverlay alone now (see the
            // 2026-10-25 note above: pane masks drew dots on a real
            // head unit and are deleted, not just disabled).
            Box(
                Modifier
                    .weight(if (splitScreen) 1.75f else 1f)
                    .fillMaxHeight(),
            ) {
                MapPane(navStatus, frame)
            }
            if (splitScreen) {
                val side = Modifier.weight(1f).fillMaxHeight()
                if (panel == SidePanel.LYRICS) SideLyrics(now, side) else DockedPlayer(now, side)
            }
        }
        HomeFrameOverlay(Modifier.fillMaxSize())
        }
    }
}

/**
 * Opaque backdrop frame over the Home row: outer rect minus an inner
 * rounded rect (12dp inset, 20dp radius), drawn in the window layer so
 * it masks even a full-frame SurfaceView layer underneath. No pointer
 * handling — Canvas takes none — so it never intercepts a gesture.
 */
@Composable
private fun HomeFrameOverlay(modifier: Modifier = Modifier) {
    val backdrop = MaterialTheme.colorScheme.background
    Canvas(modifier) {
        val inset = 12.dp.toPx()
        val radius = 20.dp.toPx()
        val inner = Rect(inset, inset, size.width - inset, size.height - inset)
        val path = Path().apply {
            addRect(Rect(Offset.Zero, size))
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    inner,
                    topLeft = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                    topRight = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                    bottomLeft = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                    bottomRight = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                )
            )
            fillType = PathFillType.EvenOdd
        }
        drawPath(path, backdrop)
    }
}

/**
 * r43: [chromeBottomClearance] lifts the nav app's template chrome off
 * the frame bottom in floating mode, so bottom chrome (the
 * travel-estimate bar) keeps its classic place above the bar while the
 * map surface itself stays full-size behind it. Classic passes 0.
 */
@Composable
private fun MapPane(
    status: CarAppConnection.Status,
    frame: CarEnvironment.Frame,
    chromeBottomClearance: Dp = 0.dp,
) {
    val navigator = LocalCarNavigator.current
    if (status.phase == CarAppConnection.Phase.RUNNING) {
        // The map is a SurfaceView (its own SurfaceFlinger layer), so the
        // Box clip above rounds the Compose chrome but NOT the map itself
        // — Compose clip cannot round a separate window layer, and claiming
        // otherwise would be wrong. The outer corners are rounded for
        // real by HomeFrameOverlay (the whole-row frame, drawn above the
        // SurfaceView since a default SurfaceView renders behind the
        // window); nothing is drawn over the map pane itself any more,
        // so no mask can show up as a dot on it. Map gestures pass
        // through untouched: the frame takes no pointer input.
        Box(Modifier.fillMaxSize()) {
            CarAppStage(
                CarServices.nav, status, frame, embedded = true,
                chromeBottomClearance = chromeBottomClearance,
            ) { CarServices.stopNav() }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val hasLast = CarSettings.lastNav.value != null
        val hasNavApps by CarServices.hasNavApps.collectAsStateWithLifecycle()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        when (status.phase) {
            CarAppConnection.Phase.BINDING, CarAppConnection.Phase.HANDSHAKE ->
                Text("Starting ${status.app ?: "the map"}…", style = MaterialTheme.typography.titleMedium)
            CarAppConnection.Phase.REJECTED, CarAppConnection.Phase.FAILED -> {
                Text("${status.app ?: "The map app"} would not start.", style = MaterialTheme.typography.titleMedium)
                status.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                GsButton(onClick = navigator::apps, tone = GsTone.Tonal) { Text("Choose another app") }
            }
            else -> if (!hasNavApps) {
                // Nothing to auto-select and nothing to pick from Car apps either - this is the
                // only case worth a distinct screen, since every other IDLE case resolves itself.
                Text("No mapping apps found.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Install a navigation app that supports Android Auto and it will start here on its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (hasLast) {
                // IDLE with a last app on record means the driver stopped it themselves - it will
                // not come back on its own, so the screen must offer a way to bring it back rather
                // than sit on a "starting" message that never resolves.
                Text("Map stopped.", style = MaterialTheme.typography.titleMedium)
                GsButton(onClick = { scope.launch { CarServices.reconnectNav(frame) } }, tone = GsTone.Tonal) { Text("Reopen") }
                GsButton(onClick = { navigator.apps() }, tone = GsTone.Tonal) { Text("Car apps") }
            } else {
                // Autostart is still picking one - a navigation app is installed, so this resolves
                // to a "Starting..." state itself, in the next frame or two.
            }
        }
    }
}

/**
 * The player, docked beside the map: art, title, progress, transport, then like, shuffle, repeat
 * and lyrics. While it's here the nav bar's pill steps aside on Home, so the controls never show
 * twice. The X sends it back to the nav bar.
 */
@Composable
private fun DockedPlayer(now: NowPlaying, modifier: Modifier) {
    val navigator = LocalCarNavigator.current
    val media = CarServices.media
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val art by produceState(now.art, now.art, now.artUri) { value = now.art ?: MediaArt.load(context, now.artUri) }

    Surface(
        modifier = modifier,
        // r54 (D4): panel shape through the style rule, so Pitlane squares it.
        shape = gsButtonShape(RoundedCornerShape(20.dp)),
        color = rememberArtColor(art, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
            // Whatever height the controls below leave over, so the art grows on a taller
            // screen and shrinks rather than pushing the buttons off a short one.
            val artSize = minOf(maxWidth - 44.dp, maxHeight - 250.dp).coerceIn(56.dp, 200.dp)
            // Audit (second video): the panel's content sat top-loaded with
            // ~40% dead space below the mode row. The block is centred
            // vertically now, so the dead space splits top and bottom
            // instead of pooling underneath.
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth()) {
                    // Art opens the full media screen, same as on the pill.
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .size(artSize)
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.surface)
                            .clickable { navigator.media() },
                    ) {
                        art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                    GsIconButton(
                        Icons.Filled.Close, "Back to the nav bar", navigator::closeSidePanel,
                        Modifier.align(Alignment.TopEnd), size = 36.dp, iconSize = 20.dp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    now.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    now.artist, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (now.durationMs > 0) {
                    PlayerProgress(now, media, Modifier.fillMaxWidth().padding(vertical = 2.dp), showTimes = false)
                } else {
                    Spacer(Modifier.height(10.dp))
                }
                TransportRow(now, media, skip = 48.dp, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                PlayModeRow(now, media, size = 42.dp, modifier = Modifier.fillMaxWidth()) {
                    GsIconButton(
                        ImageVector.vectorResource(R.drawable.lyrics_24), "Lyrics", navigator::showSideLyrics,
                        size = 42.dp, iconSize = 22.dp,
                    )
                }
            }
        }
    }
}

/**
 * Lyrics alone beside the map, big enough to read at a glance. No art: that space goes to a
 * larger title and artist instead. The player goes back to the nav bar meanwhile, whose dock
 * button swaps these back out for the controls.
 */
@Composable
private fun SideLyrics(now: NowPlaying, modifier: Modifier) {
    val navigator = LocalCarNavigator.current
    val context = LocalContext.current
    val art by produceState(now.art, now.art, now.artUri) { value = now.art ?: MediaArt.load(context, now.artUri) }
    Surface(
        modifier = modifier,
        // r54 (D4): panel shape through the style rule, so Pitlane squares it.
        shape = gsButtonShape(RoundedCornerShape(20.dp)),
        color = rememberArtColor(art, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        now.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        now.artist, style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                GsIconButton(Icons.Filled.Close, "Hide lyrics", navigator::closeSidePanel, size = 36.dp, iconSize = 20.dp)
            }
            Spacer(Modifier.height(12.dp))
            LyricsPanel(now, Modifier.weight(1f).fillMaxWidth(), lineStyle = MaterialTheme.typography.headlineSmall)
        }
    }
}
