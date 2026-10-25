package app.autospace.mirror

/**
 * Pure tap-vs-drag exit decision for mirror touches (Phase 10 mirror
 * fix). No Android types, so unit tests cover it directly.
 *
 * The old relay stopped the mirror on ANY finger-up that landed on
 * letterbox padding or in the Fill exit corner — including a drag that
 * STARTED on phone content and wandered off it, and a drag that started
 * on the exit button and slid away. On a portrait phone in a landscape
 * car the Fit bars are ~73% of the frame width, so almost any touch
 * outside the narrow content strip closed the session; in Fill, any
 * drag ending in the top-right corner did the same. That is what
 * Robert hit: "touching the car screen just closed the session".
 *
 * New exit rule, for both modes: the mirror stops ONLY on a deliberate
 * tap — finger DOWN and finger UP both inside the exit region, with
 * no move out of it in between:
 *  - Fill: the exit region is the drawn GL exit button (top-right
 *    corner, [MirrorGeometry.exitRegion]). Every other touch relays
 *    to the phone, including drags that end in the corner.
 *  - Fit: the exit region is the letterbox padding (the mapper returns
 *    null there). A tap on the padding exits; a drag from content
 *    that ends on padding does NOT — its UP relays at the last
 *    content point, so the phone gesture completes instead of hanging.
 * The phone notification's Stop action remains as a backup exit, and
 * the head-unit BACK key exits too (Phase 9 follow-on).
 *
 * The caller supplies [map] (frame point -> phone point, null on
 * padding) and [inExit] (frame point inside the Fill exit button)
 * per event; this class only decides. A gesture that was swallowed
 * from its DOWN (it started on the button/padding) never relays, even
 * if it later wanders onto content — its DOWN never reached the phone,
 * so an UP there would be a dangling release.
 */
class MirrorExitTracker {
    enum class RelayAction { DOWN, MOVE, UP }
    enum class StopReason { EXIT_BUTTON, PADDING_TAP }

    sealed interface Decision {
        data class Stop(val reason: StopReason) : Decision
        data object Swallow : Decision
        data class Relay(val action: RelayAction, val x: Float, val y: Float) : Decision
    }

    private var pendingExit = false
    private var pendingPadding = false
    private var relaying = false
    private var lastX = 0f
    private var lastY = 0f

    fun down(
        x: Float,
        y: Float,
        inExit: Boolean,
        map: (Float, Float) -> Pair<Float, Float>?,
    ): Decision {
        pendingExit = false
        pendingPadding = false
        relaying = false
        if (inExit) {
            pendingExit = true
            return Decision.Swallow
        }
        val p = map(x, y)
        if (p == null) {
            pendingPadding = true
            return Decision.Swallow
        }
        relaying = true
        lastX = p.first
        lastY = p.second
        return Decision.Relay(RelayAction.DOWN, p.first, p.second)
    }

    fun move(
        x: Float,
        y: Float,
        inExit: Boolean,
        map: (Float, Float) -> Pair<Float, Float>?,
    ): Decision {
        if (pendingExit) {
            // A drag out of the button cancels the exit tap; the
            // gesture stays swallowed (its DOWN never reached the phone).
            if (!inExit) pendingExit = false
            return Decision.Swallow
        }
        if (pendingPadding) {
            if (map(x, y) != null) pendingPadding = false
            return Decision.Swallow
        }
        if (!relaying) return Decision.Swallow
        // Dragged off content: hold the last content point and relay
        // nothing, so the UP below can still complete the gesture.
        val p = map(x, y) ?: return Decision.Swallow
        lastX = p.first
        lastY = p.second
        return Decision.Relay(RelayAction.MOVE, p.first, p.second)
    }

    fun up(
        x: Float,
        y: Float,
        inExit: Boolean,
        map: (Float, Float) -> Pair<Float, Float>?,
    ): Decision {
        val stopExit = pendingExit && inExit
        val stopPadding = pendingPadding && map(x, y) == null
        pendingExit = false
        pendingPadding = false
        if (stopExit) {
            relaying = false
            return Decision.Stop(StopReason.EXIT_BUTTON)
        }
        if (stopPadding) {
            relaying = false
            return Decision.Stop(StopReason.PADDING_TAP)
        }
        if (!relaying) return Decision.Swallow
        relaying = false
        val p = map(x, y)
        return if (p != null) {
            lastX = p.first
            lastY = p.second
            Decision.Relay(RelayAction.UP, p.first, p.second)
        } else {
            Decision.Relay(RelayAction.UP, lastX, lastY)
        }
    }

    fun cancel(): Decision {
        pendingExit = false
        pendingPadding = false
        return if (relaying) {
            relaying = false
            Decision.Relay(RelayAction.UP, lastX, lastY)
        } else {
            Decision.Swallow
        }
    }
}
