package app.autospace.car

import androidx.compose.foundation.Image
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.FrostedPanelSurface
import app.autospace.car.theme.GsKit
import app.autospace.car.theme.GsIconButton
import app.autospace.car.theme.gsButtonShape
import app.autospace.car.theme.gsShape
import app.autospace.media.MediaArt
import app.autospace.media.NowPlaying

/**
 * Floating Home (r34): the map full-bleed with media as a floating card over
 * it, behind an experimental toggle that defaults OFF. Classic Home is
 * untouched when the toggle is off.
 *
 * Per-session card dismissal: in-memory only, so a fresh process (or cycling
 * the toggle off and back on, which resets it) brings the card back. It is
 * deliberately NOT persisted: a dismissed card must never be unrecoverable,
 * and the toggle itself is the re-enable path. r36 adds two more restore
 * paths, because toggle-cycling from Settings is unreachable while driving:
 * the nav-bar pill's dock button restores the card while dismissed in
 * floating mode, and a playback-start transition (paused/stopped -> playing)
 * re-enables it — see [shouldResurfaceOnPlayTransition] and the CarHome
 * effect that drives it.
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
 * matters: the toggle (default OFF), something actually playing, the card
 * not dismissed this session, actually on Home, and never while the lyrics
 * column owns the side (lyrics falls back to the classic split instead).
 */
fun shouldShowFloatingCard(
    floatingEnabled: Boolean,
    isPlaying: Boolean,
    dismissed: Boolean,
    sidePanel: SidePanel,
    onHome: Boolean,
): Boolean = floatingEnabled && isPlaying && !dismissed && onHome && sidePanel != SidePanel.LYRICS

/**
 * Whether the nav bar's now-playing pill steps aside for the floating card.
 * Mirrors the docked-column rule (pill steps aside when the player is
 * already on screen): the card IS the player on screen, so the pill hides
 * while it is visible and comes back the moment the card is dismissed.
 */
fun shouldStepAsidePillForFloatingCard(
    floatingEnabled: Boolean,
    dismissed: Boolean,
    onHome: Boolean,
): Boolean = floatingEnabled && !dismissed && onHome

/**
 * Whether the pill steps aside for the docked player column. r36: this
 * applies ONLY in classic mode. In floating mode the classic column NEVER
 * renders (floating branch = MapPane + card only), so letting this gate fire
 * there hid the pill whenever sidePanel was CONTROLS — docked before floating
 * was enabled, or dock pressed from another screen, which sets CONTROLS +
 * Home. Dismissed card + CONTROLS panel then meant no card AND no pill:
 * total media UI loss. In floating mode the floating gate above is the only
 * step-aside that may hide the pill.
 */
fun shouldStepAsidePillForDockedColumn(
    floatingEnabled: Boolean,
    sidePanel: SidePanel,
    onHome: Boolean,
): Boolean = !floatingEnabled && sidePanel == SidePanel.CONTROLS && onHome

/**
 * Whether the nav bar's now-playing pill shows, placement gates only (media
 * readiness — phase READY and a session active — is a separate gate in
 * NavNowPlaying). The pill hides exactly when it would repeat a player
 * already on screen: the docked column (classic mode only), the floating
 * card, or the full media screen itself.
 */
fun shouldShowNavPill(
    floatingEnabled: Boolean,
    dismissed: Boolean,
    sidePanel: SidePanel,
    onHome: Boolean,
    onMediaScreen: Boolean,
): Boolean = !shouldStepAsidePillForDockedColumn(floatingEnabled, sidePanel, onHome) &&
    !shouldStepAsidePillForFloatingCard(floatingEnabled, dismissed, onHome) &&
    !onMediaScreen

/**
 * Whether the pill's dock button restores the floating card instead of
 * docking. r36: in floating mode, while on Home and dismissed, the pill is
 * the only media UI on screen, so its dock button becomes the restore
 * button (reset dismissed + panel NONE, label "Show media card"). Everywhere
 * else the dock button keeps today's togglePlayerDock behavior and label.
 */
fun pillDockRestoresFloatingCard(
    floatingEnabled: Boolean,
    dismissed: Boolean,
    onHome: Boolean,
): Boolean = floatingEnabled && dismissed && onHome

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
 * r43: the nav bar overlays the map (instead of sitting below Home
 * in-flow) exactly when the floating Home is showing: on Home, floating
 * media on, and not the lyrics split — lyrics keeps the classic split
 * with the bar in-flow, and other screens keep the classic bar. Pure so
 * it is unit-testable; the scaffold and the card clearance both read it.
 */
fun shouldOverlayNavBar(onHome: Boolean, floatingOn: Boolean, panel: SidePanel): Boolean =
    onHome && floatingOn && panel != SidePanel.LYRICS

/**
 * Card width: a glanceable card, not a panel — r42 narrows it to 40% of
 * the frame, capped at 380dp, floored at 240dp so transport still fits on
 * narrow frames, and never wider than the frame minus 24dp. Pure so it is
 * unit-testable.
 */
fun floatingCardWidth(frameWidth: Dp): Dp =
    (frameWidth * 0.40f).coerceIn(240.dp, 380.dp).coerceAtMost(frameWidth - 24.dp)

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
 * The floating media card. Ordinary Compose surface in the window layer
 * above the map SurfaceView (same mechanism as HomeFrameOverlay) — no
 * map-pipeline, VirtualDisplay, or CarAppStage changes, so it survives
 * CarAppStage's deliberate SurfaceView recreation as an independent node.
 *
 * Frosted approximation, NOT live blur: live blur over a SurfaceView is
 * impossible (a SurfaceFlinger layer cannot be sampled by an overlay), so
 * the card is a translucent fill + panel texture in the frost-bar
 * language, never claimed as blur. The fill is translucent so the map
 * reads through faintly; the content itself stays opaque for glanceability.
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
    val cardShape = gsButtonShape(GsKit.CardShape)
    // r46: the card reads the shared frosted panel surface — same shape,
    // elevation, fill, texture, border and Pitch hairline as before, now
    // shared with the frosted map chrome so the two cannot drift.
    FrostedPanelSurface(
        shape = cardShape,
        elevation = 6.dp,
        modifier = modifier,
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
                        .clickable { navigator.media() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(scheme.surface),
                    ) {
                        art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                    Column(Modifier.weight(1f)) {
                        // r42: the title wraps to two lines — the extra
                        // height that makes the narrower card slightly
                        // taller. Artist and source stay single-line.
                        // Very long titles still truncate at two lines.
                        Text(
                            now.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            now.artist, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val source = media.appLabel?.takeIf { it.isNotEmpty() } ?: now.album
                        if (source.isNotEmpty()) {
                            Text(
                                source, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
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
                    GsIconButton(
                        Icons.Filled.Close, "Hide media card", FloatingHomeState::dismiss,
                        size = FloatingCardDismissDrawnSize, iconSize = FloatingCardDismissGlyphSize,
                    )
                }
            }
            if (now.durationMs > 0) {
                PlayerProgress(now, media, Modifier.fillMaxWidth(), showTimes = false)
            } else {
                Spacer(Modifier.height(6.dp))
            }
            // r42: card transport follows the nav-bar button shape
            // (gsShape()), not circles — the full player and CompactPlayer
            // pass nothing and keep their circles.
            TransportRow(now, media, skip = 48.dp, modifier = Modifier.fillMaxWidth(), shape = gsShape())
        }
    }
}
