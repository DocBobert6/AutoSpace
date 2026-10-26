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
import androidx.compose.runtime.collectAsState
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
    val iconFrac by CarSettings.iconSize.collectAsState()
    // Phase 7: screen-sharing tiles share the launcher's tile-size slider
    // (50%-150%, 88dp height floor) and the kit panel colour.
    val tileFrac by CarSettings.tileSize.collectAsState()
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

        // Phase 9 pills: capsule pill rows (icon + label on one line),
        // 2 columns on a wide car screen — no square tiles. The
        // touch-relay warning above is unchanged.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 320.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(apps) { app ->
                PhoneAppTile(app, iconFrac, tileFrac) { PhoneMirror.open(context, app.packageName) }
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
private fun PhoneAppTile(app: PhoneApp, iconFrac: Float = 1f, tileFrac: Float = 1f, onClick: () -> Unit) {
    GsIconBox(
        onClick = onClick,
        modifier = Modifier.height((72f * tileFrac).coerceAtLeast(56f).dp),
        colors = GsColors(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.onSurface),
        shape = gsButtonShape(androidx.compose.foundation.shape.RoundedCornerShape(50)),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (iconFrac > 0f) {
                app.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(48.dp * iconFrac)) }
            }
            Text(
                app.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
