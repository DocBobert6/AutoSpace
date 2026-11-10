package app.autospace.update

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import app.autospace.BuildConfig
import app.autospace.GearslipActivity
import app.autospace.GearslipLog
import app.autospace.SessionStatus
import app.autospace.notify.CarMotion
import app.autospace.notify.CarNotification
import app.autospace.notify.CarNotifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Looks for a newer AutoSpace on GitHub and installs it through Android's own installer.
 *
 * Which releases count depends on the driver's channel. Stable takes only GitHub's "latest"
 * release, which leaves out pre-releases and drafts. Beta adds pre-releases tagged "beta", and
 * Alpha adds every pre-release. The app checks at most once a day: when
 * it opens, and when a drive ends. With automatic checks off it never asks GitHub on its own,
 * only when the driver taps Check for updates. After a drive a new version also gets
 * a notification, once per version, since the driver is parked and the app is likely closed. The
 * car screen shows the same news once per version, at plug-in and only while parked.
 * Nothing runs in the background otherwise, and nothing else is sent: the request is the same one
 * a browser makes for the release page. Debug builds never check on their own.
 *
 * The APK is checked against the size and SHA-256 GitHub lists for it, and Android refuses an
 * update signed with a different key, so a bad download can't replace the app.
 */
object UpdateChecker {

    data class Release(val version: String, val apkUrl: String, val size: Long, val sha256: String?, val pageUrl: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val fraction: Float) : State
        data class Ready(val release: Release, val apk: File) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    /**
     * A 403/429 from GitHub's release API. Carries the wait GitHub asked for, so the
     * check path can honor it instead of hammering: Retry-After wins when present,
     * X-RateLimit-Reset (epoch seconds) is the fallback, and neither means "later,
     * unsaid". An IOException subclass on purpose — the failure mapping must test
     * for this type BEFORE the generic network branch, or a rate limit would read
     * as a connection problem.
     */
    class RateLimitedException(
        val statusCode: Int,
        val retryAfterSeconds: Long?,
        val resetEpochSeconds: Long?,
    ) : IOException("GitHub answered $statusCode (rate limited)")

    /**
     * A download that finished but failed the final gate (size or SHA-256). The
     * download path deletes the file on this failure only: a damaged file must
     * never be resumed from, while a merely interrupted one must be.
     */
    class UpdateVerificationException(message: String) : Exception(message)

    /**
     * r172 (audit §5 5-19): the notification's Install action. GearslipActivity
     * hands a tap carrying this extra to [installFromAction]; the guards live in
     * [install] itself, so the action is safe to tap at any time.
     */
    const val EXTRA_INSTALL_UPDATE = "app.autospace.update.INSTALL"

    /**
     * r172 (audit §5 5-22): only https may serve an update APK. Pure, so the unit
     * test pins it: a saved release whose URL was tampered to http in prefs is
     * refused here, before any bytes are fetched.
     */
    internal fun isSecureApkUrl(url: String): Boolean = runCatching {
        val parsed = URL(url)
        parsed.protocol == "https" && parsed.host?.isNotBlank() == true
    }.getOrDefault(false)

    /**
     * r172 (audit §5 5-20): the rate-limit message for a check failure. The wait
     * is Retry-After first, then the seconds until X-RateLimit-Reset, then
     * nothing — "try again later" with no invented deadline. Pure, so the unit
     * test pins the wording and the precedence without a device.
     */
    internal fun rateLimitMessage(retryAfterSeconds: Long?, resetEpochSeconds: Long?, nowMs: Long): String {
        val waitSecs = retryAfterSeconds?.takeIf { it > 0 }
            ?: resetEpochSeconds?.let { (it * 1000 - nowMs) / 1000 }?.takeIf { it > 0 }
            ?: return "GitHub is rate-limiting checks right now — try again later."
        val minutes = ((waitSecs + 59) / 60).coerceAtLeast(1)
        return waitMinutesMessage(minutes)
    }

    /** Shared wording for a rate-limit wait, from minutes until it lifts. */
    private fun waitMinutesMessage(minutes: Long): String =
        "GitHub is rate-limiting checks right now — try again in $minutes minute${if (minutes == 1L) "" else "s"}."

    /** r172 (audit §5 5-20): the timestamp automatic checks must not retry before, or null. */
    internal fun rateLimitUntilMs(retryAfterSeconds: Long?, resetEpochSeconds: Long?, nowMs: Long): Long? {
        val waitSecs = retryAfterSeconds?.takeIf { it > 0 }
            ?: resetEpochSeconds?.let { (it * 1000 - nowMs) / 1000 }?.takeIf { it > 0 }
            ?: return null
        return nowMs + waitSecs * 1000
    }

    /**
     * r172 (audit §5 5-20): what a failed check tells the driver. Rate limits get
     * the rate-limit message (with the wait when GitHub gave one); the connection
     * message is reserved for actual network errors; anything else — a bad answer,
     * an unparseable body — gets a plain message that never blames the connection.
     * The RateLimited branch must stay first: it is an IOException subclass.
     */
    internal fun checkFailureMessage(error: Throwable, nowMs: Long): String = when (error) {
        is RateLimitedException -> rateLimitMessage(error.retryAfterSeconds, error.resetEpochSeconds, nowMs)
        is IOException -> "Couldn't reach GitHub. Check the connection and try again."
        else -> "Couldn't check for updates. Try again."
    }

    /**
     * r172 (audit §5 5-23): what a partial file means before a download starts.
     * VERIFY_COMPLETE still checks the digest — a complete file with a wrong
     * digest is damaged, not done. Pure, so the unit test pins it.
     */
    internal enum class ResumePlan { FRESH, RESUME, VERIFY_COMPLETE, RESTART }

    internal fun resumePlan(existingBytes: Long, totalBytes: Long): ResumePlan = when {
        existingBytes <= 0L -> ResumePlan.FRESH
        totalBytes > 0 && existingBytes == totalBytes -> ResumePlan.VERIFY_COMPLETE
        totalBytes > 0 && existingBytes > totalBytes -> ResumePlan.RESTART
        else -> ResumePlan.RESUME
    }

    /** r172 (audit §5 5-23): only a 206 to a ranged request appends to a partial file. */
    internal fun resumesStream(responseCode: Int, usedRange: Boolean): Boolean =
        responseCode == 206 && usedRange

    /** r172 (audit §5 5-18/5-19): the cache file for a release, shared by download and restore. */
    internal fun apkFileName(version: String): String = "AutoSpace-$version.apk"

    private val flow = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = flow.asStateFlow()

    private const val PREFS = "updates"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_FOUND = "found"
    /** Named for the notifications toggle it grew out of; it now covers every automatic check. */
    private const val KEY_AUTO = "notify"
    /** The old on/off switch for pre-releases. On meant every pre-release, which is now Alpha. */
    private const val KEY_BETA = "beta"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_NOTIFIED = "notified_version"
    private const val KEY_CAR_NOTIFIED = "car_notified_version"
    /** r172 (audit §5 5-18): a deferred install that must survive process death. */
    private const val KEY_PENDING_INSTALL = "pending_install"
    /** r172 (audit §5 5-19): a Ready update announced at a drive end, once per version. */
    private const val KEY_READY_NOTIFIED = "ready_notified_version"
    /** r172 (audit §5 5-20): automatic checks must not retry before this timestamp. */
    private const val KEY_RATE_LIMITED_UNTIL = "rate_limited_until"
    /** Lets the car screen settle after plug-in, so the card isn't lost behind the first frames. */
    private const val CAR_DELAY_MS = 8_000L
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 4100
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val LATEST = "https://api.github.com/repos/DocBobert6/AutoSpace/releases/latest"
    /** Newest first, pre-releases included. Ten covers any run of betas between two releases. */
    private const val ALL = "https://api.github.com/repos/DocBobert6/AutoSpace/releases?per_page=10"

    /** Set when the driver tapped Install but first had to allow installs from AutoSpace. */
    @Volatile private var installWhenAllowed = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Whether AutoSpace checks on its own, and tells the driver what it finds on the phone and the
     * car screen. On by default: the check sends nothing about the phone.
     */
    fun autoCheckEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoCheckEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, on).apply()
        if (!on) NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Which releases count as updates. Each channel also takes everything the one before it does. */
    enum class Channel { STABLE, BETA, ALPHA }

    /**
     * The channel for a build that never picked one, from the version's own stage (r41):
     * an alpha build defaults to Alpha, a beta build to Beta, anything else to Stable.
     * Pure, so the unit test pins it without a device.
     */
    fun defaultChannelForVersion(versionName: String): Channel {
        val lower = versionName.lowercase()
        return when {
            "alpha" in lower -> Channel.ALPHA
            "beta" in lower -> Channel.BETA
            else -> Channel.STABLE
        }
    }

    /**
     * r221 (experimental flavor): a build only ever offers releases from its
     * own line. The standard (beta) build never offers an experimental tag,
     * and the experimental build never offers a beta or plain tag. The gate
     * lives here, in the shared updater code, so it applies on every channel
     * and on both the list and the single-release (Stable) fetch paths.
     * Pure, so the unit test pins it in both flavor variants.
     */
    internal fun acceptsTag(tag: String): Boolean =
        if (BuildConfig.FLAVOR == "experimental") tag.contains("-exp", ignoreCase = true)
        else !tag.contains("-exp", ignoreCase = true)

    /**
     * Resolves the effective channel from its three inputs, in precedence order: an
     * explicitly saved channel always wins; the old beta on/off switch means Alpha
     * (it used to mean every pre-release); otherwise the version's stage decides.
     * Pure, so the unit test pins the precedence without a device.
     */
    internal fun resolveChannel(savedName: String?, oldBeta: Boolean, versionName: String): Channel {
        savedName?.let { saved -> Channel.entries.firstOrNull { it.name == saved }?.let { return it } }
        if (oldBeta) return Channel.ALPHA
        return defaultChannelForVersion(versionName)
    }

    fun channel(context: Context): Channel {
        val p = prefs(context)
        return resolveChannel(p.getString(KEY_CHANNEL, null), p.getBoolean(KEY_BETA, false), BuildConfig.VERSION_NAME)
    }

    /**
     * Switching channels forgets what the old one found: an alpha found earlier shouldn't still be
     * offered on Stable. Checks again right away if automatic checks are on.
     */
    fun setChannel(context: Context, channel: Channel) {
        prefs(context).edit().putString(KEY_CHANNEL, channel.name).remove(KEY_BETA).remove(KEY_FOUND).remove(KEY_CHECKED_AT).apply()
        val current = flow.value
        if (current is State.Checking || current is State.Downloading) return
        updatesDir(context).deleteRecursively()
        flow.value = State.Idle
        if (autoCheckEnabled(context) && !BuildConfig.DEBUG) check(context)
    }

    private fun dueForCheck(context: Context) =
        !BuildConfig.DEBUG && autoCheckEnabled(context) && System.currentTimeMillis() - prefs(context).getLong(KEY_CHECKED_AT, 0) >= DAY_MS &&
            // r172 (audit §5 5-20): a rate limit GitHub asked us to honor still
            // running means no automatic retry yet, whatever the day cadence says.
            System.currentTimeMillis() >= prefs(context).getLong(KEY_RATE_LIMITED_UNTIL, 0)

    /** r172 (audit §5 5-18): the deferred-install flag, beside the in-memory one. */
    private fun setPendingInstall(context: Context, pending: Boolean) {
        prefs(context).edit().putBoolean(KEY_PENDING_INSTALL, pending).apply()
    }

    private fun pendingInstall(context: Context): Boolean = prefs(context).getBoolean(KEY_PENDING_INSTALL, false)

    /**
     * r172 (audit §5 5-18/5-19): a downloaded update that survived process death —
     * the release is in prefs and the APK is still in the cache dir. Fail-closed:
     * no digest, a size disagreement, or a digest disagreement all return null,
     * and a complete-but-damaged file is deleted so the next download starts
     * fresh. A partial file is left alone — it is for a later download to resume
     * from, never deleted by a restore attempt.
     */
    private fun restoreReady(context: Context): State.Ready? {
        val release = savedRelease(context) ?: return null
        val expectedSha = release.sha256 ?: return null
        val apk = File(updatesDir(context), apkFileName(release.version))
        if (!apk.isFile) return null
        if (release.size > 0 && apk.length() > release.size) {
            apk.delete() // longer than the release: cannot be a prefix of it
            return null
        }
        if (release.size > 0 && apk.length() == release.size) {
            if (sha256Of(apk).equals(expectedSha, ignoreCase = true)) return State.Ready(release, apk)
            apk.delete() // complete but damaged: never install it, never resume from it
            return null
        }
        // Shorter than the release: a partial for a later download to resume from.
        return null
    }

    /** AutoSpace came to the front: check if a day has passed, and finish an install that waited on permission. */
    fun onAppOpened(context: Context) {
        val app = context.applicationContext
        val current = flow.value
        // r172 (audit §5 5-18): a deferred install survives process death. The
        // in-memory flag dies with the process; the persisted flag does not, and
        // the downloaded APK is still in the cache dir to restore from.
        if (pendingInstall(app) && (current is State.Idle || current is State.Ready)) {
            val ready = current as? State.Ready ?: restoreReady(app)
            if (ready != null && !carConnected && app.packageManager.canRequestPackageInstalls()) {
                flow.value = ready
                install(app) // clears the persisted flag when it fires, below
                return
            }
            if (ready == null) {
                // Nothing to install any more (apk gone or damaged): drop the stale flag.
                setPendingInstall(app, false)
            } else {
                // Still deferred (car connected, or permission still missing):
                // keep the flag and fall through to the normal path.
                installWhenAllowed = true
            }
        } else if (installWhenAllowed && current is State.Ready && context.packageManager.canRequestPackageInstalls()) {
            installWhenAllowed = false
            install(context)
            return
        }
        if (current != State.Idle) return
        // A release found earlier still shows after Android closed the app.
        savedRelease(context)?.let { flow.value = State.Available(it) }
        if (dueForCheck(context)) check(context)
    }

    /**
     * The notification's Install action (r172, audit §5 5-19): install a Ready
     * update, restoring it from disk first when the process died after the
     * download. Only an Idle flow restores — restoring while a download is in
     * flight could mistake its partial file for a finished update, and any other
     * state already shows the driver what to do on the Home card. The guards
     * live in [install] itself: a connected car refuses, a missing permission
     * opens settings and defers (and the deferral persists, 5-18).
     */
    fun installFromAction(context: Context) {
        val app = context.applicationContext
        if (flow.value !is State.Ready) {
            if (flow.value !is State.Idle) {
                GearslipLog.w("update: install action while not idle — ignoring")
                return
            }
            flow.value = restoreReady(app) ?: run {
                GearslipLog.w("update: install action with nothing ready to install")
                return
            }
        }
        install(app)
    }

    /**
     * A car session ended, so the driver is parked. Checks if a day has passed, then posts a
     * notification for a new version they haven't been told about yet. r172 (audit §5 5-19):
     * a downloaded update whose install waited out the drive gets its own treatment — it
     * outranks a merely-available one. When the app is in front, the deferred install
     * fires directly (the same auto-install the download itself attempted); otherwise
     * the driver gets a once-per-version "ready to install" notification with a real
     * Install action, even when the version was already announced while available.
     */
    fun onDriveEnded(context: Context) {
        if (!autoCheckEnabled(context)) return
        val app = context.applicationContext
        Thread({
            val current = flow.value
            if (dueForCheck(app) && current !is State.Checking && current !is State.Downloading) {
                flow.value = State.Checking
                flow.value = checkNow(app)
            }
            val ready = flow.value as? State.Ready
            if (ready != null && !carConnected && isAppForegrounded(app)) {
                GearslipLog.i("update: drive ended with ${ready.release.version} ready and app in front — installing")
                install(app)
                return@Thread
            }
            val release = foundRelease(app) ?: return@Thread
            if (prefs(app).getString(KEY_NOTIFIED, null) != release.version) {
                if (notify(app, release)) prefs(app).edit().putString(KEY_NOTIFIED, release.version).apply()
            }
            if (ready != null && !carConnected && prefs(app).getString(KEY_READY_NOTIFIED, null) != ready.release.version) {
                if (notifyReady(app, ready.release)) prefs(app).edit().putString(KEY_READY_NOTIFIED, ready.release.version).apply()
            }
        }, "gearslip-update").start()
    }

    /**
     * r172 (audit §5 5-19): is this process the foreground app, by the system's own
     * importance for our pid. A heuristic — importance can lag — but the safe
     * direction holds either way: a false negative posts the notification instead
     * of installing, and install() itself still refuses while the car is connected.
     */
    private fun isAppForegrounded(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        return runCatching {
            am.runningAppProcesses?.any {
                it.pid == android.os.Process.myPid() &&
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } == true
        }.getOrDefault(false)
    }

    /**
     * r172 (audit §5 5-19): "AutoSpace X is ready to install". The Install action
     * opens the app carrying [EXTRA_INSTALL_UPDATE], which GearslipActivity hands
     * to [installFromAction] — the guards in [install] still apply, so tapping it
     * while connected again simply does nothing. A distinct PendingIntent request
     * code is required: extras do not distinguish PendingIntents, and reusing the
     * open intent's code would hand the action the open intent instead.
     */
    private fun notifyReady(context: Context, release: Release): Boolean {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, GearslipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val installAction = PendingIntent.getActivity(
            context, NOTIFICATION_ID + 1,
            Intent(context, GearslipActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_INSTALL_UPDATE, true),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("AutoSpace ${release.version} is ready to install")
            .setContentText("Tap Install to update.")
            .setContentIntent(open)
            .addAction(android.R.drawable.stat_sys_download, "Install", installAction)
            .setAutoCancel(true)
            .build()
        return runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
            .onSuccess { GearslipLog.i("update: told the driver ${release.version} is ready to install") }
            .onFailure { GearslipLog.w("update: could not post the ready notification (${it.message})") }
            .isSuccess
    }

    /**
     * The car screen just came up. A new version gets a card in the top right, like a message,
     * once per version. Skipped while moving; the next plug-in tries again.
     */
    fun onCarConnected(context: Context) {
        if (!autoCheckEnabled(context)) return
        val app = context.applicationContext
        Thread({
            val current = flow.value
            if (dueForCheck(app) && current !is State.Checking && current !is State.Downloading) {
                flow.value = State.Checking
                flow.value = checkNow(app)
            }
            val release = foundRelease(app) ?: return@Thread
            if (prefs(app).getString(KEY_CAR_NOTIFIED, null) == release.version) return@Thread
            Thread.sleep(CAR_DELAY_MS)
            if (!carConnected || CarMotion.moving) return@Thread
            CarNotifications.post(
                CarNotification(
                    key = "gearslip-update",
                    id = CarNotifications.nextId(),
                    packageName = app.packageName,
                    appLabel = "AutoSpace",
                    title = "AutoSpace ${release.version} is out",
                    text = "Update in the AutoSpace app or from GitHub",
                    postedAt = System.currentTimeMillis(),
                    icon = runCatching { app.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96) }.getOrNull(),
                    reply = null,
                ),
                quiet = false,
            )
            prefs(app).edit().putString(KEY_CAR_NOTIFIED, release.version).apply()
            GearslipLog.i("update: showed ${release.version} on the car screen")
        }, "gearslip-update").start()
    }

    private fun foundRelease(app: Context): Release? = when (val s = flow.value) {
        is State.Available -> s.release
        is State.Downloading -> s.release
        is State.Ready -> s.release
        else -> savedRelease(app)
    }

    /** Posts "AutoSpace X is out"; tapping it opens the app, where the Home card has the button. */
    private fun notify(context: Context, release: Release): Boolean {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, GearslipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("AutoSpace ${release.version} is out")
            .setContentText("Tap to update.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
            .onSuccess { GearslipLog.i("update: told the driver about ${release.version}") }
            .onFailure { GearslipLog.w("update: could not post the notification (${it.message})") }
            .isSuccess
    }

    /** Asks GitHub now. Ignored while a check or download is under way. */
    fun check(context: Context) {
        val current = flow.value
        if (current is State.Checking || current is State.Downloading) return
        val app = context.applicationContext
        // r172 (audit §5 5-20): the rate-limit wait binds a manual tap too — a
        // check GitHub just refused would only 429 again. The wait is surfaced
        // at once instead of sending a doomed request, and the gate lifts itself.
        val limitedUntil = prefs(app).getLong(KEY_RATE_LIMITED_UNTIL, 0)
        val now = System.currentTimeMillis()
        if (limitedUntil > now) {
            val minutes = ((limitedUntil - now + 59_000) / 60_000).coerceAtLeast(1)
            flow.value = State.Failed(waitMinutesMessage(minutes))
            return
        }
        flow.value = State.Checking
        Thread({ flow.value = checkNow(app) }, "gearslip-update").start()
    }

    private fun checkNow(app: Context): State = runCatching { fetchNewest(channel(app)) }.fold(
        onSuccess = { release ->
            prefs(app).edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).remove(KEY_RATE_LIMITED_UNTIL).apply()
            if (release != null && isNewer(release.version, BuildConfig.VERSION_NAME)) {
                GearslipLog.i("update: ${release.version} is out (this is ${BuildConfig.VERSION_NAME})")
                save(app, release)
                State.Available(release)
            } else {
                prefs(app).edit().remove(KEY_FOUND).apply()
                updatesDir(app).deleteRecursively()
                State.UpToDate
            }
        },
        onFailure = {
            // r172 (audit §5 5-20): a rate limit is remembered so automatic checks
            // honor the wait GitHub asked for; the message names the kind — the
            // connection message is reserved for actual network errors.
            val now = System.currentTimeMillis()
            if (it is RateLimitedException) {
                rateLimitUntilMs(it.retryAfterSeconds, it.resetEpochSeconds, now)?.let { until ->
                    prefs(app).edit().putLong(KEY_RATE_LIMITED_UNTIL, until).apply()
                }
            }
            GearslipLog.w("update: could not check: ${it.message}")
            State.Failed(checkFailureMessage(it, now))
        },
    )

    /** Downloads [release], then opens the installer once the file checks out. */
    fun download(context: Context, release: Release) {
        if (flow.value is State.Downloading) return
        val app = context.applicationContext
        flow.value = State.Downloading(release, 0f)
        Thread({
            // r172 (audit §5 5-23): keep the dir — a partial file is a resume, not
            // garbage. Only other versions' files go, so a stale download cannot
            // pile up beside the one being resumed.
            val dir = updatesDir(app).apply { mkdirs() }
            val apk = File(dir, apkFileName(release.version))
            dir.listFiles()?.filter { it != apk }?.forEach { it.delete() }
            flow.value = runCatching {
                fetchApk(release, apk)
                GearslipLog.i("update: downloaded ${release.version}")
                State.Ready(release, apk)
            }.getOrElse {
                // r172 (audit §5 5-23): only a damaged file is deleted. An
                // interrupted download keeps its partial, so the next attempt
                // resumes instead of restarting — the final gate below
                // re-verifies the whole file either way.
                if (it is UpdateVerificationException) apk.delete()
                GearslipLog.w("update: download failed: ${it.message}")
                State.Failed(it.message ?: "The download failed. Try again.", release)
            }
            if (flow.value is State.Ready) install(app)
        }, "gearslip-update").start()
    }

    /**
     * Hands the downloaded APK to Android's installer. The first time, Android wants the driver to
     * allow installs from AutoSpace; that opens its settings page, and the install carries on when
     * they come back.
     */
    fun install(context: Context) {
        val ready = flow.value as? State.Ready ?: return
        // Installing closes AutoSpace, which would drop the car screen mid-drive.
        if (carConnected) return
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            installWhenAllowed = true
            // r172 (audit §5 5-18): the deferral persists — process death must
            // not lose an install that was already waiting on this permission.
            setPendingInstall(context, true)
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logshare", ready.apk)
        // r172 (audit §5 5-18): the install fires now, so the deferral is consumed.
        installWhenAllowed = false
        setPendingInstall(context, false)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** The newest release with an APK that [channel] takes. Drafts never count.
     * r221: the flavor gate ([acceptsTag]) keeps each build on its own line. */
    private fun fetchNewest(channel: Channel): Release? {
        val stable = channel == Channel.STABLE
        val conn = (URL(if (stable) LATEST else ALL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AutoSpace/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode == 404) return null // no stable release yet
            // r172 (audit §5 5-20): a rate limit is its own failure kind, with the
            // wait GitHub asked for — never a generic connection message.
            if (conn.responseCode == 403 || conn.responseCode == 429) {
                throw RateLimitedException(
                    statusCode = conn.responseCode,
                    retryAfterSeconds = conn.getHeaderField("Retry-After")?.toLongOrNull(),
                    resetEpochSeconds = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull(),
                )
            }
            if (conn.responseCode !in 200..299) error("GitHub answered ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (stable) return release(JSONObject(body))?.takeIf { acceptsTag(it.version) }
            val list = org.json.JSONArray(body)
            return (0 until list.length()).map { list.getJSONObject(it) }
                .filterNot { it.optBoolean("draft") }
                .filter {
                    channel == Channel.ALPHA || !it.optBoolean("prerelease") ||
                        it.optString("tag_name").contains("beta", ignoreCase = true)
                }
                // r221: a build only ever sees its own line's tags.
                .filter { acceptsTag(it.optString("tag_name")) }
                .mapNotNull(::release)
                .reduceOrNull { best, next -> if (isNewer(next.version, best.version)) next else best }
        } finally {
            conn.disconnect()
        }
    }

    /** A release as GitHub lists it, or null when it has no APK attached. */
    private fun release(json: JSONObject): Release? {
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") && !it.optString("name").contains("debug") }
            ?: return null
        return Release(
            version = json.getString("tag_name").removePrefix("v"),
            apkUrl = apk.getString("browser_download_url"),
            size = apk.optLong("size", -1),
            sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            pageUrl = json.optString("html_url"),
        )
    }

    /**
     * r172 (audit §5 5-21/5-22/5-23): the download, hardened. A release without a
     * digest is refused before any bytes are fetched — size alone never verifies
     * an update (Q10 decision: fail-closed; the updater serves only GitHub release
     * assets, which all carry a digest, so no legitimate path lacks one). A
     * non-https URL is refused the same way. An interrupted download resumes:
     * a partial file is continued with a Range request, a complete partial is
     * verified instead of re-downloaded, and a server that ignores the range
     * restarts the file on the same connection (a 416 restarts on a fresh one).
     * Size + SHA-256 over the whole file stay the final gate, resumed or not.
     */
    private fun fetchApk(release: Release, into: File) {
        val expectedSha = release.sha256 ?: error("Couldn't verify this update.")
        if (!isSecureApkUrl(release.apkUrl)) error("The update link isn't secure, so it was refused.")
        val total = release.size.takeIf { it > 0 } ?: -1L
        val existing = if (into.exists()) into.length() else 0L
        var resumeFrom = when (resumePlan(existing, total)) {
            ResumePlan.FRESH -> 0L
            ResumePlan.RESUME -> existing
            ResumePlan.VERIFY_COMPLETE -> {
                if (sha256Of(into).equals(expectedSha, ignoreCase = true)) return // already whole and verified
                into.delete() // complete but damaged: start fresh below
                0L
            }
            ResumePlan.RESTART -> {
                into.delete() // longer than the release: cannot be a prefix of it
                0L
            }
        }
        // At most two attempts: the resume, then a fresh download when the server
        // refuses the range outright (416). A 200 to a ranged request restarts on
        // the same connection, so it needs no second attempt.
        repeat(2) { attempt ->
            val useRange = resumeFrom > 0
            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "AutoSpace/${BuildConfig.VERSION_NAME}")
                if (useRange) setRequestProperty("Range", "bytes=$resumeFrom-")
            }
            try {
                when {
                    conn.responseCode == 416 && attempt == 0 -> {
                        // Range unsatisfiable: the partial is not a prefix the server
                        // recognizes. Drop it and start fresh on the next attempt.
                        into.delete()
                        resumeFrom = 0L
                    }
                    conn.responseCode in 200..299 -> {
                        val resumed = resumesStream(conn.responseCode, useRange)
                        if (!resumed && useRange) {
                            // Server ignored the range and sent the whole file:
                            // the partial is not a prefix of this stream.
                            into.delete()
                            resumeFrom = 0L
                        }
                        val remaining = conn.contentLengthLong.takeIf { it >= 0 } ?: 0L
                        val totalForProgress = if (total > 0) total else resumeFrom + remaining
                        var done = resumeFrom
                        var shown = -1
                        conn.inputStream.use { input ->
                            FileOutputStream(into, resumed).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    done += n
                                    // Whole percents only, so the screen isn't redrawn for every buffer.
                                    val percent = if (totalForProgress > 0) (done * 100 / totalForProgress).toInt() else 0
                                    if (percent != shown) {
                                        shown = percent
                                        flow.value = State.Downloading(release, percent / 100f)
                                    }
                                }
                            }
                        }
                        // Final gate: size + SHA-256 over the whole file, resumed or not.
                        if (total > 0 && into.length() != total) throw UpdateVerificationException("The download stopped early. Try again.")
                        val hex = sha256Of(into)
                        if (!hex.equals(expectedSha, ignoreCase = true)) throw UpdateVerificationException("The download came out damaged. Try again.")
                        return
                    }
                    else -> error("GitHub answered ${conn.responseCode}")
                }
            } finally {
                conn.disconnect()
            }
        }
        error("The download failed. Try again.")
    }

    /** SHA-256 of a whole file, for the final gate and for restoring downloads. */
    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Installing waits until the car is unplugged. */
    val carConnected: Boolean
        get() = SessionStatus.state.value.phase.let { it == SessionStatus.Phase.CONNECTING || it == SessionStatus.Phase.PROJECTING }

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates")

    private fun save(context: Context, release: Release) {
        val json = JSONObject()
            .put("version", release.version)
            .put("apkUrl", release.apkUrl)
            .put("size", release.size)
            .put("sha256", release.sha256 ?: "")
            .put("pageUrl", release.pageUrl)
        prefs(context).edit().putString(KEY_FOUND, json.toString()).apply()
    }

    /** The release found last time, if it is still newer than this build (it isn't after updating). */
    private fun savedRelease(context: Context): Release? {
        val json = prefs(context).getString(KEY_FOUND, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val release = Release(
            version = json.optString("version"),
            apkUrl = json.optString("apkUrl"),
            size = json.optLong("size", -1),
            sha256 = json.optString("sha256").ifBlank { null },
            pageUrl = json.optString("pageUrl"),
        )
        return release.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
    }

    /**
     * "0.2.00-alpha" beats "0.1.10-alpha": the numbers before the dash compare one by one. With the
     * same numbers, a plain release beats a beta, and a beta beats an alpha, so "0.3.0" reaches
     * someone on "0.3.0-beta". With the same numbers and the same stage, the number after the
     * stage word decides, so "0.5.0-beta.9" reaches someone on "0.5.0-beta.4" — without it a
     * newer beta looked the same as the one already installed and the updater said up to date.
     * An experimental tag ("0.5.0-exp.1") is a stage of its own below beta, so it never beats a
     * beta — and its suffix decides between experimental builds, so "0.5.0-exp.2" reaches
     * someone on "0.5.0-exp.1".
     */
    internal fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        fun stage(v: String): Int {
            val tag = v.substringAfter('-', "").lowercase()
            return when {
                tag.isEmpty() -> 3
                "beta" in tag -> 2
                "alpha" in tag -> 1
                else -> 0
            }
        }
        fun suffix(v: String): Int {
            // The number after the stage word ("beta.9" -> 9, "exp.2" -> 2); a bare stage ("beta") has none.
            val tag = v.substringAfter('-', "").lowercase()
            val afterStage = when {
                "beta" in tag -> tag.substringAfter("beta")
                "alpha" in tag -> tag.substringAfter("alpha")
                "exp" in tag -> tag.substringAfter("exp")
                else -> return 0
            }
            return afterStage.trimStart('.', '-', '_').takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        val a = parts(candidate)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        val stageA = stage(candidate)
        val stageB = stage(installed)
        if (stageA != stageB) return stageA > stageB
        return suffix(candidate) > suffix(installed)
    }
}
