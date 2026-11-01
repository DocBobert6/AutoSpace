package app.autospace.ui

import androidx.compose.ui.unit.dp
import app.autospace.car.theme.DismissTargetSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * r168 (audit §5 R6): pins for the accessibility round. The dismiss
 * touch-target floor and the intro preview description resolve through
 * pure constants, so a later edit cannot silently shrink a dismiss
 * target or rename the preview description. Hit-area rendering and
 * TalkBack behavior need a live device and are NOT pinned here.
 */
class IntroAccessibilityTest {

    @Test fun dismissTargetMeets48dpFloor() {
        assertEquals(48.dp, DismissTargetSize)
    }

    @Test fun introPreviewDescriptionIsPinned() {
        assertEquals("Preview of the car screen", INTRO_PREVIEW_DESCRIPTION)
    }
}
