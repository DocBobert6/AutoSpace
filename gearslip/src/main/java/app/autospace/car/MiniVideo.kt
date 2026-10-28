package app.autospace.car

import android.view.LayoutInflater
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.autospace.R
import app.autospace.car.theme.FrostedPanelSurface
import app.autospace.car.theme.GsIconButton
import app.autospace.car.theme.GsKit
import app.autospace.car.theme.cardShapeForLook
import app.autospace.car.theme.gsLook
import app.autospace.notify.CarMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * r75: browser picture-in-picture, EXPERIMENTAL, achievable shape only.
 *
 * A video playing inside the browser WebView can NEVER be detached to a
 * car widget: the WebView owns that stream, and system PiP appears on
 * the phone, not the car screen. So this is not a detach. It is a
 * handoff: the browser button sends a playable URL the media finder
 * already extracted to AutoSpace's own Media3 player, which plays it
 * in a mini video card on Home. The button therefore appears only
 * where a handoff is actually possible — gate on AND the active tab's
 * finder list non-empty — never as a dead button, never promising to
 * pop out WebView video.
 *
 * The mini card owns its OWN ExoPlayer instance, created in the card's
 * composition and released in onDispose, mirroring the full player's
 * one-player-per-screen rule. It is a Home glance surface, not
 * background playback: leaving Home disposes the card's player, so
 * playback stops, and returning Home recreates it — r118: silently
 * from the shared live position (see VideoPositionSync), not from
 * the start. Position continuity lives in the shared holder, not in
 * prompts: the full player's Resume prompt now appears only for a
 * fresh open after the app was closed, when the holder is empty.
 *
 * The passenger gate is the SAME gate as video today (see
 * PassengerGate.kt): moving is polled 1 s from CarMotion.moving; while
 * the gate is open and unanswered the video branch stays composed, so
 * video keeps playing behind the dialog's dim; "Yes" (driver) drops to
 * the audio-only branch (no PlayerView attached, so no video frame is
 * sent); "I'm a passenger" shows video. A passenger's allowance
 * persists across stops and across parked-to-moving transitions until
 * the mini video is cleared; a driver's "Yes" only closes the gate
 * until the next parked-to-moving transition, which re-asks.
 */
object MiniVideoState {
    data class Item(val url: String, val title: String? = null)

    private val activeFlow = MutableStateFlow<Item?>(null)
    val active: StateFlow<Item?> = activeFlow.asStateFlow()

    private val gateOpenFlow = MutableStateFlow(false)
    val gateOpen: StateFlow<Boolean> = gateOpenFlow.asStateFlow()

    /** r118: mini card size. Small is the r75 card; large is the grown card; fullscreen is not a card size — the size button expands to the full player instead. */
    private val sizeFlow = MutableStateFlow(MiniVideoSize.SMALL)
    val size: StateFlow<MiniVideoSize> = sizeFlow.asStateFlow()

    var passengerAllowed: Boolean = false
        private set
    var driverAnswered: Boolean = false
        private set

    /** A fresh PiP tap: new video, fresh gate — a passenger is re-asked, like a fresh browser entry. */
    fun popOut(url: String, title: String?) {
        activeFlow.value = Item(url, title)
        gateOpenFlow.value = false
        passengerAllowed = false
        driverAnswered = false
        sizeFlow.value = MiniVideoSize.SMALL
        // A fresh handoff starts at 0, never at a stale live position
        // for the same URL — but a pending switch is preserved: it
        // belongs to an in-flight expand/minimize, not to this handoff.
        VideoPositionSync.clearLive(url)
    }

    /** Dismiss, or expand to the full player: playback stops here, state resets. */
    fun clear() {
        // The live position for the dismissed URL goes, so a later
        // fresh pop-out of the same URL starts at 0; a pending switch
        // survives, because expand() sets it before clearing.
        activeFlow.value?.let { VideoPositionSync.clearLive(it.url) }
        activeFlow.value = null
        gateOpenFlow.value = false
        passengerAllowed = false
        driverAnswered = false
        sizeFlow.value = MiniVideoSize.SMALL
    }

    /** Parked: the gate closes, a driver's "Yes" lapses; a passenger's allowance persists. */
    fun onParked() {
        gateOpenFlow.value = false
        driverAnswered = false
    }

    /** Moving with no passenger allowance: the gate opens (unless already answered this stretch). */
    fun onMovingUnanswered() {
        if (!passengerAllowed && !driverAnswered) gateOpenFlow.value = true
    }

    fun onDrivingAnswer() {
        gateOpenFlow.value = false
        driverAnswered = true
    }

    fun onPassengerAnswer() {
        gateOpenFlow.value = false
        passengerAllowed = true
    }

    /**
     * r118: advances the size button one step. Returns true when the
     * card grew (small -> large); returns false at large, where the
     * caller's next step is expanding to the full player.
     */
    fun advanceSize(): Boolean {
        if (sizeFlow.value == MiniVideoSize.SMALL) {
            sizeFlow.value = MiniVideoSize.LARGE
            return true
        }
        return false
    }
}

/** r118: mini card sizes. FULLSCREEN is not a value here — the size button expands to the full player instead of growing a third card size. */
enum class MiniVideoSize { SMALL, LARGE }

/**
 * r118: the size button's cycle, pure so the unit test pins it.
 * Small grows to large; large has nowhere to grow, so it returns
 * null — the caller's cue to expand to the full player.
 */
fun cycleMiniVideoSize(size: MiniVideoSize): MiniVideoSize? = when (size) {
    MiniVideoSize.SMALL -> MiniVideoSize.LARGE
    MiniVideoSize.LARGE -> null
}

/**
 * r118: the card width for a mini size, in dp as a Float so the rule
 * is JVM-testable (same convention as CardClearance). Small is the
 * r75 slot width exactly (40% of the frame, 240–380dp, never wider
 * than the frame minus the 24dp slot pad); large grows to 60%,
 * 360–560dp, under the same frame-minus-pad ceiling.
 */
fun miniVideoCardWidthDp(size: MiniVideoSize, frameWidthDp: Float): Float = when (size) {
    MiniVideoSize.SMALL -> (frameWidthDp * 0.40f).coerceIn(240f, 380f).coerceAtMost(frameWidthDp - 24f)
    MiniVideoSize.LARGE -> (frameWidthDp * 0.60f).coerceIn(360f, 560f).coerceAtMost(frameWidthDp - 24f)
}

/** r118: the video area height for a mini size. Small is the r75 180dp; large is 320dp, a 16:9 area at the large card's middle width. */
fun miniVideoVideoHeightDp(size: MiniVideoSize): Float = when (size) {
    MiniVideoSize.SMALL -> 180f
    MiniVideoSize.LARGE -> 320f
}

/**
 * r118: the mini drag gate, pure so the unit test pins it. Parked
 * only — the mini card has no dock toggle, so unlike the media card
 * there is nothing else to gate on. Enforced at arm time and on
 * every move, exactly like the media card's gate.
 */
fun miniVideoDragApplies(moving: Boolean): Boolean = !moving

/**
 * r118: what the mini card's title line shows. The pick is "the
 * video title, not the raw URL": a present title wins as before,
 * but a missing title no longer falls back to the raw URL. The
 * derivation is deliberately plain — the last path segment, decoded,
 * extension stripped, separators spaced — so a finder item with no
 * title still reads as a name, and a bare host URL falls back to
 * the host rather than a scheme-and-query string.
 */
fun miniVideoDisplayTitle(title: String?, url: String): String {
    title?.takeIf { it.isNotBlank() }?.let { return it }
    val path = url.substringBefore('?').substringBefore('#')
    val last = path.substringAfterLast('/')
    val stem = runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrNull() ?: last
    val withoutExt = if ('.' in stem) stem.substringBeforeLast('.') else stem
    val spaced = withoutExt.replace(Regex("[_\\-+.]+"), " ").trim()
    if (spaced.isNotBlank()) return spaced
    val host = url.substringAfter("://", "").substringBefore('/')
    if (host.isNotBlank()) return host
    return "Video"
}


/** r75: the mini card shows only while gated on, handed off, and on Home. */
fun shouldShowMiniVideoCard(pipGateOn: Boolean, active: Boolean, onHome: Boolean): Boolean =
    pipGateOn && active && onHome

/** r75: what the mini card shows. VIDEO_BEHIND_GATE composes the video branch; only the dialog differs. */
enum class MiniVideoBranch {
    VIDEO,
    AUDIO_ONLY,
    VIDEO_BEHIND_GATE,
}

/**
 * r75: the branch rule, pure so the unit test pins it. The gate
 * dialog wins whenever it is open; otherwise a moving car without a
 * passenger allowance gets audio only; parked, or a passenger aboard,
 * gets video.
 */
fun miniVideoBranch(moving: Boolean, passengerAllowed: Boolean, gateOpen: Boolean): MiniVideoBranch =
    when {
        gateOpen -> MiniVideoBranch.VIDEO_BEHIND_GATE
        moving && !passengerAllowed -> MiniVideoBranch.AUDIO_ONLY
        else -> MiniVideoBranch.VIDEO
    }

/**
 * r75: the mini video card. Same frosted surface, shape, and elevation
 * as the floating audio card (FrostedPanelSurface, cardShapeForLook,
 * 4dp) — the r118 frosted treatment is the one it already had, so
 * item 1's change here is the title line, not the surface. Same
 * bottom-dock slot at the call site; the width is size-dependent
 * now (see miniVideoCardWidthDp), not a second width rule.
 * Glance + transport only while moving, like the audio card: title,
 * play/pause, size, dismiss. No scrubber: scrubbing lives in the
 * full player.
 *
 * r118: the card no longer always starts at 0. At creation it seeks
 * silently to the shared live position for its URL — a pending
 * switch first (expand/minimize handoff), else the last live
 * position (returning Home recreates the card from where it left
 * off) — and only a fresh handoff with no live position starts at
 * 0. The title line shows the video title, never the raw URL (see
 * miniVideoDisplayTitle). The size button cycles small -> large ->
 * fullscreen, replacing the old separate expand button; the info
 * area tap still expands, as before. Drag: press-and-hold on the
 * card BODY (the video area and the info area, not the buttons)
 * arms a drag; parked-only, session-only, and the offset resets on
 * the next video. The gate is enforced inside the callbacks, never
 * by removing the detector (the r106/r109/r111 lesson), and the
 * drag callback is read through rememberUpdatedState so the
 * once-attached detector always calls the latest clamp.
 */
@Composable
fun MiniVideoCard(
    item: MiniVideoState.Item,
    onMiniVideoDragDelta: (dxDp: Float, dyDp: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val navigator = LocalCarNavigator.current
    // CarMotion.moving is a plain value, not a flow — poll it 1 s like
    // VideoPlayerScreen and the widget column do (GPS only updates
    // every 2 s). The effect below mirrors the full player's gate
    // lifecycle exactly: parked closes the gate and lapses a driver's
    // answer; moving with no passenger allowance opens it.
    val moving by produceState(CarMotion.moving) {
        while (true) {
            value = CarMotion.moving
            delay(1_000)
        }
    }
    LaunchedEffect(moving) {
        if (!moving) MiniVideoState.onParked()
        else if (!MiniVideoState.passengerAllowed) MiniVideoState.onMovingUnanswered()
    }
    val gateOpen by MiniVideoState.gateOpen.collectAsState()
    val branch = miniVideoBranch(moving, MiniVideoState.passengerAllowed, gateOpen)
    val videoVisible = branch != MiniVideoBranch.AUDIO_ONLY
    val size by MiniVideoState.size.collectAsState()

    // r118: silent resume at creation. A pending switch (an explicit
    // expand/minimize handoff) wins; else the last live position
    // for this URL; else 0 for a fresh handoff. Read once per item,
    // before the player is built, so the seek lands before prepare.
    val initialPositionMs = remember(item.url) {
        VideoPositionSync.consumeSwitch(item.url)
            ?: VideoPositionSync.livePositionFor(item.url)
            ?: 0L
    }

    // One player per card, mirroring the full player: audio attributes
    // USAGE_MEDIA + MOVIE with audio-focus handling (Media3 1.11 has
    // no separate setHandleAudioFocus — that call fails to resolve),
    // mime types from VideoUrl exactly as buildItems() does, title
    // metadata, seek to the shared position, play straight away.
    val player = remember(item.url) {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()
            .apply {
                val builder = MediaItem.Builder().setUri(item.url).setMediaId(item.url)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title ?: "").build())
                when (VideoUrl.kindOf(item.url)) {
                    VideoUrl.Kind.HLS -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
                    VideoUrl.Kind.DASH -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
                    else -> builder
                }
                setMediaItem(builder.build(), initialPositionMs)
                prepare()
                play()
            }
    }
    var isPlaying by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        isPlaying = player.isPlaying
        onDispose {
            PlayerResume.save(context, item.url, player.currentPosition, player.duration)
            VideoPositionSync.saveLikeResume(item.url, player.currentPosition, player.duration)
            player.removeListener(listener)
            player.release()
        }
    }
    // r118: the live write half of the shared position. The full
    // player polls the same way, so whichever player is alive keeps
    // the holder fresh; switches themselves are written
    // synchronously in expand()/minimize, never left to this poll.
    LaunchedEffect(player) {
        while (true) {
            VideoPositionSync.update(item.url, player.currentPosition.coerceAtLeast(0L))
            delay(1_000)
        }
    }
    fun expand() {
        // The switch position is written synchronously, before the
        // card leaves composition: the full player consumes it at
        // creation and seeks straight there, with no Resume prompt.
        // Clear first, so the mini player releases before the full
        // player starts — never two players, never double audio.
        // (clear() preserves a pending switch by design.)
        VideoPositionSync.setSwitch(item.url, player.currentPosition.coerceAtLeast(0L))
        MiniVideoState.clear()
        navigator.videoPlayer(listOf(item.url), listOf(item.title), 0)
    }

    // r118: press-and-hold drag on the card BODY only — the video
    // area and the info area. The buttons carry no detector, so a
    // long-press on a button never arms a drag and taps on buttons
    // work exactly as today. The gate is enforced inside the
    // callbacks (r109/r111): the detector never leaves the chain,
    // so it works again the moment the gate reopens. The callback
    // is read through rememberUpdatedState, so the once-attached
    // detector always calls the latest clamp at the call site.
    val dragMoving by rememberUpdatedState(moving)
    val dragDeltaState by rememberUpdatedState(onMiniVideoDragDelta)
    val bodyDragDetector = Modifier.pointerInput(Unit) {
        var armed = false
        detectDragGesturesAfterLongPress(
            onDragStart = { armed = miniVideoDragApplies(dragMoving) },
            onDragEnd = { armed = false },
            onDragCancel = { armed = false },
        ) { change, dragAmount ->
            if (!armed) return@detectDragGesturesAfterLongPress
            // Re-check every move: a drag in flight is cancelled
            // the moment motion starts (r106 rule).
            if (!miniVideoDragApplies(dragMoving)) {
                armed = false
                return@detectDragGesturesAfterLongPress
            }
            change.consume()
            dragDeltaState(dragAmount.x / density, dragAmount.y / density)
        }
    }

    FrostedPanelSurface(
        modifier = modifier,
        shape = cardShapeForLook(gsLook(), GsKit.CardShape),
        elevation = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (videoVisible) {
                // Video branch only: no PlayerView attached in the
                // audio-only branch, so no video frame is sent while
                // the driver has answered Yes. The player instance
                // itself is untouched by the branch switch — no
                // restart, no re-buffer when the gate answers.
                // The drag detector sits on the wrapping Box, not on
                // the AndroidView: the video area is card body, and
                // the PlayerView (useController = false) does not
                // consume the touch itself.
                Box(
                    Modifier.fillMaxWidth().height(miniVideoVideoHeightDp(size).dp)
                        .then(bodyDragDetector),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            (LayoutInflater.from(ctx).inflate(R.layout.autospace_player_view, null) as PlayerView).apply {
                                useController = false
                            }
                        },
                        update = { pv ->
                            if (pv.player != player) pv.player = player
                        },
                        onRelease = { pv ->
                            pv.player = null
                        },
                        modifier = Modifier.fillMaxWidth().height(miniVideoVideoHeightDp(size).dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            } else {
                Text(
                    "Video hidden while driving — audio continues",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Info area only: tap-through to the full player.
                // Transport and dismiss are controls, so they sit
                // outside this clickable and never expand by accident.
                // The info area is card body too, so it also carries
                // the drag detector; a tap still taps (a long-press
                // released without movement moves nothing), and a
                // long-press plus movement drags instead of expanding.
                Column(
                    Modifier.weight(1f).then(bodyDragDetector).clickable { expand() },
                ) {
                    Text(
                        miniVideoDisplayTitle(item.title, item.url),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        VideoUrl.labelFor(item.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GsIconButton(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) "Pause mini video" else "Play mini video",
                    { if (isPlaying) player.pause() else player.play() },
                    size = 44.dp, iconSize = 22.dp,
                )
                // r118: the size button replaces the old separate
                // expand button. Small grows to large; at large the
                // next press expands to the full player (which the
                // shared position makes seamless — see expand()).
                GsIconButton(
                    if (size == MiniVideoSize.SMALL) Icons.Filled.OpenInFull else Icons.Filled.Fullscreen,
                    if (size == MiniVideoSize.SMALL) "Enlarge mini video" else "Open full player",
                    { if (!MiniVideoState.advanceSize()) expand() },
                    size = 44.dp, iconSize = 22.dp,
                )
                GsIconButton(
                    Icons.Filled.Close, "Dismiss mini video",
                    { MiniVideoState.clear() },
                    size = 44.dp, iconSize = 22.dp,
                )
            }
        }
    }
}
