package app.autospace.car

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsIconButton
import app.autospace.car.theme.GsSeekBar
import app.autospace.car.theme.GsSheetSurface
import app.autospace.car.theme.GsSwitch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Whether the quick settings shade is open; flipped from the nav bar. Mirrors BrightnessSheetVisibility. */
object QuickTilesVisibility {
    val state = mutableStateOf(false)
    fun open() { state.value = true }
    fun close() { state.value = false }
}

private fun notificationManager(context: Context): NotificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

/**
 * r70 (A89 permission review): the phone lets this app change its Do Not
 * Disturb state only when NotificationManager.isNotificationPolicyAccessGranted
 * is true. That grant is NOT a manifest permission the user taps through at
 * install time: it is a toggle in the phone's system settings (Do Not Disturb
 * access), opened by [openDndAccessSettings]. Two things about the grant:
 * (1) the manifest must still declare ACCESS_NOTIFICATION_POLICY, or
 * setInterruptionFilter throws; (2) an enabled notification listener is
 * granted policy access automatically, so a driver who already enabled
 * AutoSpace's listener for notification mirroring is usually granted
 * already — but the tile never assumes that and always reads the live
 * value, so a revoked grant shows as NEEDS_ACCESS instead of a dead tile.
 */
fun dndAccessGranted(context: Context): Boolean =
    notificationManager(context).isNotificationPolicyAccessGranted

/** True when the phone is currently silencing anything (any filter other than ALL). */
fun dndIsOn(context: Context): Boolean =
    notificationManager(context).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

/**
 * Sets the phone's Do Not Disturb state: ON means PRIORITY (priority
 * interruptions only, the classic DND), OFF means ALL. Returns false if the
 * grant vanished between the read and the tap, so the tile falls back to
 * NEEDS_ACCESS instead of crashing or showing a fake state.
 */
fun setPhoneDnd(context: Context, on: Boolean): Boolean = try {
    notificationManager(context).setInterruptionFilter(
        if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
        else NotificationManager.INTERRUPTION_FILTER_ALL,
    )
    true
} catch (_: SecurityException) {
    false
}

/**
 * Opens the phone's Do Not Disturb access page. That page has no car-surface
 * form: it opens on the phone screen, where the driver grants access, and
 * the shade's 1-second poll picks the grant up when they come back.
 */
fun openDndAccessSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun readDndState(context: Context): DndTileState = try {
    dndTileState(dndAccessGranted(context), dndIsOn(context))
} catch (_: SecurityException) {
    DndTileState.NEEDS_ACCESS
}

/**
 * r70 (A87/A88/A89): the quick settings shade. A small dismissible panel in
 * the kit sheet treatment, drawn over everything like the brightness sheet
 * (same CarUi draw site, same scrim-tap close, same Done + Close header),
 * so it never permanently covers the turn card, the ETA bar, or the
 * right-edge controls: it appears only on a tap of the nav-bar quick
 * settings button and leaves on scrim tap, Done, Close, or navigating to
 * Settings. It has no driving-state behavior — like the brightness sheet,
 * it stays open while moving until dismissed.
 *
 * Tiles, each backed by a real control, never a second copy of state:
 * Do Not Disturb at the top (A88) toggling the PHONE itself (A89); the
 * brightness slider on the same CarSettings.brightness flow the nav-bar
 * button and the brightness sheet already use; a Settings button that
 * navigates to the existing Settings screen. No WiFi/Bluetooth/etc.
 * tiles: the app has no car-surface controls for those, and a tile with
 * nothing behind it would be decoration. Ships normally: no experimental
 * gate anywhere on this shade.
 */
@Composable
fun QuickTilesSheet(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    // Live-read while open (1 s poll, same pattern as the nav bar's moving
    // poll): the grant can appear while the driver is on the phone's
    // settings page, and the phone's own quick settings can flip DND
    // under us. The first read is synchronous so a granted driver never
    // sees the grant button flash.
    val dnd by produceState(initialValue = readDndState(context)) {
        while (true) {
            value = readDndState(context)
            delay(1_000)
        }
    }
    val brightness by CarSettings.brightness.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))
                .pointerInput(Unit) { detectTapGestures { QuickTilesVisibility.close() } },
        )
        GsSheetSurface(
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 440.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Quick settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                GsButton(onClick = QuickTilesVisibility::close) { Text("Done") }
                GsIconButton(
                    Icons.Filled.Close, "Close quick settings",
                    QuickTilesVisibility::close, size = 56.dp, iconSize = 28.dp,
                )
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Do not disturb", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (dnd) {
                                DndTileState.ON -> "On — the phone is silenced except priority interruptions."
                                DndTileState.OFF -> "Off — the phone sounds as normal."
                                DndTileState.NEEDS_ACCESS -> "Not granted yet — granting opens the phone's settings."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when (dnd) {
                        DndTileState.ON -> GsSwitch(
                            checked = true,
                            onCheckedChange = { setPhoneDnd(context, false) },
                        )
                        DndTileState.OFF -> GsSwitch(
                            checked = false,
                            onCheckedChange = { setPhoneDnd(context, true) },
                        )
                        DndTileState.NEEDS_ACCESS -> GsButton(onClick = { openDndAccessSettings(context) }) {
                            Text("Grant access")
                        }
                    }
                }
                Text("Brightness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                GsSeekBar(fraction = brightness, onSeek = CarSettings::setBrightness)
                Text(
                    "${(brightness * 100).roundToInt()}% — dims only the car screen, never past the head unit's backlight.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Appearance, audio, voice, and more.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    GsButton(onClick = { QuickTilesVisibility.close(); onOpenSettings() }) {
                        Text("Open")
                    }
                }
            }
        }
    }
}
