package app.autospace.car

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsTone
import app.autospace.car.theme.gsShape

/**
 * r62 (A50): the turn-autoplay-off offer — a small card, placed
 * top-center by its caller so it never covers the turn card, the edge
 * controls, the widget column or the media card. Two answers only;
 * there is no third state and no nag: any dismissal resets the stop
 * count (see CarServices.autoplayOfferKeep), so the driver is asked
 * again only after three more prompt stops.
 */
@Composable
internal fun AutoplayOfferCard(
    onTurnOff: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 480.dp),
        shape = gsShape(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "You often stop autoplay right after it starts. Turn autoplay off?",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GsButton(onClick = onTurnOff, tone = GsTone.Primary) { Text("Turn autoplay off") }
                GsButton(onClick = onKeep, tone = GsTone.Neutral) { Text("Keep autoplay on") }
            }
        }
    }
}
