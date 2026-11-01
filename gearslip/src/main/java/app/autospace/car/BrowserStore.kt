package app.autospace.car

import android.content.Context
import app.autospace.AppSettings
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted browser bookmarks + history (Phase 3.4), stored as JSON arrays in
 * the app's SharedPreferences via [AppSettings] — so a bookmark survives a
 * restart, with no new storage permission and no database. History records only
 * main-frame navigations (see WebApp's doUpdateVisitedHistory), never
 * subframe loads, and is capped so it cannot grow without bound.
 */
object BrowserStore {
    private const val KEY_BOOKMARKS = "browser_bookmarks"
    private const val KEY_HISTORY = "browser_history"
    /** Global strict popup blocking, ON by default (Phase 8, Notscape's popupStrict). */
    const val KEY_POPUP_STRICT = "browser_popup_strict"
    /** Address bar position (audit/browser build, Notscape's barPosition): "top" or "bottom". */
    private const val KEY_BAR_POSITION = "browser_bar_position"
    private const val KEY_TEXT_ZOOM = "browser_text_zoom"
    /** Per-site popup settings, JSON object host -> {"allow": bool, "block": bool}. */
    private const val KEY_POPUP_SITES = "browser_popup_sites"
    private const val MAX_HISTORY = 50
    private const val MAX_BOOKMARKS = 200

    private fun read(context: Context, key: String): MutableList<String> {
        val raw = AppSettings.getString(context, key, "")
        if (raw.isBlank()) return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { array.getString(it) }
        }.getOrDefault(mutableListOf())
    }

    private fun write(context: Context, key: String, values: List<String>) {
        val array = JSONArray()
        values.forEach { array.put(it) }
        AppSettings.putString(context, key, array.toString())
    }

    fun bookmarks(context: Context): List<String> = read(context, KEY_BOOKMARKS)

    fun isBookmarked(context: Context, url: String): Boolean =
        read(context, KEY_BOOKMARKS).contains(url)

    /** Returns true when the bookmark was added (false when it was already there). */
    fun addBookmark(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        val list = read(context, KEY_BOOKMARKS)
        if (list.contains(url)) return false
        list.add(0, url)
        write(context, KEY_BOOKMARKS, list.take(MAX_BOOKMARKS))
        return true
    }

    fun removeBookmark(context: Context, url: String) {
        val list = read(context, KEY_BOOKMARKS)
        if (list.remove(url)) write(context, KEY_BOOKMARKS, list)
    }

    fun history(context: Context): List<String> = read(context, KEY_HISTORY)

    fun recordHistory(context: Context, url: String) {
        if (url.isBlank() || !url.startsWith("http")) return
        val list = read(context, KEY_HISTORY)
        list.remove(url)
        list.add(0, url)
        write(context, KEY_HISTORY, list.take(MAX_HISTORY))
    }

    fun clearHistory(context: Context) {
        write(context, KEY_HISTORY, emptyList())
    }

    // ---- Popup settings (Phase 8, Notscape's per-site popup model) ----
    // Strict = window.open / target=_blank never opens a window silently;
    // blocked targets are captured and offered from a notice instead.
    // Per site, allowPopups and blockPopups are mutually exclusive, like
    // Notscape's SiteSetting: allow = popups load (in the tab that opened
    // them, since the car browser never opens a popup tab on its own);
    // block = blocked silently, no notice;
    // neither = the strict default (capture + notice).
    /** One site's popup setting: allow, block-always, or the strict default. */
    enum class PopupSetting { STRICT, ALLOW, BLOCK }

    fun popupStrict(context: Context): Boolean =
        AppSettings.getString(context, KEY_POPUP_STRICT, "true") != "false"

    fun setPopupStrict(context: Context, strict: Boolean) {
        AppSettings.putString(context, KEY_POPUP_STRICT, strict.toString())
    }

    private fun popupSiteMap(context: Context): MutableMap<String, JSONObject> {
        val raw = AppSettings.getString(context, KEY_POPUP_SITES, "")
        val out = mutableMapOf<String, JSONObject>()
        if (raw.isBlank()) return out
        runCatching {
            val obj = JSONObject(raw)
            for (key in obj.keys()) out[key] = obj.getJSONObject(key)
        }
        return out
    }

    private fun writePopupSiteMap(context: Context, map: Map<String, JSONObject>) {
        val obj = JSONObject()
        for ((host, value) in map) obj.put(host, value)
        AppSettings.putString(context, KEY_POPUP_SITES, obj.toString())
    }

    fun popupSetting(context: Context, host: String): PopupSetting {
        if (host.isBlank()) return PopupSetting.STRICT
        val entry = popupSiteMap(context)[host] ?: return PopupSetting.STRICT
        return when {
            entry.optBoolean("allow", false) -> PopupSetting.ALLOW
            entry.optBoolean("block", false) -> PopupSetting.BLOCK
            else -> PopupSetting.STRICT
        }
    }

    fun setPopupSetting(context: Context, host: String, setting: PopupSetting) {
        if (host.isBlank()) return
        val map = popupSiteMap(context)
        if (setting == PopupSetting.STRICT) {
            map.remove(host)
        } else {
            map[host] = JSONObject()
                .put("allow", setting == PopupSetting.ALLOW)
                .put("block", setting == PopupSetting.BLOCK)
        }
        writePopupSiteMap(context, map)
    }

    fun popupAllow(context: Context, host: String): Boolean =
        popupSetting(context, host) == PopupSetting.ALLOW

    fun popupBlockAlways(context: Context, host: String): Boolean =
        popupSetting(context, host) == PopupSetting.BLOCK

    // ---- Address bar position (audit/browser build, Notscape's barPosition) ----
    // Default "top" (the position AutoSpace has always used); "bottom" moves
    // the bar below the page, and the WebApp column order (page, bar,
    // keyboard) lifts it above CarKeyboard while typing instead of
    // hiding it behind the keyboard.
    fun barAtBottom(context: Context): Boolean =
        AppSettings.getString(context, KEY_BAR_POSITION, "top") == "bottom"

    fun setBarAtBottom(context: Context, bottom: Boolean) {
        AppSettings.putString(context, KEY_BAR_POSITION, if (bottom) "bottom" else "top")
    }

    // ---- Browser batch (r21) ----
    /** Ad/tracker blocking toggle (bundled host list, BrowserFilter); ON by default. */
    private const val KEY_AD_BLOCK = "browser_ad_block"
    /** Per-site desktop choice, JSON object host -> true (desktop remembered). */
    private const val KEY_DESKTOP_SITES = "browser_desktop_sites"
    /** Saved session (non-private tabs only): "active:<idx>" + one page per line. */
    private const val KEY_SESSION = "browser_session"
    /**
     * When the session was last saved, epoch millis. Written on every
     * saveSession, cleared with the session. A session saved before this
     * key existed reads back 0 — a legacy session with no provable age.
     */
    private const val KEY_SESSION_SAVED_AT = "browser_session_saved_at"
    /**
     * Session freshness window (r130, the 4-hour rule): a saved session
     * restores only while younger than this. Exactly 4 hours is stale —
     * the rule discards at 4+ hours, it does not restore through them.
     */
    const val SESSION_FRESH_MS = 4L * 60 * 60 * 1000L

    /**
     * Pure freshness rule, no storage involved so it is unit-testable:
     * a session with no stamp (legacy, or never saved) counts as fresh —
     * with no provable age the rule restores rather than discards — and
     * a clock set backwards also counts as fresh, for the same reason:
     * only a proven 4+ hours of age discards.
     */
    fun sessionIsFresh(savedAtMs: Long, nowMs: Long): Boolean =
        savedAtMs <= 0L || nowMs - savedAtMs < SESSION_FRESH_MS
    /** Recently closed tabs, most recent first, capped. */
    private const val KEY_RECENTLY_CLOSED = "browser_recently_closed"
    private const val MAX_RECENTLY_CLOSED = 20
    /** Start-page pins the user sets himself; no invented defaults. */
    private const val KEY_PINS = "browser_start_pins"

    fun adBlockEnabled(context: Context): Boolean =
        AppSettings.getString(context, KEY_AD_BLOCK, "true") != "false"

    fun setAdBlockEnabled(context: Context, on: Boolean) {
        AppSettings.putString(context, KEY_AD_BLOCK, on.toString())
    }

    // ---- Downloads settings (r160, slice 4) ----
    /** Segmented media downloads, ON by default (see BrowserDownloads routing). */
    private const val KEY_SEGMENTED_DOWNLOADS = "browser_segmented_downloads"
    /** Wi-Fi-only downloads, OFF by default (see BrowserDownloads for the gate). */
    private const val KEY_WIFI_ONLY_DOWNLOADS = "browser_wifi_only_downloads"

    fun segmentedDownloadsEnabled(context: Context): Boolean =
        AppSettings.getString(context, KEY_SEGMENTED_DOWNLOADS, "true") != "false"

    fun setSegmentedDownloads(context: Context, on: Boolean) {
        AppSettings.putString(context, KEY_SEGMENTED_DOWNLOADS, on.toString())
    }

    fun wifiOnlyDownloadsEnabled(context: Context): Boolean =
        AppSettings.getString(context, KEY_WIFI_ONLY_DOWNLOADS, "false") == "true"

    fun setWifiOnlyDownloads(context: Context, on: Boolean) {
        AppSettings.putString(context, KEY_WIFI_ONLY_DOWNLOADS, on.toString())
    }

    // ---- Filter lists (r85) ----
    /** The driver's own network rules, one UserBlockRules line per line. */
    private const val KEY_USER_NET = "browser_user_net"
    /** The driver's own cosmetic rules, one UserBlockRules line per line.
     *  Parsed by the engine when present; no add-UI in v1 (there is no
     *  element picker on the car surface), so this key only ever fills
     *  if a future round adds one. */
    private const val KEY_USER_COSMETIC = "browser_user_cosmetic"

    fun userNetRaw(context: Context): String =
        AppSettings.getString(context, KEY_USER_NET, "")

    fun userCosmeticRaw(context: Context): String =
        AppSettings.getString(context, KEY_USER_COSMETIC, "")

    /** Appends one network rule line unless it is already stored. */
    fun addUserNetRule(context: Context, line: String) {
        if (line.isBlank()) return
        val lines = userNetRaw(context).lineSequence()
            .filter { it.isNotBlank() }.toMutableList()
        if (lines.contains(line)) return
        lines.add(line)
        AppSettings.putString(context, KEY_USER_NET, lines.joinToString("\n"))
    }

    /** Removes one network rule line (exact match). */
    fun removeUserNetRule(context: Context, line: String) {
        val lines = userNetRaw(context).lineSequence()
            .filter { it.isNotBlank() && it != line }.toList()
        AppSettings.putString(context, KEY_USER_NET, lines.joinToString("\n"))
    }

    fun desktopForHost(context: Context, host: String): Boolean {
        if (host.isBlank()) return false
        val raw = AppSettings.getString(context, KEY_DESKTOP_SITES, "")
        if (raw.isBlank()) return false
        return runCatching { JSONObject(raw).optBoolean(host, false) }.getOrDefault(false)
    }

    fun setDesktopForHost(context: Context, host: String, desktop: Boolean) {
        if (host.isBlank()) return
        val obj = runCatching { JSONObject(AppSettings.getString(context, KEY_DESKTOP_SITES, "")) }
            .getOrDefault(JSONObject())
        if (desktop) obj.put(host, true) else obj.remove(host)
        AppSettings.putString(context, KEY_DESKTOP_SITES, obj.toString())
    }

    /** Saves the session: non-private tabs only, URLs + titles, never playback state. */
    fun saveSession(context: Context, pages: List<SavedPage>, activeIndex: Int) {
        AppSettings.putString(context, KEY_SESSION, encodeSession(pages, activeIndex))
        AppSettings.putString(context, KEY_SESSION_SAVED_AT, System.currentTimeMillis().toString())
    }

    fun loadSession(context: Context): Pair<List<SavedPage>, Int> =
        decodeSession(AppSettings.getString(context, KEY_SESSION, ""))

    /** When the stored session was last saved, 0 when never saved or legacy. */
    fun sessionSavedAt(context: Context): Long =
        AppSettings.getString(context, KEY_SESSION_SAVED_AT, "0").toLongOrNull() ?: 0L

    fun clearSession(context: Context) {
        AppSettings.putString(context, KEY_SESSION, "")
        AppSettings.putString(context, KEY_SESSION_SAVED_AT, "")
    }

    fun recentlyClosed(context: Context): List<SavedPage> =
        decodePages(AppSettings.getString(context, KEY_RECENTLY_CLOSED, ""))

    fun addRecentlyClosed(context: Context, page: SavedPage) {
        if (page.url.isBlank() || !page.url.startsWith("http")) return
        val list = (listOf(page) + recentlyClosed(context))
            .distinctBy { it.url }.take(MAX_RECENTLY_CLOSED)
        AppSettings.putString(context, KEY_RECENTLY_CLOSED, encodePages(list))
    }

    fun removeRecentlyClosed(context: Context, url: String) {
        val list = recentlyClosed(context).filter { it.url != url }
        AppSettings.putString(context, KEY_RECENTLY_CLOSED, encodePages(list))
    }

    fun clearRecentlyClosed(context: Context) {
        AppSettings.putString(context, KEY_RECENTLY_CLOSED, "")
    }

    fun pins(context: Context): List<SavedPage> =
        decodePages(AppSettings.getString(context, KEY_PINS, ""))

    fun addPin(context: Context, page: SavedPage): Boolean {
        if (page.url.isBlank() || !page.url.startsWith("http")) return false
        val list = pins(context)
        if (list.any { it.url == page.url }) return false
        AppSettings.putString(context, KEY_PINS, encodePages(list + page))
        return true
    }

    fun removePin(context: Context, url: String) {
        AppSettings.putString(
            context, KEY_PINS,
            encodePages(pins(context).filter { it.url != url }),
        )
    }

    // ---- Downloads (r157, slice 1 of the downloads + offline library port) ----
    // One record per plain DownloadManager download, stored as one line
    // per record in SharedPreferences via AppSettings — the same
    // line-codec pattern as encodePages/decodePages for session pages,
    // so no new database, no new permission, and the codec stays
    // unit-testable in this tree's plain-JUnit setup (the org.json
    // framework classes return defaults under
    // unitTests.isReturnDefaultValues, so a JSON codec cannot be
    // pinned by a JVM test here). The record shape is deliberately
    // minimal but forward-compatible: r158 (slice 2) added the media
    // flag and the Offline library section on top of these records
    // without changing the store key. r159 (slice 3) adds the worker
    // fields the same way: progress and engine kind ride as extra
    // line fields, and pre-slice rows decode with unknown progress
    // and the plain engine. HLS (.m3u8) downloads get worker records
    // in slice 3; DASH (.mpd) and segmented downloads arrive in
    // slice 4 (r160), which also adds the WAITING state for
    // Wi-Fi-only plain downloads and the two download settings.
    /** Download lifecycle: in progress, finished, failed, worker-paused, or waiting for Wi-Fi. */
    enum class DownloadState { IN_PROGRESS, COMPLETE, FAILED, PAUSED, WAITING }

    /** Which engine owns a record: plain DownloadManager, or one of the workers. */
    enum class DownloadKind { PLAIN, HLS, DASH, SEGMENTED }

    /**
     * One download. [downloadManagerId] is the DownloadManager id
     * returned at enqueue time (-1 when unknown — no slice-1 record is
     * created without one, but the codec tolerates it; worker records
     * never have one, since no DownloadManager row exists for them);
     * [localSource] is the DownloadManager local URI once the download
     * completes, null until then. [isPrivate] marks a private-tab origin: such records
     * are stored but never shown in either list, and their bytes
     * are deleted with the private profile (see BrowserDownloads).
     * [isMedia] (r158, slice 2) marks a video/audio download: such
     * records live in the Offline library, not in Downloads. It
     * defaults to false so pre-flag rows decode unchanged.
     * [progress] (r159, slice 3) is the worker-reported percent
     * 0..100, or -1 when unknown — plain DownloadManager downloads
     * never report one, so they stay unknown. [kind] (r159) is the
     * engine that owns the record; pre-slice rows decode as plain.
     * [state] WAITING (r160, slice 4) is a plain record that has
     * not been enqueued yet because the Wi-Fi-only setting is on
     * and the phone is not on an unmetered network; it starts on
     * the next list refresh once Wi-Fi is available (or the
     * setting is off), and it is never a failed download.
     * [kind] DASH and SEGMENTED (r160) are the slice-4 worker
     * engines; pre-slice rows still decode as plain, and unknown
     * kind names still degrade to plain.
     */
    data class DownloadRecord(
        val id: String,
        val url: String,
        val fileName: String,
        val state: DownloadState,
        val downloadManagerId: Long,
        val localSource: String?,
        val isPrivate: Boolean,
        val createdAtMs: Long,
        val isMedia: Boolean = false,
        val progress: Int = -1,
        val kind: DownloadKind = DownloadKind.PLAIN,
    )

    private const val KEY_DOWNLOADS = "browser_downloads"
    private const val MAX_DOWNLOADS = 200

    // Line-codec field escaping, same as the session-pages codec:
    // URL-encoding keeps separators and newlines out of every field.
    private fun encDownload(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun decDownload(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /**
     * Pure codec: one line per record, fields in fixed order —
     * id | url | file | state | manager id | local source | private 1/0 | created ms
     * | media 1/0 (r158; pre-flag rows have no ninth field and decode
     * with media false) | progress percent or -1 (r159; pre-slice rows
     * have no tenth field and decode with unknown progress)
     * | engine kind name (r159; pre-slice rows have no eleventh field
     * and decode as plain, unknown names also decode as plain;
     * r160 adds DASH and SEGMENTED as real kind names, and WAITING
     * as a real state name).
     */
    fun encodeDownloads(records: List<DownloadRecord>): String =
        records.joinToString("\n") { record ->
            listOf(
                encDownload(record.id),
                encDownload(record.url),
                encDownload(record.fileName),
                record.state.name,
                record.downloadManagerId.toString(),
                encDownload(record.localSource ?: ""),
                if (record.isPrivate) "1" else "0",
                record.createdAtMs.toString(),
                if (record.isMedia) "1" else "0",
                record.progress.toString(),
                record.kind.name,
            ).joinToString("|")
        }

    /** Pure codec: short rows are skipped, blank ids/urls are skipped, unknown fields default. */
    fun decodeDownloads(raw: String): List<DownloadRecord> {
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size != 8 && parts.size != 9 && parts.size != 11) return@mapNotNull null
            val id = decDownload(parts[0])
            val url = decDownload(parts[1])
            if (id.isBlank() || url.isBlank()) return@mapNotNull null
            val state = runCatching {
                DownloadState.valueOf(parts[3])
            }.getOrDefault(DownloadState.IN_PROGRESS)
            DownloadRecord(
                id = id,
                url = url,
                fileName = decDownload(parts[2]),
                state = state,
                downloadManagerId = parts[4].toLongOrNull() ?: -1L,
                localSource = decDownload(parts[5]).takeIf { it.isNotBlank() },
                isPrivate = parts[6] == "1",
                createdAtMs = parts[7].toLongOrNull() ?: 0L,
                isMedia = parts.getOrNull(8) == "1",
                progress = parts.getOrNull(9)?.toIntOrNull() ?: -1,
                kind = parts.getOrNull(10)?.let {
                    runCatching { DownloadKind.valueOf(it) }.getOrDefault(DownloadKind.PLAIN)
                } ?: DownloadKind.PLAIN,
            )
        }
    }

    /** All records, newest first — including private-origin ones. */
    fun downloads(context: Context): List<DownloadRecord> =
        decodeDownloads(AppSettings.getString(context, KEY_DOWNLOADS, ""))

    /**
     * The records the Downloads list shows (r158): ordinary files only.
     * Private-origin records never appear, and media records live in
     * the Offline library instead of here.
     */
    fun visibleDownloads(records: List<DownloadRecord>): List<DownloadRecord> =
        records.filter { !it.isPrivate && !it.isMedia }

    /** The records the Offline library shows (r158): media, non-private, any state. */
    fun visibleLibrary(records: List<DownloadRecord>): List<DownloadRecord> =
        records.filter { it.isMedia && !it.isPrivate }

    fun addDownload(context: Context, record: DownloadRecord) {
        val list = (listOf(record) + downloads(context))
            .distinctBy { it.id }.take(MAX_DOWNLOADS)
        AppSettings.putString(context, KEY_DOWNLOADS, encodeDownloads(list))
    }

    fun updateDownload(context: Context, record: DownloadRecord) {
        val list = downloads(context).map { if (it.id == record.id) record else it }
        AppSettings.putString(context, KEY_DOWNLOADS, encodeDownloads(list))
    }

    fun removeDownload(context: Context, id: String) {
        val list = downloads(context).filter { it.id != id }
        AppSettings.putString(context, KEY_DOWNLOADS, encodeDownloads(list))
    }

    fun downloadById(context: Context, id: String): DownloadRecord? =
        downloads(context).firstOrNull { it.id == id }

    fun downloadByManagerId(context: Context, dmId: Long): DownloadRecord? =
        downloads(context).firstOrNull { it.downloadManagerId == dmId }

    /** Pure retry gate: a completed record is a no-op, anything else may re-enqueue. */
    fun retryable(record: DownloadRecord): Boolean = record.state != DownloadState.COMPLETE

    /** Pure retry transition: same record, fresh manager id, back to in progress. */
    fun retried(record: DownloadRecord, newDmId: Long): DownloadRecord =
        record.copy(state = DownloadState.IN_PROGRESS, downloadManagerId = newDmId, localSource = null)

    /**
     * Pure worker retry/resume transition (r159): same record, no
     * manager id ever (worker records never have one), back to in
     * progress with its progress and engine kind kept — the worker
     * recomputes progress from the segments already on disk as it
     * runs, so a resumed run does not restart its count from zero.
     */
    fun retriedWorker(record: DownloadRecord): DownloadRecord =
        record.copy(state = DownloadState.IN_PROGRESS, localSource = null)

    // ---- Text zoom (audit fix, 2026-10-25, A2) ----
    // WebView textZoom percent, persisted like the bar position
    // so a chosen size survives tab switches and sessions.
    // 100 is the WebView default; 50..200 keeps pages readable
    // without breaking layouts that assume near-default sizes.
    fun textZoom(context: Context): Int =
        AppSettings.getString(context, KEY_TEXT_ZOOM, "100").toIntOrNull()?.coerceIn(50, 200) ?: 100

    fun setTextZoom(context: Context, zoom: Int) {
        AppSettings.putString(context, KEY_TEXT_ZOOM, zoom.coerceIn(50, 200).toString())
    }
}

