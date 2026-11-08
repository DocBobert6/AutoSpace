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
    /** Hosts removed from Frequently visited (r186, Mariner's frequentHidden). */
    private const val KEY_FREQUENT_HIDDEN = "browser_frequent_hidden"
    /** Global strict popup blocking, ON by default (Phase 8, Notscape's popupStrict). */
    const val KEY_POPUP_STRICT = "browser_popup_strict"
    /** Address bar position (audit/browser build, Notscape's barPosition): "top" or "bottom". */
    private const val KEY_BAR_POSITION = "browser_bar_position"
    private const val KEY_TEXT_ZOOM = "browser_text_zoom"
    /** Per-site popup settings, JSON object host -> {"allow": bool, "block": bool}. */
    private const val KEY_POPUP_SITES = "browser_popup_sites"
    private const val MAX_HISTORY = 50
    private const val MAX_BOOKMARKS = 200

    // r191 (parity G2): bookmarks and history carry the page title
    // alongside the URL, reusing the pins' SavedPage shape. The stored
    // JSON array mixes plain strings (legacy entries written before
    // titles existed — a string is a URL with no title) and objects
    // {"url": ..., "title": ...}; an untitled new entry is still
    // written as a plain string, so old builds keep reading new
    // files. A malformed element is skipped, never fatal.
    //
    // The element codec is plain Kotlin over JSON text, not org.json:
    // on the JVM unit-test classpath org.json is a stub (put returns
    // null and stores nothing), so the codec has to stand on its own.
    // org.json is used only to split the stored array on the read
    // path, where the framework implementation is real.

    /** JSON-quotes one string: escapes quotes, backslashes, and control characters. */
    fun jsonQuote(value: String): String {
        val out = StringBuilder("\"")
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        return out.append("\"").toString()
    }

    /** Undoes jsonQuote's escaping (plus \uXXXX). Unknown escapes pass through as-is. */
    fun jsonUnquote(raw: String): String {
        if (!raw.contains('\\')) return raw
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch != '\\' || i + 1 >= raw.length) {
                out.append(ch)
                i++
                continue
            }
            when (val esc = raw[i + 1]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u' -> {
                    val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null && hex.length == 4) out.append(code.toChar()) else out.append("\\u").append(hex)
                    i += 2 + hex.length
                    continue
                }
                else -> out.append(esc)
            }
            i += 2
        }
        return out.toString()
    }

    /** Parses a JSON-quoted string ("...") into its value, or null when malformed. */
    fun jsonParseString(raw: String): String? {
        val text = raw.trim()
        if (!text.startsWith("\"")) return null
        var i = 1
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\') {
                i += 2
                continue
            }
            if (ch == '"') return jsonUnquote(text.substring(1, i))
            i++
        }
        return null
    }

    /**
     * The string value of [field] in a flat {"k": "v", ...} object
     * text, or null when the field is absent. Handles escaped
     * characters inside the value.
     */
    fun jsonStringField(raw: String, field: String): String? {
        val pattern = Regex("\"" + Regex.escape(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val match = pattern.find(raw) ?: return null
        return jsonUnquote(match.groupValues[1])
    }

    /**
     * One stored entry as its JSON-array element text: the bare URL
     * (JSON-quoted) when untitled, else a {"url": ..., "title": ...}
     * object.
     */
    fun encodeTitledEntry(page: SavedPage): String =
        if (page.title.isBlank()) jsonQuote(page.url)
        else "{\"url\":${jsonQuote(page.url)},\"title\":${jsonQuote(page.title)}}"

    /**
     * Parses one element text back: a {"url": ..., "title": ...}
     * object, a quoted URL string, or a bare URL (the element text
     * of a legacy plain string). Null when malformed or the URL is
     * blank — the caller skips it, never crashes.
     */
    fun decodeTitledEntry(raw: String): SavedPage? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("{")) {
            val url = jsonStringField(text, "url") ?: return null
            if (url.isBlank()) return null
            return SavedPage(url, jsonStringField(text, "title") ?: "")
        }
        val url = if (text.startsWith("\"")) jsonParseString(text) ?: return null else text
        return url.takeIf { it.isNotBlank() }?.let { SavedPage(it, "") }
    }

    /** The row title: the stored title, or the URL when none was ever recorded. */
    fun displayTitle(page: SavedPage): String = page.title.ifBlank { page.url }

    /**
     * Adds [page] at the front, dropping any older entry for the same
     * URL (a re-visit refreshes the title) and capping at [cap].
     */
    fun addTitledEntry(existing: List<SavedPage>, page: SavedPage, cap: Int): List<SavedPage> =
        (listOf(page) + existing.filter { it.url != page.url }).take(cap)

    /**
     * Fills in a missing title for [url] in place. Only blank titles
     * are filled — a recorded title is never overwritten — and a
     * blank [title] changes nothing.
     */
    fun fillMissingTitle(existing: List<SavedPage>, url: String, title: String): List<SavedPage> {
        if (title.isBlank()) return existing
        return existing.map { if (it.url == url && it.title.isBlank()) it.copy(title = title) else it }
    }

    private fun readTitled(context: Context, key: String): MutableList<SavedPage> {
        val raw = AppSettings.getString(context, key, "")
        if (raw.isBlank()) return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { decodeTitledEntry(array.get(it).toString()) }
                .filterNotNull().toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun writeTitled(context: Context, key: String, values: List<SavedPage>) {
        val joined = values.joinToString(",") { encodeTitledEntry(it) }
        AppSettings.putString(context, key, "[$joined]")
    }

    fun bookmarks(context: Context): List<SavedPage> = readTitled(context, KEY_BOOKMARKS)

    fun isBookmarked(context: Context, url: String): Boolean =
        readTitled(context, KEY_BOOKMARKS).any { it.url == url }

    /** Returns true when the bookmark was added (false when it was already there). */
    fun addBookmark(context: Context, url: String, title: String = ""): Boolean {
        if (url.isBlank()) return false
        val list = readTitled(context, KEY_BOOKMARKS)
        if (list.any { it.url == url }) return false
        writeTitled(context, KEY_BOOKMARKS, addTitledEntry(list, SavedPage(url, title), MAX_BOOKMARKS))
        return true
    }

    fun removeBookmark(context: Context, url: String) {
        val list = readTitled(context, KEY_BOOKMARKS)
        val kept = list.filter { it.url != url }
        if (kept.size != list.size) writeTitled(context, KEY_BOOKMARKS, kept)
    }

    fun history(context: Context): List<SavedPage> = readTitled(context, KEY_HISTORY)

    fun recordHistory(context: Context, url: String, title: String = "") {
        if (url.isBlank() || !url.startsWith("http")) return
        val list = readTitled(context, KEY_HISTORY)
        writeTitled(context, KEY_HISTORY, addTitledEntry(list, SavedPage(url, title), MAX_HISTORY))
    }

    /**
     * Backfills a title that arrived after the visit was recorded
     * (a page whose title loads late). Only blank titles are filled.
     */
    fun updateHistoryTitle(context: Context, url: String, title: String) {
        if (url.isBlank() || title.isBlank()) return
        val list = readTitled(context, KEY_HISTORY)
        val filled = fillMissingTitle(list, url, title)
        if (filled != list) writeTitled(context, KEY_HISTORY, filled)
    }

    fun clearHistory(context: Context) {
        writeTitled(context, KEY_HISTORY, emptyList())
    }

    // ---- Frequently visited removals (r186) ----
    // A host removed from Frequently visited is hidden from that list
    // only; its history entries are untouched, exactly as Mariner's
    // frequentHidden setting works. Stored newline-separated, one host
    // per line. There is no un-hide UI in v1; the hidden set is small
    // and clearing history does not clear it.
    fun parseFrequentHidden(raw: String): Set<String> =
        raw.lines().map { it.trim() }.filter { it.isNotBlank() }.toSet()

    fun frequentHiddenHosts(context: Context): Set<String> =
        parseFrequentHidden(AppSettings.getString(context, KEY_FREQUENT_HIDDEN, ""))

    fun hideFrequentHost(context: Context, host: String) {
        if (host.isBlank()) return
        val hidden = frequentHiddenHosts(context).toMutableSet()
        if (hidden.add(host.trim())) {
            AppSettings.putString(context, KEY_FREQUENT_HIDDEN, hidden.joinToString("\n"))
        }
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

    // ---- Reader display (r187, browser parity Phase 5 + G4) ----
    // Mariner's reader panel, stored as plain strings through AppSettings
    // like every other browser setting. The theme is blank until the user
    // picks one: blank means the reader keeps today's car-scheme colors
    // (surface / on-surface / primary), so nothing changes on upgrade;
    // a stored theme switches the shell to Mariner's Safari palette.
    private const val KEY_READER_THEME = "browser_reader_theme"
    private const val KEY_READER_FONT_SIZE = "browser_reader_font_size"
    private const val KEY_READER_FONT = "browser_reader_font"
    private const val KEY_READER_LINE_MULT = "browser_reader_line_mult"

    /** The stored reader theme key, or "" when the user never picked one. */
    fun readerTheme(context: Context): String =
        AppSettings.getString(context, KEY_READER_THEME, "")

    fun setReaderTheme(context: Context, theme: String) {
        AppSettings.putString(context, KEY_READER_THEME, readerThemeKey(theme))
    }

    /** Reader text size in px (Mariner's readerFontSize, stepper 14-28). */
    fun readerFontSizePx(context: Context): Int =
        coerceReaderFontSize(AppSettings.getString(context, KEY_READER_FONT_SIZE, "22").toIntOrNull() ?: 22)

    fun setReaderFontSizePx(context: Context, px: Int) {
        AppSettings.putString(context, KEY_READER_FONT_SIZE, coerceReaderFontSize(px).toString())
    }

    /** Reader font key (Mariner's readerFont): opensans, roboto, or inter. */
    fun readerFont(context: Context): String =
        readerFontKey(AppSettings.getString(context, KEY_READER_FONT, "opensans"))

    fun setReaderFont(context: Context, font: String) {
        AppSettings.putString(context, KEY_READER_FONT, readerFontKey(font))
    }

    /** Reader line spacing (Mariner's readerLineMult, 1.1-2.2, default 1.6). */
    fun readerLineMult(context: Context): Float =
        coerceReaderLineMult(AppSettings.getString(context, KEY_READER_LINE_MULT, "1.6").toFloatOrNull() ?: 1.6f)

    fun setReaderLineMult(context: Context, mult: Float) {
        AppSettings.putString(context, KEY_READER_LINE_MULT, "%.1f".format(java.util.Locale.US, coerceReaderLineMult(mult)))
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

    // ---- Global Privacy Control (r192, parity G1) ----
    /** GPC toggle, ON by default as in Mariner: sends Sec-GPC: 1 and sets navigator.globalPrivacyControl. */
    private const val KEY_GPC = "browser_gpc"

    /**
     * Pure GPC default rule, no storage involved so it is unit-testable:
     * nothing stored yet means ON — the toggle defaults on, as in Mariner.
     */
    fun gpcEnabledFromStored(raw: String?): Boolean = raw != "false"

    fun gpcEnabled(context: Context): Boolean =
        gpcEnabledFromStored(AppSettings.getString(context, KEY_GPC, "true"))

    fun setGpcEnabled(context: Context, on: Boolean) {
        AppSettings.putString(context, KEY_GPC, on.toString())
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

    // ---- Open links in a new tab (r190, parity Phase 7) ----
    /** Mariner's openLinksInNewTab, default off: a tapped link opens in a new tab. */
    private const val KEY_OPEN_LINKS_NEW_TAB = "browser_open_links_new_tab"

    fun openLinksInNewTab(context: Context): Boolean =
        AppSettings.getString(context, KEY_OPEN_LINKS_NEW_TAB, "false") == "true"

    fun setOpenLinksInNewTab(context: Context, on: Boolean) {
        AppSettings.putString(context, KEY_OPEN_LINKS_NEW_TAB, on.toString())
    }

    // ---- Filter lists (r85) ----
    /** The driver's own network rules, one UserBlockRules line per line. */
    private const val KEY_USER_NET = "browser_user_net"
    /** The driver's own cosmetic rules, one UserBlockRules line per line.
     *  Parsed by the engine when present; no add-UI in v1 (there is no
     *  element picker on the car surface), so this key only ever fills
     *  if a future round adds one.
     *  INTENTIONAL PLACEHOLDER (r173 audit §5 5-29): read-only key —
     *  read by userCosmeticRaw(), never written; do not sweep as dead
     *  code. */
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

    // ---- Browser menu customization (r185, parity Phase 2) ----
    // Hidden menu rows, stored as one comma-separated setting in
    // Mariner's shape. Blank means nothing hidden; the Customize
    // editor writes through here. No new permission, no new storage.
    private const val KEY_MENU_HIDDEN_ROWS = "browser_menu_hidden_rows"

    /** Pure reader for the stored hidden set — unit-testable without
     * Android, same contract as BrowserMenuRows.parseHidden. */
    fun parseMenuHidden(raw: String): Set<String> = BrowserMenuRows.parseHidden(raw)

    fun menuHiddenRows(context: Context): Set<String> =
        parseMenuHidden(AppSettings.getString(context, KEY_MENU_HIDDEN_ROWS, ""))

    fun setMenuRowVisible(context: Context, id: String, visible: Boolean) {
        val hidden = menuHiddenRows(context).toMutableSet()
        if (visible) hidden.remove(id) else hidden.add(id)
        AppSettings.putString(context, KEY_MENU_HIDDEN_ROWS, hidden.joinToString(","))
    }

    fun resetMenuRows(context: Context) {
        AppSettings.putString(context, KEY_MENU_HIDDEN_ROWS, "")
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
        CarSettings.lenientParse(KEY_SESSION_SAVED_AT, AppSettings.getString(context, KEY_SESSION_SAVED_AT, "0"), 0L, "BrowserStore") { it.toLongOrNull() }

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

    // ---- Newsstand (r162): saved articles, in-browser only ----
    // One record per saved article, stored as one line per record in
    // SharedPreferences via AppSettings — the same line-codec pattern
    // as encodePages/decodePages and the downloads codec, not org.json:
    // the org.json framework classes return defaults under
    // unitTests.isReturnDefaultValues, so a JSON codec cannot be
    // pinned by a JVM test here (see the downloads comment above).
    // The record IS the saved content: the extracted contentHtml is
    // stored, so reopening needs no re-parse and no network. Images
    // keep their original URLs and render best-effort online; fully
    // offline they show broken until image caching lands. Saved
    // articles are NEVER BookRecords and never land in the bookshelf
    // or the downloads library: articles go to Newsstand only.
    /** One saved article: the extracted content plus its origin. */
    data class SavedArticle(
        val id: String,
        val title: String,
        val siteName: String,
        val byline: String,
        val url: String,
        val savedAtMs: Long,
        val contentHtml: String,
    )

    /** Outcome of a Newsstand save, for the caller's toast. */
    enum class SaveArticleResult { SAVED, ALREADY_SAVED, FAILED, PRIVATE_REFUSED }

    private const val KEY_NEWSSTAND = "browser_newsstand"
    private const val MAX_NEWSSTAND = 200

    private fun encArticle(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun decArticle(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /**
     * Pure codec: one line per article, fields in fixed order —
     * id | title | site | byline | url | saved ms | content html.
     * URL-encoding keeps separators and newlines out of every field,
     * so multi-paragraph contentHtml survives as one line.
     */
    fun encodeNewsstand(articles: List<SavedArticle>): String =
        articles.joinToString("\n") { article ->
            listOf(
                encArticle(article.id),
                encArticle(article.title),
                encArticle(article.siteName),
                encArticle(article.byline),
                encArticle(article.url),
                article.savedAtMs.toString(),
                encArticle(article.contentHtml),
            ).joinToString("|")
        }

    /** Pure codec: short rows are skipped, blank ids/urls are skipped. */
    fun decodeNewsstand(raw: String): List<SavedArticle> {
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size != 7) return@mapNotNull null
            val id = decArticle(parts[0])
            val url = decArticle(parts[4])
            if (id.isBlank() || url.isBlank()) return@mapNotNull null
            SavedArticle(
                id = id,
                title = decArticle(parts[1]),
                siteName = decArticle(parts[2]),
                byline = decArticle(parts[3]),
                url = url,
                savedAtMs = parts[5].toLongOrNull() ?: 0L,
                contentHtml = decArticle(parts[6]),
            )
        }
    }

    /**
     * Pure private-origin gate (Mariner bookSaveAllowed, verbatim):
     * private-origin articles never save to Newsstand, because a save
     * would persist past PrivateProfile.wipe() and defeat the whole
     * private model. The button hides AND the save refuses.
     */
    fun newsstandSaveAllowed(isPrivate: Boolean): Boolean = !isPrivate

    /**
     * Pure save transition, so the cap, duplicate, and ordering rules
     * are unit-testable without a Context: a duplicate URL refuses
     * (browser-pins precedent — Mariner allows duplicates, this port
     * does not), a new article goes first, and the 201st article ages
     * out the oldest (bookmarks/downloads precedent: cap 200).
     * Returns null when the URL is already saved.
     */
    fun addToNewsstand(existing: List<SavedArticle>, article: SavedArticle): List<SavedArticle>? {
        if (existing.any { it.url == article.url }) return null
        return (listOf(article) + existing).take(MAX_NEWSSTAND)
    }

    /** All saved articles, newest first. */
    fun newsstand(context: Context): List<SavedArticle> =
        decodeNewsstand(AppSettings.getString(context, KEY_NEWSSTAND, ""))

    /**
     * Saves [article] explicitly — the caller passes the in-memory
     * article; nothing here reads any cache, and the deleted
     * ArticleCache stays deleted. Private origin refuses outright.
     */
    fun saveArticle(context: Context, article: Article, isPrivate: Boolean): SaveArticleResult {
        if (!newsstandSaveAllowed(isPrivate)) return SaveArticleResult.PRIVATE_REFUSED
        if (article.url.isBlank()) return SaveArticleResult.FAILED
        val record = SavedArticle(
            id = java.util.UUID.randomUUID().toString(),
            title = article.title,
            siteName = article.siteName,
            byline = article.byline,
            url = article.url,
            savedAtMs = System.currentTimeMillis(),
            contentHtml = article.contentHtml,
        )
        val updated = addToNewsstand(newsstand(context), record)
            ?: return SaveArticleResult.ALREADY_SAVED
        AppSettings.putString(context, KEY_NEWSSTAND, encodeNewsstand(updated))
        return SaveArticleResult.SAVED
    }

    fun removeNewsstandArticle(context: Context, id: String) {
        val list = newsstand(context).filter { it.id != id }
        AppSettings.putString(context, KEY_NEWSSTAND, encodeNewsstand(list))
    }

    // ---- Text zoom (audit fix, 2026-10-25, A2) ----
    // WebView textZoom percent, persisted like the bar position
    // so a chosen size survives tab switches and sessions.
    // 100 is the WebView default; 50..200 keeps pages readable
    // without breaking layouts that assume near-default sizes.
    fun textZoom(context: Context): Int =
        CarSettings.lenientParse(KEY_TEXT_ZOOM, AppSettings.getString(context, KEY_TEXT_ZOOM, "100"), 100, "BrowserStore") { it.toIntOrNull()?.coerceIn(50, 200) }

    fun setTextZoom(context: Context, zoom: Int) {
        AppSettings.putString(context, KEY_TEXT_ZOOM, zoom.coerceIn(50, 200).toString())
    }
}

