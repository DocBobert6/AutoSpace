package app.autospace.car

import android.os.SystemClock

/**
 * r73: multi-turn conversation (A59). A session is the completed
 * exchanges of one AI conversation: each turn is the user's utterance
 * as the model saw it plus the assistant's answer as spoken. Only
 * completed turns are ever recorded — the CarUi collector records a
 * turn only after the generation check passes, so a barged-in turn
 * superseded mid-flight (r72 TurnGate) contributes neither its
 * question nor its discarded tail, and a built-in, unmatched, refused,
 * or emergency reply is not an AI answer and is never recorded.
 * Action proposals are not recorded either: only plain answers, so a
 * follow-up that refers to a proposed action's target may dangle —
 * the honest v1 boundary, stated here rather than papered over with
 * local confirmation text dressed as model context.
 *
 * A built-in command neither records nor ends a session: it is a side
 * action, not a conversation break, so the next AI turn still sees
 * the earlier exchanges. The session ends only on timeout, on
 * toggle-off, or with the process.
 */
data class ConversationTurn(val user: String, val assistant: String)

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

        /** Total history characters kept, about 3k tokens — room left for system, reply, and search results. */
        const val MAX_HISTORY_CHARS = 12_000

        /** Silence between AI turns that ends the session. Judgment call: 10 minutes. */
        const val SESSION_TIMEOUT_MS = 10 * 60 * 1000L
    }

    private val turns = mutableListOf<ConversationTurn>()
    private var lastActivityMs: Long = 0L

    /** Appends a completed turn, dropping oldest turns first until both caps hold. Blank turns are ignored. */
    fun record(user: String, assistant: String, nowMs: Long) {
        if (user.isBlank() || assistant.isBlank()) return
        turns.add(ConversationTurn(user, assistant))
        lastActivityMs = nowMs
        while (turns.size > MAX_TURNS || totalChars() > MAX_HISTORY_CHARS) {
            if (turns.isEmpty()) break
            turns.removeAt(0)
        }
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
    }

    fun isEmpty(): Boolean = turns.isEmpty()

    private fun totalChars(): Int = turns.sumOf { it.user.length + it.assistant.length }
}

/**
 * The live session holder: in-memory only, never persisted. Spoken
 * conversation history is sensitive, and a disk copy would outlive the
 * drive, survive backups, and need its own deletion story. The session
 * dies with the process, on toggle-off (see
 * [CarSettings.setMultiTurnEnabled]), and on timeout (see
 * [ConversationHistory]).
 */
object ConversationSession {
    private val history = ConversationHistory()

    fun record(user: String, assistant: String) {
        history.record(user, assistant, SystemClock.elapsedRealtime())
    }

    fun historyForRequest(): List<ConversationTurn> =
        history.snapshot(SystemClock.elapsedRealtime())

    fun clear() = history.clear()
}

/**
 * r73: the multi-turn gate. Pure, so the unit test pins it: history
 * is sent only when the driver has opted into experimental features
 * as a whole AND the multi-turn toggle is on. Either off sends exactly
 * today's single-turn request, and the stored session is never
 * consulted — toggling off additionally clears it (see
 * [CarSettings.setMultiTurnEnabled]).
 */
fun multiTurnNow(experimentalOn: Boolean, toggleOn: Boolean): Boolean =
    experimentalOn && toggleOn
