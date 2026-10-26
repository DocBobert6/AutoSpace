package app.autospace.car

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shipped page-tracking script's listeners. The script itself runs
 * in a WebView, so the JVM suite cannot execute it — these pins guard the
 * exact wiring r47 depends on: focus/blur still tracked, and a page-side
 * value change on the focused field reported back through the same bridge,
 * guarded so a blurred field cannot pop the keyboard back up.
 */
class BrowserFieldSyncTest {

    @Test
    fun focusAndBlurListenersStillPresent() {
        assertTrue(FOCUS_TRACKING_JS.contains("addEventListener('focusin'"))
        assertTrue(FOCUS_TRACKING_JS.contains("addEventListener('focusout'"))
        assertTrue(FOCUS_TRACKING_JS.contains("GearslipField.focused"))
        assertTrue(FOCUS_TRACKING_JS.contains("GearslipField.blurred"))
    }

    @Test
    fun inputListenerReportsPageSideChangesBack() {
        assertTrue(FOCUS_TRACKING_JS.contains("addEventListener('input'"))
        // Only the focused field reports: a dismissed field must not
        // resurrect the keyboard on a later page-side rewrite.
        assertTrue(FOCUS_TRACKING_JS.contains("document.activeElement !== e.target"))
        // Contenteditable fields carry textContent, not value.
        assertTrue(FOCUS_TRACKING_JS.contains("e.target.textContent"))
    }
}
