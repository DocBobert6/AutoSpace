package app.autospace.car

import app.autospace.car.theme.GsThemes

/**
 * r80 — Pairs (presets), approved 2026-10-26 ("Presets/pairs confirm").
 * One tap sets the button style and the color scheme together. A pair
 * is only a shortcut for writing the two keys: there is no stored
 * "current pair", no lock flag, and no re-application on later loads.
 * The individual pickers read the same two keys, so they naturally
 * reflect a pair's values and stay freely changeable afterward.
 *
 * The approved pairs, in order: Pitlane+Pitlane, Verve+Verve,
 * Frost+Neutral (the default), and Meridian+Meridian (r107).
 * Horizon+Horizon is NOT here — Horizon did not exist when Pairs was
 * approved, so it waits on Robert's word as a proposed fifth pair
 * rather than being added silently.
 *
 * Pure (no Compose runtime, no Android API), so it is unit-testable
 * on the JVM.
 */
data class ThemePair(val styleId: String, val scheme: ColorScheme, val label: String)

/** The approved pairs, in picker order. */
val themePairs: List<ThemePair> = listOf(
    ThemePair(GsThemes.Pitlane.id, ColorScheme.PITLANE, "Pitlane + Pitlane"),
    ThemePair(GsThemes.Verve.id, ColorScheme.VERVE, "Verve + Verve"),
    ThemePair(GsThemes.Frost.id, ColorScheme.NEUTRAL, "Frost + Neutral"),
    ThemePair(GsThemes.Meridian.id, ColorScheme.MERIDIAN, "Meridian + Meridian"),
)

/**
 * The pair the current style + scheme combination matches, or null
 * when it matches none. Derived, never stored — a null simply means
 * no pair chip highlights, which is the truthful state when the two
 * pickers hold a combination no pair names.
 */
fun themePairFor(styleId: String, scheme: ColorScheme): ThemePair? =
    themePairs.firstOrNull { it.styleId == styleId && it.scheme == scheme }
