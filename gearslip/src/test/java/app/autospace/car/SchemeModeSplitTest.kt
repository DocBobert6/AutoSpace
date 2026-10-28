package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r35 — scheme / mode split. Pure functions only (SchemeMode.kt has no
 * Compose runtime and no Android API); composable picker wiring is covered
 * by CI compile + the device test list, not faked as unit tests.
 */
class SchemeModeSplitTest {

    @Test
    fun migrationTableMatchesSpec() {
        // Every spec §4 row, plus PHONE (Phase 7 value that may never
        // have migrated) and the unknown-name fallback.
        assertEquals(ColorScheme.NEUTRAL to AppearanceMode.SYSTEM, migrateLegacyTheme("FOLLOW_SYSTEM"))
        assertEquals(ColorScheme.NEUTRAL to AppearanceMode.LIGHT, migrateLegacyTheme("LIGHT"))
        assertEquals(ColorScheme.NEUTRAL to AppearanceMode.DARK, migrateLegacyTheme("DARK"))
        assertEquals(ColorScheme.MATERIAL_YOU to AppearanceMode.SYSTEM, migrateLegacyTheme("MATERIAL_YOU"))
        assertEquals(ColorScheme.MATERIAL_YOU to AppearanceMode.LIGHT, migrateLegacyTheme("MATERIAL_YOU_LIGHT"))
        assertEquals(ColorScheme.MATERIAL_YOU to AppearanceMode.DARK, migrateLegacyTheme("MATERIAL_YOU_DARK"))
        assertEquals(ColorScheme.NIGHT to AppearanceMode.DARK, migrateLegacyTheme("NIGHT"))
        assertEquals(ColorScheme.PITLANE to AppearanceMode.DARK, migrateLegacyTheme("PITLANE"))
        assertEquals(ColorScheme.CIRCUIT to AppearanceMode.SYSTEM, migrateLegacyTheme("CIRCUIT"))
        assertEquals(ColorScheme.HERITAGE to AppearanceMode.SYSTEM, migrateLegacyTheme("HERITAGE"))
        assertEquals(ColorScheme.MATERIAL_YOU to AppearanceMode.SYSTEM, migrateLegacyTheme("PHONE"))
        // Unknown names fall back exactly like the old FOLLOW_SYSTEM default.
        assertEquals(ColorScheme.NEUTRAL to AppearanceMode.SYSTEM, migrateLegacyTheme("BOGUS"))
        assertEquals(ColorScheme.NEUTRAL to AppearanceMode.SYSTEM, migrateLegacyTheme(""))
    }

    @Test
    fun legacyNameRecognitionMatchesMigrationTable() {
        // Every name migrateLegacyTheme maps (except the fallback) is
        // recognized, so the preview migration never mistakes a new
        // palette name for a legacy one — except MATERIAL_YOU, which is
        // deliberately both (legacy branch wins; same look either way).
        val legacy = listOf(
            "FOLLOW_SYSTEM", "LIGHT", "DARK", "MATERIAL_YOU", "MATERIAL_YOU_LIGHT",
            "MATERIAL_YOU_DARK", "NIGHT", "PITLANE", "CIRCUIT", "HERITAGE", "PHONE",
        )
        legacy.forEach { assertTrue("$it should be legacy", isLegacyThemeName(it)) }
        assertFalse(isLegacyThemeName("NEUTRAL"))
        assertFalse(isLegacyThemeName("BOGUS"))
        assertFalse(isLegacyThemeName(""))
    }

    @Test
    fun resolveDarkMatrix() {
        // Forced-dark schemes ignore mode AND phone, in both phone states.
        for (phoneDark in listOf(false, true)) {
            for (mode in AppearanceMode.entries) {
                assertTrue(resolveDark(ColorScheme.NIGHT, mode, phoneDark))
                assertTrue(resolveDark(ColorScheme.PITLANE, mode, phoneDark))
                assertTrue(resolveDark(ColorScheme.MERIDIAN, mode, phoneDark))
            }
        }
        // Every other scheme: Light forces light, Dark forces dark,
        // System defers to the phone — in both phone states.
        val following = listOf(ColorScheme.NEUTRAL, ColorScheme.MATERIAL_YOU, ColorScheme.CIRCUIT, ColorScheme.HERITAGE, ColorScheme.VERVE)
        for (scheme in following) {
            assertFalse(resolveDark(scheme, AppearanceMode.LIGHT, true))
            assertFalse(resolveDark(scheme, AppearanceMode.LIGHT, false))
            assertTrue(resolveDark(scheme, AppearanceMode.DARK, true))
            assertTrue(resolveDark(scheme, AppearanceMode.DARK, false))
            assertFalse(resolveDark(scheme, AppearanceMode.SYSTEM, false))
            assertTrue(resolveDark(scheme, AppearanceMode.SYSTEM, true))
        }
    }

    @Test
    fun effectiveSchemeOnlyAutoNightSwitchesToNight() {
        for (scheme in ColorScheme.entries) {
            assertEquals(scheme, effectiveScheme(scheme, false))
        }
        assertEquals(ColorScheme.NIGHT, effectiveScheme(ColorScheme.NIGHT, true))
        assertEquals(ColorScheme.NIGHT, effectiveScheme(ColorScheme.NEUTRAL, true))
        assertEquals(ColorScheme.NIGHT, effectiveScheme(ColorScheme.CIRCUIT, true))
        assertEquals(ColorScheme.NIGHT, effectiveScheme(ColorScheme.HERITAGE, true))
        assertEquals(ColorScheme.NIGHT, effectiveScheme(ColorScheme.MATERIAL_YOU, true))
        assertEquals(ColorScheme.NIGHT, effectiveScheme(ColorScheme.PITLANE, true))
    }

    @Test
    fun pickerContentsArePalettesAndModesOnly() {
        // The scheme picker holds exactly the palettes, in order (r48
        // appends Verve second-to-last; r79 appends Horizon; r107
        // appends Meridian last); the mode control holds exactly
        // Light / Dark / System.
        assertEquals(
            listOf(
                ColorScheme.NEUTRAL, ColorScheme.MATERIAL_YOU, ColorScheme.NIGHT,
                ColorScheme.PITLANE, ColorScheme.CIRCUIT, ColorScheme.HERITAGE,
                ColorScheme.VERVE, ColorScheme.HORIZON, ColorScheme.MERIDIAN,
            ),
            colorSchemes,
        )
        assertEquals(
            listOf(AppearanceMode.LIGHT, AppearanceMode.DARK, AppearanceMode.SYSTEM),
            appearanceModes,
        )
        // No scheme row reads as a mode, and no mode row reads as a scheme.
        // r110: the Neutral scheme's picker label is Canvas (label
        // only — the stored enum name stays NEUTRAL).
        assertEquals(
            listOf("Canvas", "Material You", "Night", "Pitlane", "Circuit", "Heritage", "Verve", "Horizon", "Meridian"),
            colorSchemes.map { it.displayName },
        )
        assertEquals(
            listOf("Light", "Dark", "System"),
            appearanceModes.map { it.displayName },
        )
        assertTrue(colorSchemes.none { it.displayName in listOf("Follow system", "Light", "Dark") })
    }

    @Test
    fun schemeForcesDarkMatchesResolveDark() {
        // schemeForcesDark (used by the settings explainer) agrees with
        // resolveDark's forced branch for every scheme and mode.
        for (scheme in ColorScheme.entries) {
            val forced = schemeForcesDark(scheme)
            for (mode in AppearanceMode.entries) {
                if (forced) assertTrue(resolveDark(scheme, mode, false))
            }
            if (!forced) {
                assertFalse(resolveDark(scheme, AppearanceMode.LIGHT, true))
            }
        }
    }
}
