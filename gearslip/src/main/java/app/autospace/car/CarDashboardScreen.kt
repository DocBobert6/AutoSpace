package app.autospace.car

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import app.autospace.car.theme.GsColors
import app.autospace.car.theme.GsKit
import app.autospace.car.theme.LocalOledDark
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsIconBox
import app.autospace.car.theme.GsIconButton
import app.autospace.car.theme.gsButtonShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.autospace.GearslipLog
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opened from the nav bar clock: the current weather, the week's calendar and the phone's
 * notifications, at a glance. The first two
 * need permissions granted on the phone (READ_CALENDAR, location) - if either was declined
 * this just shows an empty state instead of asking again from inside the car UI.
 */
@Composable
fun CarDashboardScreen() {
    val context = LocalContext.current.applicationContext

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ScreenHeader("Today") {
            GsIconButton(Icons.Filled.BrightnessMedium, "Brightness", BrightnessSheetVisibility::open)
        }
        WeatherCard(context)
        AgendaCard(context)
        NotificationsSection()
    }
}

@Composable
private fun WeatherCard(context: Context) {
    val state by Weather.state.collectAsState()
    val navigator = LocalCarNavigator.current
    LaunchedEffect(Unit) { Weather.refresh(context) }

    GsIconBox(
        onClick = { navigator.weather() },
        colors = GsColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
        // r54 (D4): shape through the style rule, so Pitlane squares it.
        shape = gsButtonShape(MaterialTheme.shapes.extraLarge),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                WeatherState.Loading -> Text("Getting weather…", style = MaterialTheme.typography.bodyLarge)
                is WeatherState.Unavailable -> Text(
                    s.reason, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is WeatherState.Ready -> {
                    val w = s.data
                    WeatherIcon(w.code, w.isDay, size = 44.dp)
                    Column {
                        Text(
                            "${w.temp}${w.tempUnit}  ·  ${Weather.describe(w.code)}",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        )
                        Text(w.place, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaCard(context: Context) {
    val result by produceState<AgendaResult?>(cachedAgenda) {
        value = withContext(Dispatchers.IO) { fetchAgenda(context) }.also { cachedAgenda = it }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Coming up", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        when (val r = result) {
            null -> Text("Loading calendar…", style = MaterialTheme.typography.bodyMedium)
            AgendaResult.Denied -> {
                Text(
                    "Calendar access is off — turn it on in the setup guide",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // r216: the guide button opens AutoSpace's own system app settings
                // page instead of the phone app (r169's launcher handoff) — the
                // calendar permission the guide asks for is granted there.
                GsButton(onClick = { openAppSettings(context) }) { Text("Open on phone") }
            }
            AgendaResult.Empty -> Text(
                "Nothing in the next week.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is AgendaResult.Events -> r.events.forEach { EventRow(it) }
        }
    }
}

@Composable
private fun EventRow(event: AgendaEvent) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // r54 (D4): card shape through the style rule, so Pitlane squares it.
        shape = gsButtonShape(MaterialTheme.shapes.large),
        border = if (LocalOledDark.current) BorderStroke(0.5.dp, GsKit.hairline()) else null,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                eventWhen(event),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                event.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Today" events show just a time; later days get a weekday so a week's list stays readable. */
internal fun eventWhen(event: AgendaEvent): String {
    val zone = if (event.allDay) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
    val start = Calendar.getInstance(zone).apply { timeInMillis = event.startMillis }
    val today = Calendar.getInstance()
    val sameDay = start.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        start.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val time = if (event.allDay) "All day" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.startMillis))
    if (sameDay || (event.allDay && start.before(today))) return time
    return SimpleDateFormat("EEE", Locale.getDefault()).apply { timeZone = zone }.format(Date(event.startMillis)) + " " + time
}

internal data class AgendaEvent(val title: String, val startMillis: Long, val allDay: Boolean)

/**
 * Opens AutoSpace's own system app settings page (r216; was the phone app's launcher
 * entry in r169, audit §5 5-14/5-17): the setup guide lives in the phone app and the
 * car surface has no destination for it, so the guide buttons open the app's settings
 * instead of inventing a car-side guide page — the permissions the guide asks for
 * (calendar, car apps) are granted there. FLAG_ACTIVITY_NEW_TASK is required because
 * the callers hand in the application context. A no-op when the settings page cannot
 * be opened; it never requests a permission.
 */
internal fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Sealed agenda result (r169, audit §5 5-14): denial, empty, and events are distinct —
 * the dashboard must not render "nothing scheduled" for a denied permission.
 */
internal sealed interface AgendaResult {
    data object Denied : AgendaResult
    data object Empty : AgendaResult
    data class Events(val events: List<AgendaEvent>) : AgendaResult
}

/** Pure mapping behind [fetchAgenda]: denial beats everything, then empty, then events. */
internal fun agendaResult(permissionGranted: Boolean, events: List<AgendaEvent>): AgendaResult =
    if (!permissionGranted) AgendaResult.Denied
    else if (events.isEmpty()) AgendaResult.Empty
    else AgendaResult.Events(events)

@Volatile private var cachedAgenda: AgendaResult? = null

/** Widget layer (r37): the dashboard's cached agenda, if it was warmed. */
internal fun cachedAgendaSnapshot(): AgendaResult? = cachedAgenda

/** Loads the weather and the agenda ahead of time, so the dashboard opens with both in place. */
fun warmDashboard(context: Context) {
    Weather.refresh(context, force = true)
    cachedAgenda = fetchAgenda(context)
}

/**
 * Denied means no permission, Empty means nothing scheduled. Looks from the start of today so an
 * event already under way still shows, a week ahead so tomorrow's does too, and skips ones
 * that have finished. A failed query still reads as Empty, as before — only denial is newly
 * distinct. Does not request the permission; it only reads it, so a denied calendar stays
 * denied until the driver grants it.
 */
internal fun fetchAgenda(context: Context): AgendaResult {
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
        return AgendaResult.Denied
    }

    val now = System.currentTimeMillis()
    val startOfToday = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val end = startOfToday + 7 * 24 * 60 * 60 * 1000L
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        .appendPath(startOfToday.toString())
        .appendPath(end.toString())
        .build()

    val events = mutableListOf<AgendaEvent>()
    val projection = arrayOf(
        CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
    )
    runCatching {
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")
            ?.use { cursor: Cursor ->
                while (cursor.moveToNext() && events.size < 10) {
                    val allDay = cursor.getInt(3) == 1
                    // All-day events are stored in UTC midnight, so "ended" means the day is over, not the instant.
                    if (!allDay && cursor.getLong(2) < now) continue
                    events.add(AgendaEvent(cursor.getString(0) ?: "(untitled)", cursor.getLong(1), allDay))
                }
            }
    }.onFailure { GearslipLog.w("calendar query failed: ${it.message}") }
    return agendaResult(true, events)
}
