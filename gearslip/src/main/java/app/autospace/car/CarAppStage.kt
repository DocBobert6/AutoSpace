package app.autospace.car

import app.autospace.car.theme.*
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.hypot
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.car.app.model.Template
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.autospace.GearslipLog
import app.autospace.host.CarAppConnection

/**
 * Shows a connected templated app: its own drawing surface, with host chrome over the top.
 *
 * The surface is a plain SurfaceView. Because the whole car UI is a Presentation on a
 * VirtualDisplay wired to the encoder, a SurfaceView here is composited by SurfaceFlinger into
 * that display like any other layer - so the app's map reaches the head unit down the existing
 * H.264 pipe without Gearslip ever touching a pixel of it.
 */
@Composable
fun CarAppStage(
    connection: CarAppConnection,
    status: CarAppConnection.Status,
    frame: CarEnvironment.Frame,
    /** Inside the home screen's map pane: no title bar, the template gets the whole area. */
    embedded: Boolean = false,
    /**
     * r97: the start-side strip the steering-side rail occupies. The
     * lent surface extends under the rail's strip, so the map fills
     * the strip instead of the scaffold's background showing through
     * as a white band behind the floating rail; the chrome sibling
     * keeps the inset size, so no chrome ever moves, and the app's
     * visible area ([visibleAreaFor] with the matching coveredStartPx)
     * is the uncovered remainder, so centering is unchanged. Only
     * Home's map pane passes a non-zero value — a non-map app's
     * surface must NOT extend under the rail.
     */
    surfaceStartInset: Dp = 0.dp,
    /**
     * r99: the bottom analog of [surfaceStartInset] — the strip
     * the Meridian bottom pill occupies. The lent surface extends
     * under the pill's strip the same way, and the visible area
     * ([visibleAreaFor] with the matching coveredBottomPx) is the
     * uncovered remainder. Only Home's map pane passes a non-zero
     * value; the two strips are never both nonzero.
     */
    surfaceBottomInset: Dp = 0.dp,
    onDisconnect: () -> Unit,
) {
    val template by connection.template.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        // The app's own drawing goes underneath; everything else is the host's rendering of
        // the template it described, laid over the top.
        if (status.surfaceRequested) {
            AppSurface(
                connection, frame, template,
                Modifier.fillMaxSize().extendSurfaceStart(surfaceStartInset).extendSurfaceBottom(surfaceBottomInset),
                surfaceStartInset,
                surfaceBottomInset,
            )
        }
        TemplateChrome(
            template,
            Modifier.fillMaxSize().padding(
                top = if (embedded) 0.dp else CHROME_BAR,
            ),
            // r78: the connected app's package, for the collapsed
            // pane's logo (its own launcher icon).
            appPackage = connection.appPackage,
        )
        if (!embedded) ChromeBar(status, onDisconnect, Modifier.align(Alignment.TopCenter))
    }
}

/**
 * Extends a surface under the start-side rail strip: measures the child
 * wider by the inset and places it shifted into the strip (LTR: shifted
 * left; RTL: start is right, so it overflows right instead). No-op at
 * zero or negative insets, and on an unbounded width, where no strip
 * width can be computed. The host never clips this, so the extension
 * is not cut off at the frame edge; the rail, composed later in the
 * scaffold, keeps hit-testing first, so rail taps reach the rail and
 * taps in the margin reach the map underneath.
 */
internal fun Modifier.extendSurfaceStart(inset: Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        if (inset <= 0.dp || constraints.maxWidth == Constraints.Infinity) {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        } else {
            val extra = inset.roundToPx()
            val placeable = measurable.measure(
                constraints.copy(maxWidth = constraints.maxWidth + extra),
            )
            layout(placeable.width - extra, placeable.height) {
                if (layoutDirection == LayoutDirection.Ltr) {
                    placeable.place(-extra, 0)
                } else {
                    placeable.place(0, 0)
                }
            }
        }
    },
)

/**
 * r99: the bottom analog of [extendSurfaceStart] — the lent
 * surface extends under the Meridian bottom pill's strip, so the
 * map fills the strip instead of the scaffold's background showing
 * through as a band behind the floating pill. The surface is
 * measured taller and placed at the same top, so it grows
 * downward under the pill; the layout box keeps the inset height.
 */
internal fun Modifier.extendSurfaceBottom(inset: Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        if (inset <= 0.dp || constraints.maxHeight == Constraints.Infinity) {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        } else {
            val extra = inset.roundToPx()
            val placeable = measurable.measure(
                constraints.copy(maxHeight = constraints.maxHeight + extra),
            )
            layout(placeable.width, placeable.height - extra) {
                placeable.place(0, 0)
            }
        }
    },
)

/**
 * A SurfaceView lent to the app for as long as it is on screen.
 *
 * The app writes into this from its own process, so the surface must outlive the handoff;
 * [SurfaceHolder.Callback] is what tells us when that stops being true.
 *
 * [surfaceStartInset] is the start-side strip the surface extends under
 * (see [CarAppStage]); the visible area reported to the app covers only
 * the uncovered remainder.
 */
@Composable
private fun AppSurface(
    connection: CarAppConnection,
    frame: CarEnvironment.Frame,
    template: Template?,
    modifier: Modifier = Modifier,
    surfaceStartInset: Dp = 0.dp,
    surfaceBottomInset: Dp = 0.dp,
) {
    var size by remember { mutableStateOf(0 to 0) }

    // The app lays its map out around whatever the host covers, so a template change moves the
    // visible area even though the surface itself hasn't changed.
    // r97: the visible area is the surface minus the covered strip,
    // so the app centers in the uncovered remainder exactly as when
    // the surface itself was inset.
    val coveredStartPx = with(LocalDensity.current) { surfaceStartInset.roundToPx() }
    val coveredBottomPx = with(LocalDensity.current) { surfaceBottomInset.roundToPx() }
    LaunchedEffect(template, size, coveredStartPx, coveredBottomPx) {
        val (width, height) = size
        if (width > 0 && height > 0) {
            val visible = visibleAreaFor(template, width, height, coveredStartPx, coveredBottomPx)
            connection.reportAreas(visible = visible, stable = visible)
        }
    }

    // Audit (second video): closing the media split black-flashed the
    // map for ~1s — the split close resizes the pane, the size-keyed
    // SurfaceView below is recreated, and the new surface stays black
    // until the app redraws into it. The surface itself still
    // recreates per size (the freeze lesson below is NOT revisited:
    // resizing in place can freeze a map app for good), but the flash
    // is masked: when a SurfaceView is released, its last frame is
    // copied out with PixelCopy and held as a window-layer image over
    // the new surface, fading out after 1.2s — long enough to cover
    // the redraw, short enough that a stale map never lingers. If the
    // copy fails (a secure surface, a dead surface) there is simply
    // no placeholder and behaviour is exactly as before. The image
    // takes no pointer input, so map gestures pass through untouched.
    // Bitmaps are deliberately NOT recycled by hand: the fade keeps
    // drawing the last bitmap after the live one clears, and drawing
    // a recycled bitmap crashes — short-lived references here are
    // reclaimed by the collector instead.
    var snapshot by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var snapshotShown by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    if (snapshot != null) snapshotShown = snapshot
    val snapshotAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (snapshot != null) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(300),
        label = "mapSnapshot",
    )
    val snapshotHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    fun captureFrom(view: SurfaceView) {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return
        try {
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(view.holder.surface, bmp, { result ->
                if (result == android.view.PixelCopy.SUCCESS) snapshot = bmp
            }, snapshotHandler)
        } catch (e: IllegalArgumentException) {
            GearslipLog.i("stage: snapshot unavailable (${e.message})")
        }
    }
    LaunchedEffect(snapshot) {
        val bmp = snapshot
        if (bmp != null) {
            kotlinx.coroutines.delay(1200)
            if (snapshot === bmp) snapshot = null
            kotlinx.coroutines.delay(350)
            if (snapshotShown === bmp) snapshotShown = null
        }
    }

    // A fresh SurfaceView - and so a fresh Surface - for every size, the way Android Auto hands an
    // app a new surface rather than resizing the old one. A resized SurfaceView keeps the same
    // BufferQueue, and apps that draw through their own VirtualDisplay build the
    // new one on it before releasing the old: the old display's disconnect then takes the new one's
    // connection with it and the map freezes for good. The nav bar hiding for the search keyboard
    // and the lyrics pane opening both resize this area.
    androidx.compose.foundation.layout.Box(modifier) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val epoch by connection.surfaceEpoch.collectAsState()
            androidx.compose.runtime.key(constraints.maxWidth, constraints.maxHeight, epoch) {
                SurfaceHost(
                    connection, frame, onSize = { size = it }, Modifier.fillMaxSize(),
                    onSurfaceViewReleased = ::captureFrom,
                )
            }
        }
        if (snapshotShown != null && snapshotAlpha > 0f) {
            androidx.compose.foundation.Image(
                snapshotShown!!.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alpha = snapshotAlpha,
            )
        }
    }

    DisposableEffect(connection) {
        onDispose {
            connection.detachSurface()
            snapshot = null
            snapshotShown = null
        }
    }
}

@Composable
private fun SurfaceHost(
    connection: CarAppConnection,
    frame: CarEnvironment.Frame,
    onSize: (Pair<Int, Int>) -> Unit,
    modifier: Modifier,
    onSurfaceViewReleased: (SurfaceView) -> Unit = {},
) {
    AndroidView(
        onRelease = { view -> onSurfaceViewReleased(view) },
        modifier = modifier
            .pointerInput(connection) {
                detectTapGestures { connection.surfaceClick(it.x, it.y) }
            }
            .pointerInput(connection) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    // GestureDetector reports scroll as previous-minus-current; a Compose pan
                    // is the other way round.
                    if (pan != Offset.Zero) connection.surfaceScroll(-pan.x, -pan.y)
                    if (zoom != 1f) connection.surfaceScale(centroid.x, centroid.y, zoom)
                }
            }
            .pointerInput(connection) {
                // detectTransformGestures has no velocity, so a fling is measured here alongside
                // it: one finger lifting while still moving lets the map glide on, as on a car.
                // Velocity is the finger's own, which is GestureDetector's onFling convention.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var pinched = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.changes.size > 1) pinched = true
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) {
                            val v = tracker.calculateVelocity()
                            if (!pinched && hypot(v.x, v.y) >= FLING_MIN_PX_S) {
                                connection.surfaceFling(v.x, v.y)
                            }
                            break
                        }
                    }
                }
            },
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        GearslipLog.i("stage: surface ready at ${width}x$height")
                        onSize(width to height)
                        connection.attachSurface(holder.surface, width, height, frame.densityDpi)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        GearslipLog.i("stage: surface destroyed")
                        // Only this view's own surface: a replaced view's teardown can land after
                        // its successor has already lent the app a new one.
                        connection.detachSurface(holder.surface)
                    }
                })
            }
        },
    )
}

@Composable
private fun ChromeBar(
    status: CarAppConnection.Status,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    status.app ?: "Car app",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val detail = buildList {
                    status.template?.let { add(it) }
                    add(if (status.surfaceAttached) "drawing" else "waiting for the app")
                }.joinToString(" - ")
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            GsButton(onClick = onDisconnect, tone = GsTone.Tonal) { Text("Stop") }
        }
    }
}

/** Height of [ChromeBar]; the template's own chrome starts below it. */
private val CHROME_BAR = 44.dp

/** Slower than this when the finger lifts and it was a drag that stopped, not a fling. */
private const val FLING_MIN_PX_S = 400f
