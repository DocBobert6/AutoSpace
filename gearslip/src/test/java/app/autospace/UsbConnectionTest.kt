package app.autospace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit round R9 (manifest + dependency hygiene), item 5-03: the USB connection option
 * follows the saved switch AND the device feature. The switch alone also gates wireless,
 * so it must never be hidden or disabled on a feature-less device — only the USB alias
 * follows this rule, and the attach path refuses explicitly when the feature is absent.
 *
 * The manifest flag, the JitPack removal, the catalog deletions, the core-ktx declaration,
 * and the assembleRelease fail-fast have no JVM seam: the CI build is their proof, and the
 * pre-push checks grep the manifest and build files for them instead of pretending a test
 * covers them.
 */
class UsbConnectionTest {

    @Test
    fun `switch on plus feature present allows the USB connection`() {
        assertTrue(AppSettings.usbConnectionAllowed(switchOn = true, hasUsbAccessoryFeature = true))
    }

    @Test
    fun `switch off blocks the USB connection even with the feature present`() {
        assertFalse(AppSettings.usbConnectionAllowed(switchOn = false, hasUsbAccessoryFeature = true))
    }

    @Test
    fun `feature absent blocks the USB connection even with the switch on`() {
        assertFalse(AppSettings.usbConnectionAllowed(switchOn = true, hasUsbAccessoryFeature = false))
    }

    @Test
    fun `switch off plus feature absent blocks the USB connection`() {
        assertFalse(AppSettings.usbConnectionAllowed(switchOn = false, hasUsbAccessoryFeature = false))
    }
}
