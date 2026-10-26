package app.autospace.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * r41 — stage-based channel default. Pure functions only (UpdateChecker has
 * Android API behind its Context methods, but defaultChannelForVersion and
 * resolveChannel touch none of it); the prefs wiring is covered by CI
 * compile + the device test list, not faked as unit tests.
 */
class UpdateChannelTest {

    @Test
    fun alphaVersionDefaultsToAlpha() {
        assertEquals(UpdateChecker.Channel.ALPHA, UpdateChecker.defaultChannelForVersion("0.4.0-alpha"))
        assertEquals(UpdateChecker.Channel.ALPHA, UpdateChecker.defaultChannelForVersion("0.3.01-alpha"))
    }

    @Test
    fun betaVersionDefaultsToBeta() {
        assertEquals(UpdateChecker.Channel.BETA, UpdateChecker.defaultChannelForVersion("1.0.0-beta"))
        assertEquals(UpdateChecker.Channel.BETA, UpdateChecker.defaultChannelForVersion("0.5.0-beta.1"))
    }

    @Test
    fun plainVersionDefaultsToStable() {
        assertEquals(UpdateChecker.Channel.STABLE, UpdateChecker.defaultChannelForVersion("1.0.0"))
        assertEquals(UpdateChecker.Channel.STABLE, UpdateChecker.defaultChannelForVersion("0.9.2"))
    }

    @Test
    fun savedChannelAlwaysWins() {
        assertEquals(UpdateChecker.Channel.STABLE, UpdateChecker.resolveChannel("STABLE", false, "0.4.0-alpha"))
        assertEquals(UpdateChecker.Channel.BETA, UpdateChecker.resolveChannel("BETA", false, "1.0.0"))
        assertEquals(UpdateChecker.Channel.ALPHA, UpdateChecker.resolveChannel("ALPHA", false, "1.0.0"))
    }

    @Test
    fun oldBetaSwitchMeansAlphaWhenNothingSaved() {
        assertEquals(UpdateChecker.Channel.ALPHA, UpdateChecker.resolveChannel(null, true, "1.0.0"))
    }

    @Test
    fun unparseableSavedNameFallsThroughToStage() {
        assertEquals(UpdateChecker.Channel.ALPHA, UpdateChecker.resolveChannel("BOGUS", false, "0.4.0-alpha"))
        assertEquals(UpdateChecker.Channel.STABLE, UpdateChecker.resolveChannel("BOGUS", false, "1.0.0"))
    }
}
