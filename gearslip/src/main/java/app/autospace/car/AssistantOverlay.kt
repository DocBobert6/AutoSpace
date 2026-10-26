package app.autospace.car

import app.autospace.car.theme.*
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import app.autospace.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the assistant hears and says, laid over the bottom of whatever screen is up: a dark shade
 * so white words read over a bright map, and a glow along the bottom edge in the phone's own
 * colours that breathes while listening. The shade passes touches through; only the buttons take them.
 */
@Composable
fun AssistantOverlay(modifier: Modifier = Modifier) {
    val state by CarAssistant.state.collectAsState()
    val pending by AssistantCommands.aiPending.collectAsState()
    // The sheet must outlive the spoken question: once the question finishes speaking the
    // phase returns to IDLE, but the proposal stays open until its window lapses or the
    // user answers — so the overlay stays up for a pending proposal even while idle.
    if (state.phase == CarAssistant.Phase.IDLE && pending == null) return
    val context = LocalContext.current
    val navigator = LocalCarNavigator.current
    val frame by CarEnvironment.frame.collectAsState()
    val scope = rememberCoroutineScope()
    // While moving, confirmations are voice-only (r25 §5.4): the sheet hides, so a moving
    // driver never faces a tap target — the spoken question plus yes/no still works.
    // CarMotion.moving is a polled value, so poll it while a proposal is open.
    val moving by produceState(initialValue = false, pending) {
        while (pending != null) {
            value = app.autospace.notify.CarMotion.moving
            delay(2_000)
        }
    }
    // Expiry clears the proposal silently: no unsolicited speech while driving. A voice
    // answer after expiry is handled by the next handle() call, which declines it the
    // same way.
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        val wait = p.expiresAt - android.os.SystemClock.elapsedRealtime()
        if (wait > 0) {
            delay(wait)
            if (AssistantCommands.aiPending.value == p) AssistantCommands.clearPendingAiAction()
        } else if (AssistantCommands.aiPending.value == p) {
            AssistantCommands.clearPendingAiAction()
        }
    }
    val listening = state.phase == CarAssistant.Phase.LISTENING && state.ready
    val glow = MaterialTheme.colorScheme.primary
    val glow2 = MaterialTheme.colorScheme.tertiary
    val breathe by rememberInfiniteTransition(label = "glow").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow",
    )
    val strength = when (state.phase) {
        CarAssistant.Phase.LISTENING -> if (state.ready) breathe else 0.3f
        CarAssistant.Phase.ERROR -> 0.3f
        else -> 0.6f
    }

    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black.copy(alpha = 0.85f)))
                // A wide, flat oval of light rising from the bottom edge.
                scale(1f, 0.22f, Offset(size.width / 2, size.height)) {
                    drawCircle(
                        Brush.radialGradient(
                            0f to glow.copy(alpha = 0.9f * strength), 0.5f to glow2.copy(alpha = 0.35f * strength), 1f to Color.Transparent,
                            center = Offset(size.width / 2, size.height), radius = size.width * 0.55f,
                        ),
                        radius = size.width * 0.55f, center = Offset(size.width / 2, size.height),
                    )
                }
            }
            .padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // AI confirmation sheet (r25 §5.2): the proposal's exact question, with 44dp+
        // Accept / Decline. Taps execute through the same pending-action path a voice
        // "yes" takes — never a second execution route. Hidden while moving (voice-only
        // there, per §5.4), and hidden once the window lapses.
        val proposal = pending
        if (proposal != null && !moving) {
            GsSheetSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "AI suggests:",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        proposal.question,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GsButton(
                            onClick = {
                                scope.launch {
                                    val result = AssistantCommands.acceptPendingAiAction(context, navigator, frame)
                                    if (result != null) CarAssistant.reply(result)
                                }
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) { Text("Accept") }
                        GsButton(
                            tone = GsTone.Tonal,
                            onClick = {
                                if (AssistantCommands.declinePendingAiAction()) {
                                    CarAssistant.reply("Okay, not doing that.")
                                }
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) { Text("Decline") }
                    }
                    Text(
                        "Say yes or no, or tap. No answer means no.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.phase != CarAssistant.Phase.IDLE) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PulsingMic(listening)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val (small, big) = when (state.phase) {
                    CarAssistant.Phase.LISTENING -> "" to state.heard.ifBlank { if (state.ready) "Listening…" else "One moment…" }
                    CarAssistant.Phase.THINKING -> "" to state.heard.ifBlank { "…" }
                    else -> state.heard to state.reply
                }
                if (small.isNotBlank()) {
                    Text(small, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // AI badge (r25 §4): every AI answer carries a visible "AI" mark; the
                // spoken "From AI:" prefix runs only on the first answer per session.
                if (state.fromAi) {
                    Text(
                        "AI",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    // Grounded source line (r27): shown, never spoken; the count is not
                    // shown here — the line claims a source exists, not how many.
                    AiAssistant.sourceLine(state.grounded, state.provider)?.let { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Text(
                    big,
                    color = if (big == "Listening…" || big == "One moment…" || big == "…") Color.White.copy(alpha = 0.7f) else Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.missingPermission) {
                GsButton(tone = GsTone.Tonal, onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                }) { Text("Grant") }
            }
            GsIconButton(Icons.Filled.Close, "Dismiss", CarAssistant::cancel)
        }
        }
        }
    }
}

/** A filled circle with a mic glyph, pulsing gently while actually listening. */
@Composable
private fun PulsingMic(listening: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.size(36.dp),
    ) {
        Row(horizontalArrangement = Arrangement.Center) {
            MicGlyph(Modifier.padding(8.dp).size(20.dp))
        }
    }
    // `listening` only changes what's drawn elsewhere in the row (the "Listening…" label);
    // the glyph itself doesn't need to animate for this to read clearly on a glance.
}

/** The assistant's mic, from `R.drawable.mic_24`, tinted with the current content colour. */
@Composable
fun MicGlyph(modifier: Modifier = Modifier) {
    Icon(ImageVector.vectorResource(R.drawable.mic_24), contentDescription = "Assistant", modifier = modifier)
}
