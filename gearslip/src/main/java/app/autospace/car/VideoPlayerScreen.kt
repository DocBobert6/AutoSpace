package app.autospace.car

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsIconButton
import app.autospace.car.theme.GsSeekBar
import app.autospace.car.theme.GsTone
import app.autospace.notify.CarMotion
import kotlinx.coroutines.delay

/**
 * AutoSpace's own video player (Phase 3.3), opened from the browser's media
 * finder. One player per screen, created in composition and released in
 * onDispose — Close, Back, and Home all stop audio fully, because the player
 * dies with the screen (no background audio after leaving it; that would need
 * a hoisted player + session, a separate phase).
 *
 * Parked-only video, audio may continue (locked decision): while [CarMotion]
 * says the car is moving, the PlayerView is removed from composition and an
 * audio-only placeholder shows instead — no video frame reaches the head unit,
 * audio keeps playing from the same player. Re-attaching on stop resumes video
 * at the current audio position. Honest limit, inherited from CarMotion: with
 * no location permission or no recent GPS fix it answers "not moving", so a
 * missing signal never hides video — this player is fail-open, by design.
 */
@Composable
fun VideoPlayerScreen(url: String, title: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    var retryKey by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    // Phase 6, Notscape AVKit language: controls overlay the video and
    // auto-hide ~3.5s after the last touch while playing. They NEVER hide
    // while paused (otherwise there would be nothing to resume with), while
    // driving (audio controls must stay reachable), or on an error screen.
    // Any control tap resets the timer; tapping the video itself toggles.
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionKey by remember { mutableIntStateOf(0) }
    var overflowOpen by remember { mutableStateOf(false) }
    fun touchControls() { controlsVisible = true; interactionKey++ }

    val player = remember(url, retryKey) {
        ExoPlayer.Builder(context)
            // Audio focus is handled here, via setAudioAttributes' second argument:
            // the Builder has no separate setHandleAudioFocus in Media3 1.11 (that
            // call fails to resolve and poisons the whole chain's type inference).
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()
            .apply {
                val item = when (VideoUrl.kindOf(url)) {
                    VideoUrl.Kind.HLS -> MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
                    VideoUrl.Kind.DASH -> MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_MPD).build()
                    else -> MediaItem.fromUri(url)
                }
                setMediaItem(item)
                prepare()
                playWhenReady = true
            }
    }
    val listener = remember {
        object : Player.Listener {
            override fun onIsPlayingChanged(v: Boolean) { isPlaying = v }
            override fun onPlayerError(e: PlaybackException) {
                error = e.message ?: "This video could not be played."
            }
        }
    }
    DisposableEffect(player) {
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // CarMotion.moving is a plain value, not a flow — poll it like CarWhatsNew does.
    // 1 s here (GPS only updates every 2 s) keeps video-blanking lag to ~1–3 s.
    val moving by produceState(CarMotion.moving) {
        while (true) { value = CarMotion.moving; delay(1_000) }
    }
    val position by produceState(0L to C.TIME_UNSET) {
        while (true) {
            value = player.currentPosition to player.duration
            delay(500)
        }
    }
    val (pos, dur) = position
    val isLive = dur == C.TIME_UNSET || player.isCurrentMediaItemLive

    fun seekBy(deltaMs: Long) {
        val target = (pos + deltaMs).coerceAtLeast(0L)
        player.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
    }

    LaunchedEffect(controlsVisible, isPlaying, moving, interactionKey, error) {
        if (controlsVisible && isPlaying && !moving && error == null) {
            delay(3_500)
            controlsVisible = false
            overflowOpen = false
        }
    }

    if (error != null) {
        // Error screen keeps the old pushed-down layout and always-visible
        // controls: there is nothing to auto-hide over.
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title ?: "Video",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                trailing = {
                    GsIconButton(Icons.Filled.Close, "Close", onClose, size = 64.dp, iconSize = 32.dp)
                },
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Could not play this video.",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    error ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Videos that need sign-in, DRM-locked streams, and YouTube pages " +
                        "are not playable here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GsButton(onClick = { error = null; retryKey++ }) { Text("Retry") }
                    GsButton(onClick = onClose, tone = GsTone.Neutral) { Text("Close") }
                }
            }
        }
    } else if (moving) {
        // Audio-only placeholder: no surface attached, so no video frame is sent.
        // Controls stay visible and reachable while driving — they control audio.
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title ?: "Video",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                trailing = {
                    GsIconButton(Icons.Filled.Close, "Close", onClose, size = 64.dp, iconSize = 32.dp)
                },
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Video hidden while driving — audio continues",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    title ?: url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PlayerTransport(
                pos = pos,
                dur = dur,
                isLive = isLive,
                isPlaying = isPlaying,
                onPlayPause = { if (isPlaying) player.pause() else player.play(); touchControls() },
                onSeekBy = { seekBy(it); touchControls() },
                onSeekTo = { fraction -> player.seekTo((fraction * dur).toLong()); touchControls() },
            )
        }
    } else {
        // Parked video: AVKit overlay layout. Controls float over the video
        // on gradient scrims — scrims, not blur, because PlayerView is a
        // SurfaceView and cannot be backdrop-captured (Notscape A3 rule).
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                    }
                },
                onRelease = { it.player = null },
            )
            // Tap the video itself to show/hide controls. This sits under the
            // control rows, so taps on buttons never reach it.
            Box(
                Modifier.fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (controlsVisible) { controlsVisible = false; overflowOpen = false }
                        else touchControls()
                    },
            )
            AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                // Top gradient bar: close, centred title, overflow.
                Box(
                    Modifier.fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                            ),
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GsIconButton(
                            Icons.Filled.Close, "Close", onClose,
                            size = 64.dp, iconSize = 32.dp, shape = CircleShape,
                        )
                        Text(
                            title ?: "Video",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        GsIconButton(
                            Icons.Filled.MoreVert, "More options",
                            { overflowOpen = !overflowOpen; touchControls() },
                            size = 64.dp, iconSize = 32.dp, shape = CircleShape,
                        )
                    }
                    if (overflowOpen) {
                        Column(
                            Modifier.align(Alignment.TopEnd)
                                .padding(top = 76.dp, end = 4.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    MaterialTheme.shapes.extraLarge,
                                )
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GsButton(onClick = { player.seekTo(0); overflowOpen = false; touchControls() }) {
                                Text("Restart from beginning")
                            }
                            GsButton(onClick = onClose, tone = GsTone.Neutral) { Text("Close player") }
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)),
                            ),
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    PlayerTransport(
                        pos = pos,
                        dur = dur,
                        isLive = isLive,
                        isPlaying = isPlaying,
                        onPlayPause = { if (isPlaying) player.pause() else player.play(); touchControls() },
                        onSeekBy = { seekBy(it); touchControls() },
                        onSeekTo = { fraction -> player.seekTo((fraction * dur).toLong()); touchControls() },
                        onSurface = true,
                    )
                }
            }
        }
    }
}

/**
 * Phase 6 transport, shared by the parked overlay and the driving audio
 * layout: bottom scrubber with elapsed on the left and remaining on the
 * right, and the AVKit centre cluster —10s / big play-pause / +10s as
 * circular frosted buttons. The seek bar is VOD-only; live shows LIVE.
 */
@Composable
private fun PlayerTransport(
    pos: Long,
    dur: Long,
    isLive: Boolean,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Float) -> Unit,
    onSurface: Boolean = false,
) {
    val timeColor = if (onSurface) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        if (isLive) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text("LIVE", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = timeColor)
            }
        } else if (dur > 0) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(formatTime(pos), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = timeColor)
                Box(Modifier.weight(1f)) {
                    GsSeekBar(
                        fraction = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f),
                        onSeek = onSeekTo,
                    )
                }
                Text(
                    "-" + formatTime((dur - pos).coerceAtLeast(0L)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = timeColor,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GsIconButton(
                Icons.Filled.Replay10, "Back 10 seconds", { onSeekBy(-10_000) },
                size = 72.dp, iconSize = 36.dp, shape = CircleShape,
            )
            Spacer(Modifier.size(24.dp))
            GsIconButton(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (isPlaying) "Pause" else "Play",
                onPlayPause,
                tone = GsTone.Primary,
                size = 96.dp,
                iconSize = 44.dp,
                shape = CircleShape,
            )
            Spacer(Modifier.size(24.dp))
            GsIconButton(
                Icons.Filled.Forward10, "Forward 10 seconds", { onSeekBy(10_000) },
                size = 72.dp, iconSize = 36.dp, shape = CircleShape,
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms < 0) return "--:--"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
