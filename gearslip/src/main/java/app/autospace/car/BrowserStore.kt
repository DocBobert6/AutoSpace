package app.autospace.car

import android.content.Context
import app.autospace.AppSettings
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
    // One record per plain DownloadManager download, stored as a JSON array
    // in SharedPreferences via AppSettings — the same pattern as bookmarks
    // and history above, so no new database and no new permission. The
    // record shape is deliberately minimal but forward-compatible: slice 2
    // adds the media flag and the Offline library section on top of these
    // records without changing this codec. HLS (.m3u8), DASH (.mpd), and
    // segmented downloads get records only in slices 3–4; until then this
    // list covers plain DownloadManager downloads only.
    /** Plain-download lifecycle: in progress, finished, or failed. */
    enum class DownloadState { IN_PROGRESS, COMPLETE, FAILED }

    /**
     * One plain download. [downloadManagerId] is the DownloadManager id
     * returned at enqueue time (-1 when unknown — no slice-1 record is
     * created without one, but the codec tolerates it); [localSource] is
     * the DownloadManager local URI once the download completes, null
     * until then. [isPrivate] marks a private-tab origin: such records
     * are stored but never shown in the Downloads list, and their bytes
     * are deleted with the private profile (see BrowserDownloads).
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
    )

    private const val KEY_DOWNLOADS = "browser_downloads"
    private const val MAX_DOWNLOADS = 200

    /** Pure codec: one JSON object per record, tolerant on decode. */
    fun encodeDownloads(records: List<DownloadRecord>): String {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("url", record.url)
                    .put("file", record.fileName)
                    .put("state", record.state.name)
                    .put("dm", record.downloadManagerId)
                    .put("local", record.localSource ?: "")
                    .put("private", record.isPrivate)
                    .put("created", record.createdAtMs),
            )
        }
        return array.toString()
    }

    /** Pure codec: malformed rows are skipped, missing fields get safe defaults. */
    fun decodeDownloads(raw: String): List<DownloadRecord> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optString("id")
                val url = obj.optString("url")
                if (id.isBlank() || url.isBlank()) return@mapNotNull null
                val state = runCatching {
                    DownloadState.valueOf(obj.optString("state"))
                }.getOrDefault(DownloadState.IN_PROGRESS)
                DownloadRecord(
                    id = id,
                    url = url,
                    fileName = obj.optString("file"),
                    state = state,
                    downloadManagerId = obj.optLong("dm", -1L),
                    localSource = obj.optString("local").takeIf { it.isNotBlank() },
                    isPrivate = obj.optBoolean("private", false),
                    createdAtMs = obj.optLong("created", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** All records, newest first — including private-origin ones. */
    fun downloads(context: Context): List<DownloadRecord> =
        decodeDownloads(AppSettings.getString(context, KEY_DOWNLOADS, ""))

    /** The records the Downloads list shows: private-origin records never appear. */
    fun visibleDownloads(records: List<DownloadRecord>): List<DownloadRecord> =
        records.filter { !it.isPrivate }

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

