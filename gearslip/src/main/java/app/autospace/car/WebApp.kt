package app.autospace.car

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import app.autospace.GearslipLog
import app.autospace.car.theme.GsButton
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
@Composable
fun WebApp(content: String) {
    val navigator = LocalCarNavigator.current
    val ring = String.format("#%06X", MaterialTheme.colorScheme.primary.toArgb() and 0xFFFFFF)
    var web by remember { mutableStateOf<WebView?>(null) }
    var fieldText by remember { mutableStateOf<String?>(null) }
    // Media found on the current page (Phase 3.2), deduplicated by URL, newest first.
    val foundMedia = remember { mutableStateListOf<FoundMedia>() }
    var sheetOpen by remember { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    fun addMedia(url: String, title: String?) {
        if (!VideoUrl.isPlayableUrl(url)) return
        if (foundMedia.none { it.url == url }) {
            foundMedia.add(0, FoundMedia(url, title?.takeIf { it.isNotBlank() }))
        }
    }

    DisposableEffect(web) {
        navigator.backInterceptor = {
            val view = web
            if (view != null && view.canGoBack()) {
                view.goBack()
                true
            } else {
                false
            }
        }
        onDispose { navigator.backInterceptor = null }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Back steps through the page's history first (the interceptor above), then leaves.
            ScreenHeader("Browser", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            // Finder button (Phase 3.2): shown only when media was actually detected
            // on the current page. A GsButton, so it follows the app theme like all chrome.
            if (foundMedia.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GsButton(onClick = { sheetOpen = true }) {
                        Text("Media on this page (${foundMedia.size})")
                    }
                }
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = BrowserClient(
                            ring = ring,
                            onMedia = { url, title -> mainHandler.post { addMedia(url, title) } },
                            onPageStart = { mainHandler.post { foundMedia.clear() } },
                        )
                        addJavascriptInterface(
                            FieldFocusBridge(onFocus = { fieldText = it }, onBlur = { fieldText = null }),
                            "GearslipField",
                        )
                        if (content.startsWith("http://") || content.startsWith("https://")) {
                            loadUrl(content)
                        } else {
                            loadDataWithBaseURL(null, content, "text/html", "utf-8", null)
                        }
                        web = this
                    }
                },
                // Phase 3.0: pages are NOT themed. Never re-enable algorithmic darkening here.
                update = { it.settings.isAlgorithmicDarkeningAllowed = false },
                onRelease = { it.destroy() },
            )
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
        if (sheetOpen) {
            MediaFinderSheet(
                items = foundMedia,
                onOpen = { item ->
                    sheetOpen = false
                    navigator.videoPlayer(item.url, item.title)
                },
                onClose = { sheetOpen = false },
            )
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
        }
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
        if (request?.isForMainFrame == true) {
            GearslipLog.w("car web page HTTP ${response?.statusCode} for ${request.url}")
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
