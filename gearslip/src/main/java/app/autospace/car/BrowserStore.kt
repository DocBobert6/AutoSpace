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
}
