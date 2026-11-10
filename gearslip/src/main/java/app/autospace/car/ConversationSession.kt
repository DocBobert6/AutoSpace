package app.autospace.car

import android.os.SystemClock

/**
 * r73: multi-turn conversation (A59). A session is the completed
 * exchanges of one AI conversation: each turn is the user's utterance
 * as the model saw it plus the assistant's answer as spoken. Only
 * completed turns are ever recorded — the CarUi collector records a
 * turn only after the generation check passes, so a barged-in turn
 * superseded mid-flight (r72 TurnGate) contributes neither its
 * question nor its discarded tail, and an unmatched, refused,
 * or emergency reply is not an AI answer and is never recorded.
 * Action proposals are not recorded either: only plain answers, so a
 * follow-up that refers to a proposed action's target may dangle —
 * the honest v1 boundary, stated here rather than papered over with
 * local confirmation text dressed as model context.
 *
 * r117: built-in spoken replies join the session too, but
 * MULTI-TURN-GATED: a built-in turn records only when a session
 * already exists, so a lone one-shot command ("pause", "volume
 * up") never starts a session on its own, while a command spoken
 * inside an ongoing conversation ("what's the weather", asked as
 * a follow-up) extends the context later turns see. The gate is
 * the session itself — a built-in neither records nor ends a
 * session on its own, and a recorded built-in still counts
 * toward the caps and the timeout exactly like an AI turn.
 * A built-in command neither records nor ends a session: it is a side
 * action, not a conversation break, so the next AI turn still sees
 * the earlier exchanges. The session ends only on timeout, on
 * toggle-off, or with the process.
 *
 * Audit §6 T7, decided rule, stated so a future round does not
 * "fix" it: accepted-action results NEVER record. A proposal-acceptance
 * reply (the user saying yes to a confirmed AI action) is not
 * session-recorded, while the identical direct local command IS
 * recorded via [ConversationHistory.recordBuiltin] — the asymmetry is
 * deliberate (action proposals and confirmations are never recorded),
 * not a bug. The cost is honest: a follow-up that refers to a
 * proposed action's target may dangle, exactly as the v1 boundary
 * above already states for plain answers.
 *
 * r117: [ConversationTurn.fromAi] marks which turns were AI
 * answers, so surfaces that render the session (the live widget)
 * can show the AI badge on AI turns exactly like the overlay
 * does. Built-in turns carry false.
 */
data class ConversationTurn(val user: String, val assistant: String, val fromAi: Boolean = false)

/**
 * Pure session history: append, truncate, timeout, clear. No Android
 * imports, so the unit test pins it on the plain JVM. Times are
 * elapsed-realtime milliseconds supplied by the caller; the
 * [ConversationSession] holder supplies them.
 */
class ConversationHistory {
    companion object {
        /** Turns kept: 8 exchanges = 16 messages, well inside the model context. */
        const val MAX_TURNS = 8

        /**
         * Total history characters kept, about 3k tokens — room left for system, reply, and search results.
         * Audit §6 T6, decided: an oversized single turn TRUNCATES to this cap (assistant text
         * first, then the user text) instead of dropping whole — the old cap loop dropped a
         * lone over-cap turn entirely while still advancing the activity timestamp.
         */
        const val MAX_HISTORY_CHARS = 12_000

        /** Silence between AI turns that ends the session. Judgment call: 10 minutes. */
        const val SESSION_TIMEOUT_MS = 10 * 60 * 1000L
    }

    private val turns = mutableListOf<ConversationTurn>()
    private var lastActivityMs: Long = 0L

    /**
     * Appends a completed turn, dropping oldest turns first until both caps hold.
     * Blank turns are ignored. Audit §6 N4: the same expiry check as [snapshot] /
     * [recordBuiltin] runs here, judged at the single [nowMs] this call supplies
     * for both the check and the activity stamp — so a turn landing past the
     * boundary clears the stale session and starts a fresh one instead of
     * resurrecting it. Audit §6 T6: returns whether a turn was actually
     * appended, so [recordBuiltin] no longer reports success for a blank turn
     * [record] ignored; an oversized single turn truncates to the cap instead
     * of dropping whole.
     */
    fun record(user: String, assistant: String, nowMs: Long, fromAi: Boolean = false): Boolean {
        if (user.isBlank() || assistant.isBlank()) return false
        if (turns.isNotEmpty() && nowMs - lastActivityMs > SESSION_TIMEOUT_MS) {
            turns.clear()
        }
        turns.add(ConversationTurn(user, assistant, fromAi))
        lastActivityMs = nowMs
        while (turns.size > 1 && (turns.size > MAX_TURNS || totalChars() > MAX_HISTORY_CHARS)) {
            turns.removeAt(0)
        }
        if (turns.size == 1 && totalChars() > MAX_HISTORY_CHARS) {
            val only = turns[0]
            val keptUser = only.user.take(MAX_HISTORY_CHARS)
            val keptAssistant = only.assistant.take((MAX_HISTORY_CHARS - keptUser.length).coerceAtLeast(0))
            turns[0] = ConversationTurn(keptUser, keptAssistant, only.fromAi)
        }
        return true
    }

    /**
     * r117: the multi-turn gate for built-in replies. Records only
     * when a session already exists (a previous turn is still
     * inside its timeout); on an empty or expired session it
     * records nothing and returns false, so a lone command never
     * starts a session. A recorded built-in counts toward the
     * caps and refreshes the timeout exactly like an AI turn.
     * The expiry check stays here, before delegating to [record]:
     * [record]'s own clear-then-append would otherwise let an
     * expired session's built-in turn start a new session, which
     * is exactly what this gate refuses.
     */
    fun recordBuiltin(user: String, assistant: String, nowMs: Long): Boolean {
        if (turns.isEmpty()) return false
        if (nowMs - lastActivityMs > SESSION_TIMEOUT_MS) {
            turns.clear()
            return false
        }
        return record(user, assistant, nowMs, fromAi = false)
    }

    /** The turns to send, oldest first; an expired session clears itself and reads empty. */
    fun snapshot(nowMs: Long): List<ConversationTurn> {
        if (turns.isNotEmpty() && nowMs - lastActivityMs > SESSION_TIMEOUT_MS) {
            turns.clear()
        }
        return turns.toList()
    }

    fun clear() {
        turns.clear()
        // Audit §6 T6: the activity timestamp resets with the turns, so no
        // future check can read a stale timestamp off an emptied session.
        lastActivityMs = 0L
    }

    fun isEmpty(): Boolean = turns.isEmpty()

    private fun totalChars(): Int = turns.sumOf { it.user.length + it.assistant.length }
}

/**
 * The live session holder: in-memory only, never persisted. Spoken
 * conversation history is sensitive, and a disk copy would outlive the
 * drive, survive backups, and need its own deletion story. The session
 * dies with the process, and on timeout (see
 * [ConversationHistory]).
 */
object ConversationSession {
    private val history = ConversationHistory()

    // r220: returns whether a turn was appended, so the CarUi collector
    // knows whether the turn joined the session (the durable-memory hook
    // learns only from session-joined turns).
    fun record(user: String, assistant: String, fromAi: Boolean = false): Boolean {
        return history.record(user, assistant, SystemClock.elapsedRealtime(), fromAi)
    }

    /** r117: gated built-in record — true when the turn joined the session, false when the gate refused it. */
    fun recordBuiltin(user: String, assistant: String): Boolean =
        history.recordBuiltin(user, assistant, SystemClock.elapsedRealtime())

    fun historyForRequest(): List<ConversationTurn> =
        history.snapshot(SystemClock.elapsedRealtime())

    fun clear() = history.clear()
}
