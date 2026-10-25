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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import android.support.v4.media.session.PlaybackStateCompat
import app.autospace.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.media.session.PlaybackState
import app.autospace.media.MediaArt
import kotlinx.coroutines.delay
import androidx.compose.material3.Icon
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.luminance
import app.autospace.media.ArtColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.autospace.host.CarAppConnection
import app.autospace.media.CarMedia
import app.autospace.media.NowPlaying
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

    LaunchedEffect(Unit) { CarServices.autostart(frame) }
    LaunchedEffect(phase) { if (phase == CarMedia.Phase.READY) CarServices.autoplayIfDue() }

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

    // Phase 8: 12dp outer padding (so rounded corners actually show against
    // the backdrop), 12dp gap, map 1.75 / media 1.0 — the media pane is
    // ~36% of the width instead of ~32%, about 22px wider on an 800px
    // screen once padding and gap are counted. With nothing playing the
    // map still takes the full row (minus the padding), as before.
    // Phase 10: the audit video's full-width map showed NO padding or
    // rounded corners at all — the pane's own clip + MapCornerOverlays
    // cannot be trusted for this, because a SurfaceView is its own
    // SurfaceFlinger layer: if that layer ever spans the full frame
    // (it is lent at frame size in places, and the app keeps drawing
    // into it), it paints straight over the Row's padding area and the
    // pane-level overlays sit inset over map video, invisible. So the
    // Home frame is ALSO drawn here, in the window layer above the
    // SurfaceView: an opaque backdrop-coloured frame with a rounded
    // inner cut, over the whole Row. When the SurfaceView is correctly
    // pane-sized this frame paints backdrop over backdrop and changes
    // nothing; when it is full-frame, this frame is what restores the
    // 12dp padding and 20dp corners. It takes no pointer input, so map
    // gestures pass through untouched.
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Audit (second video): the pane's clip is gone. It cannot
            // round a SurfaceView (a separate window layer), and a Compose
            // clip DOES apply to descendant Canvas draws — so it was
            // clipping MapCornerOverlays' own corner masks into no-ops,
            // leaving the split mode's inner corners unmasked while the
            // outer corners relied on HomeFrameOverlay alone. The masks
            // below do the rounding now, at all four pane corners, in
            // every theme: their colour is the background token, never
            // a hardcoded black (verified at source).
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
 * Rounds the map pane's corners for real, despite the map being a
 * SurfaceView. Each corner gets a backdrop-coloured mask: the corner
 * square minus a quarter circle (EvenOdd path), so only the square's
 * outer corner is painted over and the map shows through the rounded
 * cut. No pointer handling, so it never intercepts a map gesture; and
 * it sits above the SurfaceView only because a default SurfaceView
 * renders behind the window — if the stage ever moves to
 * setZOrderOnTop, these masks would silently stop working and the map
 * would go square-cornered again.
 */
@Composable
private fun MapCornerOverlays(modifier: Modifier = Modifier) {
    val backdrop = MaterialTheme.colorScheme.background
    Canvas(modifier) {
        val r = 20.dp.toPx()
        val w = size.width
        val h = size.height
        fun corner(rect: Rect, circle: Rect) {
            drawPath(
                Path().apply {
                    addRect(rect)
                    addOval(circle)
                    fillType = PathFillType.EvenOdd
                },
                backdrop,
            )
        }
        // Top-left: circle centred at the inner corner (r, r).
        corner(Rect(Offset.Zero, Size(r, r)), Rect(Offset.Zero, Size(2 * r, 2 * r)))
        // Top-right: circle centred at (w - r, r).
        corner(
            Rect(Offset(w - r, 0f), Size(r, r)),
            Rect(Offset(w - 2 * r, 0f), Size(2 * r, 2 * r)),
        )
        // Bottom-left: circle centred at (r, h - r).
        corner(
            Rect(Offset(0f, h - r), Size(r, r)),
            Rect(Offset(0f, h - 2 * r), Size(2 * r, 2 * r)),
        )
        // Bottom-right: circle centred at (w - r, h - r).
        corner(
            Rect(Offset(w - r, h - r), Size(r, r)),
            Rect(Offset(w - 2 * r, h - 2 * r), Size(2 * r, 2 * r)),
        )
    }
}

@Composable
private fun MapPane(status: CarAppConnection.Status, frame: CarEnvironment.Frame) {
    val navigator = LocalCarNavigator.current
    if (status.phase == CarAppConnection.Phase.RUNNING) {
        // The map is a SurfaceView (its own SurfaceFlinger layer), so the
        // Box clip above rounds the Compose chrome but NOT the map itself
        // — Compose clip cannot round a separate window layer, and claiming
        // otherwise would be wrong. The corners are rounded for real by
        // MapCornerOverlays: backdrop-coloured masks over the four corners,
        // drawn above the SurfaceView (default Z-order: behind the window),
        // which no touch handling touches, so map gestures pass through.
        Box(Modifier.fillMaxSize()) {
            CarAppStage(CarServices.nav, status, frame, embedded = true) { CarServices.stopNav() }
            MapCornerOverlays(Modifier.fillMaxSize())
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
        shape = RoundedCornerShape(20.dp),
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
        shape = RoundedCornerShape(20.dp),
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
