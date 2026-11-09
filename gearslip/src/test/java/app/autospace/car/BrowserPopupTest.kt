package app.autospace.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r202 (compat findings 1+6): the popup-allow decision — Mariner's
 * rule. Strict off lets every popup through as a real tab; strict on
 * lets through only a per-site Allow. (A per-site Block is decided
 * before this is consulted, so it has no case here.)
 */
class BrowserPopupTest {

    @Test
    fun strictOffAllowsEveryPopup() {
        assertTrue(popupAllowed(false, BrowserStore.PopupSetting.STRICT))
        assertTrue(popupAllowed(false, BrowserStore.PopupSetting.ALLOW))
        assertTrue(popupAllowed(false, BrowserStore.PopupSetting.BLOCK))
    }

    @Test
    fun strictOnAllowsOnlyPerSiteAllow() {
        assertTrue(popupAllowed(true, BrowserStore.PopupSetting.ALLOW))
        assertFalse(popupAllowed(true, BrowserStore.PopupSetting.STRICT))
        assertFalse(popupAllowed(true, BrowserStore.PopupSetting.BLOCK))
    }
}
