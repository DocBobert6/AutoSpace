package app.autospace.car

import android.graphics.Typeface
import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import app.autospace.R
import app.autospace.car.theme.frostBar
import app.autospace.notify.CarMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AutoSpace's video player, opened from the browser's media finder — since
 * Phase 6's exact-player build, Notscape Mariner's browser video player
 * (PlayerActivity, 0.0.83 + Phase D completion) ported into the car, in
 * the Phase 6 AVKit layout language where the two already matched (top
 * capsule, centre circles, bottom title + options capsule, slim scrubber,
 * 3.5s tap-to-show auto-hide).
 *
 * One player per screen, created in composition and released in onDispose —
 * Close, Back, and Home all stop audio fully, because the player dies with
 * the screen (no background audio after leaving it; that would need a
 * hoisted player + session, a separate phase).
 *
 * Passenger-gated video (Robert 2026-10-25 05:40, explicitly
 * REVERSING the old locked parked-only decision for the passenger
 * case only): while [CarMotion] says the car is moving, entering
 * the player shows the "Are you driving?" gate (see PassengerGate);
 * "Yes" falls back to the audio-only branch below, "I'm a
 * passenger" shows the video picture while moving. The gate is
 * per-entry and never persisted, and it re-appears on a
 * parked→moving transition while video is showing. When the car
 * stops, video returns to the normal layout without re-asking.
 * Honest limits, unchanged: with no location permission or no
 * recent GPS fix CarMotion answers "not moving", so the gate does
 * not appear (fail-open, by design), and the app cannot verify
 * the tapper is actually a passenger on a driver-visible screen.
 *
 * Car adaptations to Notscape's player (only these; everything else ports
 * as-is): touch targets stay car-large (centre circles 72/96/60dp, not
 * Notscape's phone 56/76/48dp); NO downloader anywhere in the player
 * (explicitly excluded); NO phone PiP (a car Presentation has no PiP
 * window — Close player is the car equivalent); NO phone brightness/volume
 * drag gestures (the car already has its own brightness slider and volume
 * controls); the overflow menu is gone (Close + Stop in the top capsule
 * cover it; scrubber + skip buttons cover Restart); the driving branch
 * shows transport only, not the options capsule; the error screen's Close
 * is the car's Back (both return to the browser).
 */

/**
 * Phase 9 immersive fullscreen: the player publishes its controls
 * visibility here so CarUi can hide the nav bar with it. CarUi reads
 * this flow; the player writes it on every controls change and resets
 * it to true when it leaves, so a hidden bar can never outlive the
 * player. Defaults true, so the bar shows until the player says
 * otherwise (and while paused/driving/error/listen mode, when the
 * player never hides its controls at all).
 */
object PlayerImmersiveState {
    val controlsVisible = MutableStateFlow(true)
}

@Composable
fun VideoPlayerScreen(urls: List<String>, titles: List<String?>, startIndex: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    var retryKey by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var stallRecoveries by remember { mutableIntStateOf(0) }
    var currentIndex by remember { mutableIntStateOf(startIndex.coerceIn(0, (urls.size - 1).coerceAtLeast(0))) }
    var listenMode by remember { mutableStateOf(false) }
    // Controls overlay: shown on tap/interaction, auto-hide ~3.5s after the
    // last touch while playing. They NEVER hide while paused (otherwise
    // there would be nothing to resume with), while driving (audio controls
    // must stay reachable), in listen mode (Notscape: chrome stays up there),
    // or on an error screen. Any control tap resets the timer; tapping the
    // video itself toggles.
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionKey by remember { mutableIntStateOf(0) }
    fun touchControls() { controlsVisible = true; interactionKey++ }

    // Scaling (Notscape Phase C item 6): resize mode + pinch zoom/pan. The
    // PlayerView rides a TextureView (res/layout/autospace_player_view.xml)
    // so the graphicsLayer transform below really moves the picture. The
    // mode persists for the session; zoom/pan reset on track change, mode
    // change, and entering listen mode.
    var scaleMode by remember { mutableStateOf(ScaleMode.FIT) }
    var pinchScale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var showScaleRow by remember { mutableStateOf(false) }
    var transformActive by remember { mutableStateOf(false) }
    fun baseScale() = if (scaleMode == ScaleMode.CROP) 1.25f else 1f
    fun effScale() = baseScale() * pinchScale
    fun resetZoom() { pinchScale = 1f; panX = 0f; panY = 0f }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    fun applyScaleMode(m: ScaleMode) {
        scaleMode = m
        resetZoom()
        playerViewRef?.resizeMode = when (m) {
            ScaleMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            ScaleMode.FILL, ScaleMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            ScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    // Speed (persisted) and skip interval (persisted; default 10, Notscape's).
    val persistedSpeed by CarSettings.playerSpeed.collectAsState()
    val persistedSkipSec by CarSettings.playerSkipSec.collectAsState()
    var speed by remember { mutableFloatStateOf(persistedSpeed) }
    var skipSec by remember { mutableIntStateOf(persistedSkipSec) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var gestureLabel by remember { mutableStateOf("") }

    // Audio / subtitle / quality track selection (Notscape Phase D).
    var audioTracks by remember { mutableStateOf<List<TrackChoice>>(emptyList()) }
    var textTracks by remember { mutableStateOf<List<TrackChoice>>(emptyList()) }
    var selectedAudioId by remember { mutableStateOf<String?>(null) }
    var selectedTextId by remember { mutableStateOf<String?>(null) }
    var subtitlesOff by remember { mutableStateOf(false) }
    var showAudioRow by remember { mutableStateOf(false) }
    var showTextRow by remember { mutableStateOf(false) }
    var videoTracks by remember { mutableStateOf<List<TrackChoice>>(emptyList()) }
    var selectedVideoId by remember { mutableStateOf<String?>(null) }
    var showQualityRow by remember { mutableStateOf(false) }
    var parsedQualities by remember { mutableStateOf<List<ParsedQuality>>(emptyList()) }
    var selectedParsedBw by remember { mutableStateOf<Long?>(null) }

    fun closeAllRows() { showScaleRow = false; showAudioRow = false; showTextRow = false; showQualityRow = false }

    fun buildItems(): List<MediaItem> = urls.mapIndexed { i, u ->
        val builder = MediaItem.Builder().setUri(u).setMediaId(u)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(titles.getOrNull(i) ?: "").build())
        when (VideoUrl.kindOf(u)) {
            VideoUrl.Kind.HLS -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            VideoUrl.Kind.DASH -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            else -> builder
        }
        builder.build()
    }

    val player = remember(urls, retryKey) {
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
                setPlaybackSpeed(speed)
                setMediaItems(buildItems(), currentIndex, 0L)
                prepare()
                playWhenReady = true
            }
    }
    fun refreshTracks(p: Player) {
        val groups = p.currentTracks.groups
        val audios = mutableListOf<TrackChoice>()
        val texts = mutableListOf<TrackChoice>()
        val videos = mutableListOf<Pair<TrackChoice, Int>>()
        var selA: String? = null
        var selT: String? = null
        groups.forEachIndexed { gi, g ->
            if (g.type == C.TRACK_TYPE_VIDEO) {
                for (ti in 0 until g.length) {
                    val f = g.getTrackFormat(ti)
                    val label = when {
                        f.height > 0 -> "${f.height}p"
                        f.width > 0 -> "${f.width}x${f.height}"
                        else -> "Video ${videos.size + 1}"
                    }
                    videos.add(TrackChoice(gi, ti, label, "$gi:$ti") to f.height)
                }
                return@forEachIndexed
            }
            if (g.type != C.TRACK_TYPE_AUDIO && g.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (ti in 0 until g.length) {
                val f = g.getTrackFormat(ti)
                val fallback = if (g.type == C.TRACK_TYPE_AUDIO) "Audio ${audios.size + 1}" else "Subtitles ${texts.size + 1}"
                val label = f.label?.takeIf { it.isNotBlank() }
                    ?: f.language?.takeIf { it.isNotBlank() && it != "und" }
                        ?.let { runCatching { java.util.Locale.forLanguageTag(it).displayLanguage }.getOrNull() }
                    ?: fallback
                val choice = TrackChoice(gi, ti, label, "$gi:$ti")
                if (g.type == C.TRACK_TYPE_AUDIO) {
                    audios.add(choice)
                    if (g.isTrackSelected(ti)) selA = choice.id
                } else {
                    texts.add(choice)
                    if (g.isTrackSelected(ti)) selT = choice.id
                }
            }
        }
        audioTracks = audios
        textTracks = texts
        videoTracks = videos.sortedByDescending { it.second }.map { it.first }
        selectedAudioId = selA
        selectedTextId = selT
        subtitlesOff = texts.isNotEmpty() && selT == null
    }
    fun selectAudio(choice: TrackChoice) {
        val group = player.currentTracks.groups.getOrNull(choice.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, choice.trackIndex))
            .build()
        selectedAudioId = choice.id
    }
    fun selectText(choice: TrackChoice?) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (choice == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            subtitlesOff = true
            selectedTextId = null
        } else {
            val group = player.currentTracks.groups.getOrNull(choice.groupIndex) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, choice.trackIndex))
            subtitlesOff = false
            selectedTextId = choice.id
        }
        player.trackSelectionParameters = builder.build()
    }
    fun selectVideo(choice: TrackChoice?) {
        val builder = player.trackSelectionParameters.buildUpon()
            .setMinVideoBitrate(0)
            .setMaxVideoBitrate(Int.MAX_VALUE)
        if (choice == null) {
            builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            selectedVideoId = null
        } else {
            val group = player.currentTracks.groups.getOrNull(choice.groupIndex) ?: return
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, choice.trackIndex))
            selectedVideoId = choice.id
        }
        selectedParsedBw = null
        player.trackSelectionParameters = builder.build()
    }
    fun selectParsedQuality(bandwidth: Long?) {
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (bandwidth == null) {
            builder.setMinVideoBitrate(0).setMaxVideoBitrate(Int.MAX_VALUE)
        } else {
            builder.setMinVideoBitrate(bandwidth.toInt()).setMaxVideoBitrate(bandwidth.toInt())
        }
        selectedVideoId = null
        selectedParsedBw = bandwidth
        player.trackSelectionParameters = builder.build()
    }

    val listener = remember {
        object : Player.Listener {
            override fun onIsPlayingChanged(v: Boolean) { isPlaying = v }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = player.currentMediaItemIndex
                refreshTracks(player)
            }
            override fun onTracksChanged(tracks: Tracks) { refreshTracks(player) }
            override fun onPlayerError(e: PlaybackException) {
                error = e.message ?: "This video could not be played."
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    stallRecoveries = 0
                    refreshTracks(player)
                }
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
    // Passenger gate, same per-entry, never-persisted rule as the
    // browser's: stopping clears the allowance, so moving again
    // re-asks; answering "Yes" just leaves passengerAllowed false,
    // which drops the player into the audio-only branch below.
    var gateOpen by remember { mutableStateOf(false) }
    var passengerAllowed by remember { mutableStateOf(false) }
    LaunchedEffect(moving) {
        if (!moving) { passengerAllowed = false; gateOpen = false }
        else if (!passengerAllowed) { gateOpen = true }
    }
    val position by produceState(0L to C.TIME_UNSET) {
        while (true) {
            value = player.currentPosition to player.duration
            currentIndex = player.currentMediaItemIndex
            delay(500)
        }
    }
    val (pos, dur) = position
    val isLive = dur == C.TIME_UNSET || player.isCurrentMediaItemLive
    val currentTitle = titles.getOrNull(currentIndex) ?: urls.getOrNull(currentIndex) ?: "Video"
    val currentUrl = urls.getOrNull(currentIndex) ?: ""

    fun seekBy(deltaMs: Long) {
        val target = (pos + deltaMs).coerceAtLeast(0L)
        player.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
    }
    fun cycleSpeed() {
        val steps = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val next = steps[(steps.indexOf(speed) + 1) % steps.size]
        speed = next
        CarSettings.setPlayerSpeed(next)
        player.setPlaybackSpeed(next)
    }
    fun cycleSleep() {
        val steps = listOf(0, 10, 20, 30, 60)
        sleepMinutes = steps[(steps.indexOf(sleepMinutes) + 1) % steps.size]
    }

    LaunchedEffect(player) { resetZoom() }
    // A new item starts on Auto: last item's manual quality override (and
    // its row) never follows the queue.
    LaunchedEffect(currentIndex) {
        resetZoom()
        selectedVideoId = null
        selectedParsedBw = null
        showQualityRow = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setMinVideoBitrate(0)
            .setMaxVideoBitrate(Int.MAX_VALUE)
            .build()
    }
    // Parsed HLS qualities: when the player exposes fewer than two
    // renditions for an HLS master, the master's own variant list drives
    // the selector instead, and a pick is an exact bitrate pin. Probed by
    // content (#EXTM3U + #EXT-X-STREAM-INF), not by name. DASH renditions
    // come through the player's own track list, so no manifest parse is
    // needed there. Unlike Notscape, no browser identity or page cookies
    // are attached — the finder hands over direct media URLs.
    LaunchedEffect(currentIndex) {
        parsedQualities = emptyList()
        val u = urls.getOrNull(currentIndex) ?: return@LaunchedEffect
        if (!u.startsWith("http", ignoreCase = true)) return@LaunchedEffect
        val text = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.inputStream.bufferedReader().use { reader ->
                    val sb = StringBuilder()
                    var line = reader.readLine() ?: return@use null
                    sb.append(line)
                    if (!line.trim().trimStart('﻿').startsWith("#EXTM3U")) return@use null
                    sb.append('\n').append(reader.readText())
                    sb.toString().take(300_000)
                }
            }.getOrNull()
        } ?: return@LaunchedEffect
        if (!text.contains("#EXT-X-STREAM-INF")) return@LaunchedEffect
        val found = mutableListOf<ParsedQuality>()
        val lines = text.lines()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
            val height = res?.groupValues?.get(2)?.toIntOrNull() ?: 0
            found.add(
                ParsedQuality(
                    label = when {
                        height > 0 -> "${height}p"
                        bw > 0 -> String.format(java.util.Locale.US, "%.1f Mbps", bw / 1_000_000.0)
                        else -> "Variant"
                    },
                    bandwidth = bw,
                ),
            )
        }
        parsedQualities = found.sortedByDescending { it.bandwidth }.distinctBy { it.label }
    }
    LaunchedEffect(controlsVisible, isPlaying, moving, interactionKey, error, listenMode) {
        if (controlsVisible && isPlaying && !moving && error == null && !listenMode) {
            delay(3_500)
            controlsVisible = false
            closeAllRows()
        }
    }
    // Phase 9 immersive: publish every controls change for CarUi, and
    // reset to shown when the player leaves, so the nav bar can never
    // stay hidden after the player closes. Tap toggles controls and
    // nav bar together because both read the same published value.
    LaunchedEffect(controlsVisible) {
        PlayerImmersiveState.controlsVisible.value = controlsVisible
    }
    DisposableEffect(Unit) {
        onDispose { PlayerImmersiveState.controlsVisible.value = true }
    }
    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes > 0) {
            delay(sleepMinutes * 60_000L)
            player.pause()
            sleepMinutes = 0
        }
    }
    // Stall watchdog (Notscape 0.0.9995): a healthy buffer resolves in
    // seconds. Still buffering after 8 s with play intent: rebuild the load
    // at the current position, at most twice per item. Notscape leaves a
    // third stall buffering; the car build fails honestly instead, with a
    // named error on the error screen.
    LaunchedEffect(player, isBuffering) {
        if (!isBuffering) return@LaunchedEffect
        delay(8_000)
        if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
            if (stallRecoveries < 2) {
                stallRecoveries++
                val idx = player.currentMediaItemIndex
                val at = player.currentPosition.coerceAtLeast(0)
                player.stop()
                player.setMediaItems(buildItems(), idx, at)
                player.prepare()
                player.play()
            } else {
                error = "The stream stalled and could not recover."
            }
        }
    }
    LaunchedEffect(isPlaying) { playerViewRef?.keepScreenOn = isPlaying }
    LaunchedEffect(playerViewRef) { if (playerViewRef != null) applyScaleMode(scaleMode) }
    LaunchedEffect(gestureLabel) {
        if (gestureLabel.isNotEmpty()) { delay(900); gestureLabel = "" }
    }
    // Hold-to-scan (Notscape iOS-style): holding a side of the video scans
    // through it in 3-second steps until release.
    val scanScope = rememberCoroutineScope()
    var scanJob by remember { mutableStateOf<Job?>(null) }
    fun startScan(dir: Int) {
        scanJob?.cancel()
        scanJob = scanScope.launch {
            while (true) {
                val d = if (player.duration == C.TIME_UNSET) 0L else player.duration.coerceAtLeast(0)
                player.seekTo((player.currentPosition + dir * 3_000L).coerceIn(0L, d))
                gestureLabel = if (dir > 0) "Fast-forward »" else "« Rewind"
                delay(220)
            }
        }
    }
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (gestureLabel == "Fast-forward »" || gestureLabel == "« Rewind") gestureLabel = ""
    }
    fun openRow(row: String) {
        touchControls()
        val wasScale = row == "scale" && showScaleRow
        val wasAudio = row == "audio" && showAudioRow
        val wasText = row == "text" && showTextRow
        val wasQuality = row == "quality" && showQualityRow
        closeAllRows()
        when (row) {
            "scale" -> showScaleRow = !wasScale
            "audio" -> showAudioRow = !wasAudio
            "text" -> showTextRow = !wasText
            "quality" -> showQualityRow = !wasQuality
        }
    }
    // Shared by the parked centre cluster and the driving audio layout.
    @Composable
    fun centreCluster() {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (urls.size > 1) {
                PlayerIconButton(
                    Icons.Filled.SkipPrevious, "Previous",
                    { player.seekToPreviousMediaItem(); touchControls() },
                    size = 60.dp, iconSize = 30.dp,
                )
            }
            if (isBuffering) {
                Spacer(Modifier.size(72.dp))
            } else {
                PlayerCircle(72.dp, { seekBy(-skipSec * 1000L); touchControls() }) { SkipBackIcon(skipSec) }
            }
            // Play/pause with Notscape's buffering treatment: while
            // buffering, the button fills and empties in a steady cycle
            // (tween 1600, Reverse) — a buffer has no honest progress.
            // Notscape's fill is primary at 0.35 over the frost circle,
            // and the icon is onSurface, not onPrimary: the old Gs
            // Primary box (primary background, onPrimary icon) is gone.
            PlayerCircle(96.dp, { if (isPlaying) player.pause() else player.play(); touchControls() }) {
                Box(
                    Modifier.size(96.dp).clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isBuffering) {
                        val cycle = rememberInfiniteTransition(label = "bufferFill")
                        val fillFrac = cycle.animateFloat(
                            initialValue = 0f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(animation = tween(1600), repeatMode = RepeatMode.Reverse),
                            label = "bufferFillFrac",
                        )
                        Box(
                            Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(fillFrac.value)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                        )
                    }
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
            if (isBuffering) {
                Spacer(Modifier.size(72.dp))
            } else {
                PlayerCircle(72.dp, { seekBy(skipSec * 1000L); touchControls() }) { SkipForwardIcon(skipSec) }
            }
            if (urls.size > 1) {
                PlayerIconButton(
                    Icons.Filled.SkipNext, "Next",
                    { player.seekToNextMediaItem(); touchControls() },
                    size = 60.dp, iconSize = 30.dp,
                )
            }
        }
    }
    @Composable
    fun scrubberRow(onVideo: Boolean = true) {
        // Over video the times are white on the scrim; in the driving
        // audio layout they sit on the theme surface, so they take the
        // theme's colours instead (white there would be invisible).
        val timeColor = if (onVideo) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
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
                    PlayerScrubber(
                        fraction = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f),
                        ink = timeColor,
                        onSeek = { fraction -> player.seekTo((fraction * dur).toLong()); touchControls() },
                    )
                }
                Text(
                    "−" + formatTime((dur - pos).coerceAtLeast(0L)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = timeColor,
                )
            }
        }
    }

    if (error != null) {
        // Error screen keeps the old pushed-down layout and always-visible
        // controls: there is nothing to auto-hide over. Close returns to
        // the browser, which is the car's Back here.
        Column(Modifier.fillMaxSize()) {
            PlayerScreenHeader(currentTitle, onClose)
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
                    androidx.compose.material3.Button(onClick = { error = null; retryKey++ }) { Text("Retry") }
                    androidx.compose.material3.OutlinedButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    } else if (moving && !passengerAllowed) {
        // Audio-only placeholder: no surface attached, so no video frame is sent.
        // This is the "Yes, I'm driving" branch of the passenger gate
        // (and the whole branch, before the gate existed).
        // Controls stay visible and reachable while driving — they control audio.
        // The options capsule is parked chrome; driving shows transport only.
        Column(Modifier.fillMaxSize()) {
            PlayerScreenHeader(currentTitle, onClose)
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
                    currentTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                scrubberRow(onVideo = false)
                Spacer(Modifier.height(12.dp))
                centreCluster()
                Spacer(Modifier.height(12.dp))
            }
        }
    } else {
        // Parked video: Notscape's iOS-26 player layout — one frosted capsule
        // of actions at the top-left, separate frosted transport circles in
        // the centre, and at the bottom a title block + options capsule over
        // a slim scrubber. Controls fade together and auto-hide while playing
        // (3.5s). Scrims/frost stand in for blur, which is never used over
        // the live stream (standing rule), even though a TextureView could
        // be backdrop-captured.
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (!listenMode) {
                AndroidView(
                    factory = { ctx ->
                        (LayoutInflater.from(ctx).inflate(R.layout.autospace_player_view, null) as PlayerView).apply {
                            playerViewRef = this
                        }
                    },
                    update = { pv ->
                        if (pv.player != player) pv.player = player
                        pv.resizeMode = when (scaleMode) {
                            ScaleMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            ScaleMode.FILL, ScaleMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            ScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        applySubtitleStyle(pv.context, pv)
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val eff = effScale()
                        scaleX = eff
                        scaleY = eff
                        translationX = panX
                        translationY = panY
                    },
                )
            } else {
                // Listen mode: headphones presentation, video surface gone.
                // Audio keeps playing from the same player; chrome stays up
                // (auto-hide is disabled in listen mode) and zoom is reset.
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.Headphones, contentDescription = null, tint = Color.White, modifier = Modifier.size(96.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(currentTitle, color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("Listen mode — audio continues", color = Color.White.copy(alpha = 0.7f))
                }
            }

            // Gesture layer, under the chrome so taps on buttons never reach it.
            // Four detectors, Notscape's arrangement: tap (with hold-to-scan),
            // drag (pan while zoomed), pinch counter, pinch transform.
            // NOT ported: phone brightness/volume drags (the car has its own
            // brightness slider and volume controls) and horizontal drag-seek
            // (the scrubber + skip buttons cover seeking; a car drag-seek is
            // imprecise and would fight pan-at-zoom).
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { offset ->
                                // Hold-to-scan lives in the SAME tap detector
                                // (Notscape Phase G repair): as a separate
                                // pointerInput it swallowed plain taps, so
                                // the controls only ever answered a slide.
                                // Arm after 480ms — a tap or double-tap
                                // releases long before that — then scan
                                // until the press ends.
                                val job = scanScope.launch {
                                    delay(480)
                                    startScan(if (offset.x < size.width / 2) -1 else 1)
                                }
                                tryAwaitRelease()
                                job.cancel()
                                stopScan()
                            },
                            onDoubleTap = { offset ->
                                val w = size.width
                                if (effScale() > 1.01f && offset.x > w / 3 && offset.x < 2 * w / 3) {
                                    // Zoomed: a centre double-tap resets the
                                    // zoom (side double-taps still seek).
                                    resetZoom()
                                    gestureLabel = "Zoom reset"
                                } else if (offset.x < w / 2) {
                                    player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                                    gestureLabel = "-10s"
                                } else {
                                    player.seekTo(player.currentPosition + 10_000)
                                    gestureLabel = "+10s"
                                }
                                touchControls()
                            },
                            onTap = {
                                if (controlsVisible) { controlsVisible = false; closeAllRows() }
                                else touchControls()
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, drag ->
                                if (transformActive) {
                                    // A pinch owns this gesture.
                                    change.consume()
                                    return@detectDragGestures
                                }
                                if (effScale() > 1.01f) {
                                    // Zoomed in: a one-finger drag pans the
                                    // picture (seek/brightness/volume return
                                    // at 1x — but the car has no surface
                                    // seek/brightness/volume gestures at
                                    // all, so at 1x a drag simply does
                                    // nothing).
                                    val eff = effScale()
                                    val maxX = (size.width * (eff - 1f) / 2f).coerceAtLeast(0f)
                                    val maxY = (size.height * (eff - 1f) / 2f).coerceAtLeast(0f)
                                    panX = (panX + drag.x).coerceIn(-maxX, maxX)
                                    panY = (panY + drag.y).coerceIn(-maxY, maxY)
                                    touchControls()
                                }
                                change.consume()
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        // Pinch detection: this Compose version's
                        // detectTransformGestures has no start/end callbacks,
                        // so "a pinch is happening" is tracked by counting
                        // pressed pointers directly (Notscape 0.0.83).
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                transformActive = event.changes.count { it.pressed } >= 2
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (!listenMode) {
                                pinchScale = (pinchScale * zoom).coerceIn(1f, 3f)
                                val eff = effScale()
                                val maxX = (size.width * (eff - 1f) / 2f).coerceAtLeast(0f)
                                val maxY = (size.height * (eff - 1f) / 2f).coerceAtLeast(0f)
                                panX = (panX + pan.x).coerceIn(-maxX, maxX)
                                panY = (panY + pan.y).coerceIn(-maxY, maxY)
                                touchControls()
                            }
                        }
                    },
            )

            if (gestureLabel.isNotEmpty()) {
                Box(Modifier.align(Alignment.Center)) {
                    Text(
                        gestureLabel,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)).padding(12.dp),
                    )
                }
            }

            // ---- Top capsule: Close + Stop. No phone PiP in a car
            // Presentation — Close player is the car equivalent.
            AnimatedVisibility(
                visible = controlsVisible && error == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Row(
                    Modifier.padding(16.dp)
                        .playerCapsule()
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerIconButton(Icons.Filled.Close, "Close player", onClose, size = 56.dp, iconSize = 28.dp)
                    PlayerIconButton(
                        Icons.Filled.Stop, "Stop playback",
                        { player.stop(); onClose },
                        size = 56.dp, iconSize = 28.dp,
                    )
                }
            }

            // ---- Centre transport: separate frosted circles.
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                centreCluster()
            }

            // ---- Bottom: title + options capsule, then the slim scrubber.
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                            ),
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    // One row open at a time: opening one closes the others.
                    if (showScaleRow && !listenMode) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Row(
                                Modifier.playerCapsule()
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ScaleMode.entries.forEach { m ->
                                    PlayerTextButton(
                                        m.label, selected = scaleMode == m,
                                        onClick = { touchControls(); applyScaleMode(m) },
                                    )
                                }
                            }
                        }
                    }
                    if (showQualityRow && (videoTracks.size > 1 || parsedQualities.size > 1)) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Row(
                                Modifier.playerCapsule()
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PlayerTextButton(
                                    "Auto", selected = selectedVideoId == null && selectedParsedBw == null,
                                    onClick = {
                                        touchControls()
                                        if (videoTracks.size > 1) selectVideo(null) else selectParsedQuality(null)
                                    },
                                )
                                if (videoTracks.size > 1) {
                                    videoTracks.forEach { choice ->
                                        PlayerTextButton(
                                            choice.label, selected = selectedVideoId == choice.id,
                                            onClick = { touchControls(); selectVideo(choice) },
                                        )
                                    }
                                } else {
                                    parsedQualities.forEach { q ->
                                        PlayerTextButton(
                                            q.label, selected = selectedParsedBw == q.bandwidth,
                                            onClick = { touchControls(); selectParsedQuality(q.bandwidth) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (showAudioRow && audioTracks.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Row(
                                Modifier.playerCapsule()
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                audioTracks.forEach { choice ->
                                    PlayerTextButton(
                                        choice.label, selected = selectedAudioId == choice.id,
                                        onClick = { touchControls(); selectAudio(choice) },
                                    )
                                }
                            }
                        }
                    }
                    if (showTextRow && textTracks.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Row(
                                Modifier.playerCapsule()
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PlayerTextButton(
                                    "Off", selected = subtitlesOff,
                                    onClick = { touchControls(); selectText(null) },
                                )
                                textTracks.forEach { choice ->
                                    PlayerTextButton(
                                        choice.label, selected = !subtitlesOff && selectedTextId == choice.id,
                                        onClick = { touchControls(); selectText(choice) },
                                    )
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            val host = remember(currentIndex) {
                                runCatching {
                                    android.net.Uri.parse(urls.getOrNull(currentIndex) ?: "").host ?: ""
                                }.getOrDefault("")
                            }
                            if (host.isNotBlank()) {
                                Text(
                                    host,
                                    color = Color.White.copy(alpha = 0.65f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                currentTitle,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        // Options capsule: listen, audio, quality, subtitles,
                        // scaling, speed, sleep. An active option's icon is
                        // tinted primary (Notscape's treatment); the scaling
                        // button is tinted whenever the mode is not Fit, and
                        // the sleep button whenever a timer is running.
                        Row(
                            Modifier.playerCapsule()
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlayerIconButton(
                                Icons.Filled.Headphones, if (listenMode) "Show video" else "Listen mode",
                                {
                                    touchControls()
                                    listenMode = !listenMode
                                    if (listenMode) { resetZoom(); showScaleRow = false }
                                },
                                size = 56.dp, iconSize = 28.dp, active = listenMode,
                            )
                            if (audioTracks.size > 1) {
                                PlayerIconButton(
                                    Icons.Filled.Audiotrack, "Audio track",
                                    { openRow("audio") },
                                    size = 56.dp, iconSize = 28.dp, active = showAudioRow,
                                )
                            }
                            if ((videoTracks.size > 1 || parsedQualities.size > 1) && !listenMode) {
                                PlayerIconButton(
                                    Icons.Filled.HighQuality, "Video quality",
                                    { openRow("quality") },
                                    size = 56.dp, iconSize = 28.dp,
                                    active = showQualityRow || selectedVideoId != null || selectedParsedBw != null,
                                )
                            }
                            if (textTracks.isNotEmpty()) {
                                PlayerIconButton(
                                    Icons.Filled.Subtitles, "Subtitles",
                                    { openRow("text") },
                                    size = 56.dp, iconSize = 28.dp, active = showTextRow || !subtitlesOff,
                                )
                            }
                            if (!listenMode) {
                                PlayerIconButton(
                                    Icons.Filled.AspectRatio, "Video scaling",
                                    { openRow("scale") },
                                    size = 56.dp, iconSize = 28.dp, active = showScaleRow || scaleMode != ScaleMode.FIT,
                                )
                            }
                            PlayerTextButton(
                                if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed}×",
                                selected = false,
                                onClick = { touchControls(); cycleSpeed() },
                            )
                            PlayerTextButton(
                                if (sleepMinutes == 0) "Sleep" else "${sleepMinutes}m",
                                selected = sleepMinutes != 0,
                                onClick = { touchControls(); cycleSleep() },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    scrubberRow()
                }
            }
        }
    }
    // Passenger gate, emitted after the branch so it draws above the
    // video, the audio-only placeholder and the error screen alike.
    // "Yes" is NOT onClose: it keeps the player open on the
    // audio-only branch; only "I'm a passenger" shows the picture.
    if (gateOpen) {
        PassengerGateDialog(
            onDriving = { gateOpen = false },
            onPassenger = { passengerAllowed = true; gateOpen = false },
        )
    }
}

// ---- Notscape player chrome, exempt from AutoSpace's GsTheme styling ----
// Phase 8 (Robert 2026-10-25 00:32, correcting Phase 6/7): the player must
// be basically the exact same player as Notscape Mariner's, so its chrome
// wears Notscape's playerCapsule — the kit frostBar material — and NOT a
// Gs control, and it must NOT vary with GsTheme (Frost/Flat/Tactile/
// Keycap). Colours come from MaterialTheme's scheme (neutral/dynamic per
// AppTheme), exactly as Notscape's do; on-video labels stay WHITE.
// Notscape's playerCapsule passes elevation 4dp (the kit default is 6dp);
// the car build uses 4dp to match Notscape's player, not the kit default.
// Car sizes are scale, not style, and stay: prev/next 60dp, skip 72dp,
// play-pause 96dp, options icons 56dp.
@Composable
private fun Modifier.playerCapsule(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(50)): Modifier =
    this.frostBar(shape, elevation = 4.dp)

/** A frosted circular transport button, Notscape's FrostCircle, car-scaled. */
@Composable
private fun PlayerCircle(size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.size(size).playerCapsule(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A frost-capsule icon button: tint primary only when active, else onSurface (Notscape's rule). */
@Composable
private fun PlayerIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 56.dp,
    iconSize: androidx.compose.ui.unit.Dp = 28.dp,
    active: Boolean = false,
) {
    Box(
        modifier = Modifier.size(size).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** A frost-row text button: primary + bold only when selected, else onSurface. */
@Composable
private fun PlayerTextButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * The player's slim scrubber (Notscape landscape language): a thin ink
 * track with a small round thumb, white over video, onSurface off video.
 * Tap or drag seeks; the seek commits on release, like GsSeekBar.
 */
@Composable
private fun PlayerScrubber(fraction: Float, ink: Color, onSeek: (Float) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)
    Box(
        modifier = Modifier.fillMaxWidth().height(36.dp)
            .pointerInput(Unit) {
                detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let(onSeek); dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50))
                .background(ink.copy(alpha = 0.3f)),
        )
        Box(
            Modifier.fillMaxWidth().height(4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier.fillMaxWidth(shown).fillMaxHeight().clip(RoundedCornerShape(50))
                    .background(ink),
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val thumbX = (maxWidth - 16.dp) * shown
            Box(
                Modifier.padding(start = thumbX).size(16.dp).clip(CircleShape).background(ink),
            )
        }
    }
}

/** The error/driving branches' header: plain Material3, no Gs control. */
@Composable
private fun PlayerScreenHeader(title: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
        }
    }
}

/** Skip-button faces (Notscape 0.0.9997): the baked glyph where Material
 * ships one (5/10/30), the plain curved arrow with the seconds beneath it
 * everywhere else on the ladder. Car-scaled up from Notscape's phone sizes. */
@Composable
private fun SkipBackIcon(sec: Int) {
    when (sec) {
        5 -> Icon(Icons.Filled.Replay5, "Back 5 seconds", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
        10 -> Icon(Icons.Filled.Replay10, "Back 10 seconds", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
        30 -> Icon(Icons.Filled.Replay30, "Back 30 seconds", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Undo, "Back $sec seconds", modifier = Modifier.size(26.dp))
            Text(sec.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun SkipForwardIcon(sec: Int) {
    when (sec) {
        5 -> Icon(Icons.Filled.Forward5, "Forward 5 seconds", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
        10 -> Icon(Icons.Filled.Forward10, "Forward 10 seconds", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
        30 -> Icon(Icons.Filled.Forward30, "Forward 30 seconds", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Redo, "Forward $sec seconds", modifier = Modifier.size(26.dp))
            Text(sec.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Player scaling modes (Notscape Phase C item 6): Fit, Stretch and Fill map
 * straight onto the Media3 resize modes; Crop is Fill (cover) with an extra
 * fixed 1.25x zoom on top, for a stronger centre crop. */
private enum class ScaleMode(val label: String) { FIT("Fit"), STRETCH("Stretch"), FILL("Fill"), CROP("Crop") }

/** One selectable audio/subtitle/quality track in the player's track rows. */
private data class TrackChoice(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val id: String,
)

/** A quality parsed from an HLS master playlist (Notscape 0.0.967). */
private data class ParsedQuality(
    val label: String,
    val bandwidth: Long,
)

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Notscape's subtitle styling (0.0.991), from the Video player settings
 * values. Applies only when at least one subtitle option is set — until
 * then the player's default (the system caption style) stands untouched.
 * Car limits: no Settings page writes these yet (reported follow-up), and
 * only system typefaces are mapped — Notscape's bundled opensans/inter
 * fonts and custom font files have no AutoSpace resource to load from. */
private fun applySubtitleStyle(context: android.content.Context, view: PlayerView) {
    val font = CarSettings.subtitleFont()
    val size = CarSettings.subtitleSize()
    val color = CarSettings.subtitleColor()
    val bg = CarSettings.subtitleBg()
    val edge = CarSettings.subtitleEdge()
    if (font.isEmpty() && size.isEmpty() && color.isEmpty() && bg.isEmpty() && edge.isEmpty()) return
    val sv = view.subtitleView ?: return
    val fg = when (color) {
        "yellow" -> 0xFFFFFF00.toInt()
        "cyan" -> 0xFF00FFFF.toInt()
        "green" -> 0xFF00FF00.toInt()
        "magenta" -> 0xFFFF00FF.toInt()
        "red" -> 0xFFFF0000.toInt()
        "blue" -> 0xFF0000FF.toInt()
        "black" -> 0xFF000000.toInt()
        else -> 0xFFFFFFFF.toInt()
    }
    val bgColor = when (bg) {
        "black50" -> 0x80000000.toInt()
        "black75" -> 0xBF000000.toInt()
        "black" -> 0xFF000000.toInt()
        "white75" -> 0xBFFFFFFF.toInt()
        else -> 0
    }
    val edgeType = when (edge) {
        "shadow" -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        "none" -> CaptionStyleCompat.EDGE_TYPE_NONE
        else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
    }
    val face = when (font) {
        "roboto" -> Typeface.create("sans", Typeface.NORMAL)
        "serif" -> Typeface.SERIF
        "mono" -> Typeface.MONOSPACE
        else -> null
    }
    sv.setStyle(CaptionStyleCompat(fg, bgColor, 0, edgeType, 0xFF000000.toInt(), face))
    val pct = size.toIntOrNull() ?: 100
    sv.setFractionalTextSize(0.0533f * pct / 100f)
}
