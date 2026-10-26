package app.autospace.car

/**
 * What the browser passenger gate should do on one 1-second motion poll.
 *
 * r47 — the gate no longer opens on the first moving poll, and never over an
 * active typing session:
 *
 * 1. SUSTAINED MOTION. [app.autospace.notify.CarMotion] judges motion from a
 *    single latest GPS speed sample (over 2 m/s, fresh within 15 s), and GPS
 *    fixes arrive at most every 2 s. A stationary phone's drift spike therefore
 *    reads as "moving" for the life of one bad fix — roughly 2–3 consecutive
 *    1 s polls — which is exactly how the gate popped mid-session over the
 *    keyboard in car preview, where the car was not moving at all. Requiring
 *    [REQUIRED_STREAK] consecutive moving polls (4, about 3 s after the first
 *    moving poll) means one bad fix can never open the gate; real driving
 *    sustains speed for minutes, so its gate only arrives ~3 s later than
 *    before. That delay is the stated cost, and the safety rule is unchanged:
 *    the gate still appears before any browsing while genuinely moving.
 * 2. NEVER OVER TYPING. If the streak completes while a keyboard is up, the
 *    gate is DEFERRED, not dropped: it opens the moment typing ends (a poll
 *    with typing inactive while still moving). An interrupted entry is what
 *    was wrong, not the gate itself — the gate is still required before
 *    browsing while moving, and a submit or dismiss ends typing, so the
 *    deferral cannot outlast the entry.
 * 3. STOPS CLOSE AN UNANSWERED GATE, as before: a not-moving poll clears a
 *    pending deferral and closes an open gate. A granted allowance suppresses
 *    everything until the session ends (the caller owns the allowance).
 *
 * Pure state machine, no Compose, no clock of its own — the caller feeds one
 * poll per second, so every path is unit-testable. One instance per browser
 * session (remembered in the browser composable); a fresh entry starts clean.
 */
enum class GateSignal { NONE, OPEN, CLOSE }

class PassengerGateTrigger(
    private val requiredStreak: Int = REQUIRED_STREAK,
) {
    private var streak = 0
    private var pending = false
    private var open = false

    /**
     * One poll. [moving] is the current motion answer, [typingActive] whether
     * any car keyboard is up, [passengerAllowed] whether the session already
     * holds a passenger allowance. Returns OPEN when the gate should appear
     * now, CLOSE when an open gate should go away (car stopped), else NONE.
     */
    fun onPoll(moving: Boolean, typingActive: Boolean, passengerAllowed: Boolean): GateSignal {
        if (passengerAllowed) {
            streak = 0
            pending = false
            open = false
            return GateSignal.NONE
        }
        if (!moving) {
            streak = 0
            pending = false
            return if (open) {
                open = false
                GateSignal.CLOSE
            } else {
                GateSignal.NONE
            }
        }
        streak++
        if (typingActive) {
            if (streak >= requiredStreak) pending = true
            return GateSignal.NONE
        }
        if (pending || streak >= requiredStreak) {
            pending = false
            open = true
            return GateSignal.OPEN
        }
        return GateSignal.NONE
    }

    companion object {
        /** Consecutive moving polls before the gate may open (see class KDoc). */
        const val REQUIRED_STREAK = 4
    }
}
