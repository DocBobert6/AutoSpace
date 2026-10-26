package app.autospace.car

import android.media.session.PlaybackState
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.autospace.car.theme.GsColors
import app.autospace.car.theme.GsIconBox
import app.autospace.car.theme.GsIconButton
import app.autospace.car.theme.GsSeekBar
import app.autospace.car.theme.GsTone
import app.autospace.car.theme.gsColors
import app.autospace.car.theme.gsLook
import app.autospace.car.theme.stateChangeMotionSpec
import app.autospace.media.ArtColor
import app.autospace.media.CarMedia
import app.autospace.media.CustomAction
import app.autospace.media.MediaArt
import app.autospace.media.NowPlaying
import app.autospace.notify.CarMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sin

/*
 * The pieces every player on the car screen is built from - the media screen, the column beside
 * the map - so a button looks and behaves the same wherever it turns up.
 */

/** How many bars the waveform scrubber draws across the track width. */
internal const val WAVEFORM_BAR_COUNT = 48

/** Bars lit at [fraction] of the track: floor, clamped, so 0% lights none and 100% lights all. */
internal fun filledWaveformBars(fraction: Float, bars: Int = WAVEFORM_BAR_COUNT): Int =
    (fraction.coerceIn(0f, 1f) * bars).toInt().coerceIn(0, bars)

/**
 * A bar's height as a fraction of the scrubber height: a fixed tasteful pattern, deterministic
 * per index. r51 bars are STYLED, not the track's real waveform — they never claim otherwise.
 * Smooth variation between 0.35 and 1.0, never flat, never zero.
 */
internal fun waveformBarHeightFraction(index: Int, bars: Int = WAVEFORM_BAR_COUNT): Float {
    val i = index.coerceIn(0, (bars - 1).coerceAtLeast(0))
    val v = abs(sin(i * 1.7f) * 0.6f + sin(i * 0.61f) * 0.4f)
    return 0.35f + 0.65f * v
}

/** An x position in a scrubber of [width] pixels as a seek fraction, clamped; zero width seeks to 0. */
internal fun seekFractionForX(x: Float, width: Float): Float =
    if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)

/**
 * The scrubber as waveform-styled bars (r51 hybrid): filled bars in [accent], the rest neutral.
 * Gesture mirrors [GsSeekBar] exactly — tap seeks at once, a drag previews and commits on
 * release — so only the drawing changes, never the feel. No thumb: the filled edge IS the
 * position. No swipe handler lives here: a drag only ever produces a seek fraction, never a
 * source change (the full-player no-swipe rule).
 */
@Composable
internal fun WaveformSeekBar(
    fraction: Float,
    onSeek: (Float) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)
    val filled = filledWaveformBars(shown)
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    // A taller touch area than the bars themselves, so it's easy to hit on a car screen.
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(Unit) { detectTapGestures { onSeek(seekFractionForX(it.x, size.width.toFloat())) } }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = seekFractionForX(it.x, size.width.toFloat()) },
                            onDragEnd = { dragging?.let(onSeek); dragging = null },
                            onDragCancel = { dragging = null },
                        ) { change, _ -> dragging = seekFractionForX(change.position.x, size.width.toFloat()) }
                    },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val gap = 3.dp.toPx()
            val barWidth = ((size.width - gap * (WAVEFORM_BAR_COUNT - 1)) / WAVEFORM_BAR_COUNT).coerceAtLeast(1f)
            val step = barWidth + gap
            for (i in 0 until WAVEFORM_BAR_COUNT) {
                val barHeight = size.height * waveformBarHeightFraction(i)
                drawRoundRect(
                    color = if (i < filled) accent else track,
                    topLeft = Offset(i * step, (size.height - barHeight) / 2),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
                )
            }
        }
    }
}

/**
 * The album-adaptive accent for the progress tint + play/pause button (r51 hybrid), or null
 * when the art gives nothing usable — null means "scheme primary", exactly as before, never a
 * near-grey wash. A tint counts as usable only at saturation 0.30+, so muted/duotone covers
 * fall back instead of tinting the player grey.
 */
internal fun playerAccentColor(tint: ArtColor.Tint?, dark: Boolean): Color? {
    if (tint == null || tint.saturation < 0.3f) return null
    return Color.hsl(tint.hue, tint.saturation.coerceIn(0.4f, 0.7f), if (dark) 0.65f else 0.45f)
}

/** Content color on an accent container: black on light accents, white on dark ones. */
internal fun accentContentColor(accent: Color): Color =
    if (accent.luminance() > 0.5f) Color.Black else Color.White

/**
 * The current track's album-adaptive accent, resolved once per art (cached per art, never per
 * frame) — null when the art is missing or neutral, which the callers read as scheme primary.
 */
@Composable
internal fun rememberPlayerAccent(now: NowPlaying): Color? {
    val art = rememberArt(now)
    val tint by produceState<ArtColor.Tint?>(null, art) {
        value = art?.let { withContext(Dispatchers.Default) { runCatching { ArtColor.of(it) }.getOrNull() } }
    }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return playerAccentColor(tint, dark)
}

/**
 * The play/pause button's corner (r51 hybrid morph): fully round while playing, the style's
 * own corner while paused — round morph targets under rounded styles, square under Pitlane
 * (6dp). Pure so it is unit-testable; the animation itself lives in [TransportRow].
 */
internal fun playMorphCorner(playing: Boolean, styleCorner: Dp, playSize: Dp): Dp =
    if (playing) playSize / 2 else styleCorner

/**
 * The seek bar, with elapsed and total time under it when [showTimes]. [waveform] swaps the
 * capsule bar for waveform bars (full player only — the floating card keeps the capsule);
 * [accent] tints the waveform fill, defaulting to the scheme primary.
 */
@Composable
internal fun PlayerProgress(now: NowPlaying, media: CarMedia, modifier: Modifier = Modifier, showTimes: Boolean = true, waveform: Boolean = false, accent: Color? = null) {
    if (now.durationMs <= 0) return
    var position by remember { mutableLongStateOf(now.currentPosition()) }
    LaunchedEffect(now) {
        while (true) {
            position = now.currentPosition()
            if (!now.playing) break
            delay(500)
        }
    }
    Column(modifier) {
        val fraction = position.toFloat() / now.durationMs
        val seekTo = { f: Float -> media.seek((f * now.durationMs).toLong()) }
        val enabled = now.canDo(PlaybackState.ACTION_SEEK_TO)
        if (waveform) {
            WaveformSeekBar(
                fraction = fraction,
                onSeek = seekTo,
                accent = accent ?: MaterialTheme.colorScheme.primary,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        } else {
            GsSeekBar(
                fraction = fraction,
                onSeek = seekTo,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        if (showTimes) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatDuration(now.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "1:05" for under a minute past the hour, "1:01:05" once an hour is involved. */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** r42: TransportRow buttons are circles unless the caller passes a shape — the floating card passes gsShape() so its buttons match the nav-bar buttons. Kept as a named default so the default itself is unit-testable. */
internal val DefaultTransportShape: Shape = CircleShape

/**
 * Previous, play/pause, next — or, with [showSeek], the video player's centred 5-button
 * cluster: Previous / -10s / Play (largest) / +10s / Next, 18dp apart like video's, play >
 * seek > prev/next mirroring video's 96/72/60 at skip = 60dp. [skip] sizes prev/next; play
 * is a step bigger (x1.25) in the 3-button row. [shape] defaults to circles. [morphPlay]
 * morphs the play button's corner on play-state change (round while playing, style corner
 * while paused), with the Verve motion language — restrained spring parked, instant snap
 * while moving — for every style, per the hybrid spec (not gated on the Verve theme); a
 * mid-animation moving flip does not retroactively re-spec, the r48 edge. [accent] tints
 * the play button (null = scheme primary, exactly as before). Seek buttons hide when there
 * is no duration (live/radio) and grey out when seeking is unsupported; every other button
 * stays in place greyed when unavailable, so the row never shifts. The floating card passes
 * neither flag, so its compact 3-button row is byte-for-byte today's.
 */
@Composable
internal fun TransportRow(now: NowPlaying, media: CarMedia, skip: Dp, modifier: Modifier = Modifier, shape: Shape = DefaultTransportShape, showSeek: Boolean = false, morphPlay: Boolean = false, accent: Color? = null) {
    val play = if (showSeek) skip * 1.6f else skip * 1.25f
    val seekSize = skip * 1.2f
    val arrangement = if (showSeek) Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally) else Arrangement.SpaceEvenly
    // Always animated (cheap, one Dp) so the branch below never changes composition order;
    // the result is only used when morphPlay is on.
    val playCorner by animateDpAsState(
        targetValue = playMorphCorner(now.playing, gsLook().corner, play),
        animationSpec = stateChangeMotionSpec(CarMotion.moving),
        label = "play morph",
    )
    val playShape = if (morphPlay) RoundedCornerShape(playCorner) else shape
    Row(modifier, horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
        GsIconButton(
            MediaIcons.Previous, "Previous", media::previous,
            enabled = now.canDo(PlaybackState.ACTION_SKIP_TO_PREVIOUS), size = skip, iconSize = skip * 0.58f, shape = shape,
        )
        if (showSeek && now.durationMs > 0) {
            val seekEnabled = now.canDo(PlaybackState.ACTION_SEEK_TO)
            GsIconBox(
                onClick = { media.seek((now.currentPosition() - 10_000).coerceIn(0, now.durationMs)) },
                modifier = Modifier.size(seekSize),
                enabled = seekEnabled,
                shape = shape,
            ) {
                Text("-10", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        GsIconButton(
            if (now.playing) MediaIcons.Pause else Icons.Filled.PlayArrow,
            if (now.playing) "Pause" else "Play",
            media::togglePlay,
            colors = if (accent != null) GsColors(accent, accentContentColor(accent)) else gsColors(GsTone.Primary),
            size = play, iconSize = play * 0.58f, shape = playShape,
        )
        if (showSeek && now.durationMs > 0) {
            val seekEnabled = now.canDo(PlaybackState.ACTION_SEEK_TO)
            GsIconBox(
                onClick = { media.seek((now.currentPosition() + 10_000).coerceIn(0, now.durationMs)) },
                modifier = Modifier.size(seekSize),
                enabled = seekEnabled,
                shape = shape,
            ) {
                Text("+10", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        GsIconButton(
            MediaIcons.Next, "Next", media::next,
            enabled = now.canDo(PlaybackState.ACTION_SKIP_TO_NEXT), size = skip, iconSize = skip * 0.58f, shape = shape,
        )
    }
}

/**
 * Like, shuffle and repeat, then up to [maxOther] of the app's other buttons, all icon-only. One
 * the app doesn't support stays on screen greyed out, so the row never shifts between apps.
 * [trailing] adds buttons of the caller's own at the end (the side column's lyrics button).
 */
@Composable
internal fun PlayModeRow(
    now: NowPlaying,
    media: CarMedia,
    size: Dp,
    modifier: Modifier = Modifier,
    maxOther: Int = 0,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val modes by media.modes.collectAsState()
    val liked = now.liked
    val shuffleKnown = modes.shuffle != PlaybackStateCompat.SHUFFLE_MODE_INVALID
    val repeatKnown = modes.repeat != PlaybackStateCompat.REPEAT_MODE_INVALID
    val shuffleOn = shuffleKnown && modes.shuffle != PlaybackStateCompat.SHUFFLE_MODE_NONE
    val repeatOn = repeatKnown && modes.repeat != PlaybackStateCompat.REPEAT_MODE_NONE
    val icon = size * 0.52f
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        // An app's own like, shuffle or repeat button shows whether it's on only through its icon,
        // so that icon is drawn, as Android Auto does, and lit when its name says it's on.
        // Gearslip's own stand in for apps that report a heart rating or shuffle and repeat modes.
        val likeAction = now.likeAction
        if (likeAction != null) {
            CustomActionButton(likeAction, media, size, toggle = true)
        } else {
            GsIconButton(
                if (liked == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                if (liked == true) "Unlike" else "Like",
                media::toggleLike,
                enabled = liked != null,
                tone = if (liked == true) GsTone.Primary else GsTone.Neutral,
                size = size, iconSize = icon,
            )
        }
        val shuffleAction = now.shuffleAction
        if (shuffleAction != null) {
            CustomActionButton(shuffleAction, media, size, toggle = true)
        } else {
            GsIconButton(
                MediaIcons.Shuffle, if (shuffleOn) "Shuffle off" else "Shuffle on",
                media::toggleShuffle,
                enabled = shuffleKnown,
                tone = if (shuffleOn) GsTone.Primary else GsTone.Neutral,
                size = size, iconSize = icon,
            )
        }
        val repeatAction = now.repeatAction
        if (repeatAction != null) {
            CustomActionButton(repeatAction, media, size, toggle = true)
        } else {
            GsIconButton(
                if (modes.repeat == PlaybackStateCompat.REPEAT_MODE_ONE) MediaIcons.RepeatOne else MediaIcons.Repeat,
                "Repeat",
                media::cycleRepeat,
                enabled = repeatKnown,
                tone = if (repeatOn) GsTone.Primary else GsTone.Neutral,
                size = size, iconSize = icon,
            )
        }
        now.otherActions.take(maxOther).forEach { CustomActionButton(it, media, size) }
        trailing()
    }
}

/**
 * One of the app's own buttons, drawn with the app's own icon - it lives in the app's package,
 * not Gearslip's, so it's loaded from there. Its name stands in if the icon won't load.
 */
@Composable
private fun CustomActionButton(action: CustomAction, media: CarMedia, size: Dp, toggle: Boolean = false) {
    val icon = rememberActionIcon(action)
    GsIconBox(
        onClick = { media.custom(action) },
        modifier = Modifier.size(size),
        colors = gsColors(if (toggle && action.on) GsTone.Primary else GsTone.Neutral),
    ) {
        if (icon != null) {
            Icon(icon, action.name, Modifier.size(size * 0.52f))
        } else {
            Text(action.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun rememberActionIcon(action: CustomAction): ImageBitmap? {
    val context = LocalContext.current
    val app by CarServices.mediaApp.collectAsState()
    val pkg = app?.component?.packageName
    val icon by produceState<ImageBitmap?>(null, pkg, action.icon) {
        value = if (pkg == null || action.icon == 0) null else withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getResourcesForApplication(pkg)
                    .getDrawable(action.icon, null).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }
    return icon
}

/** The track's art, from the session's bitmap or, failing that, its art URI. */
@Composable
internal fun rememberArt(now: NowPlaying): android.graphics.Bitmap? {
    val context = LocalContext.current
    val art by produceState(now.art, now.art, now.artUri) { value = now.art ?: MediaArt.load(context, now.artUri) }
    return art
}

/**
 * A background taken from the album art's main colour, in place of [fallback]. Only the hue and a
 * capped amount of its saturation come from the art; the lightness stays close to the theme's
 * own ([darkLightness] or [lightLightness]), so text drawn in the theme's colours reads the same
 * on any cover. Art with no real colour in it keeps [fallback]. Fades between songs rather than
 * jumping.
 */
@Composable
internal fun rememberArtColor(
    art: android.graphics.Bitmap?,
    fallback: Color,
    darkLightness: Float = 0.2f,
    lightLightness: Float = 0.86f,
): Color {
    val dark = fallback.luminance() < 0.5f
    val tint by produceState<ArtColor.Tint?>(null, art) {
        value = art?.let { withContext(Dispatchers.Default) { runCatching { ArtColor.of(it) }.getOrNull() } }
    }
    val target = tint?.let {
        if (dark) Color.hsl(it.hue, it.saturation.coerceIn(0.3f, 0.5f), darkLightness)
        else Color.hsl(it.hue, it.saturation.coerceIn(0.35f, 0.55f), lightLightness)
    } ?: fallback
    return animateColorAsState(target, tween(600), label = "art colour").value
}
