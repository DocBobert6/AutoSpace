package app.autospace.car

import app.autospace.car.theme.ControlLook
import app.autospace.car.theme.pitlanePanelBevel
import app.autospace.car.theme.shouldDrawControlTexture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r32 Pitlane audit fix: texture-once-per-surface rule + panel bevel values. */
class PitlaneAuditFixTest {

    private val pitlaneLook = ControlLook(weaveAlpha = 0.05f, grainAlpha = 0.03f)
    private val plainLook = ControlLook()

    @Test
    fun controlTextureDrawsStandaloneUnderPitlane() {
        assertTrue(shouldDrawControlTexture(pitlaneLook, false))
    }

    @Test
    fun controlTextureSuppressedOnTexturedPanel() {
        // A control on a Pitlane panel skips its own weave/grain — the
        // panel's stronger pattern already textures that surface.
        assertFalse(shouldDrawControlTexture(pitlaneLook, true))
    }

    @Test
    fun controlTextureNeverDrawsWithoutMaterial() {
        assertFalse(shouldDrawControlTexture(plainLook, false))
        assertFalse(shouldDrawControlTexture(plainLook, true))
    }

    @Test
    fun panelBevelMatchesControlBevel() {
        // r32: panels carry the same edge as controls (r26's weaker panel
        // values were what the audit found indiscernible).
        assertEquals(0.50f to 0.60f, pitlanePanelBevel(true))
        assertEquals(0.45f to 0.55f, pitlanePanelBevel(false))
    }
}
