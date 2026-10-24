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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
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
    onDisconnect: () -> Unit,
) {
    val template by connection.template.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        // The app's own drawing goes underneath; everything else is the host's rendering of
        // the template it described, laid over the top.
        if (status.surfaceRequested) {
            AppSurface(connection, frame, template, Modifier.fillMaxSize())
        }
        TemplateChrome(template, Modifier.fillMaxSize().padding(top = if (embedded) 0.dp else CHROME_BAR))
        if (!embedded) ChromeBar(status, onDisconnect, Modifier.align(Alignment.TopCenter))
    }
}

/**
 * A SurfaceView lent to the app for as long as it is on screen.
 *
 * The app writes into this from its own process, so the surface must outlive the handoff;
 * [SurfaceHolder.Callback] is what tells us when that stops being true.
 */
@Composable
private fun AppSurface(
    connection: CarAppConnection,
    frame: CarEnvironment.Frame,
    template: Template?,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(0 to 0) }

    // The app lays its map out around whatever the host covers, so a template change moves the
    // visible area even though the surface itself hasn't changed.
    LaunchedEffect(template, size) {
        val (width, height) = size
        if (width > 0 && height > 0) {
            val visible = visibleAreaFor(template, width, height)
            connection.reportAreas(visible = visible, stable = visible)
        }
    }

    // A fresh SurfaceView - and so a fresh Surface - for every size, the way Android Auto hands an
    // app a new surface rather than resizing the old one. A resized SurfaceView keeps the same
    // BufferQueue, and apps that draw through their own VirtualDisplay build the
    // new one on it before releasing the old: the old display's disconnect then takes the new one's
    // connection with it and the map freezes for good. The nav bar hiding for the search keyboard
    // and the lyrics pane opening both resize this area.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val epoch by connection.surfaceEpoch.collectAsState()
        androidx.compose.runtime.key(constraints.maxWidth, constraints.maxHeight, epoch) {
            SurfaceHost(connection, frame, onSize = { size = it }, Modifier.fillMaxSize())
        }
    }

    DisposableEffect(connection) { onDispose { connection.detachSurface() } }
}

@Composable
private fun SurfaceHost(
    connection: CarAppConnection,
    frame: CarEnvironment.Frame,
    onSize: (Pair<Int, Int>) -> Unit,
    modifier: Modifier,
) {
    AndroidView(
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
