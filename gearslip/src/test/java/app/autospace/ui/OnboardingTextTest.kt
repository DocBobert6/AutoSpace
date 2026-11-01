package app.autospace.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r170 (audit §5 R8): pins for the onboarding text/UX round. The changed
 * wordings resolve through pure constants and the notification-prompt gate
 * is a pure predicate, so a later edit cannot silently re-widen the intro
 * privacy claim, drop the gentler Android Auto alternative, hide the cost
 * of skipping the adb step, or re-launch a dead prompt. Rendering on a
 * live device (actual intro pages, actual row visibility) is NOT pinned
 * here — no Robolectric in this tree — and is device verified instead.
 */
class OnboardingTextTest {

    @Test fun voicePrivacySentenceIsQualified() {
        assertEquals(
            "Your phone turns speech into text by itself, and that step sends nothing anywhere, unless you switch on a cloud assistant.",
            VOICE_PRIVACY_SENTENCE,
        )
    }

    @Test fun androidAutoPagePointsAtTheInAppSwitch() {
        assertEquals(
            "Or turn off just AutoSpace's USB connection in Settings, under Connection.",
            ANDROID_AUTO_SWITCH_LINE,
        )
    }

    @Test fun adbPageStatesTheCostOfSkipping() {
        assertEquals(
            "If you skip this step, nothing else changes — notifications just stay hidden during capture.",
            ADB_SKIP_COST_LINE,
        )
    }

    @Test fun promptRequestedOnFirstAttempt() {
        assertTrue(shouldRequestNotifications(granted = false, showRationale = false, askedBefore = false))
    }

    @Test fun promptRequestedAgainWhileRationaleRemains() {
        assertTrue(shouldRequestNotifications(granted = false, showRationale = true, askedBefore = true))
    }

    @Test fun promptNeverRelaunchedAfterPermanentDenial() {
        assertFalse(shouldRequestNotifications(granted = false, showRationale = false, askedBefore = true))
    }

    @Test fun promptNeverRequestedWhenGranted() {
        assertFalse(shouldRequestNotifications(granted = true, showRationale = false, askedBefore = false))
        assertFalse(shouldRequestNotifications(granted = true, showRationale = true, askedBefore = true))
    }

    @Test fun rowShowsOnlyForPermanentDenial() {
        assertTrue(notificationRowVisible(granted = false, showRationale = false, askedBefore = true))
        assertFalse(notificationRowVisible(granted = false, showRationale = false, askedBefore = false))
        assertFalse(notificationRowVisible(granted = false, showRationale = true, askedBefore = true))
        assertFalse(notificationRowVisible(granted = true, showRationale = false, askedBefore = true))
    }

    @Test fun promptAndRowPartitionTheDeniedStates() {
        // Every not-granted state gets exactly one of the two behaviors: a live
        // prompt, or the deep-link row — never both, never neither.
        for (rationale in listOf(false, true)) {
            for (asked in listOf(false, true)) {
                val prompt = shouldRequestNotifications(granted = false, showRationale = rationale, askedBefore = asked)
                val row = notificationRowVisible(granted = false, showRationale = rationale, askedBefore = asked)
                assertTrue("granted=false rationale=$rationale asked=$asked", prompt != row)
            }
        }
    }
}
