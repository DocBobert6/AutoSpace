package app.autospace.car

import android.view.LayoutInflater
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.setValue
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
 * playback stops, and returning Home recreates it from the start.
 * Position continuity lives in the full player, which offers its usual
 * Resume prompt from the position the mini card saves on dispose.
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
    }

    /** Dismiss, or expand to the full player: playback stops here, state resets. */
    fun clear() {
        activeFlow.value = null
        gateOpenFlow.value = false
        passengerAllowed = false
        driverAnswered = false
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
}

/**
 * r75: the PiP gate. Pure, so the unit test pins it: the button and
 * the card render only when the driver has opted into experimental
 * features as a whole AND the PiP toggle is on. Either off returns
 * exactly today's browser, and the stored toggle is preserved, never
 * rewritten, while the gate is off. The toggle joins no master switch.
 */
fun browserPipNow(experimentalOn: Boolean, toggleOn: Boolean): Boolean =
    experimentalOn && toggleOn

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
 * 4dp), same bottom-dock slot and width at the call site — no second
 * width rule. Glance + transport only while moving, like the audio
 * card: title, play/pause, expand to the full player, dismiss. No
 * scrubber: scrubbing lives in the full player.
 */
@Composable
fun MiniVideoCard(item: MiniVideoState.Item, modifier: Modifier = Modifier) {
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

    // One player per card, mirroring the full player: audio attributes
    // USAGE_MEDIA + MOVIE with audio-focus handling (Media3 1.11 has
    // no separate setHandleAudioFocus — that call fails to resolve),
    // mime types from VideoUrl exactly as buildItems() does, title
    // metadata, play straight away. No resume prompt here: the mini
    // card always starts at 0, and the full player offers Resume from
    // the position saved on dispose.
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
                setMediaItem(builder.build())
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
            player.removeListener(listener)
            player.release()
        }
    }
    fun expand() {
        // Clear first, so the mini player releases before the full
        // player starts — never two players, never double audio.
        MiniVideoState.clear()
        navigator.videoPlayer(listOf(item.url), listOf(item.title), 0)
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
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
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
                Column(
                    Modifier.weight(1f).clickable { expand() },
                ) {
                    Text(
                        item.title?.takeIf { it.isNotBlank() } ?: item.url,
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
                GsIconButton(
                    Icons.Filled.OpenInFull, "Open full player",
                    { expand() },
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
