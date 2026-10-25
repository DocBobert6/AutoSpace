package app.autospace.car

import android.app.DownloadManager
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
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
import kotlinx.coroutines.delay
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

@Composable
fun WebApp(content: String) {
    val navigator = LocalCarNavigator.current
    val context = LocalContext.current
    // Consumed once, on first composition: a voice search lands here, a normal open does not.
    val voiceUrl = remember { BrowserLaunch.pendingUrl?.also { BrowserLaunch.pendingUrl = null } }
    val startUrl = voiceUrl ?: if (content.startsWith("http")) content else ProjectedContent.DEFAULT_URL
    val ring = String.format("#%06X", MaterialTheme.colorScheme.primary.toArgb() and 0xFFFFFF)
    var web by remember { mutableStateOf<WebView?>(null) }
    var fieldText by remember { mutableStateOf<String?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    // Phase 10 tabs: the tab list + active id live in composition (see
    // BrowserTab). The first tab is the launch payload; every later tab
    // starts at no-AI DuckDuckGo. Tabs are never zero: closing the last
    // tab resets it to a fresh start page in place (see closeTab).
    val tabs = remember {
        mutableStateListOf(
            BrowserTab(
                0,
                startUrl,
                rawHtml = if (voiceUrl == null && !content.startsWith("http")) content else null,
            )
        )
    }
    var nextTabId by remember { mutableStateOf(1) }
    var activeTabId by remember { mutableStateOf(0) }
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
    fun newTab() {
        val tab = BrowserTab(nextTabId++, ProjectedContent.DEFAULT_URL)
        tabs.add(tab)
        activeTabId = tab.id
        addressEditing = false
        addressSelected = false
        tabsOpen = false
    }
    fun switchTab(tab: BrowserTab) {
        activeTabId = tab.id
        addressEditing = false
        addressSelected = false
        tabsOpen = false
    }
    fun closeTab(tab: BrowserTab) {
        if (tabs.size == 1) {
            // Never zero tabs: reset the last tab to a fresh start page
            // in its existing WebView instead of removing it.
            tab.view?.loadUrl(ProjectedContent.DEFAULT_URL)
            tab.foundMedia.clear()
            tab.loadError = null
            tab.title = null
            tabsOpen = false
            return
        }
        val idx = tabs.indexOf(tab)
        tabs.remove(tab)
        if (tab.id == activeTabId) {
            activeTabId = tabs[(idx - 1).coerceAtLeast(0)].id
        }
        // Destroy after composition has detached it (posted, not now:
        // this tab's AndroidView may still be composed this frame).
        val v = tab.view
        tab.view = null
        mainHandler.post { runCatching { v?.destroy() } }
    }
    // Keep the Back/Home/Reload pointer on the active tab's WebView.
    LaunchedEffect(activeTabId) { web = activeTab.view }
    var bookmarks by remember { mutableStateOf<List<String>>(emptyList()) }
    var historyList by remember { mutableStateOf<List<String>>(emptyList()) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var jsDialog by remember { mutableStateOf<JsDialogState?>(null) }
    // Phase 8 strict popup blocking (Notscape's model, car-adapted — no
    // tabs exist here, so an opened popup loads in the main view instead
    // of a new window, and Back is the way back from it):
    // strict ON by default; a blocked target is captured (up to 5, in
    // arrival order) and offered from a frost-panel notice; a per-site
    // Allow loads it in the main view; a per-site Block always silences
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
    fun onPopupUrl(tab: BrowserTab, host: String, url: String, transport: WebView, allowed: Boolean) {
        if (allowed) {
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
    }
    fun hideFullscreen() {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        fullscreenView = null
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
            tabs.forEach { runCatching { it.view?.destroy() } }
        }
    }

    DisposableEffect(web, sheetOpen, listsOpen, jsDialog, fullscreenView, showPopupNotice, tabsOpen) {
        navigator.backInterceptor = {
            when {
                fullscreenView != null -> { hideFullscreen(); true }
                jsDialog != null -> { jsDialog?.cancel(); jsDialog = null; true }
                showPopupNotice -> { clearPendingPopups(); true }
                tabsOpen -> { tabsOpen = false; true }
                sheetOpen -> { sheetOpen = false; true }
                listsOpen -> { listsOpen = false; true }
                web != null && web!!.canGoBack() -> { web!!.goBack(); true }
                else -> false
            }
        }
        onDispose { navigator.backInterceptor = null }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Back steps through the page's history first (the interceptor above), then leaves.
            ScreenHeader("Browser", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            // Address/search bar + navigation (Phase 3.4, item 1).
            // Tapping the address pill opens the car keyboard; submitting
            // navigates (a URL loads, anything else searches no-AI
            // DuckDuckGo). Phase 6, Notscape capsule language
            // (car-adapted): circular frosted nav buttons grouped around
            // the URL pill, inset 12dp from the edges. Unlike Notscape's
            // phone bar, nothing collapses here — Back/Forward/Reload/
            // Home stay reachable while driving.
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
                GsIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", { web?.goBack() }, enabled = activeTab.canGoBack, size = 44.dp, iconSize = 22.dp, shape = androidx.compose.foundation.shape.CircleShape)
                GsIconButton(Icons.AutoMirrored.Filled.ArrowForward, "Forward", { web?.goForward() }, enabled = activeTab.canGoForward, size = 44.dp, iconSize = 22.dp, shape = androidx.compose.foundation.shape.CircleShape)
                GsIconButton(Icons.Filled.Refresh, "Reload", { web?.reload() }, size = 44.dp, iconSize = 22.dp, shape = androidx.compose.foundation.shape.CircleShape)
                GsIconButton(Icons.Filled.Home, "Home", { web?.loadUrl(if (content.startsWith("http")) content else ProjectedContent.DEFAULT_URL) }, size = 44.dp, iconSize = 22.dp, shape = androidx.compose.foundation.shape.CircleShape)
                Box(
                    Modifier.weight(1f).height(44.dp)
                        .frostBar(androidx.compose.foundation.shape.RoundedCornerShape(50), 6.dp)
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
                    // across the pill behind the text while loading, as
                    // a fraction-width primary wash from the start edge.
                    if (activeTab.progress in 1..99) {
                        Box(
                            Modifier.height(44.dp)
                                .fillMaxWidth(activeTab.progress / 100f)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                                    androidx.compose.foundation.shape.RoundedCornerShape(50),
                                ),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
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
                                Text(activeTab.currentUrl, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
                        GsIconButton(Icons.Filled.PlayArrow, "Media on this page", { sheetOpen = true }, size = 44.dp, iconSize = 22.dp, shape = androidx.compose.foundation.shape.CircleShape)
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
                // Tabs (Phase 10): count badge always shown (never zero
                // tabs); the switcher sheet holds New tab + the list.
                Box(contentAlignment = Alignment.TopEnd) {
                    GsIconButton(Icons.Filled.Tab, "Tabs", { tabsOpen = true }, size = 44.dp, iconSize = 22.dp, shape = androidx.compose.foundation.shape.CircleShape)
                    Box(
                        Modifier.size(20.dp)
                            .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${tabs.size}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                GsIconButton(Icons.Filled.Star, "Saved & history", { refreshLists(); listsOpen = true }, size = 44.dp, iconSize = 22.dp, shape = androidx.compose.foundation.shape.CircleShape)
            }
            // Phase 10 tabs: only the active tab's WebView is composed,
            // keyed by tab id so switching disposes one AndroidView and
            // composes the next. The factory returns a tab's EXISTING
            // WebView when it has one (a switch back re-attaches it,
            // page + history intact) and creates + loads it only once.
            // onRelease deliberately does NOT destroy: destroying here
            // would kill every tab on every switch. Tab WebViews die
            // only on tab close (posted) or on leaving the browser.
            androidx.compose.runtime.key(activeTab.id) {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    factory = { context ->
                        activeTab.view?.also { web = it } ?: WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            val tab = activeTab
                            webViewClient = BrowserClient(
                                ring = ring,
                                onMedia = { url, title -> mainHandler.post { addMedia(tab, url, title) } },
                                onPageStart = {
                                    mainHandler.post {
                                        tab.foundMedia.clear()
                                        tab.loadError = null
                                    }
                                },
                                onHistory = { url, back, forward ->
                                    mainHandler.post {
                                        tab.currentUrl = url
                                        tab.canGoBack = back
                                        tab.canGoForward = forward
                                        BrowserStore.recordHistory(context, url)
                                    }
                                },
                                onMainFrameError = { message ->
                                    mainHandler.post { tab.loadError = message }
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
            if (!addressEditing) {
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
                        web?.loadUrl(ProjectedContent.navigateUrl(addressText))
                    },
                    onDismiss = {
                        addressEditing = false
                        addressSelected = false
                    },
                )
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
                onLoad = { url ->
                    listsOpen = false
                    web?.loadUrl(url)
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
        // and offering Open popup (first captured URL, into its
        // originating tab — Phase 10 tabs; popups never open a new tab),
        // Block always for this site, and Dismiss.
        if (showPopupNotice && pendingPopups.isNotEmpty()) {
            val host = popupHost?.takeIf { it.isNotBlank() }
                ?: hostOf(pendingPopups.first()).takeIf { it.isNotBlank() }
                ?: "this site"
            val count = pendingPopups.size
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
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
                    GsButton(onClick = { openFirstPopup() }, modifier = Modifier.fillMaxWidth()) { Text("Open popup") }
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
                onClose = { tabsOpen = false },
            )
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
            modifier = Modifier.fillMaxWidth().padding(16.dp),
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
                                tab.title ?: runCatching { Uri.parse(tab.currentUrl).host ?: "" }.getOrDefault("").ifBlank { "New tab" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (isActive) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                tab.currentUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        GsIconButton(Icons.Filled.Close, "Close tab", { onCloseTab(tab) }, size = 44.dp, iconSize = 22.dp, shape = androidx.compose.foundation.shape.CircleShape)
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
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        request?.url?.toString()?.let { url ->
            if (VideoUrl.isPlayableUrl(url)) onMedia(url, null)
        }
        return null
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        if (url != null && VideoUrl.isPlayableUrl(url)) onMedia(url, null)
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
