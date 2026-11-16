package app.autospace.car

/**
 * r228 (Crimson Rain): the palette tokens for the Crimson Rain
 * scheme — the second cyberpunk palette, measured from Robert's two
 * Quadra Type-66 interior photos (~/workspace/cyberpunk-photos/
 * MEASURED.md), not picked by eye. No game is referenced anywhere:
 * the name is two genre words, and the colours are the car's own
 * interior light, not any one work's.
 *
 * - Dark ONLY: the background is near-black #0B0B0D (measured
 *   #02120C / #0C1103 on the photos' own panels); signal red
 *   (#FF003C) is the primary and the information colour — red
 *   displays are the norm in the photos, not the warning; measured
 *   teal (#20B0B0, the Crystal Dome bar's own #21B2B2) is the
 *   secondary and the cool accent — greener and dimmer than Neon
 *   Rain's pure cyan #00F0FF, which is bluer and brighter than
 *   anything in the photos; pale ice (#E8F6FF, the white gauge
 *   numerals) is the tertiary. Error is signal red, like Neon
 *   Rain's. Yellow is absent entirely: it appears in the photos
 *   only as stitching (9 yellow pixels in 6,800 sampled).
 * - Red lives in exactly two spots — the primary role and the
 *   error role, both signal red #FF003C. The tertiary is pale ice,
 *   not red: a red tertiary would put red text on red fills
 *   everywhere the assistant reads it.
 * - onPrimary is near-black #0B0B0D, not white: red + near-black
 *   reads 4.98:1, red + white only 3.95:1 (modest, pinned exact
 *   below, the same class as Dashboard's red note in r224).
 *   The research report's 5.32:1 was measured against pure black;
 *   against the real background it is 4.98:1 — still past the bar.
 * - There is no light variant: Crimson Rain forces dark
 *   (schemeForcesDark), exactly like Neon Rain, so the palette
 *   carries dark tokens only — no dead light tokens to drift.
 *
 * Contrast (WCAG sRGB, opaque RGB) — the numbers the legend gate
 * in GsControls relies on: red on the field 4.98:1, teal on the
 * field 7.40:1, pale ice on the field 17.85:1. Every role pair
 * clears 4.5:1 (onPrimary/primary 4.98:1, onSecondary/secondary
 * 7.11:1, containers 11.06:1 / 11.45:1 / 12.31:1) EXCEPT white on
 * error, 3.95:1 — modest, the same class as Neon Rain's white on
 * tertiary: error text on the field reads 4.98:1, and white text
 * on a red chip is the fix if it reads poorly on device.
 */
object CrimsonRainPalette {
    const val background: Int = 0xFF0B0B0D.toInt()
    const val onSurface: Int = 0xFFE8F6FF.toInt()
    const val surfaceVariant: Int = 0xFF141419.toInt()
    const val onSurfaceVariant: Int = 0xFF8FA3B8.toInt()
    const val primary: Int = 0xFFFF003C.toInt()
    const val onPrimary: Int = 0xFF0B0B0D.toInt()
    const val primaryContainer: Int = 0xFF40060F.toInt()
    const val onPrimaryContainer: Int = 0xFFFFC2CC.toInt()
    const val secondary: Int = 0xFF20B0B0.toInt()
    const val onSecondary: Int = 0xFF001414.toInt()
    const val secondaryContainer: Int = 0xFF003333.toInt()
    const val onSecondaryContainer: Int = 0xFFB8F4FF.toInt()
    const val tertiary: Int = 0xFFE8F6FF.toInt()
    const val onTertiary: Int = 0xFF0B0B0D.toInt()
    const val tertiaryContainer: Int = 0xFF1A2833.toInt()
    const val onTertiaryContainer: Int = 0xFFD6ECF5.toInt()
    const val outline: Int = 0xFF2A3A4A.toInt()
    const val outlineVariant: Int = 0xFF1A2432.toInt()
    const val error: Int = 0xFFFF003C.toInt()
    const val onError: Int = 0xFFFFFFFF.toInt()
}
