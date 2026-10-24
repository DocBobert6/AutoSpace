package app.autospace.car

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.autospace.R
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsIconButton
import app.autospace.car.theme.GsProgressBar
import app.autospace.car.theme.GsTone
import app.autospace.notify.CarNotification
import app.autospace.notify.CarNotifications
import kotlinx.coroutines.delay

/*
 * A new message on the car screen: a card at the top right, over the player beside the map on
 * Home, so the road stays in view. It offers two things - hear it, or answer it by voice - and
 * shows [VoiceReply]'s progress once either starts. A bar along its bottom empties as the card
 * runs out of time, whatever it's waiting to do: go away, or send.
 */

/** How long a new message stays up on its own. */
private const val POPUP_MS = 5_000L

/** When [this] popup goes away on its own. */
private fun CarNotification.until() = shownAt + POPUP_MS

/**
 * Times the popup out. Each new message in the same chat starts the clock again. A voice reply
 * covers the card while it runs; if the popup's time ran out meanwhile, it goes when the reply ends.
 */
@Composable
internal fun PopupTimer() {
    val popup by CarNotifications.popup.collectAsState()
    val voice by VoiceReply.state.collectAsState()
    val n = popup ?: return
    val busy = voice.phase != VoiceReply.Phase.IDLE
    LaunchedEffect(n.id, n.shownAt, busy) {
        if (busy) return@LaunchedEffect
        delay((n.until() - SystemClock.uptimeMillis()).coerceAtLeast(0L))
        CarNotifications.dismissPopup(n.id)
    }
}

@Composable
internal fun ReadGlyph(modifier: Modifier = Modifier) =
    Icon(ImageVector.vectorResource(R.drawable.volume_up_24), null, modifier)

@Composable
internal fun ReplyGlyph(modifier: Modifier = Modifier) =
    Icon(ImageVector.vectorResource(R.drawable.voice_selection_24), null, modifier)

/** "Alex", or "Alex · 3 new" when a burst of messages from one chat has been merged. */
private fun CarNotification.heading(): String {
    val who = title.ifBlank { appLabel }
    return if (burst > 1) "$who · $burst new" else who
}

/** The card. It shows the voice reply too, wherever that was started from. */
@Composable
fun NotificationPopup(showNew: Boolean, modifier: Modifier = Modifier) {
    val popup by CarNotifications.popup.collectAsState()
    val voice by VoiceReply.state.collectAsState()
    val target = voice.target
    if (voice.phase != VoiceReply.Phase.IDLE && target != null) {
        PopupCard(voice.until, voice.span, modifier, held = voice.held) { VoiceReplyBody(voice, target) }
        return
    }
    val n = popup?.takeIf { showNew } ?: return
    PopupCard(n.until(), POPUP_MS, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NotificationIcon(n, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(n.heading(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(n.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            GsIconButton(Icons.Filled.Close, "Dismiss", { CarNotifications.dismissPopup(n.id) }, size = 40.dp, iconSize = 20.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GsButton(onClick = { VoiceReply.read(n) }, tone = GsTone.Neutral, modifier = Modifier.weight(1f)) {
                ReadGlyph(Modifier.size(20.dp))
                Text("Read", Modifier.padding(start = 8.dp))
            }
            if (n.reply != null) {
                GsButton(onClick = { VoiceReply.reply(n) }, modifier = Modifier.weight(1f)) {
                    ReplyGlyph(Modifier.size(20.dp))
                    Text("Reply", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/**
 * The card's frame. When [until] is set, a bar along the bottom empties over [span] ms, so the
 * driver sees at a glance how long is left before the card acts. A [held] countdown shows its bar
 * standing still.
 */
@Composable
private fun PopupCard(until: Long, span: Long, modifier: Modifier, held: Float = 0f, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 8.dp,
        modifier = modifier.padding(12.dp).widthIn(min = 300.dp, max = 400.dp).fillMaxWidth(0.34f),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
            if (until > 0L && span > 0L) {
                val left by rememberTimeLeft(until, span)
                GsProgressBar(left, Modifier.fillMaxWidth(), height = 14.dp)
            } else if (held > 0f) {
                GsProgressBar(held, Modifier.fillMaxWidth(), height = 14.dp)
            }
        }
    }
}

/** The share of [span] still to go before [until], updated every frame so the bar moves smoothly. */
@Composable
private fun rememberTimeLeft(until: Long, span: Long) = produceState(fractionLeft(until, span), until, span) {
    // The state outlives a change of deadline, so start from the new one rather than the old value.
    value = fractionLeft(until, span)
    while (value > 0f) {
        withFrameMillis { value = fractionLeft(until, span) }
    }
}

private fun fractionLeft(until: Long, span: Long): Float =
    ((until - SystemClock.uptimeMillis()).toFloat() / span).coerceIn(0f, 1f)

/** Each step of a voice reply, with the buttons that fit it. */
@Composable
private fun VoiceReplyBody(s: VoiceReply.State, n: CarNotification) {
    val navigator = LocalCarNavigator.current
    val who = n.title.ifBlank { n.appLabel }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when (s.phase) {
            VoiceReply.Phase.OFFERING, VoiceReply.Phase.ASKING, VoiceReply.Phase.LISTENING -> ListeningDot()
            VoiceReply.Phase.SENT -> Icon(Icons.Filled.Check, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            else -> NotificationIcon(n, size = 44.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(voiceHeading(s, who), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            voiceDetail(s, n)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (s.phase == VoiceReply.Phase.CONFIRMING) {
        Text("Say “cancel” or “change it”", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    if ((s.phase == VoiceReply.Phase.READ || s.phase == VoiceReply.Phase.OFFERING) && n.reply != null) {
        QuickReplies(n)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val wide = Modifier.weight(1f)
        when (s.phase) {
            VoiceReply.Phase.READING, VoiceReply.Phase.READ -> {
                GsButton(onClick = VoiceReply::cancel, tone = GsTone.Neutral, modifier = wide) {
                    Text(if (s.phase == VoiceReply.Phase.READING) "Stop" else "Close")
                }
                if (n.reply != null) {
                    GsButton(onClick = { VoiceReply.reply(n, readFirst = false) }, modifier = wide) {
                        ReplyGlyph(Modifier.size(20.dp))
                        Text("Reply", Modifier.padding(start = 8.dp))
                    }
                }
            }
            VoiceReply.Phase.OFFERING -> {
                GsButton(onClick = VoiceReply::cancel, tone = GsTone.Neutral, modifier = wide) { Text("No") }
                GsButton(onClick = { VoiceReply.reply(n, readFirst = false) }, modifier = wide) {
                    ReplyGlyph(Modifier.size(20.dp))
                    Text("Reply", Modifier.padding(start = 8.dp))
                }
            }
            VoiceReply.Phase.ASKING, VoiceReply.Phase.LISTENING ->
                GsButton(onClick = VoiceReply::cancel, tone = GsTone.Neutral, modifier = wide) { Text("Cancel") }
            VoiceReply.Phase.CONFIRMING -> {
                GsButton(onClick = VoiceReply::cancel, tone = GsTone.Neutral, modifier = wide) { Text("Cancel") }
                GsButton(onClick = VoiceReply::sendNow, modifier = wide) { Text("Send now") }
            }
            VoiceReply.Phase.ERROR -> {
                GsButton(onClick = VoiceReply::cancel, tone = GsTone.Neutral, modifier = wide) { Text("Close") }
                if (n.reply != null) {
                    GsButton(onClick = { VoiceReply.reply(n, readFirst = false) }, tone = GsTone.Tonal, modifier = wide) { Text("Try again") }
                    GsButton(
                        onClick = { VoiceReply.cancel(); navigator.notifications(replyTo = n.key) },
                        tone = GsTone.Neutral, modifier = wide,
                    ) { Text("Type") }
                }
            }
            VoiceReply.Phase.SENT, VoiceReply.Phase.IDLE -> Unit
        }
    }
}

private fun voiceHeading(s: VoiceReply.State, who: String): String = when (s.phase) {
    VoiceReply.Phase.READING, VoiceReply.Phase.READ -> who
    VoiceReply.Phase.OFFERING, VoiceReply.Phase.ASKING -> "Reply to $who"
    VoiceReply.Phase.LISTENING -> "Listening"
    VoiceReply.Phase.CONFIRMING -> "Sending to $who"
    VoiceReply.Phase.SENT -> "Sent to $who"
    VoiceReply.Phase.ERROR -> "Couldn't reply"
    VoiceReply.Phase.IDLE -> ""
}

private fun voiceDetail(s: VoiceReply.State, n: CarNotification): String? = when (s.phase) {
    VoiceReply.Phase.READING, VoiceReply.Phase.READ -> n.text
    VoiceReply.Phase.OFFERING -> s.heard.ifBlank { "Do you want to reply? Say yes or no." }
    VoiceReply.Phase.ASKING -> "Get ready to say your reply."
    VoiceReply.Phase.LISTENING -> s.heard.ifBlank { "Say your reply" }
    VoiceReply.Phase.CONFIRMING, VoiceReply.Phase.SENT -> "“${s.heard}”"
    VoiceReply.Phase.ERROR -> s.error
    VoiceReply.Phase.IDLE -> null
}

/** Ready-made answers, one tap each: sent at once, with no voice. */
@Composable
internal fun QuickReplies(n: CarNotification, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VoiceReply.quickReplies().forEach { text ->
            GsButton(onClick = { VoiceReply.quickReply(n, text) }, tone = GsTone.Neutral) {
                Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ListeningDot(modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        MicGlyph(Modifier.size(24.dp))
    }
}
