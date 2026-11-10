package app.autospace.update

import app.autospace.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals(UpdateChecker.Channel.BETA, UpdateChecker.defaultChannelForVersion("0.5.0-beta.7"))
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

    // r219 — the number after the stage word decides when the numbers and the
    // stage match, or a newer beta looks the same as the one already installed.
    @Test
    fun newerBetaBeatsOlderBeta() {
        assertTrue(UpdateChecker.isNewer("0.5.0-beta.9", "0.5.0-beta.4"))
        assertTrue(UpdateChecker.isNewer("0.5.0-beta.10", "0.5.0-beta.9"))
        assertTrue(UpdateChecker.isNewer("0.5.0-beta.1", "0.4.155-alpha"))
    }

    @Test
    fun sameBetaIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("0.5.0-beta.1", "0.5.0-beta.1"))
        assertFalse(UpdateChecker.isNewer("0.5.0-beta.4", "0.5.0-beta.9"))
    }

    @Test
    fun plainReleaseBeatsBetaWithSuffix() {
        assertTrue(UpdateChecker.isNewer("0.5.0", "0.5.0-beta.9"))
        assertFalse(UpdateChecker.isNewer("0.5.0-beta.9", "0.5.0"))
    }

    @Test
    fun bareStageCountsAsSuffixZero() {
        assertTrue(UpdateChecker.isNewer("0.5.0-beta.1", "0.5.0-beta"))
        assertFalse(UpdateChecker.isNewer("0.5.0-beta", "0.5.0-beta"))
    }

    // r221 (experimental flavor): the suffix also decides between experimental
    // builds, and an experimental tag never beats a beta.
    @Test
    fun newerExpBeatsOlderExp() {
        assertTrue(UpdateChecker.isNewer("0.5.0-exp.2", "0.5.0-exp.1"))
        assertTrue(UpdateChecker.isNewer("0.5.0-exp.10", "0.5.0-exp.9"))
    }

    @Test
    fun sameExpIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("0.5.0-exp.1", "0.5.0-exp.1"))
        assertFalse(UpdateChecker.isNewer("0.5.0-exp.1", "0.5.0-exp.2"))
    }

    @Test
    fun expNeverBeatsBeta() {
        assertFalse(UpdateChecker.isNewer("0.5.0-exp.1", "0.5.0-beta.12"))
        assertFalse(UpdateChecker.isNewer("0.5.0-exp.1", "0.5.0-beta.4"))
        assertTrue(UpdateChecker.isNewer("0.5.0-beta.12", "0.5.0-exp.1"))
    }

    @Test
    fun expVersionDefaultsToStable() {
        assertEquals(UpdateChecker.Channel.STABLE, UpdateChecker.defaultChannelForVersion("0.5.0-exp.1"))
    }

    // r221: the flavor gate — each build only ever accepts its own line's tags.
    // This test runs in BOTH flavor variants (testStandardDebugUnitTest and
    // testExperimentalDebugUnitTest), so both sides of the gate are covered.
    @Test
    fun flavorGateAcceptsOnlyItsOwnLine() {
        if (BuildConfig.FLAVOR == "experimental") {
            assertTrue(UpdateChecker.acceptsTag("v0.5.0-exp.1"))
            assertFalse(UpdateChecker.acceptsTag("v0.5.0-beta.12"))
            assertFalse(UpdateChecker.acceptsTag("v0.5.0"))
        } else {
            assertFalse(UpdateChecker.acceptsTag("v0.5.0-exp.1"))
            assertTrue(UpdateChecker.acceptsTag("v0.5.0-beta.12"))
            assertTrue(UpdateChecker.acceptsTag("v0.5.0"))
        }
    }
}
