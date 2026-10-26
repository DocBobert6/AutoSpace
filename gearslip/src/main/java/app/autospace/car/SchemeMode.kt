package app.autospace.car

/**
 * r35 — the scheme / mode split (Robert 2026-10-26: "Color schemes should
 * just have the color schemes themselves. Light and dark mode should be its
 * own toggle."). What used to be one mixed [AppTheme]-style value is two
 * independent axes, exactly like button style vs. color scheme already are:
 *
 * - [ColorScheme] is the palette ONLY. [NEUTRAL] is the plain theme the old
 *   Follow system / Light / Dark all shared ("plain neutral theme" in the
 *   old explainer's own words). There is no Light, Dark, or Follow-system
 *   scheme any more; the mode axis decides the variant.
 * - [AppearanceMode] is light/dark on its own control. [SYSTEM] preserves
 *   the old follow-the-phone behavior, moved not removed.
 *
 * Everything in this file is pure (no Compose runtime, no Android API), so
 * it is unit-testable on the JVM. The composable pickers build their rows
 * from [colorSchemes] / [appearanceModes] and [displayName], so the lists
 * cannot drift from what the tests pin.
 */
enum class ColorScheme { NEUTRAL, MATERIAL_YOU, NIGHT, PITLANE, CIRCUIT, HERITAGE }

enum class AppearanceMode { LIGHT, DARK, SYSTEM }

/** The scheme picker, in order: palettes only, never a mode. */
val colorSchemes: List<ColorScheme> = ColorScheme.entries.toList()

/** The mode control, in order. */
val appearanceModes: List<AppearanceMode> = AppearanceMode.entries.toList()

val ColorScheme.displayName: String
    get() = when (this) {
        ColorScheme.NEUTRAL -> "Neutral"
        ColorScheme.MATERIAL_YOU -> "Material You"
        ColorScheme.NIGHT -> "Night"
        ColorScheme.PITLANE -> "Pitlane"
        ColorScheme.CIRCUIT -> "Circuit"
        ColorScheme.HERITAGE -> "Heritage"
    }

val AppearanceMode.displayName: String
    get() = when (this) {
        AppearanceMode.LIGHT -> "Light"
        AppearanceMode.DARK -> "Dark"
        AppearanceMode.SYSTEM -> "System"
    }

/**
 * One-time migration from the retired `car_app_theme` value to the new
 * axes. Every row preserves the old look exactly: the old mode-embedded
 * value becomes its palette plus the mode it embedded, and the old
 * phone-following values become their palette plus SYSTEM. Unknown names
 * fall back to NEUTRAL + SYSTEM, matching the old unknown-name fallback
 * (FOLLOW_SYSTEM = plain, following the phone). "PHONE" is in the table
 * because the Phase 7 migration (PHONE -> MATERIAL_YOU) may never have
 * run on an install that skipped the intervening builds.
 */
fun migrateLegacyTheme(stored: String): Pair<ColorScheme, AppearanceMode> =
    when (stored) {
        "FOLLOW_SYSTEM" -> ColorScheme.NEUTRAL to AppearanceMode.SYSTEM
        "LIGHT" -> ColorScheme.NEUTRAL to AppearanceMode.LIGHT
        "DARK" -> ColorScheme.NEUTRAL to AppearanceMode.DARK
        "MATERIAL_YOU", "PHONE" -> ColorScheme.MATERIAL_YOU to AppearanceMode.SYSTEM
        "MATERIAL_YOU_LIGHT" -> ColorScheme.MATERIAL_YOU to AppearanceMode.LIGHT
        "MATERIAL_YOU_DARK" -> ColorScheme.MATERIAL_YOU to AppearanceMode.DARK
        "NIGHT" -> ColorScheme.NIGHT to AppearanceMode.DARK
        "PITLANE" -> ColorScheme.PITLANE to AppearanceMode.DARK
        "CIRCUIT" -> ColorScheme.CIRCUIT to AppearanceMode.SYSTEM
        "HERITAGE" -> ColorScheme.HERITAGE to AppearanceMode.SYSTEM
        else -> ColorScheme.NEUTRAL to AppearanceMode.SYSTEM
    }

/** True for a stored name the migration table recognizes (old or PHONE). */
fun isLegacyThemeName(stored: String): Boolean = stored in setOf(
    "FOLLOW_SYSTEM", "LIGHT", "DARK", "MATERIAL_YOU", "MATERIAL_YOU_LIGHT",
    "MATERIAL_YOU_DARK", "NIGHT", "PITLANE", "CIRCUIT", "HERITAGE", "PHONE",
)

/**
 * Whether the car UI renders dark for a scheme + mode + the phone's own
 * mode. Night and Pitlane are always dark (as they always were); every
 * other scheme follows the mode control, with SYSTEM deferring to the
 * phone. This is the single place the rule lives — CarUi and the tests
 * both read it, so the rendered dark value cannot drift from the tested
 * one.
 */
fun resolveDark(scheme: ColorScheme, mode: AppearanceMode, phoneDark: Boolean): Boolean =
    when {
        scheme == ColorScheme.NIGHT || scheme == ColorScheme.PITLANE -> true
        mode == AppearanceMode.LIGHT -> false
        mode == AppearanceMode.DARK -> true
        else -> phoneDark
    }

/**
 * The scheme the car UI actually renders: the user's chosen scheme, except
 * that an active Auto night switches it to NIGHT. The stored scheme never
 * changes. (Auto night logic itself is untouched by the split — see
 * NightAuto for the precedence rule.)
 */
fun effectiveScheme(stored: ColorScheme, autoNightActive: Boolean): ColorScheme =
    if (autoNightActive && stored != ColorScheme.NIGHT) ColorScheme.NIGHT else stored

/** True when the stored scheme forces dark regardless of the mode control. */
fun schemeForcesDark(scheme: ColorScheme): Boolean =
    scheme == ColorScheme.NIGHT || scheme == ColorScheme.PITLANE
