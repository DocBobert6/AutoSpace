package app.autospace.car.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the car UI's controls look, separate from its colours: [MaterialTheme]'s colour scheme still
 * decides what colour a button is, and this decides how that colour is drawn. A theme is plain
 * data, so a user-supplied theme later is just another instance - nothing about the built-in ones
 * is special beyond being listed in [GsThemes.builtIn].
 */
@Immutable
data class GsTheme(
    /** Stable key persisted in settings; never reuse one for a different look. */
    val id: String,
    val name: String,
    val control: ControlLook,
)

/** The finish on anything tappable that has a container: buttons, toggles, keypad keys. */
@Immutable
data class ControlLook(
    val corner: Dp = 12.dp,
    /** How far the top edge is lifted towards white, 0..1, fading evenly to the base colour at the centre. */
    val topLight: Float = 0f,
    /** How far the bottom edge is pushed towards black, 0..1. */
    val bottomShade: Float = 0f,
    /**
     * [bottomShade] for containers that are already light, where lifting the top towards white
     * barely shows: those get their depth from a darker bottom edge instead.
     */
    val bottomShadeOnLight: Float = 0f,
    /** Alpha of the thin highlight along the top rim, fading out down the sides; 0 for none. */
    val rimAlpha: Float = 0f,
    /** A solid darker base under the button, like a keycap; 0 for none. */
    val lip: Dp = 0.dp,
    /** How far a soft drop shadow falls below the button; 0 for none. Its blur is twice this. */
    val shadow: Dp = 0.dp,
    val shadowAlpha: Float = 0f,
    /**
     * A 1px bevel on the edge only - light along the top, dark along the bottom - so depth reads
     * at any size without shading the face itself. 0 for none.
     */
    val bevelTop: Float = 0f,
    val bevelBottom: Float = 0f,
    /** How far the face drops while pressed; 0 for none. */
    val sink: Dp = 0.dp,
    /**
     * Alpha of the shadow cast inside the top edge of a latched control (a selected option, the
     * current screen's nav button), so it reads as pushed in. 0 for none.
     */
    val insetAlpha: Float = 0f,
    /**
     * Alpha of the dark overlay while pressed. Heavier than a ripple on purpose: the car screen
     * shows a video stream a frame or two behind the finger, so subtle feedback gets lost.
     */
    val pressedDim: Float = 0.22f,
    /** Scale while pressed, 1 for none. */
    val pressedScale: Float = 0.95f,
    /**
     * How a control with a press-and-hold action says so. The peel needs a raised face to lift
     * from; a flat look takes the dot.
     */
    val holdMarker: HoldMarkerStyle = HoldMarkerStyle.PEEL,
    /**
     * Multiplier for a control container's alpha. 1f is opaque (every Classic/Material look).
     * Frost uses <1f so surfaces read as glass over whatever sits behind them (art, a
     * header image, the nav strip) — never as live blur over the video stream, which the
     * H.264 compression would smear and which costs phone heat. If a blur pass ever proves
     * expensive, Frost still reads correctly from translucency + scrim alone.
     */
    val containerAlpha: Float = 1f,
    /** Replaces this look when the UI is light, for effects that only work on one background. */
    val light: ControlLook? = null,
)

object GsThemes {
    val Keycap = GsTheme(
        id = "keycap",
        name = "Keycap",
        control = ControlLook(lip = 4.dp, pressedScale = 1f, holdMarker = HoldMarkerStyle.PEEL),
    )

    val Tactile = GsTheme(
        id = "tactile",
        name = "Tactile",
        control = ControlLook(
            topLight = 0.05f,
            shadow = 3.dp,
            shadowAlpha = 0.32f,
            bevelTop = 0.16f,
            bevelBottom = 0.22f,
            sink = 2.dp,
            insetAlpha = 0.30f,
            pressedDim = 0.12f,
            pressedScale = 1f,
            holdMarker = HoldMarkerStyle.PEEL,
            // A wide soft shadow bands once the car's video stream compresses it on a light
            // background, so light mode gets a tight one and leans on the bevel instead.
            light = ControlLook(
                topLight = 0.05f,
                shadow = 1.5.dp,
                shadowAlpha = 0.38f,
                bevelTop = 0.24f,
                bevelBottom = 0.30f,
                sink = 2.dp,
                insetAlpha = 0.30f,
                pressedDim = 0.12f,
                pressedScale = 1f,
                holdMarker = HoldMarkerStyle.PEEL,
            ),
        ),
    )

    val Flat = GsTheme(
        id = "flat",
        name = "Flat",
        control = ControlLook(holdMarker = HoldMarkerStyle.DOT),
    )

    /** Pure Material 3: standard corner, tonal surfaces, no keycap/bevel/shadow. */
    val Material = GsTheme(
        id = "material",
        name = "Material",
        control = ControlLook(
            corner = 16.dp,
            pressedDim = 0.12f,
            pressedScale = 1f,
            holdMarker = HoldMarkerStyle.DOT,
        ),
    )

    /**
     * Frost (AutoSpace default): Material 3 + translucent surfaces + a top rim highlight.
     * No shadow, bevel, lip or sink — depth comes from translucency. Blur is deliberately
     * NOT part of ControlLook: it is only ever applied to small static backdrops (see
     * the player art treatment), never to live controls over the video stream.
     */
    val Frost = GsTheme(
        id = "frost",
        name = "Frost",
        control = ControlLook(
            corner = 24.dp,
            rimAlpha = 0.18f,
            containerAlpha = 0.78f,
            pressedDim = 0.14f,
            pressedScale = 0.98f,
            holdMarker = HoldMarkerStyle.DOT,
            light = ControlLook(
                corner = 24.dp,
                rimAlpha = 0.12f,
                containerAlpha = 0.82f,
                pressedDim = 0.10f,
                pressedScale = 0.98f,
                holdMarker = HoldMarkerStyle.DOT,
            ),
        ),
    )

    val default = Frost
    // Classic = Flat / Tactile / Keycap, kept unchanged for existing users.
    val builtIn = listOf(Material, Frost, Flat, Tactile, Keycap)

    fun byId(id: String?): GsTheme = builtIn.firstOrNull { it.id == id } ?: default
}

val LocalGsTheme = staticCompositionLocalOf { GsThemes.default }
