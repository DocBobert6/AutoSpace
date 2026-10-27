package app.autospace.car

import androidx.compose.ui.Alignment

/**
 * r69: mini-player docking (A44/A45). The floating media card is
 * bottom-anchored in all cases — A44's bottom-placement option and
 * A45's horizontal-alignment option meet in one bottom dock with
 * three horizontal positions. END is today's placement (bottom-end,
 * passenger side in a left-hand-drive car); CENTER centers the card;
 * START moves it to the opposite corner, which is the passenger side
 * in a right-hand-drive car.
 *
 * Start/End follow layout direction, they are not absolute left and
 * right: they map to Compose's Alignment.BottomStart/BottomEnd, which
 * resolve per locale direction, and the car code carries no explicit
 * LayoutDirection handling — today's BottomEnd already behaves this
 * way, so the choice inherits it rather than inventing a direction
 * rule. In a left-to-right locale, Start is left and End is right.
 */
enum class MediaCardDock {
    END,
    CENTER,
    START,
}

/** Parses a stored dock choice. Blank or unrecognized lands on END, today's placement. */
fun mediaCardDockFromStored(stored: String): MediaCardDock = when (stored) {
    MediaCardDock.END.name -> MediaCardDock.END
    MediaCardDock.CENTER.name -> MediaCardDock.CENTER
    MediaCardDock.START.name -> MediaCardDock.START
    else -> MediaCardDock.END
}

/**
 * r69: the docking gate. Pure, so the unit test pins it: the card
 * docks only when the driver has opted into experimental features as
 * a whole AND the stored choice says so. Either off returns the card
 * to exactly today's placement, and the stored choice is preserved,
 * never rewritten, while the gate is off.
 */
fun mediaCardDockNow(experimentalOn: Boolean, stored: MediaCardDock): MediaCardDock =
    if (experimentalOn) stored else MediaCardDock.END

/**
 * r69: the effective dock, gate plus collision fallback. During
 * guidance the trip bar sits at the bottom-start corner (see
 * NavigationChrome), so a Start dock would land exactly on top of it
 * and cover its Stop control. A Start dock therefore steps aside to
 * today's placement while the trip bar is showing, rather than
 * shifting any other chrome to fit. Center never lands on the trip
 * bar's corner, so it needs no fallback. Short frames are a separate,
 * unmeasured case: the column is top-anchored and the card
 * bottom-anchored, so they only meet on frames shorter than their
 * combined heights — the device list checks that visually rather than
 * inventing a numeric threshold here.
 */
fun effectiveMediaCardDock(experimentalOn: Boolean, stored: MediaCardDock, etaBarVisible: Boolean): MediaCardDock {
    val docked = mediaCardDockNow(experimentalOn, stored)
    return if (docked == MediaCardDock.START && etaBarVisible) MediaCardDock.END else docked
}

/** Maps a dock choice to the Home Box alignment. Pure, so the unit test pins it. */
fun mediaCardDockAlignment(dock: MediaCardDock): Alignment = when (dock) {
    MediaCardDock.END -> Alignment.BottomEnd
    MediaCardDock.CENTER -> Alignment.BottomCenter
    MediaCardDock.START -> Alignment.BottomStart
}
