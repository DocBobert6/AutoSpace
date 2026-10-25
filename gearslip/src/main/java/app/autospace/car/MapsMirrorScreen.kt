package app.autospace.car

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsButton
import app.autospace.mirror.PhoneApps
import app.autospace.mirror.PhoneMirror
import app.autospace.mirror.TouchRelayService

/**
 * The "Maps (mirrored)" interstitial (Phase 4.2): one tap from the launcher
 * straight to stock Maps on the phone, shown on the car screen through the
 * generic mirror. This screen itself draws only until mirroring takes the
 * frame over — it is the last thing AutoSpace draws until mirroring stops.
 *
 * It does exactly what PhoneAppsScreen's tile does, minus the chooser grid:
 * [PhoneMirror.open] stores the package as a pending launch and asks for
 * screen-capture consent while AutoSpace is still foreground (launching Maps
 * first would push AutoSpace to background, where no consent dialog can be
 * shown); once the mirror is live, the pending launch fires. If mirroring is
 * already active, Maps launches on the phone immediately.
 *
 * Guards: the launch fires once per composition ([launched]), so returning
 * from the mirror does not re-enter it in a loop; if Maps has no launch
 * intent (uninstalled/disabled between list and tap), a visible error shows
 * instead of the silent log-only no-op PhoneApps.launch would produce. Exit
 * from the mirror itself is Phase 2's Fill exit button (or the phone
 * notification stop as backup) — this tile adds no exit of its own.
 *
 * Honest limits, stated on screen: the mirror shows whatever is on the phone,
 * not just Maps; the phone must stay unlocked and awake or the mirror goes
 * black/locked; guidance audio follows the phone's live audio route, not an
 * AutoSpace template stream.
 */
@Composable
fun MapsMirrorScreen() {
    val context = LocalContext.current
    val navigator = LocalCarNavigator.current
    var launched by remember { mutableStateOf(false) }
    val mapsInstalled = remember(context) {
        context.packageManager.getLaunchIntentForPackage(MAPS_PACKAGE_NAME) != null
    }

    LaunchedEffect(Unit) {
        if (!launched) {
            launched = true
            if (mapsInstalled) PhoneMirror.open(context, MAPS_PACKAGE_NAME)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        ScreenHeader("Maps (mirrored)")
        Spacer(Modifier.height(12.dp))
        if (!TouchRelayService.enabled) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Text(
                        "Touch is off. Enable Gearslip under Settings > Accessibility on the phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    GsButton(onClick = { PhoneApps.openAccessibilitySettings(context) }) { Text("Open") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (!mapsInstalled) {
            Text(
                "Google Maps isn't installed (or is disabled) on this phone, so there's nothing to mirror.",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Install or enable Maps on the phone, then come back — this tile only appears when Maps can be launched.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Starting Maps on your phone…",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "This is Maps mirrored from your phone — not a built-in car app. " +
                    "Keep your phone unlocked and awake while navigating, or the mirror goes dark. " +
                    "The mirror shows whatever is on the phone, not just Maps.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        GsButton(onClick = { navigator.back() }, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}
