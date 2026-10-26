package app.autospace.car

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.car.app.CarToast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import app.autospace.GearslipLog
import app.autospace.ProjectedContent
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsIconButton
import app.autospace.car.theme.GsPanelCard
import app.autospace.car.theme.frostBar
import app.autospace.notify.CarMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A browser inside the car UI. [content] is a URL, or raw HTML when it does not look like one.
 * The bottom bar's Back walks the page history first and only leaves the app at the start.
 *
 * A page's own text inputs can't call up the phone's IME: the car UI lives in a Presentation on
 * a virtual display, and the platform's soft keyboard has nowhere sensible to appear there (see
 * [CarKeyboard]'s doc comment - every other text field in this app already draws its own for the
 * same reason). [FOCUS_TRACKING_JS] reports focus/blur on the page's editable elements, and
 * [CarKeyboard] edits the focused one back through injected JS instead of through the DOM's own
 * native input path, which this Presentation can't reach.
 *
 * Browser theming (Phase 3.0, corrected): the CHROME follows AutoSpace (Material You colours
 * + Gs controls, below), but PAGES are never themed — no force-dark, no algorithmic darkening
 * from the phone theme or from darkness outside. Upstream darkened pages when it was dark
 * outside; AutoSpace sets isAlgorithmicDarkeningAllowed = false, so pages render as authored
 * (their own prefers-color-scheme dark modes still work naturally). The only page injection
 * kept is the focus ring (an accessibility outline, not a theme).
 */
/**
 * One browser tab (Phase 10): its own WebView, its own history (the
 * WebView holds it), its own current URL / Back-Forward state /
 * progress / error / found media / title. The WebView lives in [view]
 * for as long as WebApp is composed — switching tabs detaches it from
 * its AndroidView WITHOUT destroying it (AndroidView's onRelease is a
 * no-op here on purpose), and switching back re-attaches the same
 * instance, so a tab's page, scroll and history survive a switch.
 * Leaving the browser entirely destroys every tab's WebView (the
 * WebApp DisposableEffect), which matches the pre-tab behaviour where
 * leaving destroyed the single WebView. [rawHtml] is only for a launch
 * payload that is not a URL (the old loadDataWithBaseURL path); new
 * tabs always start at no-AI DuckDuckGo.
 */
private class BrowserTab(val id: Int, initialUrl: String, val rawHtml: String? = null) {
    var title by mutableStateOf<String?>(null)
    var currentUrl by mutableStateOf(initialUrl)
    /** Private tabs (r21): own WebView profile, never in history, session, or recently closed. */
    var isPrivate by mutableStateOf(false)
    /** Per-tab desktop user agent (r21); remembered per host in BrowserStore. */
    var desktopMode by mutableStateOf(false)
    /** A new-tab start page (r21): no WebView yet — the start page composable shows instead. */
    var isStartPage by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var progress by mutableStateOf(0)
    var loadError by mutableStateOf<String?>(null)
    val foundMedia = mutableStateListOf<FoundMedia>()
    var view: WebView? = null
}

/**
 * A URL the browser should open instead of its launch payload, set once by the voice
 * assistant's "search for X" command and consumed by the next [WebApp] composition. The
 * browser itself takes no URL parameter (its launch payload is [ProjectedContent]), so a
 * pending URL is the least invasive way to hand it one without changing the navigator.
 */
object BrowserLaunch {
    @Volatile var pendingUrl: String? = null
}

/**
 * Browser immersive fullscreen (Robert 2026-10-25), following the
 * PlayerImmersiveState pattern: the browser publishes here, CarUi
 * reads it to hide the nav bar. Per-entry session state, never
 * persisted as a setting: WebApp clears it on entry and on leaving,
 * so a later entry never starts immersive, and a hidden bar can
 * never outlive the browser. This is page fullscreen, NOT the
 * WebChromeClient fullscreen-video overlay (fullscreenView) — a
 * video's custom view keeps its own behaviour and BACK order.
 */
object BrowserImmersiveState {
    val immersive = MutableStateFlow(false)
    fun enter() { immersive.value = true }
    fun exit() { immersive.value = false }
}

@Composable
fun WebApp(content: String) {
    val navigator = LocalCarNavigator.current
    val context = LocalContext.current
    // Consumed once, on first composition: a voice search lands here, a normal open does not.
    val voiceUrl = remember { BrowserLaunch.pendingUrl?.also { BrowserLaunch.pendingUrl = null } }
    val startUrl = voiceUrl ?: if (content.startsWith("http")) content else ProjectedContent.DEFAULT_URL
    // Browser immersive fullscreen (Robert 2026-10-25): read the
    // published flow so header/bar/pill follow it, and never start
    // immersive on entry — leaving the browser clears it (see the
    // DisposableEffect below), this is the belt-and-braces half.
    val immersive by BrowserImmersiveState.immersive.collectAsState()
    LaunchedEffect(Unit) { BrowserImmersiveState.exit() }
    // Scroll-hide (Robert 2026-10-25 06:20 redirect): the address
    // bar row — and ONLY it — follows the active tab's WebView
    // scroll, in BOTH modes. Scroll down past the threshold hides
    // it, scroll up past the threshold shows it again, and it
    // always reappears at the top of the page. The deltas live in
    // a plain remembered holder, not State: scroll events fire
    // rapidly, and only the hide/show flips need recomposition.
    // Session state only, never persisted; leaving the browser
    // drops the whole composition, so nothing survives an entry.
    var barHiddenByScroll by remember { mutableStateOf(false) }
    val barScrollThresholdPx = remember { (24f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1) }
    val scrollHideAccum = remember { object { var down = 0; var up = 0 } }
    fun resetBarScrollHide() {
        barHiddenByScroll = false
        scrollHideAccum.down = 0
        scrollHideAccum.up = 0
    }
    val ring = String.format("#%06X", MaterialTheme.colorScheme.primary.toArgb() and 0xFFFFFF)
    var web by remember { mutableStateOf<WebView?>(null) }
    var fieldText by remember { mutableStateOf<String?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    // Phase 10 tabs: the tab list + active id live in composition (see
    // BrowserTab). The first tab is the launch payload; every later tab
    // starts at the start page (r21). Tabs are never zero: closing the last
    // tab resets it to a fresh start page in place (see closeTab).
    // Session restore (r21): when there is no explicit launch payload
    // (no voice URL, no http content), a saved session reopens its tabs
    // as URLs only — never playback state — with private tabs excluded
    // (they were never saved). A fresh install has no session and opens
    // no-AI DuckDuckGo, as before; an explicit http content payload
    // still wins over a session, as it did over the default.
    val restoredSession = remember {
        if (voiceUrl == null && !content.startsWith("http")) BrowserStore.loadSession(context)
        else emptyList<SavedPage>() to 0
    }
    val tabs = remember {
        val list = mutableStateListOf<BrowserTab>()
        if (restoredSession.first.isNotEmpty()) {
            restoredSession.first.forEachIndexed { index, page ->
                val tab = BrowserTab(index, page.url)
                tab.title = page.title.takeIf { it.isNotBlank() }
                list.add(tab)
            }
        } else {
            list.add(
                BrowserTab(
                    0,
                    startUrl,
                    rawHtml = if (voiceUrl == null && !content.startsWith("http")) content else null,
                )
            )
        }
        list
    }
    var nextTabId by remember { mutableStateOf(tabs.size) }
    var activeTabId by remember {
        mutableStateOf(
            if (restoredSession.first.isNotEmpty()) restoredSession.second.coerceIn(0, tabs.size - 1) else 0,
        )
    }
    /** Persists the session from the current tabs (non-private only). */
    fun saveSessionNow() {
        val pages = tabs.filter { !it.isPrivate && !it.isStartPage && it.currentUrl.startsWith("http") }
            .map { SavedPage(it.currentUrl, it.title ?: "") }
        if (pages.isEmpty()) BrowserStore.clearSession(context)
        else {
            val active = tabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0)
            val activeNonPrivate = tabs.take(active + 1).count { !it.isPrivate && !it.isStartPage } - 1
            BrowserStore.saveSession(context, pages, activeNonPrivate.coerceAtLeast(0))
        }
    }
    val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
    var tabsOpen by remember { mutableStateOf(false) }
    fun addMedia(tab: BrowserTab, url: String, title: String?) {
        if (!VideoUrl.isPlayableUrl(url)) return
        if (tab.foundMedia.none { it.url == url }) {
            tab.foundMedia.add(0, FoundMedia(url, title?.takeIf { it.isNotBlank() }))
        }
    }
    // Phase 3.4 state: address editing, lists, fullscreen video, and JS
    // dialogs — all in-composition, never real windows. Per-tab state
    // (URL, history, progress, error, media) lives on BrowserTab now.
    var addressText by remember { mutableStateOf(if (startUrl.startsWith("http")) startUrl else "") }
    var addressEditing by remember { mutableStateOf(false) }
    // Phase 10 select-all: true from the moment the pill is tapped until
    // the first keypress (see the address keyboard below). Address-only:
    // page-field editing (fieldText) has no selection state and is
    // untouched by this.
    var addressSelected by remember { mutableStateOf(false) }
    var listsOpen by remember { mutableStateOf(false) }
    // Audit fix (2026-10-25, A2/A3): browser text zoom (persisted
    // in BrowserStore, applied to the active tab) and find-in-page
    // state. The find bar follows tab switches: the active-tab
    // effect below re-runs the query on the new tab's WebView.
    var textZoom by remember { mutableStateOf(BrowserStore.textZoom(context)) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findMatches by remember { mutableStateOf("") }
    /** A new tab (r21) is a start page, private or not — never a bare search page. */
    fun newTab(isPrivate: Boolean = false) {
        val tab = BrowserTab(nextTabId++, "")
        tab.isPrivate = isPrivate
        tab.isStartPage = true
        tabs.add(tab)
        activeTabId = tab.id
        addressEditing = false
        addressSelected = false
        tabsOpen = false
        resetBarScrollHide()
        saveSessionNow()
    }
    /** A new tab already aimed at a URL (popups, long-press, recently closed). */
    fun newUrlTab(url: String, isPrivate: Boolean = false) {
        val tab = BrowserTab(nextTabId++, url)
        tab.isPrivate = isPrivate
        tab.desktopMode = BrowserStore.desktopForHost(context, hostOf(url))
        tabs.add(tab)
        activeTabId = tab.id
        addressEditing = false
        addressSelected = false
        tabsOpen = false
        resetBarScrollHide()
        saveSessionNow()
    }
    /** Loads [url] into [tab], waking a start-page tab (which has no WebView yet). */
    fun openUrlInTab(tab: BrowserTab, url: String) {
        tab.isStartPage = false
        tab.desktopMode = BrowserStore.desktopForHost(context, hostOf(url))
        if (tab.view != null) {
            tab.view?.loadUrl(url)
        } else {
            tab.currentUrl = url
        }
        saveSessionNow()
    }
    fun switchTab(tab: BrowserTab) {
        activeTabId = tab.id
        addressEditing = false
        addressSelected = false
        tabsOpen = false
        resetBarScrollHide()
        saveSessionNow()
    }
    fun closeTab(tab: BrowserTab) {
        val wasPrivate = tab.isPrivate
        if (tabs.size == 1) {
            // Never zero tabs: reset the last tab to a fresh start page
            // in place instead of removing it.
            if (tab.isStartPage) {
                tabsOpen = false
                resetBarScrollHide()
                return
            }
            tab.isStartPage = true
            tab.currentUrl = ""
            tab.title = null
            tab.foundMedia.clear()
            tab.loadError = null
            val v = tab.view
            tab.view = null
            mainHandler.post {
                runCatching { v?.destroy() }
                if (wasPrivate) PrivateProfile.wipe()
            }
            tabsOpen = false
            resetBarScrollHide()
            saveSessionNow()
            return
        }
        if (!wasPrivate && !tab.isStartPage && tab.currentUrl.startsWith("http")) {
            BrowserStore.addRecentlyClosed(context, SavedPage(tab.currentUrl, tab.title ?: ""))
        }
        val idx = tabs.indexOf(tab)
        tabs.remove(tab)
        if (tab.id == activeTabId) {
            activeTabId = tabs[(idx - 1).coerceAtLeast(0)].id
            resetBarScrollHide()
        }
        // Destroy after composition has detached it (posted, not now:
        // this tab's AndroidView may still be composed this frame).
        val v = tab.view
        tab.view = null
        mainHandler.post {
            runCatching { v?.destroy() }
            // The private profile dies with the last private tab.
            if (wasPrivate && tabs.none { it.isPrivate }) PrivateProfile.wipe()
        }
        saveSessionNow()
    }
    /** Downloads [url] through the phone Downloads folder — the same path the
     *  download listener uses, shared with the long-press menu (r21). */
    fun downloadUrl(url: String) {
        runCatching {
            val filename = URLUtil.guessFileName(url, null, null)
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(filename)
                .setDescription("Downloaded from the car browser")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            context.getSystemService(DownloadManager::class.java)?.enqueue(request)
            GearslipLog.i("car browser download started: $filename")
        }.onFailure { e ->
            GearslipLog.e("car browser download FAILED: $url - ${e.message}")
        }
    }
    /** Shares [url] cleaned of tracking parameters (r21 CleanLink). */
    fun shareUrl(url: String) {
        val cleaned = CleanLink.strip(url)
        if (cleaned.isBlank()) return
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, cleaned)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(send, "Share link"))
        }
    }
    /** Copies [url] cleaned of tracking parameters (r21 CleanLink) to the clipboard. */
    fun copyUrl(url: String) {
        val cleaned = CleanLink.strip(url)
        runCatching {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Link", cleaned))
            GearslipLog.i("car browser link copied (cleaned)")
        }
    }
    // r21 lists, declared before the tab-switch effect so that effect
    // can refresh them (a lambda cannot capture a variable declared
    // after it in the same scope).
    var recentlyClosed by remember { mutableStateOf(BrowserStore.recentlyClosed(context)) }
    var pins by remember { mutableStateOf(BrowserStore.pins(context)) }
    // Keep the Back/Home/Reload pointer on the active tab's WebView.
    // Also (A2) applies the stored text zoom to the newly active
    // tab and re-runs an open find query on it, so zoom/find
    // follow tab switches instead of silently resetting.
    // r21: tab switches also refresh the start page's lists, so a
    // pin added in one tab shows on a new tab's start page.
    LaunchedEffect(activeTabId) {
        web = activeTab.view
        web?.settings?.textZoom = textZoom
        if (findOpen && findQuery.isNotEmpty()) web?.findAllAsync(findQuery)
        bookmarks = BrowserStore.bookmarks(context)
        historyList = BrowserStore.history(context)
        recentlyClosed = BrowserStore.recentlyClosed(context)
        pins = BrowserStore.pins(context)
    }
    var bookmarks by remember { mutableStateOf(BrowserStore.bookmarks(context)) }
    var historyList by remember { mutableStateOf(BrowserStore.history(context)) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var jsDialog by remember { mutableStateOf<JsDialogState?>(null) }
    // Phase 8 strict popup blocking (Notscape's model, car-adapted):
    // strict ON by default; a blocked target is captured (up to 5, in
    // arrival order) and offered from a frost-panel notice; a per-site
    // Allow loads it in the tab that opened it (Phase 10 tabs; popups
    // never open a new tab on their own); a per-site Block always silences
    // it with no notice. Captured transport WebViews are never shown —
    // they exist only to learn the popup's URL — and are destroyed when
    // the notice is cleared.
    var popupStrict by remember { mutableStateOf(BrowserStore.popupStrict(context)) }
    var popupSettingForHost by remember { mutableStateOf(BrowserStore.PopupSetting.STRICT) }
    val pendingPopups = remember { mutableStateListOf<String>() }
    var popupHost by remember { mutableStateOf<String?>(null) }
    var showPopupNotice by remember { mutableStateOf(false) }
    val capturedPopupViews = remember { mutableListOf<WebView>() }
    fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host ?: "" }.getOrDefault("")
    fun clearPendingPopups() {
        pendingPopups.clear()
        showPopupNotice = false
        capturedPopupViews.forEach { runCatching { it.destroy() } }
        capturedPopupViews.clear()
    }
    // Phase 10 popup behaviour, unchanged except for tabs: an allowed
    // popup loads in the tab that opened it (and switches to it), and
    // opening a captured popup loads it in its originating tab too and
    // switches to it. Popups never open a new tab on their own.
    var popupTabId by remember { mutableStateOf<Int?>(null) }
    // Audit/browser build: address bar position (Notscape's barPosition,
    // persisted in BrowserStore; default top, the position AutoSpace has
    // always used) and the settings menu that replaced the ScreenHeader
    // exit arrow (Robert 2026-10-25 04:43). The menu is a kit sheet, not
    // a DropdownMenu: real popup windows crash on this virtual display,
    // the same reason JS dialogs are in-composition overlays.
    var barBottom by remember { mutableStateOf(BrowserStore.barAtBottom(context)) }
    // Exiting immersive restores the bar: without this, leaving
    // immersive (pill, BACK, keyboard) while the page sat at the top
    // left the bar hidden with no scroll event left to restore it —
    // the stuck-hidden-at-page-top case. Same for moving the bar
    // top/bottom: it reappears at its new edge instead of staying
    // hidden there. (Placed here, not by the immersive read above:
    // both names are only in scope from this point down.)
    LaunchedEffect(immersive) { if (!immersive) resetBarScrollHide() }
    LaunchedEffect(barBottom) { resetBarScrollHide() }
    var settingsMenuOpen by remember { mutableStateOf(false) }
    // Browser batch (r21) state: reader article + read-aloud progress live only
    // while the reader is open (no background service); the long-press target
    // is a URL plus whether it is an image; the search-engine editor text is
    // typed on the car keyboard like an address. Ad blocking and desktop mode
    // are persisted in BrowserStore and read fresh where they apply.
    var readerArticle by remember { mutableStateOf<Article?>(null) }
    var readerOpen by remember { mutableStateOf(false) }
    var readerNotice by remember { mutableStateOf<String?>(null) }
    var readingChunks by remember { mutableStateOf<List<String>>(emptyList()) }
    var readingIndex by remember { mutableStateOf(-1) }
    var readingToken by remember { mutableStateOf(0) }
    fun stopReading() {
        readingToken++
        readingIndex = -1
        app.autospace.speech.VoiceEngine.stop()
    }
    fun startReading() {
        val article = readerArticle ?: return
        val chunks = ArticleExtractor.sentenceChunks(ArticleExtractor.plainText(article))
        if (chunks.isEmpty()) {
            readerNotice = "Nothing to read aloud on this page."
            return
        }
        readingChunks = chunks
        val token = readingToken + 1
        readingToken = token
        fun playChunk(index: Int) {
            if (index >= chunks.size || token != readingToken) return
            readingIndex = index
            val started = app.autospace.speech.VoiceEngine.play(context, chunks[index], onDone = {
                if (token == readingToken) {
                    if (index + 1 < chunks.size) playChunk(index + 1)
                    else readingIndex = -1
                }
            }, onSilent = {
                if (token == readingToken) readingIndex = -1
            })
            if (!started) {
                readerNotice = "No spoken voice is downloaded yet. Download one in Settings first."
                readingIndex = -1
            }
        }
        playChunk(0)
    }
    data class LongPressTarget(val url: String, val isImage: Boolean)
    var longPressTarget by remember { mutableStateOf<LongPressTarget?>(null) }
    var engineEditing by remember { mutableStateOf(false) }
    var engineEditText by remember { mutableStateOf("") }
    var engineEditError by remember { mutableStateOf<String?>(null) }
    var adBlockOn by remember { mutableStateOf(BrowserStore.adBlockEnabled(context)) }
    /** Bumped to recreate the active tab's WebView (desktop toggle, r21). */
    var webViewEpoch by remember { mutableStateOf(0) }
    /** Reader mode (r21): runs vendored Readability against a clone of the
     *  live document, gated by isProbablyReaderable; a page that is not
     *  article-like yields a plain notice, never a broken reader. */
    fun enterReaderMode() {
        settingsMenuOpen = false
        val view = activeTab.view
        if (view == null || activeTab.isStartPage) {
            readerNotice = "Reader mode needs a loaded page."
            return
        }
        val url = activeTab.currentUrl
        view.evaluateJavascript(ArticleExtractor.loadLibrary(context) + ArticleExtractor.GLUE_JS) { result ->
            val article = ArticleExtractor.parseResult(result, url)
            if (article == null) {
                readerNotice = "This page doesn't look like an article."
            } else {
                stopReading()
                readerArticle = article
                readerOpen = true
            }
        }
    }
    /** Flips this tab's desktop mode, remembers it per host, and recreates
     *  the tab's WebView so the new user agent actually applies (a user
     *  agent change on a live WebView would not take effect until reload,
     *  and the tab's URL reloads from the fresh view). */
    fun toggleDesktop() {
        val tab = activeTab
        if (tab.isStartPage) return
        tab.desktopMode = !tab.desktopMode
        BrowserStore.setDesktopForHost(context, hostOf(tab.currentUrl), tab.desktopMode)
        val v = tab.view
        tab.view = null
        web = null
        mainHandler.post { runCatching { v?.destroy() } }
        webViewEpoch++
    }
    // Passenger gate (Robert 2026-10-25 05:40; rule corrected
    // 2026-10-25 after the every-stop re-ask report): the allowance
    // lasts the whole browser session — from entering the browser
    // until it closes — and is NEVER persisted. Stopping does NOT
    // clear it: the old code cleared passengerAllowed whenever
    // CarMotion said "not moving", so every red light re-asked a
    // passenger who had already answered. Now a stop only closes a
    // gate that is still open and unanswered; a granted allowance
    // survives any number of stops, and a fresh entry (the screen
    // recomposes from scratch, passengerAllowed back to false)
    // re-asks, as it must. Answering "Yes" is NOT an allowance — it
    // closes the browser outright — so the next entry still gates.
    // CarMotion is polled like the player does (1 s; GPS only
    // updates every 2 s).
    val moving by produceState(CarMotion.moving) {
        while (true) { value = CarMotion.moving; delay(1_000) }
    }
    var gateOpen by remember { mutableStateOf(false) }
    var passengerAllowed by remember { mutableStateOf(false) }
    LaunchedEffect(moving) {
        if (!moving) { gateOpen = false }
        else if (!passengerAllowed) { gateOpen = true }
    }
    // Immersive + keyboard: an address cannot be edited while the
    // bar is hidden, so opening the address keyboard (tapping the
    // pill to edit) exits immersive and restores the bar. The bar
    // is unreachable while immersive, so this is a guard for any
    // path that starts an edit — the bar is back and usable for
    // typing, with the bottom-bar-above-keyboard order unchanged.
    LaunchedEffect(addressEditing) {
        if (addressEditing) BrowserImmersiveState.exit()
    }
    fun openFirstPopup() {
        val url = pendingPopups.firstOrNull() ?: return
        pendingPopups.removeAt(0)
        val tab = tabs.firstOrNull { it.id == popupTabId } ?: activeTab
        tab.view?.loadUrl(url)
        if (tab.id != activeTabId) activeTabId = tab.id
        if (pendingPopups.isEmpty()) {
            showPopupNotice = false
            capturedPopupViews.forEach { runCatching { it.destroy() } }
            capturedPopupViews.clear()
        }
    }
    // Audit/browser build, Notscape's "Open all": the first captured URL
    // loads in its originating tab (as Open once does); every further
    // URL opens as a NEW tab — but only because the driver explicitly
    // tapped Open all, never on a popup's own initiative, which is what
    // the never-a-new-tab rule forbids. The new tabs are not switched
    // to; the driver lands back on the originating tab.
    fun openAllPopups() {
        val urls = pendingPopups.toList()
        if (urls.isEmpty()) return
        val origin = tabs.firstOrNull { it.id == popupTabId } ?: activeTab
        origin.view?.loadUrl(urls.first())
        if (origin.id != activeTabId) activeTabId = origin.id
        urls.drop(1).forEach { url ->
            val tab = BrowserTab(nextTabId++, url)
            tab.desktopMode = BrowserStore.desktopForHost(context, hostOf(url))
            tabs.add(tab)
        }
        clearPendingPopups()
    }
    // Audit/browser build, Notscape's popunder guard (see PopunderHosts
    // for why the host set is built-in rather than filter-derived).
    // Deliberately independent of the global strict switch, exactly as
    // Notscape's guard is: a popunder is never an "allowed popup", it
    // is the swap trick, so strict-off does not let it through — only a
    // per-site Block silences it entirely (no notice), like window.open.
    fun capturePopunder(url: String, pageHost: String) {
        if (BrowserStore.popupSetting(context, pageHost) == BrowserStore.PopupSetting.BLOCK) {
            GearslipLog.i("car browser popunder dropped silently for $pageHost (block always)")
            return
        }
        if (url !in pendingPopups && pendingPopups.size < 5) {
            pendingPopups.add(url)
            popupHost = pageHost
            popupTabId = activeTab.id
            showPopupNotice = true
            GearslipLog.i("car browser popunder captured for $pageHost")
        } else {
            GearslipLog.i("car browser popunder over cap for $pageHost, dropped")
        }
    }
    fun onPopupUrl(tab: BrowserTab, host: String, url: String, transport: WebView, allowed: Boolean) {        if (allowed) {
            runCatching { transport.destroy() }
            tab.view?.loadUrl(url)
            if (tab.id != activeTabId) activeTabId = tab.id
        } else if (pendingPopups.size < 5) {
            capturedPopupViews.add(transport)
            pendingPopups.add(url)
            popupHost = host
            popupTabId = tab.id
            showPopupNotice = true
        } else {
            // Over the 5-popup cap: drop the extra silently rather than
            // growing the notice without bound.
            runCatching { transport.destroy() }
        }
    }
    fun refreshLists() {
        bookmarks = BrowserStore.bookmarks(context)
        historyList = BrowserStore.history(context)
        popupStrict = BrowserStore.popupStrict(context)
        popupSettingForHost = BrowserStore.popupSetting(context, hostOf(activeTab.currentUrl))
        recentlyClosed = BrowserStore.recentlyClosed(context)
        pins = BrowserStore.pins(context)
        adBlockOn = BrowserStore.adBlockEnabled(context)
    }
    fun hideFullscreen() {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        fullscreenView = null
    }
    // Scroll-hide decision (Robert 2026-10-25 06:20): called on
    // the main thread for the ACTIVE tab only — a background
    // tab's scroll never moves the bar. Down accumulates until
    // the 24dp threshold, then the bar hides (only once past
    // the threshold position too, so a non-scrolling page —
    // scrollY pinned at 0 — keeps the bar visible); up
    // accumulates the same way before the bar returns, so tiny
    // direction jitters mid-scroll cannot flicker it; scrollY 0
    // always restores it. While the car keyboard is open
    // (address edit, or a page field being typed) or any
    // sheet/dialog/notice/video/gate is up, scrolls change
    // nothing — a scroll under an overlay must not flip the
    // bar, and the bar cannot be reached to open a sheet while
    // it is hidden, so sheets never open from a hidden bar.
    fun onWebScroll(tabId: Int, scrollY: Int, oldScrollY: Int) {
        if (tabId != activeTabId) return
        // Near the top restores the bar, not just exactly 0: a scroll
        // clamped just above 0 (resize, content change) used to leave
        // the bar hidden with nowhere left to scroll up to.
        if (scrollY < barScrollThresholdPx) {
            resetBarScrollHide()
            return
        }
        if (addressEditing || fieldText != null || settingsMenuOpen || tabsOpen ||
            sheetOpen || listsOpen || jsDialog != null || showPopupNotice ||
            fullscreenView != null || gateOpen || findOpen) return
        val delta = scrollY - oldScrollY
        if (delta > 0) {
            scrollHideAccum.up = 0
            scrollHideAccum.down += delta
            if (scrollHideAccum.down >= barScrollThresholdPx && scrollY >= barScrollThresholdPx) {
                barHiddenByScroll = true
            }
        } else if (delta < 0) {
            scrollHideAccum.down = 0
            scrollHideAccum.up += -delta
            if (scrollHideAccum.up >= barScrollThresholdPx) barHiddenByScroll = false
        }
    }

    // Popup notice auto-dismiss (Notscape's 12,000 ms, restarted when a
    // new popup arrives so a late capture is not cut off mid-read), then
    // pending popups are cleared exactly as Notscape clears them.
    LaunchedEffect(showPopupNotice, pendingPopups.size) {
        if (showPopupNotice) {
            delay(12_000)
            clearPendingPopups()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            capturedPopupViews.forEach { runCatching { it.destroy() } }
            // Leaving the browser destroys every tab's WebView (pre-tab
            // behaviour for the single WebView); switching tabs does not.
            val hadPrivate = tabs.any { it.isPrivate }
            tabs.forEach { runCatching { it.view?.destroy() } }
            // Session restore (r21): leaving saves the session, and a
            // private profile dies with its last private tab — leaving
            // with private tabs open destroys those views too.
            saveSessionNow()
            if (hadPrivate) PrivateProfile.wipe()
            stopReading()
            // Leaving clears immersive session state, so a later
            // entry never starts immersive (never persisted).
            BrowserImmersiveState.exit()
        }
    }

    DisposableEffect(web, sheetOpen, listsOpen, jsDialog, fullscreenView, showPopupNotice, tabsOpen, settingsMenuOpen, gateOpen, immersive, readerOpen, longPressTarget, engineEditing) {
        navigator.backInterceptor = {
            when {
                // Passenger gate first: head-unit BACK while the gate is
                // up is the "Yes, I'm driving" answer — the browser
                // closes, it does not just dismiss the gate and leave
                // the browser open behind it.
                gateOpen -> { gateOpen = false; navigator.forceBack(); true }
                fullscreenView != null -> { hideFullscreen(); true }
                jsDialog != null -> { jsDialog?.cancel(); jsDialog = null; true }
                // Reader (r21) closes before page history: BACK leaves
                // the article and returns to the page underneath.
                readerOpen -> { stopReading(); readerOpen = false; true }
                longPressTarget != null -> { longPressTarget = null; true }
                engineEditing -> { engineEditing = false; true }
                showPopupNotice -> { clearPendingPopups(); true }
                // Head-unit BACK closes the settings menu first, then the
                // other overlays, then steps back a page, then — only
                // when the page has no history left — exits the browser
                // (the interceptor returning false lets navigator.back()
                // pop the screen). The menu's Close browser button is
                // the only path that exits regardless of page history.
                settingsMenuOpen -> { settingsMenuOpen = false; true }
                tabsOpen -> { tabsOpen = false; true }
                sheetOpen -> { sheetOpen = false; true }
                listsOpen -> { listsOpen = false; true }
                // Audit fix (2026-10-25, A2): an open find bar
                // closes first, clearing the page highlights, and
                // never steps back a page while it is up.
                findOpen -> { findOpen = false; web?.clearMatches(); true }
                // Immersive exit sits here, after every overlay
                // above and before page history: head-unit BACK
                // exits immersive FIRST, and only a second BACK
                // steps back a page. Never a tap toggle — a tap
                // on the page is a link click.
                immersive -> { BrowserImmersiveState.exit(); true }
                web != null && web!!.canGoBack() -> { web!!.goBack(); true }
                else -> false
            }
        }
        onDispose { navigator.backInterceptor = null }
    }

    // Audit/browser build: the bar as one local composable, so the
    // same Notscape bar can sit at the top or the bottom (see
    // BrowserStore.barAtBottom). Column order below is header, [top
    // bar], page, [bottom bar], keyboards — so a bottom bar always
    // sits ABOVE CarKeyboard while typing, never behind it.
    val browserBar: @Composable () -> Unit = {
            // Address/search bar + navigation (Phase 3.4, item 1).
            // Tapping the address pill opens the car keyboard; submitting
            // navigates (a URL loads, anything else searches no-AI
            // DuckDuckGo). Audit/browser build, Notscape capsule language:
            // nav buttons in one frost capsule, the URL pill as its own
            // frost capsule with the load progress filling it, the tabs
            // button as Notscape's frost box with the count inside.
            // Unlike Notscape's phone bar, nothing collapses here —
            // Back/Forward/Reload/Home stay reachable while driving, and
            // every target stays 44dp or larger.
            // Phase 10, slimmer + Notscape-like: 44dp circular buttons
            // (Notscape's 44dp), a slimmer 44dp frost URL pill, tighter
            // padding (12dp sides, 2dp vertical, 6dp gaps), load
            // progress filling the pill itself (Notscape's pill fill)
            // instead of a separate bar below, the media finder as a
            // circular icon in the bar (count badge, hidden when no
            // media — the old row below the bar is gone), and a tabs
            // button with a tab-count badge. The pill is a frostBar
            // (not a GsButton) so its height can sit at 44dp under
            // GsButton's 48dp minimum; it still follows the theme
            // through the frost material + hairline.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Notscape's floating capsule group: Back/Forward in one
                // frost capsule (Notscape hides the group when there is
                // no history; the car keeps it always visible, because a
                // target that vanishes while driving is worse than a
                // disabled one — the disabled state is shown instead).
                // Reload/Home ride in the same capsule so the whole bar
                // is two frost groups + pill, as in Notscape.
                Row(
                    Modifier.frostBar(androidx.compose.foundation.shape.RoundedCornerShape(50), 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BarNavButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", { web?.goBack() }, activeTab.canGoBack)
                    BarNavButton(Icons.AutoMirrored.Filled.ArrowForward, "Forward", { web?.goForward() }, activeTab.canGoForward)
                    BarNavButton(Icons.Filled.Refresh, "Reload", { web?.reload() }, true)
                    BarNavButton(Icons.Filled.Home, "Home", {
                        if (activeTab.isStartPage) {
                            openUrlInTab(activeTab, if (content.startsWith("http")) content else ProjectedContent.DEFAULT_URL)
                        } else {
                            web?.loadUrl(if (content.startsWith("http")) content else ProjectedContent.DEFAULT_URL)
                        }
                    }, true)
                }
                Box(
                    Modifier.weight(1f).height(44.dp)
                        .frostBar(androidx.compose.foundation.shape.RoundedCornerShape(50), 6.dp)
                        // Notscape's pill swipe: a horizontal drag over
                        // the pill switches tabs (90px threshold, as in
                        // Notscape); a tap without drag still edits, as
                        // before. Swiping stays within the tab list and
                        // never creates or closes a tab.
                        .pointerInput(activeTabId, tabs.size) {
                            var dragTotal = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { dragTotal = 0f },
                                onHorizontalDrag = { _, amount -> dragTotal += amount },
                                onDragEnd = {
                                    val idx = tabs.indexOfFirst { it.id == activeTabId }
                                    val target = when {
                                        dragTotal < -90f && idx in 0 until tabs.size - 1 -> tabs[idx + 1]
                                        dragTotal > 90f && idx > 0 -> tabs[idx - 1]
                                        else -> null
                                    }
                                    if (target != null) switchTab(target)
                                    dragTotal = 0f
                                },
                                onDragCancel = { dragTotal = 0f },
                            )
                        }
                        .clickable {
                            // Tapping the pill selects all: starting an
                            // edit seeds from the active tab's URL and
                            // selects it; tapping again mid-edit
                            // re-selects (a tap while already editing
                            // must NOT re-seed — that wiped typed text).
                            if (!addressEditing) {
                                addressText = activeTab.currentUrl
                                addressEditing = true
                            }
                            addressSelected = true
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // Notscape pill fill: the load progress washes
                    // across the pill behind the text while loading, in
                    // the theme's onSurface ink (Notscape 0.0.63 onward:
                    // the accent-blue fill read as leftover teal, so the
                    // fill is a neutral onSurface gradient that fades
                    // out at its leading edge).
                    if (activeTab.progress in 1..99) {
                        Box(
                            Modifier.height(44.dp)
                                .fillMaxWidth(activeTab.progress / 100f)
                                .background(
                                    Brush.horizontalGradient(
                                        0f to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f),
                                        0.7f to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f),
                                        1f to MaterialTheme.colorScheme.onSurface.copy(alpha = 0f),
                                    ),
                                    androidx.compose.foundation.shape.RoundedCornerShape(50),
                                ),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            // While editing, show what is actually being
                            // typed: the live addressText, not
                            // currentUrl (the old behaviour made every
                            // keypress invisible, since CarKeyboard
                            // itself draws keys, not a field). Long text
                            // shows its tail, so the newest keystroke
                            // stays visible instead of hiding behind an
                            // ellipsis. While selected, the text sits on
                            // a primaryContainer highlight, so the
                            // selection itself is visible in the pill.
                            if (addressEditing) {
                                if (addressText.isEmpty()) {
                                    Text(
                                        "Type an address or search…",
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    val shown = if (addressText.length <= 40) addressText
                                        else "…" + addressText.takeLast(38)
                                    val label = if (addressSelected) shown else shown + "▏"
                                    if (addressSelected) {
                                        Text(
                                            label,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.background(
                                                MaterialTheme.colorScheme.primaryContainer,
                                                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                            ).padding(horizontal = 4.dp, vertical = 2.dp),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    } else {
                                        Text(
                                            label,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    if (activeTab.isStartPage) "New tab" else activeTab.currentUrl,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                        // One-tap clear while editing: clears the whole
                        // address (selection or not) and keeps editing,
                        // so the driver can start over without backspacing.
                        if (addressEditing && addressText.isNotEmpty()) {
                            GsIconButton(
                                Icons.Filled.Close, "Clear address",
                                { addressText = ""; addressSelected = false },
                                size = 32.dp, iconSize = 18.dp,
                                shape = androidx.compose.foundation.shape.CircleShape,
                            )
                        }
                    }
                }
                // Media finder (Phase 10, moved into the bar): a circular
                // icon with a count badge, shown only when the active
                // tab actually has media — the old GsButton row below
                // the bar is gone.
                if (activeTab.foundMedia.isNotEmpty()) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        Box(
                            Modifier.size(44.dp)
                                .frostBar(androidx.compose.foundation.shape.CircleShape, 6.dp)
                                .clickable { sheetOpen = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.Icon(
                                Icons.Filled.PlayArrow, "Media on this page",
                                tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp),
                            )
                        }
                        Box(
                            Modifier.size(20.dp)
                                .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${activeTab.foundMedia.size}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
                // Tabs (Phase 10, Notscape's tab button): a 44dp frost
                // box holding the count on a small surface (25dp, 8dp
                // radius) — the count is always shown, never zero tabs;
                // the switcher sheet holds New tab + the list.
                Box(
                    Modifier.size(44.dp)
                        .frostBar(androidx.compose.foundation.shape.CircleShape, 6.dp)
                        .clickable { tabsOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(25.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
                                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${tabs.size}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Box(
                    Modifier.size(44.dp)
                        .frostBar(androidx.compose.foundation.shape.CircleShape, 6.dp)
                        .clickable { refreshLists(); listsOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Filled.Star, "Saved & history",
                        tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp),
                    )
                }
                // Immersive fullscreen (Robert 2026-10-25): a 44dp
                // frost circle in the bar row, same target size as
                // the tabs/saved buttons, so it is present wherever
                // the bar sits (top or bottom — browserBar is
                // shared) and whenever the bar is shown (the bar
                // scroll-hides in both modes, see onWebScroll).
                // Tapping it enters immersive; the floating exit
                // pill is the on-screen way back (never a tap
                // toggle — a tap on the page is a link click).
                Box(
                    Modifier.size(44.dp)
                        .frostBar(androidx.compose.foundation.shape.CircleShape, 6.dp)
                        .clickable { BrowserImmersiveState.enter() },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Filled.OpenInFull, "Enter fullscreen",
                        tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp),
                    )
                }
            }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Audit/browser build: the ScreenHeader exit arrow is GONE
            // (Robert 2026-10-25 04:43). The header is now the title +
            // a settings-menu button; leaving the browser is the menu's
            // Close browser button, or BACK stepping through page
            // history first (see the back interceptor). Immersive
            // fullscreen (Robert 2026-10-25, bar rule redirected
            // 06:20) skips the header, leaving the weight(1f) page
            // WebView to fill the frame — the page reflows to its
            // new size, nothing is stretched or cropped — but the
            // bar STAYS VISIBLE on immersive entry and hides only
            // on scroll down (see onWebScroll); in normal mode the
            // header stays and only the bar scroll-hides.
            if (!immersive) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Browser",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    GsIconButton(Icons.Filled.MoreVert, "Browser settings", { settingsMenuOpen = true }, size = 56.dp, iconSize = 28.dp)
                }
            }
            // Floating bar (Notscape parity, audit fix): the page fills the
            // whole frame and the bar overlays it at the top or bottom
            // edge instead of taking a docked row — hiding or showing
            // the bar never resizes the page, so scroll positions and
            // scroll events stay stable. The overlay Box draws nothing
            // and takes no touches outside the bar itself, so page taps
            // pass through everywhere but the bar.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Phase 10 tabs: only the active tab's WebView is composed,
                // keyed by tab id so switching disposes one AndroidView and
                // composes the next. The factory returns a tab's EXISTING
                // WebView when it has one (a switch back re-attaches it,
                // page + history intact) and creates + loads it only once.
                // onRelease deliberately does NOT destroy: destroying here
                // would kill every tab on every switch. Tab WebViews die
                // only on tab close (posted) or on leaving the browser.
                // Start page (r21): a start-page tab has no WebView at all — the
                // ported start page composable fills the page area instead, and
                // opening anything from it wakes the tab (see openUrlInTab).
                if (activeTab.isStartPage) {
                    BrowserStartPage(
                        pins = pins,
                        bookmarks = bookmarks,
                        onSearch = { input ->
                            openUrlInTab(activeTab, BrowserSearch.navigateUrl(context, input))
                        },
                        onOpen = { url -> openUrlInTab(activeTab, url) },
                        onRemovePin = { url ->
                            BrowserStore.removePin(context, url)
                            pins = BrowserStore.pins(context)
                        },
                    )
                } else androidx.compose.runtime.key(activeTab.id, webViewEpoch) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            activeTab.view?.also { web = it } ?: WebView(context).apply {
                                // Audit fix (2026-10-25, A2): new tabs
                                // start at the stored text zoom, and
                                // report find matches for the find bar.
                                settings.textZoom = BrowserStore.textZoom(context)
                                setFindListener { active, matches, _ ->
                                    mainHandler.post {
                                        findMatches = if (matches > 0) "$active of $matches" else ""
                                    }
                                }
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                // Hardening baseline (r21): applied to every
                                // tab WebView at creation (see BrowserExtras
                                // for the geolocation / JS-interface notes).
                                applyWebHardening(settings)
                                val tab = activeTab
                                // Desktop site (r21): a desktop tab wears
                                // the desktop user agent; a mobile tab
                                // keeps the WebView default. Per-site
                                // memory is applied at tab creation and
                                // on toggle (see toggleDesktop).
                                if (tab.desktopMode) {
                                    settings.userAgentString =
                                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
                                }
                                // Private tabs (r21): the private profile
                                // must be assigned BEFORE anything loads.
                                if (tab.isPrivate) PrivateProfile.applyTo(this)
                                // Long-press link/image menu (r21): the
                                // hit test tells link from image; anything
                                // else keeps the default behaviour.
                                setOnLongClickListener {
                                    val hr = hitTestResult
                                    val extra = hr?.extra
                                    if (extra == null) {
                                        false
                                    } else when (hr.type) {
                                        WebView.HitTestResult.SRC_ANCHOR_TYPE,
                                        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                                            mainHandler.post {
                                                longPressTarget = LongPressTarget(extra, false)
                                            }
                                            true
                                        }
                                        WebView.HitTestResult.IMAGE_TYPE -> {
                                            mainHandler.post {
                                                longPressTarget = LongPressTarget(extra, true)
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                // Scroll-hide (Robert 2026-10-25 06:20):
                                // the tab's own scroll drives only the
                                // bar row (see onWebScroll). The listener
                                // rides this WebView, so a tab switch
                                // (re-attach reuses the same view) keeps
                                // it, and leaving the browser destroys
                                // the view with it.
                                setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                                    mainHandler.post { onWebScroll(tab.id, scrollY, oldScrollY) }
                                }
                                webViewClient = BrowserClient(
                                    ring = ring,
                                    onMedia = { url, title -> mainHandler.post { addMedia(tab, url, title) } },
                                    onPageStart = {
                                        mainHandler.post {
                                            tab.foundMedia.clear()
                                            tab.loadError = null
                                            // A new page load restores
                                            // the bar (active tab only;
                                            // in immersive mode the
                                            // header/nav bar stay hidden
                                            // until immersive exit).
                                            if (tab.id == activeTabId) resetBarScrollHide()
                                        }
                                    },
                                    onHistory = { url, back, forward ->
                                        mainHandler.post {
                                            tab.currentUrl = url
                                            tab.canGoBack = back
                                            tab.canGoForward = forward
                                            // Private tabs (r21) never
                                            // write history — and the
                                            // session saves with them
                                            // filtered out too.
                                            if (!tab.isPrivate) {
                                                BrowserStore.recordHistory(context, url)
                                            }
                                            saveSessionNow()
                                        }
                                    },
                                    onMainFrameError = { message ->
                                        mainHandler.post { tab.loadError = message }
                                    },
                                    onPopunder = { url, host ->
                                        mainHandler.post { capturePopunder(url, host) }
                                    },
                                )
                                webChromeClient = BrowserChrome(
                                    context = context,
                                    onPopupUrl = { host, url, transport, allowed ->
                                        mainHandler.post { onPopupUrl(tab, host, url, transport, allowed) }
                                    },
                                    onProgress = { p -> mainHandler.post { tab.progress = p } },
                                    onTitle = { t -> mainHandler.post { tab.title = t?.takeIf { it.isNotBlank() } } },
                                    onShowFullscreen = { view, callback ->
                                        // Fullscreen / dialogs belong to the
                                        // tab in front: a background tab's
                                        // video must not seize the screen.
                                        if (tab.id == activeTabId) {
                                            mainHandler.post {
                                                fullscreenView = view
                                                fullscreenCallback = callback
                                            }
                                        }
                                    },
                                    onHideFullscreen = { mainHandler.post { hideFullscreen() } },
                                    onJsDialog = { dialog ->
                                        if (tab.id == activeTabId) {
                                            mainHandler.post { jsDialog = dialog }
                                        }
                                    },
                                )
                                val downloadOwner = this
                                setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
                                    // Phase 3.4, item 4: downloads go to the phone Downloads
                                    // folder with a started/failed notice, instead of silently
                                    // doing nothing (the old behaviour).
                                    runCatching {
                                        val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                                        val request = DownloadManager.Request(Uri.parse(url))
                                            .setTitle(filename)
                                            .setDescription("Downloaded from the car browser")
                                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                                        context.getSystemService(DownloadManager::class.java)?.enqueue(request)
                                        GearslipLog.i("car browser download started: $filename")
                                        CarToasts.show(downloadOwner, "Download started: $filename", CarToast.LENGTH_SHORT)
                                    }.onFailure { e ->
                                        GearslipLog.e("car browser download FAILED: $url - ${e.message}")
                                        CarToasts.show(downloadOwner, "Download failed", CarToast.LENGTH_LONG)
                                    }
                                })
                                addJavascriptInterface(
                                    FieldFocusBridge(onFocus = { fieldText = it }, onBlur = { fieldText = null }),
                                    "GearslipField",
                                )
                                if (tab.rawHtml != null) {
                                    loadDataWithBaseURL(null, tab.rawHtml, "text/html", "utf-8", null)
                                } else {
                                    loadUrl(tab.currentUrl)
                                }
                                tab.view = this
                                web = this
                            }
                        },
                        // Phase 3.0: pages are NOT themed. Never re-enable algorithmic darkening here.
                        update = { it.settings.isAlgorithmicDarkeningAllowed = false },
                        // Tabs keep their WebView (see above) — but it MUST
                        // leave its parent here, or re-attaching it to the
                        // next tab's AndroidView throws "already has a parent".
                        onRelease = { view -> (view.parent as? ViewGroup)?.removeView(view) },
                    )
                }
                if (!barHiddenByScroll) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = if (barBottom) Alignment.BottomCenter else Alignment.TopCenter,
                    ) { browserBar() }
                }
            }
            if (!addressEditing && !engineEditing) {
                fieldText?.let { text ->
                    CarKeyboard(
                        text = text,
                        onTextChange = { updated ->
                            fieldText = updated
                            web?.evaluateJavascript(setFieldValueJs(updated), null)
                        },
                        onSubmit = { web?.evaluateJavascript(SUBMIT_FIELD_JS, null) },
                        onDismiss = {
                            fieldText = null
                            web?.evaluateJavascript(BLUR_FIELD_JS, null)
                        },
                    )
                }
            }
            // Address keyboard (Phase 3.4, fixed Phase 6): submitting navigates;
            // dismissing keeps the current page. While editing, the pill above
            // shows the live addressText with a caret, so every keypress is
            // visible; a dismissed edit simply reverts the pill to the URL.
            // Phase 10 select-all: CarKeyboard is a pure function of text
            // with no selection state, so selection lives HERE. While
            // addressSelected, the first keypress replaces the whole
            // selection (a letter/space reports old+char, so the result
            // is just the added suffix) and one backspace clears it all
            // (it reports old-minus-one, so the result is empty). After
            // that first keypress the selection is spent and typing
            // appends normally. The clear X in the pill and a fresh pill
            // tap re-select; submit/dismiss clear the selection.
            // Audit fix (2026-10-25, A2): find-in-page keyboard.
            // Typing re-runs findAllAsync on every change (empty
            // clears the highlights); submit jumps to the next
            // match; dismiss closes the bar and clears highlights.
            // It never touches the address edit above, and the
            // passenger gate still appears first while moving.
            if (findOpen && !addressEditing && !engineEditing) {
                CarKeyboard(
                    text = findQuery,
                    onTextChange = { updated ->
                        findQuery = updated
                        if (updated.isEmpty()) web?.clearMatches() else web?.findAllAsync(updated)
                    },
                    onSubmit = { web?.findNext(true) },
                    onDismiss = { findOpen = false; web?.clearMatches() },
                )
            }
            if (addressEditing) {
                CarKeyboard(
                    text = addressText,
                    onTextChange = { updated ->
                        if (addressSelected) {
                            addressSelected = false
                            addressText = when {
                                updated.length > addressText.length ->
                                    updated.takeLast(updated.length - addressText.length)
                                updated.length < addressText.length -> ""
                                else -> updated
                            }
                        } else {
                            addressText = updated
                        }
                    },
                    onSubmit = {
                        addressEditing = false
                        addressSelected = false
                        // Search uses the engine setting (r21); a
                        // start-page tab wakes into a real tab here.
                        val url = BrowserSearch.navigateUrl(context, addressText)
                        if (activeTab.isStartPage) openUrlInTab(activeTab, url) else web?.loadUrl(url)
                    },
                    onDismiss = {
                        addressEditing = false
                        addressSelected = false
                    },
                )
            }
            // Custom search engine editor (r21): a panel above the car
            // keyboard (not a scrim sheet — the keyboard must stay
            // reachable while typing the template). Save validates
            // with SearchTemplate's plain errors; Reset returns to
            // No-AI DuckDuckGo.
            if (engineEditing) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Custom search engine",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    Text(
                        if (engineEditText.isEmpty()) "https://example.com/search?q=%s" else engineEditText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (engineEditText.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Must start with https:// and contain %s where the search terms go.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    engineEditError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GsButton(onClick = {
                            val problem = SearchTemplate.validate(engineEditText)
                            if (problem != null) {
                                engineEditError = problem
                            } else {
                                BrowserSearch.setCustomTemplate(context, engineEditText)
                                engineEditing = false
                            }
                        }, modifier = Modifier.weight(1f)) { Text("Save") }
                        GsButton(onClick = {
                            BrowserSearch.clearCustomTemplate(context)
                            engineEditing = false
                        }, modifier = Modifier.weight(1f)) { Text("Reset to default") }
                        GsButton(onClick = { engineEditing = false }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    }
                }
                CarKeyboard(
                    text = engineEditText,
                    onTextChange = { updated ->
                        engineEditText = updated
                        engineEditError = null
                    },
                    onSubmit = {
                        val problem = SearchTemplate.validate(engineEditText)
                        if (problem != null) {
                            engineEditError = problem
                        } else {
                            BrowserSearch.setCustomTemplate(context, engineEditText)
                            engineEditing = false
                        }
                    },
                    onDismiss = { engineEditing = false },
                )
            }
        }
        // Immersive fullscreen exit pill (Robert 2026-10-25): a
        // small floating pill over the page's top-end corner,
        // always reachable, never part of the page content — a
        // tap here is the on-screen way out (tap-to-toggle is
        // forbidden: a tap on the page is a link click). The
        // surrounding Box draws nothing and takes no touches, so
        // page taps pass straight through everywhere but the
        // pill itself. Drawn before the error/menu/gate/video
        // overlays below, so those still cover it when active,
        // and the passenger gate still appears first while moving.
        if (immersive) {
            // The pill must never sit on the bar: with the bar at the
            // top and visible it drops below it (12dp margin + the
            // 48dp bar row); with the bar hidden by scroll, or at the
            // bottom, it rides the top edge as before.
            val pillTop = if (!barBottom && !barHiddenByScroll) 60.dp else 12.dp
            Box(
                Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = pillTop, bottom = 12.dp),
                contentAlignment = Alignment.TopEnd,
            ) {
                Box(
                    Modifier.height(44.dp)
                        .frostBar(RoundedCornerShape(50), 6.dp)
                        .clickable { BrowserImmersiveState.exit() }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Filled.FullscreenExit, "Exit fullscreen",
                            tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp),
                        )
                        Text(
                            "Exit fullscreen",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        // Audit fix (2026-10-25, A2): find-in-page pill. It rides
        // the top-START corner (the immersive exit pill owns the
        // top-end), dropping below a visible top bar exactly like
        // that pill, so the two never overlap. Prev/Next step
        // through matches; Close clears the highlights. The pill
        // only shows the query — typing happens in the CarKeyboard
        // below, so this never needs its own text field.
        if (findOpen) {
            val pillTop = if (!barBottom && !barHiddenByScroll) 60.dp else 12.dp
            Box(
                Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = pillTop, bottom = 12.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                Box(
                    Modifier.height(44.dp)
                        .frostBar(RoundedCornerShape(50), 6.dp)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (findQuery.isEmpty()) "Find in page" else findQuery,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp).widthIn(max = 220.dp),
                        )
                        if (findMatches.isNotEmpty()) {
                            Text(
                                findMatches,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Box(Modifier.size(44.dp).clickable { web?.findNext(false) }, contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowUp, "Previous match", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                        }
                        Box(Modifier.size(44.dp).clickable { web?.findNext(true) }, contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowDown, "Next match", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                        }
                        Box(Modifier.size(44.dp).clickable { findOpen = false; web?.clearMatches() }, contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(Icons.Filled.Close, "Close find", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
        // Friendly error page (Phase 3.4, item 2): replaces the log-only error.
        // The WebView stays composed underneath, so Retry just reloads it.
        // Phase 8: a kit panel, not a bare column, like every other browser
        // notice — the page itself stays unthemed, the chrome does not.
        if (activeTab.loadError != null) {
            Box(
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                GsPanelCard(Modifier.fillMaxWidth().padding(32.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "This page couldn't load",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                        Text(
                            activeTab.loadError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GsButton(onClick = { activeTab.loadError = null; web?.reload() }) { Text("Retry") }
                            GsButton(onClick = { activeTab.loadError = null; web?.goBack() }) { Text("Go back") }
                        }
                    }
                }
            }
        }
        if (settingsMenuOpen) {
            BrowserSettingsMenu(
                barBottom = barBottom,
                onBarPosition = { bottom ->
                    BrowserStore.setBarAtBottom(context, bottom)
                    barBottom = bottom
                },
                popupStrict = popupStrict,
                onPopupStrict = { strict ->
                    BrowserStore.setPopupStrict(context, strict)
                    popupStrict = strict
                },
                popupSetting = popupSettingForHost,
                onPopupSetting = { setting ->
                    BrowserStore.setPopupSetting(context, hostOf(activeTab.currentUrl), setting)
                    popupSettingForHost = setting
                },
                currentHost = hostOf(activeTab.currentUrl),
                onOpenLists = { settingsMenuOpen = false; refreshLists(); listsOpen = true },
                onOpenTabs = { settingsMenuOpen = false; tabsOpen = true },
                onCloseMenu = { settingsMenuOpen = false },
                onCloseBrowser = { settingsMenuOpen = false; navigator.forceBack() },
                textZoom = textZoom,
                onTextZoom = { zoom ->
                    val clamped = zoom.coerceIn(50, 200)
                    BrowserStore.setTextZoom(context, clamped)
                    textZoom = clamped
                    activeTab.view?.settings?.textZoom = clamped
                },
                onFindInPage = { settingsMenuOpen = false; findQuery = ""; findMatches = ""; findOpen = true },
                // Audit fix (2026-10-25, A3): share intent to the
                // phone browser. The car tab is untouched — nothing
                // here closes it, switches it, or reloads it — and
                // the passenger gate is unaffected because this
                // only fires from the settings menu, which the gate
                // already stands in front of while moving.
                // r21: the shared URL is cleaned (CleanLink) first.
                onOpenOnPhone = {
                    settingsMenuOpen = false
                    shareUrl(activeTab.currentUrl)
                },
                adBlockOn = adBlockOn,
                onAdBlock = { on ->
                    BrowserStore.setAdBlockEnabled(context, on)
                    adBlockOn = on
                },
                desktopMode = activeTab.desktopMode,
                canToggleDesktop = !activeTab.isStartPage,
                onToggleDesktop = { settingsMenuOpen = false; toggleDesktop() },
                onReaderMode = { enterReaderMode() },
                searchEngineLabel = BrowserSearch.customTemplate(context)
                    .takeIf { it.isNotBlank() } ?: BrowserSearch.DEFAULT_NAME,
                isCustomEngine = BrowserSearch.customTemplate(context).isNotBlank(),
                onResetSearchEngine = {
                    BrowserSearch.clearCustomTemplate(context)
                },
                onEditSearchEngine = {
                    settingsMenuOpen = false
                    engineEditText = BrowserSearch.customTemplate(context)
                    engineEditError = null
                    engineEditing = true
                },
            )
        }
        if (listsOpen) {
            BrowserListsSheet(
                bookmarks = bookmarks,
                history = historyList,
                currentUrl = activeTab.currentUrl,
                currentHost = hostOf(activeTab.currentUrl),
                popupStrict = popupStrict,
                popupSetting = popupSettingForHost,
                onPopupStrictChange = { strict ->
                    BrowserStore.setPopupStrict(context, strict)
                    popupStrict = strict
                },
                onPopupSettingChange = { setting ->
                    BrowserStore.setPopupSetting(context, hostOf(activeTab.currentUrl), setting)
                    popupSettingForHost = setting
                },
                isBookmarked = BrowserStore.isBookmarked(context, activeTab.currentUrl),
                isPinned = pins.any { it.url == activeTab.currentUrl },
                onPinToggle = {
                    val url = activeTab.currentUrl
                    if (pins.any { it.url == url }) {
                        BrowserStore.removePin(context, url)
                    } else {
                        BrowserStore.addPin(context, SavedPage(url, activeTab.title ?: ""))
                    }
                    refreshLists()
                },
                onLoad = { url ->
                    listsOpen = false
                    if (activeTab.isStartPage) openUrlInTab(activeTab, url) else web?.loadUrl(url)
                },
                onBookmarkToggle = {
                    if (BrowserStore.isBookmarked(context, activeTab.currentUrl)) {
                        BrowserStore.removeBookmark(context, activeTab.currentUrl)
                    } else {
                        BrowserStore.addBookmark(context, activeTab.currentUrl)
                    }
                    refreshLists()
                },
                onRemoveBookmark = { url ->
                    BrowserStore.removeBookmark(context, url)
                    refreshLists()
                },
                onClearHistory = {
                    BrowserStore.clearHistory(context)
                    refreshLists()
                },
                onClose = { listsOpen = false },
            )
        }
        // Fullscreen video (Phase 3.4, item 3): the WebView's custom view fills the
        // car frame; Back (or the page) hides it via the chrome callbacks.
        fullscreenView?.let { view ->
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                AndroidView(factory = { _ -> view }, modifier = Modifier.fillMaxSize())
            }
        }
        jsDialog?.let { dialog ->
            JsDialogOverlay(
                dialog = dialog,
                onDismiss = { jsDialog = null },
            )
        }
        // Phase 8 popup notice (Notscape's popup notice, car-sized): a frost
        // panel floating over the page — NOT a Gs sheet — naming the site
        // and offering Open once / Open all (Notscape's buttons), Allow
        // popups for this site (Notscape's whitelist-then-open), Block
        // always for this site, and Dismiss. Open once / Open all load
        // into the originating tab (Open all adds further URLs as new
        // tabs only on the explicit tap — see openAllPopups); popups
        // never open a new tab on their own. Audit/browser build: the
        // notice rides on the opposite edge from a bottom bar (top,
        // below the header) so it never sits under the bar or keyboard.
        if (showPopupNotice && pendingPopups.isNotEmpty()) {
            val host = popupHost?.takeIf { it.isNotBlank() }
                ?: hostOf(pendingPopups.first()).takeIf { it.isNotBlank() }
                ?: "this site"
            val count = pendingPopups.size
            Box(
                Modifier.fillMaxSize()
                    .padding(start = 16.dp, end = 16.dp, top = if (barBottom) 88.dp else 16.dp, bottom = 16.dp),
                contentAlignment = if (barBottom) Alignment.TopCenter else Alignment.BottomCenter,
            ) {
                Column(
                    Modifier.fillMaxWidth().frostBar(RoundedCornerShape(28.dp), 6.dp).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (count == 1) "Popup blocked on $host" else "$count popups blocked on $host",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    Text(
                        pendingPopups.first(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (count > 1) {
                        GsButton(onClick = { openAllPopups() }, modifier = Modifier.fillMaxWidth()) { Text("Open all ($count)") }
                    }
                    GsButton(onClick = { openFirstPopup() }, modifier = Modifier.fillMaxWidth()) { Text("Open once") }
                    GsButton(
                        onClick = {
                            BrowserStore.setPopupSetting(context, host, BrowserStore.PopupSetting.ALLOW)
                            popupSettingForHost = BrowserStore.PopupSetting.ALLOW
                            openFirstPopup()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Allow popups for this site") }
                    GsButton(
                        onClick = {
                            BrowserStore.setPopupSetting(context, host, BrowserStore.PopupSetting.BLOCK)
                            popupSettingForHost = BrowserStore.PopupSetting.BLOCK
                            clearPendingPopups()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Block always for this site") }
                    GsButton(onClick = { clearPendingPopups() }, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
                }
            }
        }
        if (sheetOpen) {
            MediaFinderSheet(
                items = activeTab.foundMedia,
                onOpen = { item ->
                    sheetOpen = false
                    navigator.videoPlayer(
                        activeTab.foundMedia.map { it.url },
                        activeTab.foundMedia.map { it.title },
                        activeTab.foundMedia.indexOf(item).coerceAtLeast(0),
                    )
                },
                onClose = { sheetOpen = false },
            )
        }
        if (tabsOpen) {
            TabSwitcherSheet(
                tabs = tabs,
                activeTabId = activeTabId,
                onSwitch = { switchTab(it) },
                onCloseTab = { closeTab(it) },
                onNewTab = { newTab() },
                onNewPrivateTab = { newTab(isPrivate = true) },
                recentlyClosed = recentlyClosed,
                onReopenClosed = { page ->
                    BrowserStore.removeRecentlyClosed(context, page.url)
                    recentlyClosed = BrowserStore.recentlyClosed(context)
                    newUrlTab(page.url)
                },
                onClearClosed = {
                    BrowserStore.clearRecentlyClosed(context)
                    recentlyClosed = emptyList()
                },
                onClose = { tabsOpen = false },
            )
        }
        // Reader mode (r21): the extracted article in its own shell,
        // JavaScript disabled, over the page it came from. BACK closes
        // it first (see the back interceptor); closing it stops any
        // read-aloud in flight, and read-aloud progress lives only
        // while this overlay is open — there is no background service.
        if (readerOpen) {
            val article = readerArticle
            if (article != null) {
                val bgHex = String.format("#%06X", MaterialTheme.colorScheme.surface.toArgb() and 0xFFFFFF)
                val fgHex = String.format("#%06X", MaterialTheme.colorScheme.onSurface.toArgb() and 0xFFFFFF)
                val linkHex = String.format("#%06X", MaterialTheme.colorScheme.primary.toArgb() and 0xFFFFFF)
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Reader",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        if (readingIndex >= 0) {
                            Text(
                                "Reading ${readingIndex + 1} of ${readingChunks.size}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            GsButton(onClick = { stopReading() }) { Text("Stop") }
                        } else {
                            GsButton(onClick = { startReading() }) { Text("Read aloud") }
                        }
                        GsIconButton(Icons.Filled.Close, "Close reader", {
                            stopReading()
                            readerOpen = false
                        }, size = 56.dp, iconSize = 28.dp)
                    }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = false
                                applyWebHardening(settings)
                                loadDataWithBaseURL(
                                    article.url,
                                    buildReaderShell(article, bgHex, fgHex, linkHex),
                                    "text/html", "utf-8", null,
                                )
                            }
                        },
                        onRelease = { view -> runCatching { view.destroy() } },
                    )
                }
            }
        }
        // Reader notice (r21): a plain reason when extraction refuses
        // (not an article) or no spoken voice is downloaded yet.
        if (readerNotice != null) {
            Box(
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                GsPanelCard(Modifier.fillMaxWidth().padding(32.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            readerNotice ?: "",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        GsButton(onClick = { readerNotice = null }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                    }
                }
            }
        }
        // Long-press link/image menu (r21): a frost sheet, never a
        // dropdown popup window (those crash on this virtual display).
        // Every row is a full-width button, 44dp or larger.
        val longPress = longPressTarget
        if (longPress != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)).clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        onClick = { longPressTarget = null },
                    ),
                )
                app.autospace.car.theme.GsSheetSurface(
                    modifier = Modifier.fillMaxWidth().padding(
                        start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp,
                    ),
                ) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                if (longPress.isImage) "Image" else "Link",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            )
                            GsIconButton(Icons.Filled.Close, "Close menu", { longPressTarget = null }, size = 56.dp, iconSize = 28.dp)
                        }
                        Text(
                            longPress.url,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!longPress.isImage) {
                            GsButton(onClick = {
                                longPressTarget = null
                                newUrlTab(longPress.url)
                            }, modifier = Modifier.fillMaxWidth()) { Text("Open in new tab") }
                        } else {
                            GsButton(onClick = {
                                longPressTarget = null
                                newUrlTab(longPress.url)
                            }, modifier = Modifier.fillMaxWidth()) { Text("View image") }
                            GsButton(onClick = {
                                longPressTarget = null
                                downloadUrl(longPress.url)
                            }, modifier = Modifier.fillMaxWidth()) { Text("Download") }
                        }
                        GsButton(onClick = {
                            copyUrl(longPress.url)
                            longPressTarget = null
                        }, modifier = Modifier.fillMaxWidth()) { Text("Copy link") }
                        GsButton(onClick = {
                            val url = longPress.url
                            longPressTarget = null
                            shareUrl(url)
                        }, modifier = Modifier.fillMaxWidth()) { Text("Share") }
                    }
                }
            }
        }
        // Passenger gate, last so it draws above every sheet, dialog
        // and the keyboard: the scrim swallows touches, so nothing
        // behind it is reachable until Yes or I'm a passenger is tapped.
        if (gateOpen) {
            PassengerGateDialog(
                onDriving = { gateOpen = false; navigator.forceBack() },
                onPassenger = { passengerAllowed = true; gateOpen = false },
            )
        }
    }
}

/**
 * The new-tab start page (r21), ported from Notscape's StartPage minus
 * the banner — see the delivery note for exactly what was left out.
 * What survives the port: a search entry (which searches with the
 * engine setting, (12)), pins the user sets himself (added from Saved
 * & history, removed here — no invented defaults), and the browser's
 * own bookmarks. What does not survive: the banner card, the update
 * note under it, the emulator sections (Shortcuts for sections /
 * consoles / games), Continue watching/listening (playback positions
 * belong to the player, not the browser), Continue reading (no books
 * here), and Frequently visited (history already has its own sheet).
 * Pages themselves are never themed — this page is AutoSpace chrome
 * shown instead of a WebView, not a restyled web page.
 */
@Composable
private fun BrowserStartPage(
    pins: List<SavedPage>,
    bookmarks: List<String>,
    onSearch: (String) -> Unit,
    onOpen: (String) -> Unit,
    onRemovePin: (String) -> Unit,
) {
    var searchText by remember { mutableStateOf("") }
    var searchEditing by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "New tab",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
        Box(
            Modifier.fillMaxWidth().height(44.dp)
                .frostBar(androidx.compose.foundation.shape.RoundedCornerShape(50), 6.dp)
                .clickable { searchEditing = true }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                if (searchText.isEmpty()) "Search or type an address…" else searchText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (searchText.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            "Search uses your search engine setting (No-AI DuckDuckGo unless you set a custom one).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Pins", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        if (pins.isEmpty()) {
            Text(
                "No pins yet. Pin a page from Saved & history (the star button) and it shows here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            pins.forEach { pin ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        )
                        .clickable { onOpen(pin.url) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            pin.title.takeIf { it.isNotBlank() }
                                ?: runCatching { Uri.parse(pin.url).host ?: pin.url }.getOrDefault(pin.url),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            pin.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    GsIconButton(Icons.Filled.Close, "Remove pin", { onRemovePin(pin.url) }, size = 44.dp, iconSize = 22.dp, shape = androidx.compose.foundation.shape.CircleShape)
                }
            }
        }
        Text("Bookmarks", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        if (bookmarks.isEmpty()) {
            Text(
                "No bookmarks yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            bookmarks.forEach { url ->
                GsButton(onClick = { onOpen(url) }, tone = app.autospace.car.theme.GsTone.Neutral, modifier = Modifier.fillMaxWidth()) {
                    Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (searchEditing) {
            CarKeyboard(
                text = searchText,
                onTextChange = { searchText = it },
                onSubmit = {
                    val input = searchText
                    searchEditing = false
                    if (input.isNotBlank()) onSearch(input)
                },
                onDismiss = { searchEditing = false },
            )
        }
    }
}

/**
 * One button inside the browser's Notscape frost capsule: a plain
 * 44dp IconButton with a 24dp onSurface icon (Notscape's bar buttons
 * wear the frost material itself, not a Gs control). A disabled button
 * dims its icon instead of vanishing, so the bar never reshapes while
 * driving.
 */
@Composable
private fun BarNavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    androidx.compose.material3.IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
    ) {
        androidx.compose.material3.Icon(
            icon, label,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * The browser's settings menu (audit/browser build): a regular-browser
 * menu in content — every setting the browser can change, plus Close.
 * A kit sheet, not a DropdownMenu, because real popup windows crash on
 * this virtual display. Rows are checkable where they hold a choice;
 * the selected row is bold on primary, the Phase 10 selected language.
 */
@Composable
private fun BrowserSettingsMenu(
    barBottom: Boolean,
    onBarPosition: (Boolean) -> Unit,
    popupStrict: Boolean,
    onPopupStrict: (Boolean) -> Unit,
    popupSetting: BrowserStore.PopupSetting,
    onPopupSetting: (BrowserStore.PopupSetting) -> Unit,
    currentHost: String,
    onOpenLists: () -> Unit,
    onOpenTabs: () -> Unit,
    onCloseMenu: () -> Unit,
    onCloseBrowser: () -> Unit,
    // Audit fix (2026-10-25, A2/A3): text zoom, find-in-page,
    // and open-on-phone, all from this same sheet.
    textZoom: Int,
    onTextZoom: (Int) -> Unit,
    onFindInPage: () -> Unit,
    onOpenOnPhone: () -> Unit,
    // Browser batch (r21).
    adBlockOn: Boolean,
    onAdBlock: (Boolean) -> Unit,
    desktopMode: Boolean,
    canToggleDesktop: Boolean,
    onToggleDesktop: () -> Unit,
    onReaderMode: () -> Unit,
    searchEngineLabel: String,
    isCustomEngine: Boolean,
    onResetSearchEngine: () -> Unit,
    onEditSearchEngine: () -> Unit,
) {
    @Composable
    fun MenuRow(label: String, checked: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth()
                .background(
                    if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                )
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (checked) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Check, null,
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
            } else {
                Spacer(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (checked) androidx.compose.ui.text.font.FontWeight.Bold
                else androidx.compose.ui.text.font.FontWeight.Normal,
                color = if (checked) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    @Composable
    fun MenuSection(title: String) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)).clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onCloseMenu,
            ),
        )
        app.autospace.car.theme.GsSheetSurface(
            // The car nav bar (64dp) overlays the bottom of the
            // browser screen, so a bottom sheet padded only 16dp had
            // its last rows covered by it. 80dp clears it with the
            // usual 16dp margin; when the nav bar is hidden the extra
            // margin is harmless, and the sheet still scrolls within
            // the space left.
            modifier = Modifier.fillMaxWidth().padding(
                start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp,
            ),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Browser settings", style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    GsIconButton(Icons.Filled.Close, "Close settings", onCloseMenu, size = 56.dp, iconSize = 28.dp)
                }
                MenuSection("Address bar")
                MenuRow("At the top", !barBottom) { onBarPosition(false) }
                MenuRow("At the bottom", barBottom) { onBarPosition(true) }
                Text(
                    "At the bottom, the bar sits above the keyboard while typing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MenuSection("Popups")
                MenuRow(
                    if (popupStrict) "Strict popup blocking: On" else "Strict popup blocking: Off",
                    popupStrict,
                ) { onPopupStrict(!popupStrict) }
                if (currentHost.isNotBlank()) {
                    MenuSection("Popups for $currentHost")
                    MenuRow("Strict default", popupSetting == BrowserStore.PopupSetting.STRICT) {
                        onPopupSetting(BrowserStore.PopupSetting.STRICT)
                    }
                    MenuRow("Allow", popupSetting == BrowserStore.PopupSetting.ALLOW) {
                        onPopupSetting(BrowserStore.PopupSetting.ALLOW)
                    }
                    MenuRow("Block always", popupSetting == BrowserStore.PopupSetting.BLOCK) {
                        onPopupSetting(BrowserStore.PopupSetting.BLOCK)
                    }
                }
                MenuSection("Text size")
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GsButton(onClick = { onTextZoom(textZoom - 25) }, modifier = Modifier.weight(1f)) { Text("A−") }
                    Text(
                        "$textZoom%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.widthIn(min = 56.dp),
                    )
                    GsButton(onClick = { onTextZoom(textZoom + 25) }, modifier = Modifier.weight(1f)) { Text("A+") }
                }
                Text(
                    "Larger or smaller page text, 50% to 200%. Saved, so it applies to every tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MenuSection("Page")
                MenuRow("Find in page", false) { onFindInPage() }
                MenuRow("Reader mode", false) { onReaderMode() }
                Text(
                    "Reader mode shows the article text large, with JavaScript off. " +
                        "It works only on pages that look like articles.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (canToggleDesktop) {
                    MenuRow("Desktop site", desktopMode) { onToggleDesktop() }
                    Text(
                        "Desktop site flips this tab to the desktop user agent and back. " +
                            "The choice is remembered for this site. New tabs start mobile.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MenuRow("Open on phone", false) { onOpenOnPhone() }
                Text(
                    "Open on phone shares this page's address to the phone's browser, " +
                        "with tracking parameters removed; the car tab stays open.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MenuSection("Ads & trackers")
                MenuRow("Block ads & trackers", adBlockOn) { onAdBlock(!adBlockOn) }
                Text(
                    "Blocks requests to a bundled list of ad and tracker hosts. " +
                        "The list ships with the app and never blocks the page itself. " +
                        "Turning it off loads everything again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MenuSection("Search engine")
                MenuRow("No-AI DuckDuckGo", !isCustomEngine) { onResetSearchEngine() }
                MenuRow(
                    if (isCustomEngine) searchEngineLabel else "Custom search engine…",
                    isCustomEngine,
                ) { onEditSearchEngine() }
                Text(
                    "A custom engine must be an https URL with %s where the search " +
                        "terms go. It drives the address bar and the start page search. " +
                        "Voice search always uses No-AI DuckDuckGo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MenuSection("Browser")
                MenuRow("Saved & history", false) { onOpenLists() }
                MenuRow("Tabs", false) { onOpenTabs() }
                GsButton(onClick = onCloseBrowser, modifier = Modifier.fillMaxWidth()) { Text("Close browser") }
            }
        }
    }
}

/**
 * Tab switcher (Phase 10): a bottom kit sheet listing every tab by
 * title (or host, or "New tab") + URL, with the active tab bold in
 * primary, a close X per tab, and a New tab row on top. Back closes
 * the sheet (see the back interceptor); closing the last tab resets
 * it to a fresh start page instead of leaving zero tabs.
 */
@Composable
private fun TabSwitcherSheet(
    tabs: List<BrowserTab>,
    activeTabId: Int,
    onSwitch: (BrowserTab) -> Unit,
    onCloseTab: (BrowserTab) -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    recentlyClosed: List<SavedPage>,
    onReopenClosed: (SavedPage) -> Unit,
    onClearClosed: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)).clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onClose,
            ),
        )
        app.autospace.car.theme.GsSheetSurface(
            // Same nav-bar clearance as the settings sheet (see
            // there): 80dp bottom padding, harmless when hidden.
            modifier = Modifier.fillMaxWidth().padding(
                start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp,
            ),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Tabs (${tabs.size})", style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    GsIconButton(Icons.Filled.Close, "Close tabs", onClose, size = 56.dp, iconSize = 28.dp)
                }
                GsButton(onClick = onNewTab, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Icon(Icons.Filled.Add, null, Modifier.size(22.dp))
                    Text("New tab")
                }
                GsButton(onClick = onNewPrivateTab, modifier = Modifier.fillMaxWidth()) {
                    Text("New private tab")
                }
                Text(
                    "Private tabs run in their own profile, leave no history, and " +
                        "are never restored. The profile is deleted when the last " +
                        "private tab closes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tabs.forEach { tab ->
                    val isActive = tab.id == activeTabId
                    Row(
                        Modifier.fillMaxWidth()
                            .background(
                                if (isActive) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                            )
                            .clickable { onSwitch(tab) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (tab.isPrivate) "Private · " else "") +
                                    (tab.title ?: runCatching { Uri.parse(tab.currentUrl).host ?: "" }.getOrDefault("").ifBlank { "New tab" }),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (isActive) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (tab.isStartPage) "Start page" else tab.currentUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        GsIconButton(Icons.Filled.Close, "Close tab", { onCloseTab(tab) }, size = 44.dp, iconSize = 22.dp, shape = androidx.compose.foundation.shape.CircleShape)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Recently closed",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    GsButton(onClick = onClearClosed, tone = app.autospace.car.theme.GsTone.Neutral) { Text("Clear list") }
                }
                if (recentlyClosed.isEmpty()) {
                    Text(
                        "No recently closed tabs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    recentlyClosed.forEach { page ->
                        GsButton(onClick = { onReopenClosed(page) }, tone = app.autospace.car.theme.GsTone.Neutral, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                page.title.takeIf { it.isNotBlank() } ?: page.url,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * WebViewClient for the car browser. Detection (Phase 3.2) uses
 * shouldInterceptRequest / onLoadResource (never blocking a load — it always returns
 * null / lets the resource through) plus <video>/<audio> element sources read on page
 * finish. No injected overlay on the page itself: overlays break on locked-down pages
 * and cross-site iframes, and the finder lives in AutoSpace chrome instead.
 * shouldInterceptRequest runs off the UI thread, so media reports are posted to the
 * main handler by the caller (see WebApp's onMedia).
 */
private class BrowserClient(
    private val ring: String,
    private val onMedia: (String, String?) -> Unit,
    private val onPageStart: () -> Unit,
    private val onHistory: (String, Boolean, Boolean) -> Unit = { _, _, _ -> },
    private val onMainFrameError: (String) -> Unit = {},
    private val onPopunder: (String, String) -> Unit = { _, _ -> },
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        request?.url?.toString()?.let { url ->
            if (VideoUrl.isPlayableUrl(url)) onMedia(url, null)
            // Ad/tracker blocking (r21, bundled host list): subresources
            // only — a main-frame load is NEVER blocked, so blocking
            // cannot break a page load itself. Runs off the UI thread,
            // so the toggle is read straight from BrowserStore.
            if (request.isForMainFrame == false && view != null) {
                val ctx = view.context
                if (BrowserStore.adBlockEnabled(ctx) && BrowserFilter.isBlockedUrl(url)) {
                    return BrowserFilter.emptyResponse()
                }
            }
        }
        return null
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        if (url != null && VideoUrl.isPlayableUrl(url)) onMedia(url, null)
    }

    // Audit/browser build, Notscape's popunder guard (0.0.962), logic
    // verbatim: a script-driven main-frame hop with no user gesture,
    // aimed at a known popunder host, is captured like a blocked popup
    // instead of loaded. Typed addresses never reach here (loadUrl
    // skips this callback) and tapped links carry a gesture. The host
    // test is PopunderHosts' built-in set — Notscape's set is derived
    // from filter lists AutoSpace does not have (see PopunderHosts).
    // Returning false for everything else preserves the previous
    // behaviour exactly (WebView handles http(s) itself).
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString()
        if (request?.isForMainFrame == true && request.hasGesture() == false && url != null &&
            (url.startsWith("http://") || url.startsWith("https://"))
        ) {
            val targetHost = runCatching { Uri.parse(url).host ?: "" }.getOrDefault("")
            val hereHost = runCatching { Uri.parse(view?.url ?: "").host ?: "" }.getOrDefault("")
            if (targetHost.isNotEmpty() && targetHost != hereHost &&
                PopunderHosts.isDocumentHost(targetHost)
            ) {
                onPopunder(url, hereHost)
                return true
            }
        }
        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        onPageStart()
    }

    // Main-frame history updates (Phase 3.4): the one reliable place to learn the
    // current URL and Back/Forward state, and to record history (subframe loads
    // never reach here, so history stays clean).
    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        if (view != null && url != null) {
            onHistory(url, view.canGoBack(), view.canGoForward())
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        GearslipLog.i("car web page loaded: $url")
        view?.evaluateJavascript(FOCUS_TRACKING_JS, null)
        view?.evaluateJavascript(focusRingJs(ring), null)
        view?.evaluateJavascript(EXTRACT_MEDIA_JS) { result ->
            runCatching {
                val array = JSONArray(result)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    onMedia(obj.optString("url"), obj.optString("title").takeIf { it.isNotBlank() })
                }
            }
        }
    }

    // onPageFinished fires for error pages too, so without this a blocked load (missing
    // INTERNET permission, no network) looks like a successful one.
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (request?.isForMainFrame == true) {
            GearslipLog.e("car web page FAILED: ${request.url} - ${error?.errorCode} ${error?.description}")
            onMainFrameError("Could not load ${request.url}. ${error?.description ?: "Check the connection and try again."}")
        }
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
        if (request?.isForMainFrame == true) {
            GearslipLog.w("car web page HTTP ${response?.statusCode} for ${request.url}")
        }
    }
}

/**
 * WebChromeClient for the car browser (Phase 3.4, item 3): load progress,
 * fullscreen video, and JS dialogs. Dialogs are reported to Compose and drawn
 * as in-composition overlays ([JsDialogOverlay]) — never as real Dialog
 * windows, which crash on this virtual display.
 */
private class BrowserChrome(
    private val context: android.content.Context,
    private val onPopupUrl: (host: String, url: String, transport: WebView, allowed: Boolean) -> Unit,
    private val onProgress: (Int) -> Unit,
    private val onTitle: (String?) -> Unit = {},
    private val onShowFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    private val onHideFullscreen: () -> Unit,
    private val onJsDialog: (JsDialogState) -> Unit,
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        onProgress(newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        onTitle(title)
    }

    /**
     * Phase 8 strict popup blocking (Notscape's onCreateWindow, car-adapted).
     * window.open / target=_blank never opens a window silently and never
     * navigates the main view silently:
     *  - per-site Block always: dropped silently, no notice, no transport.
     *  - allowed (strict off globally, or per-site Allow): the target's URL
     *    is captured and loaded in the tab that opened it (Phase 10;
     *    popups never open a new tab); the browser's Back control is the
     *    way back from it.
     *  - strict default: the target's URL is captured and reported, and the
     *    notice offers it, up to 5 in arrival order.
     * In the captured cases a transport WebView is still created and handed
     * to the message — without it window.open returns null and some pages
     * treat the popup as blocked and retry in a loop. The transport view is
     * never attached or shown; it exists only to learn the URL, then the
     * caller destroys it (allowed) or holds it until the notice clears.
     */
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean {
        if (resultMsg == null) return false
        val pageHost = view?.url?.let { runCatching { Uri.parse(it).host ?: "" }.getOrDefault("") } ?: ""
        val setting = BrowserStore.popupSetting(context, pageHost)
        if (setting == BrowserStore.PopupSetting.BLOCK) {
            GearslipLog.i("car browser popup blocked silently for $pageHost (block always)")
            return false
        }
        val allowed = !BrowserStore.popupStrict(context) || setting == BrowserStore.PopupSetting.ALLOW
        val transport = WebView(view?.context ?: context).apply {
            settings.javaScriptEnabled = true
            // Hardening baseline (r21) applies to captured popup
            // transports too, even though they are never shown.
            applyWebHardening(settings)
            webViewClient = PopupCaptureClient { url -> onPopupUrl(pageHost, url, this, allowed) }
        }
        (resultMsg.obj as? WebView.WebViewTransport)?.webView = transport
        resultMsg.sendToTarget()
        GearslipLog.i("car browser popup captured for $pageHost (allowed=$allowed)")
        return true
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view != null && callback != null) onShowFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        onHideFullscreen()
    }

    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        if (result == null) return false
        onJsDialog(JsDialogState.Alert(message ?: "", result))
        return true
    }

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        if (result == null) return false
        onJsDialog(JsDialogState.Confirm(message ?: "", result))
        return true
    }

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult?,
    ): Boolean {
        if (result == null) return false
        onJsDialog(JsDialogState.Prompt(message ?: "", defaultValue ?: "", result))
        return true
    }
}

/**
 * Reports the first http(s) URL a captured popup target navigates to, then
 * never again for that view. Both hooks are needed: for window.open(url)
 * the initial load may arrive via onPageStarted without shouldOverride
 * ever firing, while a script-driven hop fires shouldOverride first —
 * either way the URL is recorded exactly once, and returning true from
 * shouldOverride keeps the (never-shown) transport view from loading it.
 */
private class PopupCaptureClient(
    private val onFirstUrl: (String) -> Unit,
) : WebViewClient() {
    private var captured = false

    private fun capture(url: String?) {
        if (!captured && url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            captured = true
            onFirstUrl(url)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString()
        if (url != null && !captured) {
            capture(url)
            return true
        }
        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        capture(url)
    }
}

/** A JS dialog waiting for the driver, with its result object to answer through. */
private sealed interface JsDialogState {
    val message: String
    fun confirm() {}
    fun cancel() {}

    data class Alert(override val message: String, private val result: JsResult) : JsDialogState {
        override fun confirm() { result.confirm() }
        override fun cancel() { result.cancel() }
    }

    data class Confirm(override val message: String, private val result: JsResult) : JsDialogState {
        override fun confirm() { result.confirm() }
        override fun cancel() { result.cancel() }
    }

    data class Prompt(
        override val message: String,
        val defaultValue: String,
        private val result: JsPromptResult,
    ) : JsDialogState {
        fun confirmWith(value: String) { result.confirm(value) }
        override fun cancel() { result.cancel() }
    }
}

/** In-composition JS dialog overlay: message, car keyboard for prompts, Gs buttons. */
@Composable
private fun JsDialogOverlay(dialog: JsDialogState, onDismiss: () -> Unit) {
    var promptText by remember(dialog) { mutableStateOf((dialog as? JsDialogState.Prompt)?.defaultValue ?: "") }
    Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Phase 8: a kit panel, not a raw Material surface.
            GsPanelCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(dialog.message, style = MaterialTheme.typography.bodyLarge)
                    if (dialog is JsDialogState.Prompt) {
                        Text(promptText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (dialog) {
                            is JsDialogState.Alert -> {
                                GsButton(onClick = { dialog.confirm(); onDismiss() }, modifier = Modifier.weight(1f)) { Text("OK") }
                            }
                            is JsDialogState.Confirm -> {
                                GsButton(onClick = { dialog.confirm(); onDismiss() }, modifier = Modifier.weight(1f)) { Text("OK") }
                                GsButton(onClick = { dialog.cancel(); onDismiss() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                            }
                            is JsDialogState.Prompt -> {
                                GsButton(onClick = { dialog.confirmWith(promptText); onDismiss() }, modifier = Modifier.weight(1f)) { Text("OK") }
                                GsButton(onClick = { dialog.cancel(); onDismiss() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                            }
                        }
                    }
                }
            }
            if (dialog is JsDialogState.Prompt) {
                CarKeyboard(
                    text = promptText,
                    onTextChange = { promptText = it },
                    onSubmit = { dialog.confirmWith(promptText); onDismiss() },
                    onDismiss = { dialog.cancel(); onDismiss() },
                )
            }
        }
    }
}

/** Tells Kotlin when an editable element on the page gains or loses focus, so [CarKeyboard] can show. */
private class FieldFocusBridge(private val onFocus: (String) -> Unit, private val onBlur: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    // WebView calls into this from its own thread, never Compose's.
    @JavascriptInterface
    fun focused(value: String) { main.post { onFocus(value) } }

    @JavascriptInterface
    fun blurred() { main.post { onBlur() } }
}

// Plain `el.value = x` never reaches a React-controlled input: React replaces the native value
// setter with its own to track edits, so a direct assignment sets the DOM attribute but leaves
// React's own state (and anything bound to it, like a search box's query) unaware. Calling the
// *native* setter through the prototype, the same trick browser extensions use, is what actually
// notifies it once the 'input' event fires.
private fun setFieldValueJs(value: String) = """
    (function() {
        var el = document.activeElement;
        if (!el) return;
        var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
        var setter = Object.getOwnPropertyDescriptor(proto, 'value');
        if (setter && setter.set) { setter.set.call(el, ${JSONObject.quote(value)}); } else { el.value = ${JSONObject.quote(value)}; }
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
    })();
""".trimIndent()

// Fires a real Enter keydown first - the same event a site's own JS would see from a physical
// keyboard - and only falls back to submitting the form directly if nothing handled it, so a
// search-as-you-type page and a plain <form> both work without submitting twice.
// Dismissing the host keyboard without blurring the page's own field would leave it focused,
// so the next tap anywhere with a text cursor pops the keyboard straight back up.
private const val BLUR_FIELD_JS = "document.activeElement && document.activeElement.blur();"

private const val SUBMIT_FIELD_JS = """
    (function() {
        var el = document.activeElement;
        if (!el) return;
        var enter = new KeyboardEvent('keydown', {
            key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true,
        });
        var notHandled = el.dispatchEvent(enter);
        if (notHandled && el.form) {
            if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit();
        }
    })();
"""

/**
 * The same ring the rest of the car UI shows, on whatever link or field the knob is on. Many
 * sites hide the browser's own focus outline, which leaves a knob driver with no idea where they are.
 */
private fun focusRingJs(color: String) = """
    (function() {
        if (document.getElementById('gearslip-focus-ring')) return;
        var style = document.createElement('style');
        style.id = 'gearslip-focus-ring';
        style.textContent = ':focus-visible { outline: 3px solid $color !important; outline-offset: 2px !important; }';
        (document.head || document.documentElement).appendChild(style);
    })();
"""

/** Reports focus/blur on text inputs, textareas and contenteditable elements to [FieldFocusBridge]. */
private const val FOCUS_TRACKING_JS = """
    (function() {
        function isEditable(el) {
            if (!el) return false;
            var tag = el.tagName;
            if (tag === 'TEXTAREA') return true;
            if (el.isContentEditable) return true;
            if (tag !== 'INPUT') return false;
            var skip = ['button', 'submit', 'checkbox', 'radio', 'range', 'color', 'file', 'image', 'reset'];
            return skip.indexOf((el.type || 'text').toLowerCase()) === -1;
        }
        document.addEventListener('focusin', function(e) {
            if (isEditable(e.target)) window.GearslipField.focused(e.target.value || '');
        });
        document.addEventListener('focusout', function(e) {
            if (isEditable(e.target)) window.GearslipField.blurred();
        });
    })();
"""

/**
 * Reads <video>/<audio> elements and their <source> children (Phase 3.2). Returns an
 * array of {url, title} for the Kotlin side to filter through VideoUrl — this script
 * itself does no filtering and no page modification beyond reading the DOM.
 */
private const val EXTRACT_MEDIA_JS = """
    (function() {
        var out = [];
        function push(src, title) { if (src) out.push({url: src, title: title || ''}); }
        document.querySelectorAll('video, audio').forEach(function(el) {
            push(el.currentSrc || el.src, el.title);
            el.querySelectorAll('source').forEach(function(s) { push(s.src, el.title); });
        });
        return out;
    })();
"""
