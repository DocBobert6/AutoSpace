package app.autospace.car

import app.autospace.car.theme.*
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.autospace.notify.CarNotification
import app.autospace.notify.CarNotifications
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Replying to one notification. The list itself lives on the dashboard (see
 * [NotificationsSection]); with nothing to reply to, this is just the dashboard.
 */
@Composable
fun NotificationsScreen(replyTo: String?) {
    val history by CarNotifications.history.collectAsState()
    val navigator = LocalCarNavigator.current
    val target = history.firstOrNull { it.key == replyTo && it.reply != null }
    if (target != null) {
        ReplyPane(target, onDone = navigator::back)
    } else {
        CarDashboardScreen()
    }
}

/**
 * The notification history, under the calendar on the dashboard. Replying opens its own pane.
 * Anything arriving while this is on screen is being looked at, so it never counts as unread.
 */
@Composable
fun NotificationsSection() {
    val history by CarNotifications.history.collectAsState()
    val listening by CarNotifications.listening.collectAsState()
    val navigator = LocalCarNavigator.current
    val context = LocalContext.current.applicationContext
    LaunchedEffect(history) { CarNotifications.markRead() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Notifications",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (history.isNotEmpty()) TextButton(onClick = CarNotifications::clear) { Text("Clear all") }
        }

        if (!listening) {
            AccessBanner {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        if (history.isEmpty()) {
            Text(
                "Nothing yet. New notifications from the phone will show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            history.forEach { n ->
                key(n.key) { NotificationRow(n) { navigator.notifications(replyTo = it.key) } }
            }
        }
    }
}

@Composable
internal fun AccessBanner(onOpen: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Text(
                "Notifications are off. Allow Gearslip under Settings > Notifications > Notification access on the phone.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            GsButton(onClick = onOpen) { Text("Open") }
        }
    }
}

@Composable
private fun NotificationRow(n: CarNotification, onReply: (CarNotification) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NotificationIcon(n)
            Column(Modifier.weight(1f)) {
                Text(
                    "${n.appLabel}  ·  ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(n.postedAt))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (n.title.isNotBlank()) {
                    Text(n.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (n.text.isNotBlank()) {
                    Text(n.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                n.replied -> Icon(Icons.Filled.Check, contentDescription = "Replied", tint = MaterialTheme.colorScheme.primary)
                n.reply != null -> GsButton(onClick = { VoiceReply.reply(n) }, tone = GsTone.Tonal) {
                    ReplyGlyph(Modifier.size(20.dp))
                    Text("Reply", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
internal fun NotificationIcon(n: CarNotification, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val icon = n.icon
    if (icon != null) {
        Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp)))
    } else {
        Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(size))
    }
}

/** Same shape as search: a field on top, the host's own keyboard below. */
@Composable
private fun ReplyPane(n: CarNotification, onDone: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var text by remember(n.key) { mutableStateOf("") }

    fun send() {
        if (text.isBlank()) return
        CarNotifications.reply(context, n, text.trim())
        onDone()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GsIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onDone)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (text.isEmpty()) "Reply to ${n.title.ifBlank { n.appLabel }}" else "$text|",
                    color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // What is being answered, so the driver isn't replying blind.
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NotificationIcon(n)
            Text(n.text.ifBlank { n.title }, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }

        CarKeyboard(
            text, onTextChange = { text = it }, onSubmit = ::send,
            submitImage = Icons.AutoMirrored.Filled.Send, onDismiss = onDone,
        )
    }
}
