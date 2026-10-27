package app.autospace.car

/**
 * Generation guard for superseded assistant turns (r72 barge-in).
 *
 * A turn is one listen → command → reply cycle. When a fresh listen starts
 * (a button press, a PTT press, or the hotword firing mid-reply) or the
 * overlay is dismissed, any older turn still running — its answer may still
 * be streaming in on an IO thread — is superseded: its late stream callbacks
 * must die instead of re-speaking an interrupted reply's tail, and its reply
 * must never be spoken once its command handler returns. The gate is just a
 * counter: [beginTurn] invalidates every generation captured before it, and
 * [isCurrent] tests a captured one. Pure, so it runs on the plain JVM.
 */
class TurnGate {
    private var generation = 0

    /** The generation of the turn currently in flight, if any. */
    fun current(): Int = generation

    /**
     * A new turn begins (or the current one is abandoned): invalidates every
     * generation captured before this call and returns the new one.
     */
    fun beginTurn(): Int {
        generation++
        return generation
    }

    /** True only when [generation] is still the current turn. */
    fun isCurrent(generation: Int): Boolean = generation == this.generation
}
