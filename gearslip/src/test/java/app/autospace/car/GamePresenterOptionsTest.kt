package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePresenterOptionsTest {
    private fun lookupOf(map: Map<String, String>): (String) -> String =
        { key -> map[key] ?: "" }

    @Test
    fun everyConsoleHasAOneWordPresetName() {
        for (console in GameConsoles.ALL) {
            val preset = GamePresenterOptions.presetFor(console.key)
            assertFalse(preset.name.contains(" "))
            assertTrue(preset.name.isNotEmpty())
        }
    }

    @Test
    fun crtConsolesAndLegacyConsolesSplitAsMarinerHasThem() {
        for (console in listOf("nes", "snes", "genesis", "segacd", "32x", "ps1")) {
            val preset = GamePresenterOptions.presetFor(console)
            assertTrue(preset.crt)
            assertFalse(preset.lcd)
        }
        for (console in listOf("sms", "sg1000")) {
            val preset = GamePresenterOptions.presetFor(console)
            assertFalse(preset.crt)
            assertFalse(preset.lcd)
        }
    }

    @Test
    fun sg1000FallsBackToTheMasterSystemPreset() {
        // Mariner has no SG-1000 preset; the car falls back to
        // the same GX family's Master System preset, never to a
        // handheld LCD preset.
        assertEquals(
            GamePresenterOptions.presetFor("sms"),
            GamePresenterOptions.presetFor("sg1000")
        )
        assertEquals("Cobalt", GamePresenterOptions.presetFor("sg1000").name)
    }

    @Test
    fun unknownConsoleFallsBackToTheMasterSystemPreset() {
        assertEquals(
            GamePresenterOptions.presetFor("sms"),
            GamePresenterOptions.presetFor("no-such-console")
        )
    }

    @Test
    fun shaderModeOffIsAlwaysPlain() {
        for (console in GameConsoles.ALL) {
            assertEquals(
                0,
                GamePresenterOptions.shaderModeFor(
                    GamePresenterOptions.presetFor(console.key), false
                )
            )
        }
    }

    @Test
    fun shaderModeOnFollowsThePresetPath() {
        assertEquals(
            3,
            GamePresenterOptions.shaderModeFor(
                GamePresenterOptions.presetFor("nes"), true
            )
        )
        assertEquals(
            1,
            GamePresenterOptions.shaderModeFor(
                GamePresenterOptions.presetFor("sms"), true
            )
        )
        val lcd = PresenterPreset("Test", 0f, 0f, 0f, lcd = true)
        assertEquals(2, GamePresenterOptions.shaderModeFor(lcd, true))
    }

    @Test
    fun curvePullIsFullForCrtHalfForLcdNoneOtherwise() {
        val crt = GamePresenterOptions.presetFor("nes")
        val lcd = PresenterPreset("Test", 0f, 0f, 0f, lcd = true)
        val legacy = GamePresenterOptions.presetFor("sms")
        assertEquals(0.006f, GamePresenterOptions.curvePull(crt, true, 30), 0.000001f)
        assertEquals(0.003f, GamePresenterOptions.curvePull(lcd, true, 30), 0.000001f)
        assertEquals(0f, GamePresenterOptions.curvePull(legacy, true, 30), 0f)
        assertEquals(0f, GamePresenterOptions.curvePull(crt, false, 30), 0f)
        assertEquals(0.020f, GamePresenterOptions.curvePull(crt, true, 150), 0.000001f)
    }

    @Test
    fun shadersOnPrefersPerConsoleThenSharedThenOff() {
        assertTrue(
            GamePresenterOptions.shadersOn(
                lookupOf(mapOf("gameShaders:nes" to "true")), "nes"
            )
        )
        assertTrue(
            GamePresenterOptions.shadersOn(
                lookupOf(
                    mapOf(
                        "gameShaders:nes" to "true",
                        "gameShaders" to "false"
                    )
                ),
                "nes"
            )
        )
        assertFalse(
            GamePresenterOptions.shadersOn(
                lookupOf(
                    mapOf(
                        "gameShaders:nes" to "false",
                        "gameShaders" to "true"
                    )
                ),
                "nes"
            )
        )
        assertTrue(
            GamePresenterOptions.shadersOn(
                lookupOf(mapOf("gameShaders" to "true")), "nes"
            )
        )
        assertFalse(
            GamePresenterOptions.shadersOn(lookupOf(emptyMap()), "nes")
        )
    }

    @Test
    fun curveAndRoundDefaultOffDespitePresetDefaults() {
        // The deliberate deviation from Mariner's beta.6 preset
        // defaults: a console nobody touched stays flat and
        // square, exactly today's picture.
        val empty = lookupOf(emptyMap())
        assertFalse(GamePresenterOptions.curveOn(empty, "nes"))
        assertFalse(GamePresenterOptions.roundOn(empty, "nes"))
        assertTrue(GamePresenterOptions.presetFor("nes").curveOn)
        assertTrue(GamePresenterOptions.presetFor("nes").roundOn)
    }

    @Test
    fun curveAndRoundAmountsFallBackToPresetStrengths() {
        val empty = lookupOf(emptyMap())
        assertEquals(30, GamePresenterOptions.curveAmount(empty, "nes"))
        assertEquals(20, GamePresenterOptions.roundAmount(empty, "nes"))
        val per = lookupOf(
            mapOf(
                "gameCurvatureAmount:nes" to "60",
                "gameRoundAmount:nes" to "40"
            )
        )
        assertEquals(60, GamePresenterOptions.curveAmount(per, "nes"))
        assertEquals(40, GamePresenterOptions.roundAmount(per, "nes"))
        val shared = lookupOf(
            mapOf(
                "gameCurvatureAmount" to "50",
                "gameCurvatureAmount:snes" to "70"
            )
        )
        assertEquals(50, GamePresenterOptions.curveAmount(shared, "nes"))
        assertEquals(70, GamePresenterOptions.curveAmount(shared, "snes"))
    }

    @Test
    fun amountsClampAndBadValuesFallBack() {
        val clamped = lookupOf(
            mapOf(
                "gameCurvatureAmount:nes" to "150",
                "gameRoundAmount:nes" to "-5"
            )
        )
        assertEquals(100, GamePresenterOptions.curveAmount(clamped, "nes"))
        assertEquals(0, GamePresenterOptions.roundAmount(clamped, "nes"))
        val bad = lookupOf(
            mapOf("gameCurvatureAmount:nes" to "not-a-number")
        )
        assertEquals(30, GamePresenterOptions.curveAmount(bad, "nes"))
    }

    @Test
    fun fpsFastForwardAndIntegerDefaultOffPerConsoleOnly() {
        val empty = lookupOf(emptyMap())
        assertFalse(GamePresenterOptions.fpsOn(empty, "ps1"))
        assertFalse(GamePresenterOptions.fastForwardEnabled(empty, "ps1"))
        assertFalse(GamePresenterOptions.integerScale(empty, "ps1"))
        val on = lookupOf(
            mapOf(
                "gameFpsCounter:ps1" to "true",
                "gameFastForward:ps1" to "true",
                "gameIntegerScale:ps1" to "true"
            )
        )
        assertTrue(GamePresenterOptions.fpsOn(on, "ps1"))
        assertTrue(GamePresenterOptions.fastForwardEnabled(on, "ps1"))
        assertTrue(GamePresenterOptions.integerScale(on, "ps1"))
        // Per-console keys never leak across consoles.
        assertFalse(GamePresenterOptions.fpsOn(on, "nes"))
        assertFalse(GamePresenterOptions.fastForwardEnabled(on, "nes"))
        assertFalse(GamePresenterOptions.integerScale(on, "nes"))
    }
}
