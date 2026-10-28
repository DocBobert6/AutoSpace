package app.autospace.car

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePadMappingTest {
    @Test
    fun defaultsAreTheInverseOfGamepadMap() {
        // Every bit's default button is the button GamepadMap
        // maps to that bit today, so a console with no stored
        // mapping behaves exactly as before this round.
        val mapping = GamePadMapping.defaultMapping()
        assertEquals(12, mapping.size)
        for (bit in 0..11) {
            val code = mapping.getValue(bit)
            assertEquals(bit, GamepadMap.bitForKeyCode(code))
        }
        // And the reverse direction agrees: the default mapping
        // resolves every recognized code to the same bit.
        for (code in GamePadMapping.CANDIDATE_CODES) {
            assertEquals(
                GamepadMap.bitForKeyCode(code),
                GamePadMapping.bitForKeyCode(mapping, code),
            )
        }
    }

    @Test
    fun effectiveMappingFallsBackToDefaultsForBadRawValues() {
        val mapping = GamePadMapping.effectiveMapping(
            mapOf(
                8 to KeyEvent.KEYCODE_BUTTON_Y.toString(),
                0 to "not-a-number",
                4 to KeyEvent.KEYCODE_BUTTON_START.toString(),
                9 to "9999",
            ),
        )
        // Stored values win when they name a candidate button.
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, mapping.getValue(8))
        assertEquals(KeyEvent.KEYCODE_BUTTON_START, mapping.getValue(4))
        // Garbage and unknown codes fall back to the default.
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, mapping.getValue(0))
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, mapping.getValue(9))
        // Untouched actions keep their defaults.
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, mapping.getValue(5))
        assertEquals(KeyEvent.KEYCODE_BUTTON_L1, mapping.getValue(10))
    }

    @Test
    fun assignmentSwapsInsteadOfDuplicating() {
        val swapped = GamePadMapping.withAssignment(
            GamePadMapping.defaultMapping(),
            8,
            KeyEvent.KEYCODE_BUTTON_A,
        )
        // A (east) now takes Button A, and B (south), which held
        // Button A, takes the displaced Button B.
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, swapped.getValue(8))
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, swapped.getValue(0))
        // The mapping stays bijective: 12 distinct codes.
        assertEquals(12, swapped.values.toSet().size)
        // Re-assigning the same code to the same action changes nothing.
        val same = GamePadMapping.withAssignment(
            swapped, 8, KeyEvent.KEYCODE_BUTTON_A,
        )
        assertEquals(swapped, same)
    }

    @Test
    fun unassignedShoulderAliasesFallBackToGamepadMap() {
        // L2 and R2 are candidates but hold no bit in the
        // default mapping; the fallback keeps them driving L
        // and R exactly as today, instead of going dead.
        val mapping = GamePadMapping.defaultMapping()
        assertEquals(10, GamePadMapping.bitForKeyCode(mapping, KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(11, GamePadMapping.bitForKeyCode(mapping, KeyEvent.KEYCODE_BUTTON_R2))
        // A stored assignment beats the fallback.
        val assigned = GamePadMapping.withAssignment(
            mapping, 8, KeyEvent.KEYCODE_BUTTON_L2,
        )
        assertEquals(8, GamePadMapping.bitForKeyCode(assigned, KeyEvent.KEYCODE_BUTTON_L2))
        // And a truly unrecognized code still maps to nothing.
        assertEquals(-1, GamePadMapping.bitForKeyCode(mapping, KeyEvent.KEYCODE_UNKNOWN))
    }

    @Test
    fun assigningAnAliasLeavesTheReplacedButtonOnTheFallback() {
        // Documented, not accidental: L2 takes A (east), and
        // Button B, which held A (east), still drives it through
        // the fallback — the shoulder aliasing today's tree
        // already has, generalized to a reassigned alias.
        val assigned = GamePadMapping.withAssignment(
            GamePadMapping.defaultMapping(),
            8,
            KeyEvent.KEYCODE_BUTTON_L2,
        )
        assertEquals(8, GamePadMapping.bitForKeyCode(assigned, KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(8, GamePadMapping.bitForKeyCode(assigned, KeyEvent.KEYCODE_BUTTON_B))
    }

    @Test
    fun perConsoleIsolationIsInTheKeyShape() {
        // Isolation lives in the setting key: two consoles never
        // share a stored value, because the console is part of
        // every key. The stored values for NES resolve without any
        // influence from SNES raw values, and vice versa.
        val nesRaw = mapOf(8 to KeyEvent.KEYCODE_BUTTON_Y.toString())
        val snesRaw = mapOf(8 to KeyEvent.KEYCODE_BUTTON_X.toString())
        val nes = GamePadMapping.effectiveMapping(nesRaw)
        val snes = GamePadMapping.effectiveMapping(snesRaw)
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, nes.getValue(8))
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, snes.getValue(8))
        // Neither console's mapping leaks into the other's other actions.
        assertEquals(nes.getValue(0), snes.getValue(0))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, nes.getValue(0))
    }

    @Test
    fun routerAppliesTheStoredMappingForTheLaunchedConsole() {
        var mask = 0
        val stored = GamePadMapping.withAssignment(
            GamePadMapping.defaultMapping(),
            8,
            KeyEvent.KEYCODE_BUTTON_A,
        )
        val router = GameKeyRouter(
            setBit = { bit, on ->
                mask = if (on) mask or (1 shl bit) else mask and (1 shl bit).inv()
            },
            keyToBit = { code -> GamePadMapping.bitForKeyCode(stored, code) },
        )
        // Button A now drives A (east), bit 8 — not B (south).
        assertTrue(router.onKey(KeyEvent.KEYCODE_BUTTON_A, true))
        assertEquals(1 shl 8, mask)
        // Button B now drives B (south), bit 0, after the swap.
        assertTrue(router.onKey(KeyEvent.KEYCODE_BUTTON_B, true))
        assertEquals((1 shl 8) or (1 shl 0), mask)
        assertTrue(router.onKey(KeyEvent.KEYCODE_BUTTON_A, false))
        assertEquals(1 shl 0, mask)
        // An unassigned code still maps to nothing.
        assertFalse(router.onKey(KeyEvent.KEYCODE_UNKNOWN, true))
        assertEquals(1 shl 0, mask)
    }

    @Test
    fun routerWithoutAMappingKeepsTodaysBehavior() {
        var mask = 0
        val router = GameKeyRouter(setBit = { bit, on ->
            mask = if (on) mask or (1 shl bit) else mask and (1 shl bit).inv()
        })
        assertTrue(router.onKey(KeyEvent.KEYCODE_BUTTON_A, true))
        assertEquals(1 shl 0, mask)
    }

    @Test
    fun candidateButtonsAreExactlyTheRecognizedCodes() {
        // Every candidate is a code the router hears, and every
        // code the router hears is a candidate, so the picker can
        // never name a dead button.
        for (code in GamePadMapping.CANDIDATE_CODES) {
            assertTrue(
                "candidate $code unrecognized",
                GamepadMap.bitForKeyCode(code) >= 0,
            )
        }
        assertEquals(14, GamePadMapping.CANDIDATE_CODES.toSet().size)
        // Each of the 12 actions has a distinct label.
        assertEquals(
            12,
            GamePadMapping.ACTIONS.map { it.label }.toSet().size,
        )
    }
}
