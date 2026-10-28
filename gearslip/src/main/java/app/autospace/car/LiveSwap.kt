package app.autospace.car

/**
 * r140: the live conversation widget is retired. The pre-live-widget
 * AssistantOverlay is the one assistant surface again, and the live
 * conversation renders inside it (see AssistantOverlay.kt) — there
 * is no second surface to swap with, no widget-scoped dismiss, and
 * no published visibility flag for the overlay to step aside for.
 * What survives from this file is the pure conversation-turn logic:
 * liveness, and the turns the overlay shows.
 *
 * History rule (r140, same safety shape the widget had): while
 * moving, history is a reading surface, so the overlay shows the
 * current turn only. Parked, the FULL session is scrollable (up
 * to the 8-turn session cap), not the widget's 2-turn parked cap —
 * the overlay scrolls, so a longer history costs no glanceability.
 *
 * Duplication guard (audit §6 F3, carried into this rework): the
 * collector records a turn immediately after reply() while state
 * still holds it, so the recorded copy and the current turn are
 * the same exchange. [overlayConversationTurns] drops a trailing
 * history turn that is the current turn, so it renders once.
 * "Same exchange" is deliberately wider than exact equality: in
 * the follow-up window the heard text is cleared but the reply
 * is kept, so the current turn reads ("", reply) against a
 * recorded (user, reply) — same assistant text with a blank
 * user is the same exchange. A different question with the same
 * answer text, or the same question with a different answer, is
 * a different exchange and is NOT dropped.
 */

/** r76: a conversation is live while the assistant phase is anything but IDLE. Pure. */
fun isLiveConversation(phase: CarAssistant.Phase): Boolean =
    phase != CarAssistant.Phase.IDLE

/**
 * r140: whether the overlay's conversation section shows. Pure, so
 * the unit test pins it: the section shows while a conversation
 * is live. There is no second surface anymore, so no widget flag
 * gates it — the one-frame overlay/widget coexistence caveat
 * (audit §6 T1) is gone by construction: the overlay reads the
 * assistant phase directly, in the same composition that draws
 * it, instead of an effect-published visibility flag, and the
 * floating-card slot has no widget branch left to coexist with.
 */
fun overlayConversationRowVisible(phase: CarAssistant.Phase): Boolean =
    phase != CarAssistant.Phase.IDLE

/** r140: one turn as the overlay's history shows it. User lines render dimmer than assistant lines. */
data class OverlayConversationTurn(val user: String, val assistant: String, val fromAi: Boolean = false)

/**
 * r140: which turns the overlay shows. Pure, so the unit test pins
 * it. Parked: the full session history plus the current turn (a
 * blank current turn — listening with nothing heard yet —
 * contributes nothing). Moving: the current turn only. A trailing
 * history turn that is the current turn (see the file header) is
 * dropped, so a recorded turn renders once, never twice.
 * Session turns are never fabricated: an empty session shows
 * just the current turn.
 */
fun overlayConversationTurns(
    history: List<ConversationTurn>,
    heard: String,
    reply: String,
    moving: Boolean,
    currentFromAi: Boolean = false,
): List<OverlayConversationTurn> {
    val current = if (heard.isBlank() && reply.isBlank()) null else OverlayConversationTurn(heard, reply, currentFromAi)
    if (moving) return listOfNotNull(current)
    var shown = history.map { OverlayConversationTurn(it.user, it.assistant, it.fromAi) }
    if (current != null && shown.isNotEmpty()) {
        val last = shown.last()
        val sameExchange = last.assistant == current.assistant &&
            (current.user.isBlank() || current.user == last.user)
        if (sameExchange) shown = shown.dropLast(1)
    }
    return shown + listOfNotNull(current)
}
