package app.autospace.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.autospace.GearslipLog
import app.autospace.ScreenProjector
import app.autospace.TouchPoint
import app.autospace.car.CarEnvironment
import app.autospace.car.CarUi
import app.autospace.car.PreviewThemeOverride
import kotlinx.coroutines.delay

/**
 * Runs the real car UI on the bench and shows it on the phone.
 *
 * This is the same pipeline the car gets - a VirtualDisplay hosting a Presentation, driven by
 * the same [ScreenProjector] and fed the same touches - with an ImageReader standing in for the
 * H.264 encoder. That matters for anything composited rather than drawn by Compose, such as the
 * SurfaceView a templated app draws its map into: if it appears here, it will reach the car.
 *
 * Since 2026-10-25 the size, density and theme come from
 * [PreviewSettings] (the car preview settings page), not from a
 * fixed frame: the frame [CarEnvironment] reports is set to the
 * chosen preview size on entry and restored to whatever it was on
 * exit, so frame-dependent car code (e.g. templated-app connect
 * sizes) matches the preview while it is open. Touch mapping scales
 * by the chosen width/height, so it stays correct at every size.
 *
 * Debug affordance only. Nothing in the car session path goes through this screen.
 */
@Composable
fun CarPreviewScreen(onBack: () -> Unit, onOpenPreviewSettings: () -> Unit) {
    val context = LocalContext.current
    remember { PreviewSettings.ensure(context) }
    val previewSize by PreviewSettings.size.collectAsStateWithLifecycle()
    val previewDensity by PreviewSettings.densityDpi.collectAsStateWithLifecycle()
    val previewStyle by PreviewSettings.styleId.collectAsStateWithLifecycle()
    val previewColours by PreviewSettings.colours.collectAsStateWithLifecycle()
    val previewDarkType by PreviewSettings.darkType.collectAsStateWithLifecycle()
    val touchIndicators by PreviewSettings.touchIndicators.collectAsStateWithLifecycle()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var viewSize by remember { mutableStateOf(0 to 0) }
    var markers by remember { mutableStateOf(listOf<TouchMarker>()) }
    // Tied to how the phone itself is held, not a button: turn it sideways and the preview goes
    // fullscreen, turn it back and the phone's own chrome (the Back/title above the frame) returns.
    val fullscreen = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val width = previewSize.width
    val height = previewSize.height
    val density = previewDensity
    // Preview-only theme override; null when every picker sits on
    // "use the car's setting", in which case the preview renders
    // exactly as the real car UI would.
    val themeOverride = if (previewStyle == null && previewColours == null && previewDarkType == null) {
        null
    } else {
        PreviewThemeOverride(
            appTheme = previewColours,
            uiThemeId = previewStyle,
            darkType = previewDarkType,
        )
    }
    // The frame the environment reported before the preview opened —
    // restored on exit so leaving the preview never leaves a preview
    // size behind for the next real session.
    val previousFrame = remember { CarEnvironment.frame.value }

    // Touch markers fade on their own; a ticker prunes them so a
    // stale marker can never sit on the frame indefinitely.
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            val now = SystemClock.uptimeMillis()
            markers = markers.filter { now - it.atMs < 900 }
        }
    }

    val projector = remember { ScreenProjector(context) }

    DisposableEffect(width, height, density) {
        CarEnvironment.start(context)
        CarEnvironment.setDisplay(width, height, density)

        // COMPOSER_OVERLAY is what makes SurfaceFlinger willing to composite into this reader;
        // CPU_READ_OFTEN is what makes the resulting buffer lockable, which is how the frame
        // gets copied into a Bitmap. Without both, frames arrive but every copy fails.
        val reader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 3,
            HardwareBuffer.USAGE_COMPOSER_OVERLAY or
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_CPU_READ_OFTEN,
        )
        val handler = Handler(Looper.getMainLooper())
        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                bitmap = image.toBitmap(width, height)
            } catch (t: Throwable) {
                GearslipLog.e("preview: could not copy a frame", t)
            } finally {
                image.close()
            }
        }, handler)

        projector.start(reader.surface, width, height, density, name = "gearslip-preview") {
            CarUi(previewTheme = themeOverride)
        }

        onDispose {
            projector.stop()
            reader.close()
            bitmap = null
            CarEnvironment.setDisplay(previousFrame.width, previousFrame.height, previousFrame.densityDpi)
        }
    }

    val densityPx = LocalDensity.current

    // The frame is drawn the same way in both layouts; only what surrounds it differs.
    val frameContent: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier
                .aspectRatio(width.toFloat() / height)
                .background(Color.Black)
                .onSizeChanged { viewSize = it.width to it.height }
                .pointerInteropFilter { event ->
                    val (viewWidth, viewHeight) = viewSize
                    if (viewWidth > 0 && viewHeight > 0) {
                        if (touchIndicators &&
                            (event.actionMasked == MotionEvent.ACTION_DOWN ||
                                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN)
                        ) {
                            val i = event.actionIndex
                            markers = markers + TouchMarker(event.getX(i), event.getY(i), SystemClock.uptimeMillis())
                        }
                        projector.dispatchTouch(
                            event.actionMasked.toCarAction(),
                            event.actionIndex,
                            List(event.pointerCount) { i ->
                                TouchPoint(
                                    event.getPointerId(i),
                                    event.getX(i) * width / viewWidth,
                                    event.getY(i) * height / viewHeight,
                                )
                            },
                        )
                    }
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = "Car screen",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } ?: Text("Waiting for a frame…", color = Color.White)
            // Touch indicators (preview only, off by default): a
            // fading ring where each new finger landed, in the
            // preview's own view coordinates.
            if (touchIndicators) {
                markers.forEach { marker ->
                    val radius = with(densityPx) { 14.dp.roundToPx() }
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .offset {
                                IntOffset(marker.x.toInt() - radius, marker.y.toInt() - radius)
                            }
                            .size(28.dp)
                            .background(Color.White.copy(alpha = 0.25f), CircleShape)
                            .border(2.dp, Color.White, CircleShape),
                    )
                }
            }
        }
    }

    if (fullscreen) {
        Fullscreen(onBack) {
            // No fillMax here: left to the bounds alone, aspectRatio takes the whole width or the
            // whole height, whichever the car's shape allows, and the rest stays black.
            frameContent(Modifier)
        }
        return
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(
                "Car preview",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Text(
                "${width}x$height @ ${density}dpi - touches go to the car UI",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            frameContent(Modifier.padding(vertical = 12.dp).fillMaxWidth())

            Row {
                TextButton(onClick = onBack) { Text("Back") }
                TextButton(onClick = onOpenPreviewSettings) { Text("Preview settings") }
            }
        }
    }
}

/** One tap marker: view coordinates (not car coordinates) plus the time it landed. */
private data class TouchMarker(val x: Float, val y: Float, val atMs: Long)

/**
 * Fills the phone screen with [content], centred and letterboxed.
 *
 * A car screen is wider than it is tall, so a portrait phone would waste most of its height on
 * one. This only shows once the phone is already turned sideways (the caller drives it off the
 * phone's own orientation), so there is no rotation left to force here - just the system bars to
 * hide while it's up and restore on the way out.
 */
@Composable
private fun Fullscreen(
    onBack: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val activity = LocalContext.current.activity()

    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose { }

        val insets = WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose { insets.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Turning the phone back upright is what normally leaves fullscreen; back still leaves the
    // preview screen entirely rather than doing nothing, in case the phone's own rotation lock
    // is on and turning it sideways/back isn't an option.
    BackHandler(onBack = onBack)

    Box(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        content()

        // The frame swallows every touch inside it, so leaving needs either the back gesture or
        // a button of its own. This one sits in the corner the car UI leaves emptiest.
        FilledTonalIconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Leave the car preview")
        }
    }
}

/** Compose hands out a themed wrapper, so the activity is a few unwraps down. */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** The actions [ScreenProjector.dispatchTouch] understands reach the car UI, second fingers included. */
private fun Int.toCarAction(): Int = when (this) {
    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP,
    -> this
    else -> MotionEvent.ACTION_CANCEL
}

/**
 * Copies one frame out of the reader. The reader's rows are padded to a hardware-friendly
 * stride, so the copy is made at the padded width and then cropped back.
 */
private fun android.media.Image.toBitmap(width: Int, height: Int): Bitmap {
    val plane = planes[0]
    val pixelStride = plane.pixelStride
    val rowPadding = plane.rowStride - pixelStride * width
    val padded = Bitmap.createBitmap(
        width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888,
    )
    padded.copyPixelsFromBuffer(plane.buffer)
    return if (padded.width == width) padded
    else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
}
