package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCoreOptionsTest {
    @Test
    fun everyConsoleHasOptionsWithValidDefaults() {
        val systems = listOf(
            "nes", "sg1000", "sms", "genesis", "snes", "segacd", "32x", "ps1"
        )
        for (system in systems) {
            val options = GameCoreOptions.forConsole(system)
            assertTrue("$system has no options", options.isNotEmpty())
            for (option in options) {
                assertTrue(
                    "$system option ${option.key} has no values",
                    option.values.isNotEmpty()
                )
                assertTrue(
                    "$system option ${option.key} default ${option.default} " +
                        "not in values",
                    option.default in option.values
                )
                assertTrue(option.key.isNotBlank())
                assertTrue(option.label.isNotBlank())
            }
        }
        assertEquals(emptyList<CoreOption>(), GameCoreOptions.forConsole("ds"))
        assertEquals(emptyList<CoreOption>(), GameCoreOptions.forConsole("unknown"))
    }

    @Test
    fun sharedCoreConsolesShareTheSameOptionList() {
        val genesis = GameCoreOptions.forConsole("genesis")
        for (system in listOf("sms", "sg1000", "segacd")) {
            assertEquals(
                genesis.map { it.key },
                GameCoreOptions.forConsole(system).map { it.key }
            )
        }
    }

    @Test
    fun optionListsCarryExpectedCounts() {
        assertEquals(24, GameCoreOptions.forConsole("nes").size)
        assertEquals(29, GameCoreOptions.forConsole("snes").size)
        assertEquals(31, GameCoreOptions.forConsole("genesis").size)
        assertEquals(35, GameCoreOptions.forConsole("ps1").size)
        assertEquals(15, GameCoreOptions.forConsole("32x").size)
    }

    @Test
    fun displayFallsBackToTheRawValue() {
        val option = CoreOption("k", "Label", listOf("a", "b"), emptyMap(), "a")
        assertEquals("a", option.display("a"))
        assertEquals("z", option.display("z"))
        val labeled = CoreOption(
            "k", "Label", listOf("true", "false"),
            mapOf("true" to "On", "false" to "Off"), "true"
        )
        assertEquals("On", labeled.display("true"))
        assertEquals("Off", labeled.display("false"))
    }
}
