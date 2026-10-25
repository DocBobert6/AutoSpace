package app.autospace.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsSheetSurface

/**
 * The passenger gate, shared by the browser and the video player
 * (Robert 2026-10-25 05:40, explicitly reversing the old locked
 * "video parked-only, audio may continue" decision for the passenger
 * case only).
 *
 * Shown when a surface is entered while [app.autospace.notify.CarMotion]
 * says the car is moving, and again on a parked→moving transition
 * while a surface is open. It is an honesty gate, not a verification:
 * on a single driver-visible head-unit screen the app cannot check
 * that the person tapping "I'm a passenger" actually is one, and
 * with no GPS fix or permission CarMotion answers "not moving", so
 * the gate simply does not appear in that case (fail-open, unchanged).
 *
 * Deliberately NOT persisted: there is no setting, no "don't ask
 * again", and nothing survives a drive. The caller owns the
 * per-entry state — [onPassenger] means "allowed for this entry",
 * [onDriving] means "the driver is driving" — and must re-ask on
 * every entry while moving. In-composition overlay, never a real
 * window: real popup windows crash on this virtual display, the
 * same reason JS dialogs and the browser settings menu are
 * in-composition overlays.
 */
@Composable
fun PassengerGateDialog(
    onDriving: () -> Unit,
    onPassenger: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Scrim that swallows touches: nothing behind the gate is
        // reachable until one of the two buttons is tapped.
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            ),
        )
        GsSheetSurface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Are you driving?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Browsing the web or watching video while driving is " +
                        "dangerous and could result in an accident, injury, " +
                        "or even death. Do not drive and browse or watch video.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                GsButton(onClick = onDriving, modifier = Modifier.fillMaxWidth()) {
                    Text("Yes")
                }
                GsButton(onClick = onPassenger, modifier = Modifier.fillMaxWidth()) {
                    Text("I'm a passenger")
                }
            }
        }
    }
}
