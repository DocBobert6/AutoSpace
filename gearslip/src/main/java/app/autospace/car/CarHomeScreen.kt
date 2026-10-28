package app.autospace.car

import app.autospace.car.theme.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import app.autospace.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import app.autospace.media.MediaArt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import app.autospace.host.CarAppConnection
import app.autospace.media.CarMedia
import app.autospace.media.NowPlaying
import app.autospace.notify.CarMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The home screen: the map full-bleed, with the floating media card
 * and the widget column over it. The map
 * is whatever navigation app was used last, brought back on its own.
 * The one exception is lyrics: it keeps a split (map + lyrics column),
 * since lyrics is a view the driver explicitly opened.
 */
@Composable
/**
 * r97: [mapSurfaceStrip] is the start-side strip the steering-side
 * rail occupies, else 0.dp — the scaffold passes it so Home's map
 * surface can extend under the rail while the chrome stays inset.
 * r99: [mapSurfaceBottomStrip] is the same for the Meridian bottom
 * pill's strip, else 0.dp — the two strips are never both nonzero.
 */
fun CarHome(mapSurfaceStrip: Dp, mapSurfaceBottomStrip: Dp = 0.dp) {
    val frame by CarEnvironment.frame.collectAsStateWithLifecycle()
    val navStatus by CarServices.nav.status.collectAsStateWithLifecycle()
    val phase by CarServices.media.phase.collectAsStateWithLifecycle()
    val now by CarServices.media.now.collectAsStateWithLifecycle()
    val autoplayOffer by CarServices.autoplayOfferVisible.collectAsStateWithLifecycle()
    val widgetsDismissed by CarSettings.widgetsDismissed.collectAsStateWithLifecycle()
    val hiddenWidgets by CarSettings.hiddenWidgets.collectAsStateWithLifecycle()
    val navTemplate by CarServices.nav.template.collectAsStateWithLifecycle()
    val navPanMode by CarServices.nav.panMode.collectAsStateWithLifecycle()
    val mediaCardDock by CarSettings.mediaCardDock.collectAsStateWithLifecycle()
    val dockEnabled by CarSettings.mediaCardDockEnabled.collectAsStateWithLifecycle()
    // r106: motion poll for the media-card drag gate, plus the
    // free-drag offset and the card's measured size. CarMotion.moving
    // is a plain value, not a flow — poll it 1 s like MiniVideo and
    // the widget column do (GPS only updates every 2 s). This poll
    // lives here, not in the widget column's block, because the card
    // slot sits outside that block; its name differs from that
    // block's scoped `moving` on purpose, so nothing shadows.
    // The offset and size are session-only: remember in CarHome,
    // never written to CarSettings, so a dragged position resets
    // when Home leaves composition and survives card hide/show,
    // mini video, and live-widget swaps. Both are in dp, matching
    // the slot's dp-space geometry (see MediaCardDrag).
    val cardDragMoving by produceState(CarMotion.moving) {
        while (true) { value = CarMotion.moving; delay(1_000) }
    }
    var mediaCardDragOffsetDp by remember { mutableStateOf(Offset.Zero) }
    var mediaCardDragSizeDp by remember { mutableStateOf(Offset.Zero) }
    val miniVideoItem by MiniVideoState.active.collectAsStateWithLifecycle()
    val miniVideoGateOpen by MiniVideoState.gateOpen.collectAsStateWithLifecycle()
    // r118: mini card size + free-drag offset and measured size.
    // Session-only, like the media card's: remember in CarHome,
    // never written to CarSettings, so a dragged position resets
    // when the item changes (the next video starts in its slot)
    // and survives card hide/show. Both are in dp, matching the
    // slot's dp-space geometry (see MediaCardDrag).
    val miniVideoSize by MiniVideoState.size.collectAsStateWithLifecycle()
    var miniVideoDragOffsetDp by remember { mutableStateOf(Offset.Zero) }
    var miniVideoDragSizeDp by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(miniVideoItem?.url) { miniVideoDragOffsetDp = Offset.Zero }
    // r97: the rail is the only app bar; a non-zero strip means it is showing.
    val railVisible = mapSurfaceStrip > 0.dp

    LaunchedEffect(Unit) { CarServices.autostart(frame) }
    LaunchedEffect(phase) { if (phase == CarMedia.Phase.READY) CarServices.autoplayIfDue() }
    // r36 restore: a playback-start transition (paused/stopped -> playing) re-enables a
    // card dismissed earlier in the session, so pressing play after a stop brings the
    // card back without reaching for Settings. Transition-gated, not level-gated:
    // dismiss-while-playing + track change (playing stays true) does NOT pop it back.
    // CarHome never leaves the composition (other screens cover it), so transitions
    // are observed across screens, including the music app. The previous value lives
    // in FloatingHomeState, not a remember, so it survives recomposition.
    LaunchedEffect(now.playing) {
        if (shouldResurfaceOnPlayTransition(FloatingHomeState.lastPlaying, now.playing)) FloatingHomeState.reset()
        FloatingHomeState.lastPlaying = now.playing
    }
    // r62 (A50): the turn-autoplay-off offer answers itself after 30 s
    // of no answer, like a dismissal — the driver is asked again only
    // after three more prompt stops, never every drive.
    LaunchedEffect(autoplayOffer) {
        if (autoplayOffer) {
            delay(30_000)
            CarServices.autoplayOfferKeep()
        }
    }

    val navigator = LocalCarNavigator.current
    val panel = navigator.sidePanel

    // Coming back to the map from another screen: the map stayed loaded underneath, so its surface
    // is only replaced if it died meanwhile. The first composition is skipped, since the surface
    // is only just being lent.
    val onHome = navigator.current == CarScreen.Home
    val seenHome = remember { booleanArrayOf(false) }
    LaunchedEffect(onHome) {
        if (onHome && seenHome[0]) CarServices.nav.replaceDeadSurface()
        if (onHome) seenHome[0] = true
        // r58: leaving Home ends a glance recall — a glance is a peek,
        // not a mode, so coming back while still moving re-hides the
        // column until the button is pressed again.
        if (!onHome) WidgetDrivingGlance.clear()
    }
    val splitScreen = phase == CarMedia.Phase.READY && now.isActive && panel != SidePanel.NONE

    // r97 graduation: Home is the full-bleed map with floating cards,
    // always. The lyrics column is the one exception — it keeps a
    // split, since lyrics is a view the driver explicitly opened, not
    // a player placement.
    // r75: the mini video card takes the floating card's slot
    // while it is showing, so two cards never share one corner; the
    // audio card returns when the mini video is dismissed. r97
    // graduation: PiP is regular now, so the card shows whenever a
    // mini video is active.
    val showMiniVideo = miniVideoItem != null && onHome
    // r140: the live conversation widget is retired — the
    // AssistantOverlay is the one conversation surface again, so
    // the floating-card slot has no widget branch anymore.
    // Precedence is mini video first (hiding it would stop
    // playback — its player lives in composition), then the audio
    // card, which now stays visible during a conversation exactly
    // as it did before the widget existed.
    val showFloatingCard = shouldShowFloatingCard(
        phase == CarMedia.Phase.READY && now.isActive,
        FloatingHomeState.dismissed.value, panel, onHome,
    ) && !showMiniVideo
    // r69: mini-player docking (A44/A45). The trip bar
    // sits at the bottom-start corner during guidance (see
    // NavigationChrome), so the effective dock steps a Start dock
    // aside to today's placement while it is showing — no other
    // chrome moves to fit a docked position.
    val etaBarVisible = !navPanMode && (navTemplate as? NavigationTemplate)?.destinationTravelEstimate != null
    val cardDock = effectiveMediaCardDock(dockEnabled, mediaCardDock, etaBarVisible)
    // r77: card-clearance START veto. A Start card would land on the trip bar's Stop
    // control during guidance (the r69 fallback exists for this) and under a
    // driver-side rail (the rail occupies the left edge). The veto is
    // conservative: it ignores the column yield, so it sometimes picks CENTER
    // where START would also have cleared — safe, never a cover. The rail's
    // turn-card yield needs no separate term: a turn card showing means
    // guidance, and guidance already vetoes START through the trip bar.
    val turnCardRouting = (navTemplate as? NavigationTemplate)?.navigationInfo as? RoutingInfo
    val turnCardShowing = onHome && turnCardRouting != null &&
        (turnCardRouting.isLoading || turnCardRouting.currentStep != null)
    val clearanceStartAllowed = !etaBarVisible &&
        !(railVisible && !turnCardShowing)
    // r37 widget layer: column over the full-bleed map base only (the
    // lyrics split hides it).
    // r59 restack: the column no longer steps aside for nav templates —
    // it moves to the passenger side when the template's own chrome
    // occupies the driver side, and hides only for full-screen content
    // templates, where no corner coexists (see widgetColumnPlacement).
    // The placement is computed once and feeds both visibility and side,
    // so the two can never disagree.
    val widgetPlacement = widgetColumnPlacement(navTemplate, navPanMode)
    val showWidgetColumn = shouldShowWidgetColumn(
        widgetsDismissed, onHome, panel, widgetPlacement,
    )

    // Phase 8: 12dp outer padding (so rounded corners actually show against
    // the backdrop), 12dp gap, map 1.75 / media 1.0 — the media pane is
    // ~36% of the width instead of ~32%, about 22px wider on an 800px
    // screen once padding and gap are counted. With nothing playing the
    // map still takes the full row (minus the padding), as before.
    // Phase 10: the audit video's full-width map showed NO padding or
    // rounded corners at all — a SurfaceView is its own SurfaceFlinger
    // layer: if that layer ever spans the full frame (it is lent at
    // frame size in places, and the app keeps drawing into it), it
    // paints straight over the Row's padding area. So the Home frame
    // is drawn here, in the window layer above the SurfaceView: an
    // opaque backdrop-coloured frame with a rounded inner cut, over
    // the whole Row. When the SurfaceView is correctly pane-sized
    // this frame paints backdrop over backdrop and changes nothing;
    // when it is full-frame, this frame is what restores the 12dp
    // padding and 20dp corners. It takes no pointer input, so map
    // gestures pass through untouched.
    // 2026-10-25 (map-corner dots): the pane-level corner masks that
    // used to sit here are GONE, deliberately. On a real head unit
    // they showed as four visible dots at the map's corners: when the
    // SurfaceView spans the full frame, the masks (painted at the
    // pane's inset corners, in backdrop colour) land in the middle of
    // map video instead of over its corners, so each one reads as a
    // dot punched into the map — and even when the surface IS
    // pane-sized, the frame overlay below already rounds those same
    // corners, so the masks were redundant there. One frame only, no
    // pane masks: the dots have nowhere left to come from. Cost,
    // stated plainly: in split mode the map pane's INNER corners (the
    // ones against the media pane, which the whole-row frame does not
    // cover) go square instead of rounded. Square inner corners beat
    // dots on the map.
    Box(Modifier.fillMaxSize()) {
        if (panel != SidePanel.LYRICS) {
            // Full-bleed map: no Row padding and no frame overlay — the map
            // IS the background here, so masking its edges would defeat the
            // point. The card is an ordinary Compose surface in the window
            // layer above the map SurfaceView (same mechanism as
            // HomeFrameOverlay), so it survives CarAppStage's deliberate
            // SurfaceView recreation as an independent node. Bottom-end
            // (passenger side) reserves the driver side for the later
            // widget column; r42 moves it to 24dp in from the frame edges
            // (left and up). r97: no bottom bar exists anymore, so the
            // card clears with bottom padding of 24dp alone — and can
            // never cover the turn zone.
            Box(Modifier.fillMaxSize()) {
                MapPane(navStatus, frame, mapSurfaceStrip, mapSurfaceBottomStrip)
            }
            if (showFloatingCard) {
                // r77: the slot reads its frame here (not on the padded
                // BoxWithConstraints) so the clearance rule sees the
                // full frame; the inner Box keeps exactly today's
                // padding and alignment when the fix is off, so normal
                // frames are byte-identical.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val clearanceAlignment = mediaCardDockAlignment(
                        clearanceDock(
                            cardDock,
                            true, // r97 graduation: break 1 is regular now.
                            CardClearanceState.controls,
                            clearanceStartAllowed,
                            maxWidth.value, maxHeight.value,
                            floatingCardWidth(maxWidth).value,
                            CardClearanceState.cardHeightDp ?: 0f,
                            0f, // r97: no bottom bar strip anymore.
                        ),
                    )
                    // r77 fix: captured here, in the BoxWithConstraints
                    // scope — the inner Box content has neither the scope
                    // receiver (maxWidth) nor a composable context
                    // (LocalDensity.current) for the size callback.
                    val clearanceCardWidth = floatingCardWidth(maxWidth)
                    val clearanceDensity = LocalDensity.current
                    // r106 fix: the drag handler below lives in the inner
                    // Box content, which has neither the scope receiver
                    // (maxWidth) nor a composable context — same rule as
                    // the r77 captures above — so the frame size is
                    // captured here, in the scope, and the handler reads
                    // the locals.
                    val clearanceFrameWidth = maxWidth.value
                    val clearanceFrameHeight = maxHeight.value
                    // r109: the press-and-hold detector below is attached
                    // once (pointerInput(Unit)) and lives for the whole
                    // slot, so the geometry it reads must stay live
                    // across recompositions — the alignment and frame
                    // size are refreshed here on every composition
                    // instead of being captured once when the detector
                    // first starts.
                    val dragAlignment by rememberUpdatedState(clearanceAlignment)
                    val dragFrameWidth by rememberUpdatedState(clearanceFrameWidth)
                    val dragFrameHeight by rememberUpdatedState(clearanceFrameHeight)
                    // r111: the parked-gate inputs are read live the
                    // same way — dockEnabled and cardDragMoving are
                    // refreshed here on every composition, so the gate
                    // the detector enforces at arm time and on every
                    // move is always the current one, for the whole
                    // life of the once-attached detector node, and a
                    // gate closure can never strand a stale value in
                    // the detector. (Both inputs are delegated State
                    // reads, which already re-read live; this makes
                    // the liveness explicit instead of relying on
                    // delegate semantics — see MediaCardDrag.)
                    val dragDockEnabled by rememberUpdatedState(dockEnabled)
                    val dragMoving by rememberUpdatedState(cardDragMoving)
                    Box(
                        Modifier.fillMaxSize().padding(
                            start = MediaCardSlotPadDp.dp, top = MediaCardSlotPadDp.dp,
                            end = MediaCardSlotPadDp.dp, bottom = MediaCardSlotPadDp.dp,
                        ),
                        contentAlignment = clearanceAlignment,
                    ) {
                        FloatingMediaCard(
                            now,
                            Modifier.width(clearanceCardWidth)
                                .onSizeChanged {
                                    CardClearanceState.reportCardHeight(with(clearanceDensity) { it.height.toDp().value })
                                    mediaCardDragSizeDp = with(clearanceDensity) {
                                        Offset(it.width.toDp().value, it.height.toDp().value)
                                    }
                                }
                                // r109: press-and-hold to drag. The card
                                // drags only after a press-and-hold arms
                                // it; a release ends the drag, and the
                                // next press-and-hold arms it again.
                                // Taps still tap exactly as today, and
                                // ordinary (non-long-press) gestures on
                                // the children are untouched: a quick
                                // horizontal drag on the waveform still
                                // seeks and on the info row still swipes
                                // sessions, because movement before the
                                // long-press timeout cancels the arm
                                // wait before the card detector can claim
                                // the gesture. A long-press anywhere on
                                // the card — including on the waveform
                                // and info row — arms the card drag
                                // instead, since neither child consumes
                                // the down event. When docked the card
                                // sits exactly at its dock position
                                // (low during navigation, like every
                                // docked placement) and the stored
                                // offset is ignored, not cleared, so
                                // undocking restores it.
                                //
                                // Why the detector is attached
                                // unconditionally (r106 root cause): the
                                // r106 detector lived inside a
                                // conditional modifier — when the parked
                                // gate closed, the detector node left
                                // the chain entirely, so no grab point
                                // could drag until the gate reopened.
                                // The gate is now enforced inside the
                                // callbacks instead, so the detector
                                // survives a gate closure and works
                                // again the moment the gate reopens.
                                // r111: the gate inputs themselves are
                                // live (rememberUpdatedState above),
                                // read at arm time and on every move,
                                // so no stale gate value can ever live
                                // in the detector — and a lifecycle
                                // audit of the whole drag path (arm
                                // coverage, cancellation, slot
                                // composition, clamp, offset identity,
                                // children, ancestors) found no other
                                // one-and-done mechanism; see
                                // MediaCardDrag for the walk-through.
                                .pointerInput(Unit) {
                                    var armed = false
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            armed = mediaCardDragApplies(dragDockEnabled, dragMoving)
                                        },
                                        onDragEnd = { armed = false },
                                        onDragCancel = { armed = false },
                                    ) { change, dragAmount ->
                                        if (!armed) return@detectDragGesturesAfterLongPress
                                        // Re-check every move: a drag in
                                        // flight is cancelled the moment
                                        // motion starts (r106 rule).
                                        // The gate reads are live
                                        // (r111 rememberUpdatedState),
                                        // so this tracks reality for
                                        // the whole detector lifetime.
                                        if (!mediaCardDragApplies(dragDockEnabled, dragMoving)) {
                                            armed = false
                                            return@detectDragGesturesAfterLongPress
                                        }
                                        change.consume()
                                        val dx = dragAmount.x / density
                                        val dy = dragAmount.y / density
                                        mediaCardDragOffsetDp = clampMediaCardDragOffset(
                                            mediaCardDragOffsetDp.x + dx,
                                            mediaCardDragOffsetDp.y + dy,
                                            mediaCardAlignedLeftDp(
                                                dragAlignment,
                                                dragFrameWidth, mediaCardDragSizeDp.x, MediaCardSlotPadDp,
                                            ),
                                            dragFrameHeight - MediaCardSlotPadDp - mediaCardDragSizeDp.y,
                                            dragFrameWidth, dragFrameHeight,
                                            mediaCardDragSizeDp.x, mediaCardDragSizeDp.y,
                                            MediaCardDragMinVisibleDp,
                                        )
                                    }
                                }
                                .offset {
                                    if (dockEnabled) IntOffset.Zero
                                    else IntOffset(
                                        mediaCardDragOffsetDp.x.dp.roundToPx(),
                                        mediaCardDragOffsetDp.y.dp.roundToPx(),
                                    )
                                },
                        )
                    }
                }
            }
            if (showWidgetColumn) {
                // CarMotion.moving is a plain value, not a flow — poll it
                // like VideoPlayerScreen/CarWhatsNew do (1 s; GPS only
                // updates every 2 s). It gates the narrow-frame parked
                // exception AND the r58 hide-while-driving rule; the poll
                // runs only while the column could render, never
                // while the lyrics split is up.
                val moving by produceState(CarMotion.moving) {
                    while (true) { value = CarMotion.moving; delay(1_000) }
                }
                // r58: a glance ends when motion stops — the normal
                // rules resume from a stop, so the recall must not leak
                // into the next drive.
                LaunchedEffect(moving) { if (!moving) WidgetDrivingGlance.clear() }
                // r59 restack: the side follows the template — driver side
                // (start in LTR) on the bare map and while panning, passenger
                // side when guidance or a map pane occupies the driver side
                // (see widgetColumnPlacement). Top-anchored below the top
                // inset (proposal §3); the media card sits bottom-end, so
                // the two never collide. r97: there is no bottom bar
                // anymore; the column is top-anchored so it is
                // unaffected. The turn card stays clear because the
                // column moved, not
                // because it hid — checked visually on device (test list).
                // r58 hide-while-driving: a driving-hidden column renders
                // nothing at all, explainer included — a hidden column
                // must not explain itself mid-drive. The floating media
                // card is NOT covered by this rule and is untouched.
                if (!shouldHideWhileDriving(moving, WidgetDrivingGlance.recalled)) {
                    BoxWithConstraints(
                        Modifier.fillMaxSize().padding(12.dp),
                        contentAlignment = if (widgetPlacement == WidgetColumnPlacement.PASSENGER_SIDE) Alignment.TopEnd else Alignment.TopStart,
                    ) {
                        WidgetColumn(
                            maxWidth.value, moving, Modifier.width(widgetColumnWidth(maxWidth)),
                            hidden = hiddenWidgets,
                            dismissAtStart = widgetPlacement == WidgetColumnPlacement.PASSENGER_SIDE,
                        )
                    }
                }
            }
        } else {
        // r97 graduation: lyrics is the only split left — map plus
        // the lyrics column, with the frame overlay as before.
        Row(
            Modifier.fillMaxSize().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Audit (second video): the pane's clip is gone. It cannot
            // round a SurfaceView (a separate window layer). Corner
            // rounding comes from HomeFrameOverlay alone now (see the
            // 2026-10-25 note above: pane masks drew dots on a real
            // head unit and are deleted, not just disabled).
            Box(
                Modifier
                    .weight(if (splitScreen) 1.75f else 1f)
                    .fillMaxHeight(),
            ) {
                MapPane(navStatus, frame, mapSurfaceStrip, mapSurfaceBottomStrip)
            }
            if (splitScreen) {
                val side = Modifier.weight(1f).fillMaxHeight()
                SideLyrics(now, side)
            }
        }
        HomeFrameOverlay(Modifier.fillMaxSize())
        }
        // r62 (A50): the turn-autoplay-off offer, top-center so it never
        // covers the turn card, the edge controls, the widget column or
        // the media card. CarHome never leaves the composition, so it
        // shows over other screens too until answered or timed out.
        if (autoplayOffer) {
            Box(
                Modifier.fillMaxSize().padding(12.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                AutoplayOfferCard(
                    onTurnOff = CarServices::autoplayOfferTurnOff,
                    onKeep = CarServices::autoplayOfferKeep,
                )
            }
        }
        // r75: browser PiP — the mini video card floats over Home in
        // the same bottom-dock slot and width as the floating media
        // card (r97 graduation: PiP is regular now), and the passenger
        // gate dialog covers Home
        // while unanswered, exactly like the browser gate: video keeps
        // playing behind the dim until the driver answers Yes
        // (audio-only) or the passenger answers (video). Drawn after
        // the offer so the gate dialog sits above everything on Home;
        // the scaffold rail above this stays reachable, as it does
        // over the browser gate.
        if (showMiniVideo) {
            val miniItem = miniVideoItem
            if (miniItem != null) {
                // r77: same clearance slot as the audio card (see above). Moving the
                // mini card never disposes it — alignment is a layout parameter, not
                // a composition key — so playback continues across the step-aside.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // r118: the mini card's width follows its size now,
                    // and the clearance math uses that same width, so a
                    // grown card clears chrome exactly like the audio
                    // card does at its width.
                    val miniCardWidth = miniVideoCardWidthDp(miniVideoSize, maxWidth.value).dp
                    val miniFrameWidth = maxWidth.value
                    val miniFrameHeight = maxHeight.value
                    val clearanceAlignment = mediaCardDockAlignment(
                        clearanceDock(
                            cardDock,
                            true, // r97 graduation: break 1 is regular now.
                            CardClearanceState.controls,
                            clearanceStartAllowed,
                            maxWidth.value, maxHeight.value,
                            miniCardWidth.value,
                            CardClearanceState.cardHeightDp ?: 0f,
                            0f, // r97: no bottom bar strip anymore.
                        ),
                    )
                    // r77 fix: captured here, in the BoxWithConstraints
                    // scope — the inner Box content has neither the scope
                    // receiver (maxWidth) nor a composable context
                    // (LocalDensity.current) for the size callback.
                    val clearanceDensity = LocalDensity.current
                    Box(
                        Modifier.fillMaxSize().padding(
                            start = 24.dp, top = 24.dp, end = 24.dp,
                            bottom = 24.dp,
                        ),
                        contentAlignment = clearanceAlignment,
                    ) {
                        MiniVideoCard(
                            miniItem,
                            // r118: the drag clamp, mirroring the media
                            // card's exactly — same pure function, same
                            // 48dp minimum-visible strip, same 24dp
                            // slot pad (the slot's literal padding
                            // above is that same 24dp). The offset
                            // state is read live (a delegated State
                            // read) and the alignment/frame/size are
                            // fresh each composition, because the
                            // card reads this callback through
                            // rememberUpdatedState — the r111 lesson.
                            onMiniVideoDragDelta = { dx, dy ->
                                miniVideoDragOffsetDp = clampMediaCardDragOffset(
                                    miniVideoDragOffsetDp.x + dx,
                                    miniVideoDragOffsetDp.y + dy,
                                    mediaCardAlignedLeftDp(
                                        clearanceAlignment,
                                        miniFrameWidth, miniVideoDragSizeDp.x, MediaCardSlotPadDp,
                                    ),
                                    miniFrameHeight - MediaCardSlotPadDp - miniVideoDragSizeDp.y,
                                    miniFrameWidth, miniFrameHeight,
                                    miniVideoDragSizeDp.x, miniVideoDragSizeDp.y,
                                    MediaCardDragMinVisibleDp,
                                )
                            },
                            modifier = Modifier.width(miniCardWidth).onSizeChanged {
                                CardClearanceState.reportCardHeight(with(clearanceDensity) { it.height.toDp().value })
                                miniVideoDragSizeDp = with(clearanceDensity) {
                                    Offset(it.width.toDp().value, it.height.toDp().value)
                                }
                            }.offset {
                                IntOffset(
                                    miniVideoDragOffsetDp.x.dp.roundToPx(),
                                    miniVideoDragOffsetDp.y.dp.roundToPx(),
                                )
                            },
                        )
                    }
                }
            }
            if (miniVideoGateOpen) {
                PassengerGateDialog(
                    onDriving = { MiniVideoState.onDrivingAnswer() },
                    onPassenger = { MiniVideoState.onPassengerAnswer() },
                )
            }
        }
    }
}

/**
 * Opaque backdrop frame over the Home row: outer rect minus an inner
 * rounded rect (12dp inset, 20dp radius), drawn in the window layer so
 * it masks even a full-frame SurfaceView layer underneath. No pointer
 * handling — Canvas takes none — so it never intercepts a gesture.
 */
@Composable
private fun HomeFrameOverlay(modifier: Modifier = Modifier) {
    val backdrop = MaterialTheme.colorScheme.background
    Canvas(modifier) {
        val inset = 12.dp.toPx()
        val radius = 20.dp.toPx()
        val inner = Rect(inset, inset, size.width - inset, size.height - inset)
        val path = Path().apply {
            addRect(Rect(Offset.Zero, size))
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    inner,
                    topLeft = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                    topRight = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                    bottomLeft = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                    bottomRight = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                )
            )
            fillType = PathFillType.EvenOdd
        }
        drawPath(path, backdrop)
    }
}

/**
 * r97: [surfaceStartInset] extends the lent surface under the
 * steering-side rail's strip, so the map fills the strip instead of
 * the scaffold background showing through behind the floating rail.
 * The chrome sibling keeps the inset size, so no chrome moves.
 */
@Composable
private fun MapPane(
    status: CarAppConnection.Status,
    frame: CarEnvironment.Frame,
    surfaceStartInset: Dp = 0.dp,
    surfaceBottomInset: Dp = 0.dp,
) {
    val navigator = LocalCarNavigator.current
    if (status.phase == CarAppConnection.Phase.RUNNING) {
        // The map is a SurfaceView (its own SurfaceFlinger layer), so the
        // Box clip above rounds the Compose chrome but NOT the map itself
        // — Compose clip cannot round a separate window layer, and claiming
        // otherwise would be wrong. The outer corners are rounded for
        // real by HomeFrameOverlay (the whole-row frame, drawn above the
        // SurfaceView since a default SurfaceView renders behind the
        // window); nothing is drawn over the map pane itself any more,
        // so no mask can show up as a dot on it. Map gestures pass
        // through untouched: the frame takes no pointer input.
        Box(Modifier.fillMaxSize()) {
            CarAppStage(
                CarServices.nav, status, frame, embedded = true,
                surfaceStartInset = surfaceStartInset,
                surfaceBottomInset = surfaceBottomInset,
            ) { CarServices.stopNav() }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val hasLast = CarSettings.lastNav.value != null
        val hasNavApps by CarServices.hasNavApps.collectAsStateWithLifecycle()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        when (status.phase) {
            CarAppConnection.Phase.BINDING, CarAppConnection.Phase.HANDSHAKE ->
                Text("Starting ${status.app ?: "the map"}…", style = MaterialTheme.typography.titleMedium)
            CarAppConnection.Phase.REJECTED, CarAppConnection.Phase.FAILED -> {
                Text("${status.app ?: "The map app"} would not start.", style = MaterialTheme.typography.titleMedium)
                status.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                GsButton(onClick = navigator::apps, tone = GsTone.Tonal) { Text("Choose another app") }
            }
            else -> if (!hasNavApps) {
                // Nothing to auto-select and nothing to pick from Car apps either - this is the
                // only case worth a distinct screen, since every other IDLE case resolves itself.
                Text("No mapping apps found.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Install a navigation app that supports Android Auto and it will start here on its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (hasLast) {
                // IDLE with a last app on record means the driver stopped it themselves - it will
                // not come back on its own, so the screen must offer a way to bring it back rather
                // than sit on a "starting" message that never resolves.
                Text("Map stopped.", style = MaterialTheme.typography.titleMedium)
                GsButton(onClick = { scope.launch { CarServices.reconnectNav(frame) } }, tone = GsTone.Tonal) { Text("Reopen") }
                GsButton(onClick = { navigator.apps() }, tone = GsTone.Tonal) { Text("Car apps") }
            } else {
                // Autostart is still picking one - a navigation app is installed, so this resolves
                // to a "Starting..." state itself, in the next frame or two.
            }
        }
    }
}


/**
 * Lyrics alone beside the map, big enough to read at a glance. No art: that space goes to a
 * larger title and artist instead. The floating card stays hidden while
 * lyrics owns the side.
 */
@Composable
private fun SideLyrics(now: NowPlaying, modifier: Modifier) {
    val navigator = LocalCarNavigator.current
    val context = LocalContext.current
    val art by produceState(now.art, now.art, now.artUri) { value = now.art ?: MediaArt.load(context, now.artUri) }
    Surface(
        modifier = modifier,
        // r54 (D4): panel shape through the style rule, so Pitlane squares it.
        shape = gsButtonShape(RoundedCornerShape(20.dp)),
        color = rememberArtColor(art, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        now.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        now.artist, style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                GsIconButton(Icons.Filled.Close, "Hide lyrics", navigator::closeSidePanel, size = 36.dp, iconSize = 20.dp)
            }
            Spacer(Modifier.height(12.dp))
            LyricsPanel(now, Modifier.weight(1f).fillMaxWidth(), lineStyle = MaterialTheme.typography.headlineSmall)
        }
    }
}
