package app.autospace.car

import android.content.Context
import app.autospace.AppSettings
import org.json.JSONArray

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
}
