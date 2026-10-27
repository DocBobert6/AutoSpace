package app.autospace.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.autospace.Changelog
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsSwitch
import app.autospace.car.theme.gsButtonShape
import app.autospace.notify.CarMotion

/**
 * What's new in this build, once, the first time the car screen shows after an update - or any
 * time from Settings > About. Held back while the car is moving: a list to read is for when
 * parked. The switch turns the automatic showing off (or back on); it doesn't close the card.
 */
@Composable
fun CarWhatsNew() {
    val release = Changelog.current ?: return
    val afterUpdates by CarSettings.whatsNewAfterUpdates.collectAsState()
    val seen by CarSettings.whatsNewSeen.collectAsState()
    val open by CarSettings.whatsNewOpen.collectAsState()
    // Polled: CarMotion is a plain value, and the card should appear once the car stops.
    val moving by produceState(CarMotion.moving) {
        while (true) {
            value = CarMotion.moving
            delay(5_000)
        }
    }
    val due = afterUpdates && seen < release.versionCode && !moving
    if (!open && !due) return
    val close = { CarSettings.closeWhatsNew(release.versionCode) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = close),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            // r54 (D3): dialog shape through the style rule, so Pitlane squares it.
            shape = gsButtonShape(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            // Swallows taps on the card itself, so only the scrim around it closes.
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 640.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "What's new in ${release.versionName}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    release.changes.forEach { change ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                Modifier
                                    .padding(top = 9.dp)
                                    .size(7.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                            )
                            Text(change, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GsSwitch(afterUpdates, CarSettings::setWhatsNewAfterUpdates)
                    Text(
                        "Show after updates",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    GsButton(onClick = close, modifier = Modifier.height(56.dp)) {
                        Text("Got it", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
