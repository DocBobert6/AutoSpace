package app.autospace.car

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.FrostedPanelSurface
import app.autospace.car.theme.GsKit
import app.autospace.car.theme.cardShapeForLook
import app.autospace.car.theme.gsLook
import app.autospace.notify.CarMotion
import kotlinx.coroutines.delay

/**
 * r76: A58 Live conversation widget swap. While a live conversation is
 * active, the floating media card's slot shows this widget instead of
 * the card. "Live" is rapid turn-chaining, NOT native full-duplex:
 * the turns chain through the r57 follow-up window, the r72
 * barge-in generation guard, and the r73 multi-turn session — no
 * full-duplex machinery is claimed or built here.
 *
 * The swap condition is the assistant phase, not the session: the
 * widget shows while the assistant is LISTENING, THINKING, SPEAKING,
 * or in ERROR, and swaps back the moment the phase returns to IDLE
 * (the follow-up window expiring ends the conversation, so the
 * window IS the conversation's tail). The multi-turn session is
 * deliberately NOT the condition: it lives up to 10 minutes past the
 * last turn, and hiding the media card for 10 minutes after a
 * conversation would be wrong. The session's role is content: when
 * parked, the widget shows the session's turns above the current
 * one; the session is never duplicated — this file reads
 * [ConversationSession] itself.
 *
 * Precedence in the slot (see CarHome): mini video first, because
 * hiding it would stop playback (its player lives in composition);
 * then this widget (hiding the audio card stops nothing); then the
 * audio card. Classic Home is untouched: the swap replaces the
 * floating media card, so it lives where that card lives, and the
 * assistant overlay remains the conversation surface there.
 * The lyrics split is excluded too, like the card itself.
 *
 * Driving: the widget must not become a reading surface while
 * moving, so history shows only when parked. While moving it shows
 * the current turn only — the same class as the overlay's current
 * line, not a new reading surface. The widget has no X and no tap
 * action: it is transient (it swaps back on its own when the
 * conversation ends) and the overlay remains the interactive
 * surface; a dismiss would need widget-dismissed state outliving
 * the turn, which is scope creep for a swap. The nav-bar pill rules
 * are deliberately untouched: the pill stays hidden while the
 * widget shows exactly as while the card shows, and returns with
 * the card when the conversation ends.
 */


/** r76: a conversation is live while the assistant phase is anything but IDLE. Pure. */
fun isLiveConversation(phase: CarAssistant.Phase): Boolean =
    phase != CarAssistant.Phase.IDLE

/** r76: the widget header's phase label. Pure. */
fun livePhaseLabel(phase: CarAssistant.Phase, followUp: Boolean): String =
    when (phase) {
        CarAssistant.Phase.LISTENING -> if (followUp) "Still listening" else "Listening"
        CarAssistant.Phase.THINKING -> "Thinking"
        CarAssistant.Phase.SPEAKING -> "Speaking"
        CarAssistant.Phase.ERROR -> "Error"
        CarAssistant.Phase.IDLE -> ""
    }

/** r76: the body line when no turn text exists yet (listening with nothing heard). Pure. */
fun liveEmptyBody(phase: CarAssistant.Phase, ready: Boolean): String =
    when (phase) {
        CarAssistant.Phase.LISTENING -> if (ready) "Listening…" else "One moment…"
        CarAssistant.Phase.THINKING -> "…"
        else -> ""
    }

/** r76: one turn as the widget shows it. User lines render dimmer than assistant lines. */
data class LiveWidgetTurn(val user: String, val assistant: String)

/**
 * r76: which turns the widget shows. Pure, so the unit test pins it.
 * Parked: the last two session turns plus the current turn (a third
 * history turn would make a bottom-docked card taller than glanceable).
 * Moving: the current turn only — history is the reading surface, and
 * it stays parked. A blank current turn (listening with nothing heard
 * yet) contributes nothing; the caller renders [liveEmptyBody] instead.
 * Session turns are never fabricated: an empty session shows just the
 * current turn, and multi-turn off means an empty session by
 * construction (nothing recorded, toggle-off clears).
 */
fun liveWidgetTurns(
    history: List<ConversationTurn>,
    heard: String,
    reply: String,
    moving: Boolean,
): List<LiveWidgetTurn> {
    val shown = if (moving) emptyList() else history.takeLast(2).map { LiveWidgetTurn(it.user, it.assistant) }
    val current = if (heard.isBlank() && reply.isBlank()) null else LiveWidgetTurn(heard, reply)
    return shown + listOfNotNull(current)
}

/**
 * r76: the conversation widget. Same frosted surface, shape, and
 * elevation as the floating audio card (FrostedPanelSurface,
 * cardShapeForLook, 4dp), same bottom-dock slot and width at the
 * call site — no second width rule. The session history is read
 * here, during composition; recomposition is driven by
 * CarAssistant.state, which changes on every turn boundary, so the
 * history stays fresh without its own flow. A barged-in turn can
 * never leak in: the collector records only after the r72
 * generation check passes, so a superseded turn contributes
 * neither its question nor its discarded tail, and the current
 * turn comes from state, which the new turn overwrites.
 */
@Composable
fun LiveConversationWidget(state: CarAssistant.State, modifier: Modifier = Modifier) {
    // CarMotion.moving is a plain value, not a flow — poll it 1 s
    // like the widget column and the mini video card do (GPS only
    // updates every 2 s). The poll lives and dies with the widget,
    // which only composes while a conversation is live.
    val moving by produceState(CarMotion.moving) {
        while (true) {
            value = CarMotion.moving
            delay(1_000)
        }
    }
    val history = ConversationSession.historyForRequest()
    val turns = liveWidgetTurns(history, state.heard, state.reply, moving)
    val cardShape = cardShapeForLook(gsLook(), GsKit.CardShape)
    FrostedPanelSurface(
        shape = cardShape,
        elevation = 4.dp,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row {
                Text(
                    "Live conversation",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    livePhaseLabel(state.phase, state.followUp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (turns.isEmpty()) {
                val fallback = liveEmptyBody(state.phase, state.ready)
                if (fallback.isNotEmpty()) {
                    Text(
                        fallback,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                turns.forEachIndexed { index, turn ->
                    if (turn.user.isNotBlank()) {
                        Text(
                            turn.user,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (turn.assistant.isNotBlank()) {
                        Text(
                            turn.assistant,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (moving) 2 else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (index < turns.lastIndex) Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}
