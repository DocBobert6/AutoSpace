package app.autospace.car

/**
 * Browser menu rows (r185, browser parity Phase 2): the menu sheet's row
 * registry, in Mariner's shape. Pure Kotlin — no Android, no Compose —
 * so the unit suite can pin it. The menu renders rows in [DEFAULT_ORDER],
 * grouped under section labels; the Customize editor lists [ALL] in the
 * same order. The "Customize menu" row itself is NOT in the registry:
 * it is never hideable, so the menu always has a way back to the editor.
 *
 * Hidden rows are stored as one comma-separated setting (Mariner's
 * menuHiddenRows shape) in BrowserStore; [parseHidden] is the pure
 * reader for that stored value. Unknown ids are dropped and duplicates
 * collapse, so an old store can never hide a row that no longer exists
 * or break the menu.
 */
object BrowserMenuRows {
    const val NEW_TAB = "newTab"
    const val NEW_PRIVATE_TAB = "newPrivateTab"
    const val RELOAD = "reload"
    const val SHARE_PAGE = "sharePage"
    const val BOOKMARK = "bookmark"
    const val PIN = "pin"
    const val READ_ARTICLE = "readArticle"
    const val MEDIA = "media"
    const val FIND_IN_PAGE = "findInPage"
    const val DESKTOP_SITE = "desktopSite"
    const val BOOKMARKS = "bookmarks"
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    /** Every row the menu can show, with its static label. */
    val ALL: List<Pair<String, String>> = listOf(
        NEW_TAB to "New tab",
        NEW_PRIVATE_TAB to "New private tab",
        RELOAD to "Reload",
        SHARE_PAGE to "Share page",
        BOOKMARK to "Bookmark this page",
        PIN to "Pin to start page",
        READ_ARTICLE to "Read this article",
        MEDIA to "Media on this page",
        FIND_IN_PAGE to "Find in page",
        DESKTOP_SITE to "Desktop site",
        BOOKMARKS to "Bookmarks",
        HISTORY to "History",
        SETTINGS to "Settings",
    )

    /** The menu's default row order (tabs, page actions, library, settings). */
    val DEFAULT_ORDER: List<String> = ALL.map { it.first }

    enum class MenuSection { TABS, PAGE, LIBRARY, SETTINGS }

    fun sectionOf(id: String): MenuSection = when (id) {
        NEW_TAB, NEW_PRIVATE_TAB -> MenuSection.TABS
        BOOKMARKS, HISTORY -> MenuSection.LIBRARY
        SETTINGS -> MenuSection.SETTINGS
        else -> MenuSection.PAGE
    }

    /** Rows that persist something (a bookmark, a start-page pin) and
     * therefore never render for a private tab: a private tab saves
     * nothing. Mariner's PRIVATE_HIDDEN rule, same ids. */
    val PRIVATE_HIDDEN: Set<String> = setOf(BOOKMARK, PIN)

    /** One authority for the private-tab menu: a row shows for a private
     * tab only when it is not a persisting row. */
    fun visibleForTab(id: String, isPrivate: Boolean): Boolean =
        !isPrivate || id !in PRIVATE_HIDDEN

    /** Pure reader for the stored comma-separated hidden set: blank
     * means nothing hidden; unknown ids are dropped; duplicates
     * collapse. */
    fun parseHidden(raw: String): Set<String> =
        raw.split(",").map { it.trim() }
            .filter { it.isNotEmpty() && it in DEFAULT_ORDER.toSet() }
            .toSet()

    fun isVisible(id: String, hidden: Set<String>): Boolean = id !in hidden
}
