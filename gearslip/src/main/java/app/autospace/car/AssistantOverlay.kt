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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.material.icons.filled.Public
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
    // r97 graduation: the band is the regular listening treatment
    // now — it renders in the LISTENING phase, and speaking, thinking,
    // and idle keep exactly today's treatment. The r57 follow-up window
    // keeps the phase at LISTENING, so the band stays up through
    // "Still listening…" with no special-casing.
    val fullWidthGlow = state.phase == CarAssistant.Phase.LISTENING

    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black.copy(alpha = 0.85f)))
                if (fullWidthGlow) {
                    // r67: the band replaces the small oval while
                    // listening — a full-width strip at the bottom edge
                    // in the scheme's own primary, breathing with the
                    // same strength the oval did. It paints in
                    // drawBehind, so it takes no touches; and it lives
                    // inside this sheet, which already sits above the
                    // nav-bar strip in overlay mode and above the
                    // in-flow bar in classic mode, so it can never
                    // cover the bar, and it adds no new footprint over
                    // the turn card, widget column, or media card
                    // beyond this sheet's existing one.
                    val bandHeight = 72.dp.toPx()
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to glow.copy(alpha = 0.25f * strength),
                            1f to glow.copy(alpha = 0.75f * strength),
                        ),
                        topLeft = Offset(0f, size.height - bandHeight),
                        size = Size(size.width, bandHeight),
                    )
                } else {
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
                    CarAssistant.Phase.LISTENING -> "" to state.heard.ifBlank { if (state.followUp && state.ready) "Still listening…" else if (state.ready) "Listening…" else "One moment…" }
                    // r55: a streaming reply grows here during
                    // THINKING, so its text streams ahead of speech;
                    // with no stream in flight the reply is blank and
                    // this reads exactly as before (heard, or "…").
                    CarAssistant.Phase.THINKING -> "" to state.reply.ifBlank { state.heard.ifBlank { "…" } }
                    else -> state.heard to state.reply
                }
                if (small.isNotBlank()) {
                    Text(small, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // AI badge (r25 §4, prefix removed r41) + grounded glyph (r41): every AI
                // answer carries a visible "AI" mark; a grounded one adds one quiet glyph
                // beside it — a globe for Groq, the Google "G" for Gemini — and nothing
                // else. The vehicle never says "From AI", and no source text renders here.
                if (state.fromAi) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "AI",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        when (groundedGlyph(state.fromAi, state.grounded, state.provider)) {
                            GroundedGlyph.GLOBE -> Icon(
                                Icons.Filled.Public,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp),
                            )
                            GroundedGlyph.GOOGLE_G -> GoogleGIcon(Modifier.size(14.dp))
                            GroundedGlyph.NONE -> {}
                        }
                    }
                }
                Text(
                    big,
                    color = if (big == "Listening…" || big == "Still listening…" || big == "One moment…" || big == "…") Color.White.copy(alpha = 0.7f) else Color.White,
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

/**
 * Which grounded-source glyph, if any, sits beside the AI badge (r41). Pure, so the
 * unit test pins it: a glyph shows only for an AI answer that was actually grounded,
 * and the glyph names the provider that grounded it — globe for Groq, Google "G"
 * for Gemini. Ungrounded answers get the badge alone.
 */

internal enum class GroundedGlyph { NONE, GLOBE, GOOGLE_G }

internal fun groundedGlyph(fromAi: Boolean, grounded: Boolean, provider: AiAssistant.Provider): GroundedGlyph =
    when {
        !fromAi || !grounded -> GroundedGlyph.NONE
        provider == AiAssistant.Provider.GROQ -> GroundedGlyph.GLOBE
        else -> GroundedGlyph.GOOGLE_G
    }

/**
 * The Google "G" mark at badge size (r41): four colored arcs plus the blue bar, drawn
 * on a 24-unit viewport with the official brand colors (blue #4285F4, green #34A853,
 * yellow #FBBC05, red #EA4335). The geometry was rendered and checked visually at
 * 240px and at 28px during this round before it shipped. Multicolor by design
 * (source attribution); never tinted. Not a Material icon: no Google logo exists
 * in Material icons, so this is drawn, not borrowed.
 */
@Composable
private fun GoogleGIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val scale = size.minDimension / 24f
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = 8.25f * scale
        val stroke = Stroke(width = 4.5f * scale)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val diameter = Size(radius * 2f, radius * 2f)
        // drawArc angles: 0 = east, increasing clockwise — the same convention
        // as the PIL render verified during this round, so the two match.
        drawArc(Color(0xFF4285F4), 270f, 75f, false, topLeft, diameter, style = stroke)
        drawArc(Color(0xFF34A853), 15f, 105f, false, topLeft, diameter, style = stroke)
        drawArc(Color(0xFFFBBC05), 120f, 90f, false, topLeft, diameter, style = stroke)
        drawArc(Color(0xFFEA4335), 210f, 60f, false, topLeft, diameter, style = stroke)
        drawRect(
            Color(0xFF4285F4),
            topLeft = Offset(center.x, center.y - 2.25f * scale),
            size = Size(10.5f * scale, 4.5f * scale),
        )
    }
}
