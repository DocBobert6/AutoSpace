package app.autospace.car

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.FrostedChromeAlpha
import app.autospace.car.theme.FrostedPanelSurface
import app.autospace.car.theme.GsColors
import app.autospace.car.theme.GsIconButton
import app.autospace.car.theme.GsKit
import app.autospace.car.theme.GsTone
import app.autospace.car.theme.cardShapeForLook
import app.autospace.car.theme.gsColors
import app.autospace.car.theme.gsLook
import app.autospace.car.theme.gsShape
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.autospace.media.ArtBlur
import app.autospace.media.ArtColor
import app.autospace.media.MediaArt
import app.autospace.media.NowPlaying
import app.autospace.media.SwipeSession
import app.autospace.media.nextSwipeSession
import app.autospace.media.orderedSwipeSessions

/**
 * Floating Home (r34): the map full-bleed with media as a floating card over
 * it. r97 graduation: this is the only Home now — the classic split is
 * gone except for the lyrics view.
 *
 * Per-session card dismissal: in-memory only, so a fresh process brings
 * the card back. It is deliberately NOT persisted: a dismissed card must
 * never be unrecoverable. r36's restore paths: a playback-start
 * transition (paused/stopped -> playing) re-enables it — see
 * [shouldResurfaceOnPlayTransition] and the CarHome effect that drives
 * it. r97 graduation removed the other two paths with the toggle and
 * the nav-bar pill; a dismissed card returns on the next drive or on
 * a pause-then-play edge.
 */
object FloatingHomeState {
    val dismissed = mutableStateOf(false)

    /**
     * Last observed playing value, for the r36 playback-start edge. Lives
     * here (not in a remember) so it survives recomposition; CarHome never
     * leaves the composition, so transitions are observed across screens.
     * Plain var, not state: only the CarHome effect reads/writes it.
     */
    var lastPlaying: Boolean = false

    fun dismiss() {
        dismissed.value = true
    }

    fun reset() {
        dismissed.value = false
    }
}

/**
 * Whether the floating card shows. Pure so it is unit-testable. Every gate
 * matters: something actually playing, the card not dismissed this
 * session, actually on Home, and never while the lyrics column owns
 * the side (lyrics falls back to the split instead). r97 graduation:
 * the card is the only media treatment now — no toggle gates it.
 */
fun shouldShowFloatingCard(
    isPlaying: Boolean,
    dismissed: Boolean,
    sidePanel: SidePanel,
    onHome: Boolean,
): Boolean = isPlaying && !dismissed && onHome && sidePanel != SidePanel.LYRICS





/**
 * Whether a playback-start transition re-enables a dismissed card. r36:
 * TRANSITION-gated, not level-gated. Dismiss-while-playing + track change
 * (playing stays true, no edge) must NOT pop the card back; dismiss-while-
 * paused + press play (false -> true edge) DOES bring it back. Dismiss-
 * while-playing + pause + resume also brings it back (edge on resume) —
 * accepted, documented. NEVER reset merely because playing is currently
 * true, or dismiss-while-playing breaks instantly.
 */
fun shouldResurfaceOnPlayTransition(wasPlaying: Boolean, isPlaying: Boolean): Boolean =
    !wasPlaying && isPlaying


/**
 * Card width: a glanceable card, not a panel — r42 narrows it to 40% of
 * the frame, capped at 380dp, floored at 240dp so transport still fits on
 * narrow frames, and never wider than the frame minus 24dp. Pure so it is
 * unit-testable.
 */
fun floatingCardWidth(frameWidth: Dp): Dp =
    (frameWidth * 0.40f).coerceIn(240.dp, 380.dp).coerceAtMost(frameWidth - 24.dp)

/**
 * r133: whether the art-backed card draws a kit edge. The art IS the
 * card face, so with art no perimeter decoration (Blueprint ring,
 * Silhouette halo, Pitlane panel border, OLED hairline) may stroke
 * over it; without art the card keeps exactly today's edge. Pure so
 * it is unit-testable.
 */
fun artCardDrawsPerimeter(hasArt: Boolean): Boolean = !hasArt

/**
 * r136: the art-backed card casts no shadow. r133 removed every kit
 * edge stroke behind the perimeter flag (Blueprint ring, Silhouette
 * halo, Pitlane panel border, OLED hairline), and the 4dp Surface
 * shadow was the one perimeter effect left: a soft outline around
 * the card over the map. With art the art IS the face, so the card
 * draws nothing at its edge; without art the card keeps exactly
 * today's 4dp shadow. Pure so it is unit-testable.
 */
fun cardArtElevation(hasArt: Boolean): Dp = if (hasArt) 0.dp else 4.dp

/**
 * r136: whether an album-art tint is strong enough to color the
 * floating player's controls — the same 0.30 saturation bar the
 * progress accent uses, so muted and duotone covers fall back to
 * today's scheme treatment instead of tinting the card grey. Pure
 * so it is unit-testable.
 */
fun floatingArtUsable(tint: ArtColor.Tint?): Boolean =
    tint != null && tint.saturation >= 0.3f

/** The floating player's art-derived palette: fills plus the glyph on each fill. */
class FloatingArtPalette(
    val primary: Color,
    val primaryGlyph: Color,
    val secondary: Color,
    val secondaryGlyph: Color,
    val waveformTrack: Color,
)

/**
 * r136: the floating player's colors, taken from the playing track's
 * album art and from nothing else — no scheme token feeds them. The
 * play fill and waveform fill take the art's hue at a vivid fixed
 * lightness; previous, next, and the X take the same hue muted and
 * darker, so the row keeps its play-loudest hierarchy. The lightness
 * is fixed rather than theme-derived on purpose: the card no longer
 * reads the device theme at all. Null when the art gives no usable
 * tint — the caller then keeps exactly today's scheme treatment.
 * Pure so it is unit-testable.
 */
fun floatingArtPalette(tint: ArtColor.Tint?): FloatingArtPalette? {
    if (!floatingArtUsable(tint)) return null
    val usable = tint ?: return null
    val primary = Color.hsl(usable.hue, usable.saturation.coerceIn(0.4f, 0.7f), 0.55f)
    val secondary = Color.hsl(usable.hue, usable.saturation.coerceIn(0.3f, 0.5f), 0.32f)
    return FloatingArtPalette(
        primary = primary,
        primaryGlyph = floatingArtGlyph(primary),
        secondary = secondary,
        secondaryGlyph = floatingArtGlyph(secondary),
        waveformTrack = primary.copy(alpha = 0.30f),
    )
}

/**
 * Opaque ARGB for a Compose color, from its own channels — no Android
 * dependency, so unit tests can feed it to [contrastRatio].
 */
fun floatingColorArgb(color: Color): Int {
    fun ch(v: Float): Int = (v * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(color.red) shl 16) or (ch(color.green) shl 8) or ch(color.blue)
}

/**
 * Glyph color on an art fill: black or white, whichever contrasts
 * more against the fill by WCAG ratio. One of the two always reaches
 * 4.58:1 or better (the two ratios meet at luminance 0.179), so the
 * glyph never sits dark-on-dark or light-on-light. Pure so it is
 * unit-testable.
 */
fun floatingArtGlyph(fill: Color): Color {
    val fillArgb = floatingColorArgb(fill)
    val whiteRatio = contrastRatio(floatingColorArgb(Color.White), fillArgb)
    val blackRatio = contrastRatio(floatingColorArgb(Color.Black), fillArgb)
    return if (whiteRatio >= blackRatio) Color.White else Color.Black
}

/**
 * r42: dismiss button sizing — the glyph and drawn box shrink, the touch
 * target does not. Pure values so the floor is unit-testable: the drawn
 * box (36dp) stays centered in the 44dp touch target, and the glyph
 * (20dp) stays centered in the drawn box.
 */
internal val FloatingCardDismissTouchSize: Dp = 44.dp
internal val FloatingCardDismissDrawnSize: Dp = 36.dp
internal val FloatingCardDismissGlyphSize: Dp = 20.dp

/**
 * r71 (A28): minimum horizontal travel for an info-area drag to count as a
 * session swipe rather than a tap wobble. 24dp sits well above touch slop
 * and well below a deliberate swipe; below it the drag is ignored and the
 * tap (open the full player) proceeds exactly as today.
 */
internal val FloatingCardSessionSwipeMinDp: Dp = 24.dp


/**
 * The floating media card. Ordinary Compose surface in the window layer
 * above the map SurfaceView (same mechanism as HomeFrameOverlay) — no
 * map-pipeline, VirtualDisplay, or CarAppStage changes, so it survives
 * CarAppStage's deliberate SurfaceView recreation as an independent node.
 *
 * Frosted approximation, NOT live blur: live blur over a SurfaceView is
 * impossible (a SurfaceFlinger layer cannot be sampled by an overlay), so
 * the card is a translucent layer + panel texture in the frost-bar
 * language, never claimed as blur. The content itself stays opaque for
 * glanceability.
 *
 * r129: when album art is available, the artwork itself is the card's
 * background, drawn OPAQUE with only a very slight blur — replacing
 * r97's 0.88-alpha treatment, which averaged the art into an
 * unrecognizable wash over the map and read as scheme fill. No scheme
 * fill sits underneath the art (the panel fill is transparent when art
 * is present); no scrim or overlay is added. No art means no background
 * image: the card keeps exactly today's background (pinned 0.88 fill).
 *
 * Turn-zone clearance is structural, not numeric: the card is bottom-
 * anchored in the Home Box while the nav bar (which carries the turn pill
 * on non-Home screens) sits below that Box in-flow, and on Home the nav
 * app draws its own turn UI at the top of its surface — a compact
 * bottom-end card cannot reach either. No numeric "turn zone" is invented
 * here; the device test list checks it visually.
 *
 * Content is glance + transport only while moving (same rule as the pill
 * and the docked column): title/artist/source, progress, prev/play/next.
 * No browsing, typing, or queue editing from the card. Tapping the info
 * area (outside the controls) opens the full player, exactly like tapping
 * the pill's art does today. The dismiss button hides the card for the
 * session; the pill comes back, and cycling the toggle re-enables it.
 */
@Composable
internal fun FloatingMediaCard(now: NowPlaying, modifier: Modifier = Modifier) {
    val navigator = LocalCarNavigator.current
    val media = CarServices.media
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val art by produceState(now.art, now.art, now.artUri) { value = now.art ?: MediaArt.load(context, now.artUri) }
    // r60 (A27): blurred background, computed once per art change —
    // remember keys on the resolved art, and URI art is additionally
    // cached in ArtBlur, so track changes do not jank and leaving and
    // re-entering composition does not recompute. Null art (or a
    // failed blur) means no background image: the card keeps exactly
    // today's background. r129: the card passes ArtBlur's slight
    // radius, not the heavy default — the art must stay recognizable,
    // not wash out to a tint. r133: the card also passes ArtBlur's
    // own 256px working size — the 64px copy upscaled 6–12x was the
    // wash, not the radius. r138: the blurred backdrop follows the
    // SAME bitmap the sharp layer uses, keyed by that bitmap alone —
    // the URI is no longer a composition key, and ArtBlur's cache key
    // is now bound to the bitmap's identity and generation, so a URI
    // that is unchanged or lags one track behind can never return a
    // previous track's blurred image for a new bitmap. A track change
    // that changes the art bitmap changes the backdrop in the same
    // composition as the sharp cover.
    val blurredArt = remember(art) { art?.let { ArtBlur.forArt(it, now.artUri, ArtBlur.CARD_BLUR_RADIUS, ArtBlur.CARD_SAMPLE_MAX) } }
    // r136: the sharp cover, whole and undistorted, plus the palette
    // derived from the same art. The blurred layer below is drawn
    // cropped to the card's wide aspect, so a square cover lost its
    // top and bottom and read as squished; the sharp layer is fitted
    // inside the card bounds instead, so the cover looks like itself,
    // sitting on its own blurred fill (no map gaps at the sides, no
    // scrim). The palette is null when the art gives no usable tint —
    // the card then keeps exactly today's scheme treatment.
    val sharpArt = remember(art) { art?.asImageBitmap() }
    val artTint by produceState<ArtColor.Tint?>(null, art) {
        value = art?.let { withContext(Dispatchers.Default) { runCatching { ArtColor.of(it) }.getOrNull() } }
    }
    val artPalette = floatingArtPalette(artTint)
    // r48: the card takes the style corner under Verve (24dp) via the
    // look-fed card rule; every other style keeps its exact shape.
    val cardShape = cardShapeForLook(gsLook(), GsKit.CardShape)
    // r46: the card reads the shared frosted panel surface — same shape,
    // elevation, fill, texture, border and Pitch hairline as before, now
    // shared with the frosted map chrome so the two cannot drift.
    // r71 (A28): the swipe threshold in pixels, resolved once per composition.
    val swipeThresholdPx = with(LocalDensity.current) { FloatingCardSessionSwipeMinDp.toPx() }
    Box(modifier) {
        // r129: the artwork itself is the card's background now,
        // drawn OPAQUE with only a very slight blur (ArtBlur radius 2
        // on the card's own 256px working copy — r133: the 64px copy
        // upscaled 6–12x was the wash, not the radius) — no scheme
        // fill underneath (the panel fill is transparent when art is
        // present) and no 0.88 float alpha on the art. r97's 0.88
        // treatment averaged the art into a wash over the map, which
        // is why the card read as scheme fill instead of album art.
        // No scrim or overlay is added: title and controls sit
        // directly on the blurred art. Without art the card keeps
        // exactly today's background (pinned 0.88 fill, no image).
        // The art is drawn at the card's size (matchParentSize) and
        // cropped, then upscaled with bilinear filtering; the working
        // copy is 256px, so the ~2–3x upscale keeps the art readable.
        // Null art (or a failed blur) draws nothing. r136 adds a sharp
        // fitted layer over this backdrop (see above) — the backdrop
        // stays cropped, the cover itself is fitted.
        blurredArt?.let {
            Image(
                it, null,
                Modifier.matchParentSize().clip(cardShape),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
                alpha = 1f,
            )
        }
        // r136: sharp fitted cover over the blurred fill (see above).
        // Fit, never Crop or FillBounds: the cover keeps its own
        // proportions and shows whole. Null art draws nothing.
        sharpArt?.let {
            Image(
                it, null,
                Modifier.matchParentSize().clip(cardShape),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.Medium,
                alpha = 1f,
            )
        }
        FrostedPanelSurface(
            shape = cardShape,
            elevation = cardArtElevation(blurredArt != null),
            fillAlpha = if (blurredArt != null) 0f else FrostedChromeAlpha,
            // r133: with art, the art IS the card face — no kit edge
            // (ring, halo, panel border, OLED hairline) may stroke
            // over it. Without art the card keeps exactly today's edge.
            perimeter = artCardDrawsPerimeter(blurredArt != null),
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Info area only: tap-through to the full player. Transport,
                    // progress, and dismiss are controls, so they sit outside
                    // this clickable and never open the player by accident.
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { navigator.media() }
                            .pointerInput(swipeThresholdPx) {
                                // r71 (A28): the session swipe lives on the
                                // info row ONLY. The seek bar below already
                                // owns horizontal drags (tap + drag seek)
                                // and the transport row owns taps, so a
                                // card-wide swipe would fight the seek bar;
                                // the info row today only taps (open the
                                // full player), and a drag cancels that tap,
                                // so the two never fire together. Dismissal
                                // is a tap on the X button, never a swipe,
                                // so a session swipe can never dismiss the
                                // card and a dismiss can never switch
                                // sessions. Swipe left = next session,
                                // swipe right = previous, wrapping; below
                                // the threshold nothing happens and the tap
                                // proceeds. Without notification access the
                                // list is empty and the swipe is a no-op.
                                var totalDx = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { totalDx = 0f },
                                    onDragEnd = {
                                        if (abs(totalDx) >= swipeThresholdPx) {
                                            val apps = media.activeSwipeSessions()
                                            val ordered = orderedSwipeSessions(
                                                apps.map { SwipeSession(it.component.packageName, it.label) },
                                            )
                                            val current = CarServices.mediaApp.value?.component?.packageName
                                            val target = nextSwipeSession(current, ordered, forward = totalDx < 0f)
                                            val app = target?.let { t ->
                                                apps.firstOrNull { it.component.packageName == t.packageName }
                                            }
                                            if (app != null) CarServices.openMedia(app)
                                        }
                                    },
                                    onDragCancel = { totalDx = 0f },
                                ) { _, dragAmount -> totalDx += dragAmount }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            // r42: the title wraps to two lines — the extra
                            // height that makes the narrower card slightly
                            // taller. Artist and source stay single-line.
                            // Very long titles still truncate at two lines.
                            // r136: on the art-backed card the text is
                            // white, never scheme-derived and never
                            // art-tinted — the art face is busy enough
                            // without the text joining it. Without art
                            // the card keeps exactly today's scheme text.
                            Text(
                                now.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                color = if (blurredArt != null) Color.White else scheme.onSurface,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                now.artist, style = MaterialTheme.typography.bodyMedium,
                                color = if (blurredArt != null) Color.White.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            val source = media.appLabel?.takeIf { it.isNotEmpty() } ?: now.album
                            if (source.isNotEmpty()) {
                                Text(
                                    source, style = MaterialTheme.typography.labelMedium,
                                    color = if (blurredArt != null) Color.White.copy(alpha = 0.65f) else scheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    // r42: visually smaller dismiss — a 36dp drawn button
                    // with a 20dp glyph, centered in the KEPT 44dp touch
                    // target. GsIconButton draws its touch target at its own
                    // size, so the outer Box carries the 44dp target; both
                    // call dismiss, which is an idempotent boolean set, so a
                    // tap lands exactly one dismissal either way.
                    Box(
                        Modifier.size(FloatingCardDismissTouchSize).clickable { FloatingHomeState.dismiss() },
                        contentAlignment = Alignment.Center,
                    ) {
                        // r136: the X takes the art palette's secondary
                        // fill and its contrasting glyph when the art
                        // gives a usable tint; otherwise it keeps
                        // exactly today's neutral treatment.
                        GsIconButton(
                            Icons.Filled.Close, "Hide media card", FloatingHomeState::dismiss,
                            colors = artPalette?.let { GsColors(it.secondary, it.secondaryGlyph) } ?: gsColors(GsTone.Neutral),
                            size = FloatingCardDismissDrawnSize, iconSize = FloatingCardDismissGlyphSize,
                        )
                    }
                }
                if (now.durationMs > 0) {
                    // r136: waveform and times on the art palette when
                    // the art gives a usable tint; times stay white on
                    // the art-backed card, never art-tinted. Without a
                    // usable tint the progress keeps exactly today's
                    // scheme treatment.
                    PlayerProgress(
                        now, media, Modifier.fillMaxWidth(), showTimes = true, waveform = true,
                        accent = artPalette?.primary, waveformTrack = artPalette?.waveformTrack,
                        timeColor = if (blurredArt != null) Color.White else null,
                    )
                } else {
                    Spacer(Modifier.height(6.dp))
                }
                // r42: card transport follows the nav-bar button shape
                // (gsShape()), not circles — the full player and CompactPlayer
                // pass nothing and keep their circles.
                // r136: the card's transport takes the art palette when
                // the art gives a usable tint — play the vivid fill,
                // previous/next the muted fill, each with its
                // contrasting glyph. Otherwise it keeps exactly today's
                // scheme treatment.
                TransportRow(
                    now, media, skip = 48.dp, modifier = Modifier.fillMaxWidth(), shape = gsShape(),
                    accent = artPalette?.primary, accentContent = artPalette?.primaryGlyph,
                    secondaryColors = artPalette?.let { GsColors(it.secondary, it.secondaryGlyph) },
                )
            }
        }
    }
}
