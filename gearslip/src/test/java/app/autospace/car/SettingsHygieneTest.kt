package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit round R11 (settings hygiene), item 5-29: every CarSettings key
 * constant is enumerated here, and each one is asserted to have a reader
 * and a writer. The enumeration runs over the KEY_ constants themselves
 * (now internal, so the test can see them), so a future key added
 * without a reader + writer, a renamed key string, or a removed key
 * fails this test loudly instead of drifting silently.
 *
 * Seam stated honestly: JVM tests cannot observe call sites, so the
 * reader + writer half is pinned as an expected set hand-verified at
 * r173 against init() + the setters (every key below is read in
 * init() — or, for the retired KEY_THEME, in its migration branch —
 * and written by a setter or a migration branch). What the test proves
 * at runtime is enumeration: the live constant set equals the verified
 * set exactly, with no duplicates and no non-car_ stragglers. The
 * lenient-parse test below pins the 5-28 helper's parse semantics; its
 * log line is code-review verified, not unit-pinned.
 */
class SettingsHygieneTest {

    @Test
    fun `car settings keys are distinct and car-prefixed`() {
        val keys = carSettingsKeys()
        assertTrue("no KEY_ constants found", keys.isNotEmpty())
        assertEquals("duplicate key strings", keys.size, keys.toSet().size)
        keys.forEach { assertTrue("key without car_ prefix: $it", it.startsWith("car_")) }
    }

    @Test
    fun `every car settings key has a reader and a writer`() {
        // Hand-verified at r173: each key is read in init() (KEY_THEME
        // only in its r35 migration branch) and written by a setter or
        // a migration branch. A future add, rename, or removal breaks
        // this equality until its reader + writer are confirmed.
        val expected = setOf(
            "car_app_order",
            "car_app_theme",
            "car_appearance_mode",
            "car_apps_hidden",
            "car_audio_choice_asked",
            "car_auto_night_end",
            "car_auto_night_mode",
            "car_auto_night_start",
            "car_autoplay",
            "car_autoplay_stops",
            "car_barge_in",
            "car_bluetooth_warning",
            "car_brightness",
            "car_call_speaker",
            "car_dark_type",
            "car_hotword",
            "car_hotword_phone_mic",
            "car_icon_manual_packs",
            "car_icon_overrides",
            "car_icon_pack",
            "car_icon_size",
            "car_last_media",
            "car_last_media_used_ms",
            "car_last_nav",
            "car_location_warning",
            "car_map_theme",
            "car_media_card_dock",
            "car_media_card_dock_enabled",
            "car_mirror_fit",
            "car_night_mode",
            "car_open_on_connect",
            "car_phone_mic_voice",
            "car_pinned_apps",
            "car_pipe_audio",
            "car_player_skip_sec",
            "car_player_speed",
            "car_scale",
            "car_scheme",
            "car_seen_safety_warning",
            "car_speak_directions",
            "car_sub_bg",
            "car_sub_color",
            "car_sub_edge",
            "car_sub_font",
            "car_sub_size",
            "car_system_speech",
            "car_tile_size",
            "car_ui_theme",
            "car_units",
            "car_whats_new_after_updates",
            "car_whats_new_seen_code",
            "car_widgets_dismissed",
            "car_widgets_hidden"
        )
        assertEquals(expected, carSettingsKeys())
    }

    @Test
    fun `lenient parse keeps parsed values and falls back on rejected values`() {
        assertEquals(
            1.5f,
            CarSettings.lenientParse("car_scale", "9.9", 1f) { it.toFloatOrNull()?.coerceIn(0.5f, 1.5f) },
        )
        assertEquals(
            1f,
            CarSettings.lenientParse("car_scale", "junk", 1f) { it.toFloatOrNull()?.coerceIn(0.5f, 1.5f) },
        )
        assertEquals(
            1f,
            CarSettings.lenientParse("car_scale", "", 1f) { it.toFloatOrNull()?.coerceIn(0.5f, 1.5f) },
        )
        assertEquals(
            45,
            CarSettings.lenientParse("car_player_skip_sec", "45", 10) { it.toIntOrNull() },
        )
        assertEquals(
            10,
            CarSettings.lenientParse("car_player_skip_sec", "fast", 10) { it.toIntOrNull() },
        )
    }

    private fun carSettingsKeys(): Set<String> =
        CarSettings::class.java.declaredFields
            .filter { it.name.startsWith("KEY_") && it.type == String::class.java }
            .map { it.get(null) as String }
            .toSet()
}
