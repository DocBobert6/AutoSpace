package app.autospace.car

import kotlin.math.pow

/**
 * WCAG contrast math, pure and dependency-free (r33 Pitch/OLED audit).
 * Colors are ARGB Ints so the math is JVM-testable with no Compose runtime.
 * Used to MEASURE the audit's ratios, never to claim them: D4's onPrimary
 * fix, R1's hairline lift, and R3's near-black step all pin their ratios
 * in ContrastTest against these functions.
 */

/** Relative luminance of an sRGB color (0.0 black .. 1.0 white). */
fun relativeLuminance(argb: Int): Double {
    fun linear(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    val r = linear((argb shr 16) and 0xFF)
    val g = linear((argb shr 8) and 0xFF)
    val b = linear(argb and 0xFF)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** [foreground] painted at [alpha] over opaque black, as an opaque ARGB Int. */
fun blendOverBlack(foregroundArgb: Int, alpha: Float): Int {
    fun ch(shift: Int): Int {
        val v = ((foregroundArgb shr shift) and 0xFF) * alpha
        return v.toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** WCAG contrast ratio between two opaque colors (1.0 .. 21.0). */
fun contrastRatio(foregroundArgb: Int, backgroundArgb: Int): Double {
    val l1 = relativeLuminance(foregroundArgb)
    val l2 = relativeLuminance(backgroundArgb)
    val (hi, lo) = if (l1 >= l2) l1 to l2 else l2 to l1
    return (hi + 0.05) / (lo + 0.05)
}
