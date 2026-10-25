package app.autospace.car

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsIconButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the assistant understands, shown on the car screen when the driver says "help". */
object VoiceHelp {
    private val _shown = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    fun show() { _shown.value = true }
    fun hide() { _shown.value = false }

    /** Say this, and this happens. Kept in step with [AssistantCommands]. */
    val COMMANDS = listOf(
        "Navigate to…" to "Directions there. Add \"with Organic Maps\" to pick the app",
        "Play…" to "A song, artist, or album in the open music app",
        "Pause · Resume · Next · Previous" to "Control the music",
        "What's playing" to "The song and artist",
        "Call…" to "A contact by name",
        "Answer · Hang up" to "Answer a ringing call; ending an active call needs the dialer",
        "Open…" to "Any app, like Browser, Phone, or Screen sharing",
        "Exit" to "Back to the car's own screen",
        "Go home · Open apps · Settings" to "Move around AutoSpace",
        "What time is it · Weather · Phone battery" to "The time, the forecast, or the phone's battery",
        "Volume up · Volume down · Mute" to "Media volume",
        "Brightness up · Brightness down · Brightness 50" to "Car-screen brightness",
        "Stop navigation · How far · When will I arrive · Repeat that" to "End a route, ETA and distance, or the last direction again",
        "Turn speak directions on/off · Use phone mic · Start/stop screen sharing" to "Voice and mirroring toggles",
        "Search for…" to "The web, on no-AI DuckDuckGo",
        "Read my messages · Text… that…" to "Messages aloud; replies only to a thread that already exists",
        "Call emergency services" to "Asks you to confirm out loud before dialling",
        "Remind me in 10 minutes" to "A local timer, spoken when it fires",
        "Cancel · Never mind" to "Stop the assistant without an answer",
        "“Hey AutoSpace” · “Hey Gearslip”" to "Wake the assistant without a button, while connected and only when the hotword switch is on",
        "Help" to "This list",
    )
}

/** The command list over the car screen: read it at a glance, tap the cross or wait to dismiss. */
@Composable
fun VoiceHelpOverlay(modifier: Modifier = Modifier) {
    val shown by VoiceHelp.shown.collectAsState()
    if (!shown) return
    // A list left up would cover the map for the rest of the drive.
    LaunchedEffect(Unit) {
        delay(30_000)
        VoiceHelp.hide()
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier.padding(16.dp).widthIn(max = 640.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "You can say",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(top = 6.dp),
                )
                GsIconButton(Icons.Filled.Close, "Close", VoiceHelp::hide)
            }
            // Full width, so a drag anywhere on the card scrolls, not just one on the words.
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                VoiceHelp.COMMANDS.forEach { (say, does) ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(say, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(does, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
