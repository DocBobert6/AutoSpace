package app.autospace.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import app.autospace.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How a notification can be answered: the action the app attached, and the field it fills. */
class ReplyTarget(val action: Notification.Action, val input: android.app.RemoteInput)

/** One message of a conversation. [sender] is null for the phone's owner ("You"). */
data class ChatLine(val sender: String?, val text: String, val at: Long)

/**
 * One notification as the car shows it. Only what the car UI needs is kept - the phone's
 * Notification object stays with the listener, apart from the reply action.
 */
data class CarNotification(
    /** Stable per conversation: an app re-posting an update reuses the key. */
    val key: String,
    /** Unique per post, so an update to an already-shown notification still counts as new. */
    val id: Long,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val icon: Bitmap?,
    /** Null when the app offered no reply, or once the notification has left the phone. */
    val reply: ReplyTarget?,
    val replied: Boolean = false,
    /** The thread's recent messages, oldest first, when the app posts it as a conversation. */
    val lines: List<ChatLine> = emptyList(),
    val isGroup: Boolean = false,
    /** The app's own "mark as read" action, fired once the driver has opened the thread. */
    val markRead: Notification.Action? = null,
    val read: Boolean = false,
    /** How many posts this popup stands for: new messages in one chat update it, not queue. */
    val burst: Int = 1,
    /** Uptime when it popped up, which sets when the popup goes. 0 until it's shown. */
    val shownAt: Long = 0L,
)

/**
 * The car's notification centre: a short history, the one popup on screen, and an unread count.
 *
 * Held in memory only. Message contents are never written to disk, so a process restart clears
 * the history - which is the safer failure for something shown on a car screen.
 */
object CarNotifications {

    private const val MAX_HISTORY = 50

    /** A reply's own echo (apps re-post the thread with "You: ...") shouldn't pop up again. */
    private const val ECHO_WINDOW_MS = 10_000L

    private val _history = MutableStateFlow<List<CarNotification>>(emptyList())
    val history: StateFlow<List<CarNotification>> = _history.asStateFlow()

    private val _popup = MutableStateFlow<CarNotification?>(null)
    val popup: StateFlow<CarNotification?> = _popup.asStateFlow()

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    /** True while the system has bound the listener - i.e. the user has granted access. */
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private var nextId = 0L
    private var lastReplyKey: String? = null
    private var lastReplyAt = 0L

    fun setListening(on: Boolean) { _listening.value = on }

    fun nextId(): Long = nextId++

    /**
     * [quiet] adds to the history without a popup or unread badge: used to seed on connect, and
     * for anything that isn't worth interrupting a drive for (see the listener's filter).
     */
    fun post(notification: CarNotification, quiet: Boolean) {
        _history.update { list ->
            (listOf(notification) + list.filterNot { it.key == notification.key }).take(MAX_HISTORY)
        }
        val echo = notification.key == lastReplyKey &&
            System.currentTimeMillis() - lastReplyAt < ECHO_WINDOW_MS
        if (quiet || echo) return
        val now = android.os.SystemClock.uptimeMillis()
        _popup.update { shown ->
            if (shown?.key == notification.key) {
                notification.copy(burst = shown.burst + 1, shownAt = now)
            } else {
                notification.copy(shownAt = now)
            }
        }
    }

    /** The phone dismissed it. It stays in history, but its reply intent is no longer trustworthy. */
    fun gone(key: String) {
        _history.update { list -> list.map { if (it.key == key) it.copy(reply = null, markRead = null) else it } }
    }

    fun dismissPopup(id: Long) {
        _popup.update { if (it?.id == id) null else it }
    }

    /** While an app's own messages screen is up, its new messages land there, not in a popup. */
    fun dismissPopupFrom(packageName: String) {
        _popup.update { if (it?.packageName == packageName) null else it }
    }

    /** Called while the notification screen is showing: nothing is unread there. */
    fun markRead() {
        _unread.value = 0
        _popup.value = null
    }

    fun clear() {
        _history.value = emptyList()
        _popup.value = null
        _unread.value = 0
    }

    /**
     * Fires the app's own "mark as read", which also clears it from the phone - and with it the
     * reply action, so this is the driver's call, not something done on opening the thread.
     */
    fun markThreadRead(context: Context, notification: CarNotification): Boolean {
        val action = notification.markRead ?: return false
        return try {
            action.actionIntent.send(context, 0, Intent())
            _history.update { list -> list.map { if (it.key == notification.key) it.copy(read = true, markRead = null) else it } }
            true
        } catch (e: PendingIntent.CanceledException) {
            GearslipLog.w("notify: ${notification.appLabel} withdrew its mark-as-read")
            false
        }
    }

    /** Fills the app's reply field and fires its action. Returns false if the app has withdrawn it. */
    fun reply(context: Context, notification: CarNotification, text: String): Boolean {
        val target = notification.reply ?: return false
        val fill = Intent()
        val results = Bundle().apply { putCharSequence(target.input.resultKey, text) }
        android.app.RemoteInput.addResultsToIntent(arrayOf(target.input), fill, results)
        return try {
            target.action.actionIntent.send(context, 0, fill)
            lastReplyKey = notification.key
            lastReplyAt = System.currentTimeMillis()
            _history.update { list ->
                list.map { if (it.key == notification.key) it.copy(replied = true) else it }
            }
            true
        } catch (e: PendingIntent.CanceledException) {
            GearslipLog.w("notify: the reply to ${notification.appLabel} was withdrawn by the app")
            false
        }
    }
}
