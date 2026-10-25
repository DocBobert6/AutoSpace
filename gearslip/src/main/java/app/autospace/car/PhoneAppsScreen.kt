package app.autospace.car

import app.autospace.car.theme.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import app.autospace.mirror.PhoneApp
import app.autospace.mirror.PhoneApps
import app.autospace.mirror.PhoneMirror
import app.autospace.mirror.TouchRelayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Starts a phone app on the car screen.
 *
 * Picking one hands the whole frame over to the mirror, so this screen is the last thing
 * Gearslip draws until mirroring stops - either from the notification on the phone, or by
 * tapping the black bars beside the mirrored picture.
 */
@Composable
fun PhoneAppsScreen() {
    val context = LocalContext.current
    val apps by produceState(initialValue = PhoneApps.cached() ?: emptyList(), context) {
        value = PhoneApps.cached() ?: withContext(Dispatchers.IO) { PhoneApps.installed(context).also { PhoneApps.warm(context) } }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        ScreenHeader("Screen sharing")
        Spacer(Modifier.height(12.dp))
        if (!TouchRelayService.enabled) {
            TouchRelayWarning()
            Spacer(Modifier.height(12.dp))
        }

        // Phase 6, phone-drawer language scaled UP for the car: search-style
        // header above, clean icon grid with the label underneath, neutral
        // Frost tiles — icons larger than a phone's, never smaller. The
        // touch-relay warning above is unchanged.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 144.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(apps) { app ->
                PhoneAppTile(app) { PhoneMirror.open(context, app.packageName) }
            }
        }
    }
}

/**
 * Mirroring shows the phone whether or not touch works, so this warns rather than blocks -
 * the picture is still useful, it just cannot be driven from the car yet.
 */
@Composable
private fun TouchRelayWarning() {
    val context = LocalContext.current
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
                "Touch is off. Enable AutoSpace under Settings > Accessibility on the phone.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            GsButton(onClick = { PhoneApps.openAccessibilitySettings(context) }) { Text("Open") }
        }
    }
}

@Composable
private fun PhoneAppTile(app: PhoneApp, onClick: () -> Unit) {
    GsIconBox(
        onClick = onClick,
        modifier = Modifier.height(152.dp),
        colors = GsColors(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            app.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(72.dp)) }
            Spacer(Modifier.height(10.dp))
            Text(
                app.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
