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
    /**
     * Alpha of Notscape's 0.5dp hairline (outline colour) around the control, 0 for none.
     * Phase 7: Frost wears the hairline exactly as FrostKit's frostBar does, alongside its
     * shadow and gradient — Phase 6 had only the rim, which is a white fade, not an outline.
     */
    val hairlineAlpha: Float = 0f,
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
     * Frost uses 0.97f — near-solid, per Notscape's solid-frost recipe (Phase 6):
     * the surface reads as frost from its gradient + shadow + hairline, never from
     * a live blur over the video stream, which the H.264 compression would smear
     * and which costs phone heat.
     */
    val containerAlpha: Float = 1f,
    /** Replaces this look when the UI is light, for effects that only work on one background. */
    val light: ControlLook? = null,
)

object GsThemes {
    /**
     * Edge (2026-10-25, r20): Keycap's successor, picked by Robert from the six
     * replacement options. The outline IS the style — a strong hairline, a 1px bevel
     * on the edge only, and nothing else: no gradient (topLight 0), no shadow, no lip.
     * The face is flat, so the hold marker is the DOT, not the PEEL (which needs a
     * raised face to lift from). New id "edge": the old "keycap" id is never reused
     * for a different look, and CarSettings migrates stored "keycap" to "edge" on load.
     */
    val Edge = GsTheme(
        id = "edge",
        name = "Edge",
        control = ControlLook(
            corner = 16.dp,
            topLight = 0f,
            hairlineAlpha = 0.85f,
            bevelTop = 0.35f,
            bevelBottom = 0.45f,
            shadow = 0.dp,
            pressedDim = 0.18f,
            containerAlpha = 1f,
            holdMarker = HoldMarkerStyle.DOT,
            // Light variant: the same outline, slightly softer — hairline 0.70 and
            // bevels a step lower, so the edge reads on white without shouting.
            light = ControlLook(
                corner = 16.dp,
                topLight = 0f,
                hairlineAlpha = 0.70f,
                bevelTop = 0.30f,
                bevelBottom = 0.40f,
                shadow = 0.dp,
                pressedDim = 0.18f,
                containerAlpha = 1f,
                holdMarker = HoldMarkerStyle.DOT,
            ),
        ),
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

    /**
     * Frost (AutoSpace default, Phase 7): Notscape Mariner's final solid-frost
     * recipe, not a live blur. Notscape shipped Haze backdrop blur in Phase A
     * and then REMOVED it entirely (0.0.63, Robert's ruling) — and a blur pass
     * cannot capture the surfaces that matter most here anyway (SurfaceView /
     * Media3 video, and the H.264 car stream, which smears any blur). Depth
     * therefore comes from a near-solid surface (alpha 0.97, lighter at the
     * top), a 6dp shadow, and a 0.5dp hairline at outline @0.45 — FrostKit's
     * frostBar, in that order. Phase 6 had only a 2dp shadow and a white rim,
     * which is why Frost read as flat; the 6dp shadow and the true hairline
     * are the Phase 7 correction. Cards use the 20dp radius from that kit's
     * concentric ladder. The neutral palette itself lives in CarUi's plain
     * colour scheme: plain Frost surfaces must be neutral system backgrounds,
     * never the wallpaper-dynamic colour, which is what washed Phase 1–5
     * Frost tiles flat pale peach.
     */
    val Frost = GsTheme(
        id = "frost",
        name = "Frost",
        control = ControlLook(
            corner = 20.dp,
            topLight = 0.06f,
            rimAlpha = 0.35f,
            hairlineAlpha = 0.45f,
            shadow = 6.dp,
            shadowAlpha = 0.30f,
            containerAlpha = 0.97f,
            pressedDim = 0.14f,
            pressedScale = 0.98f,
            holdMarker = HoldMarkerStyle.DOT,
            light = ControlLook(
                corner = 20.dp,
                topLight = 0.04f,
                rimAlpha = 0.30f,
                hairlineAlpha = 0.45f,
                shadow = 6.dp,
                shadowAlpha = 0.25f,
                containerAlpha = 0.97f,
                pressedDim = 0.10f,
                pressedScale = 0.98f,
                holdMarker = HoldMarkerStyle.DOT,
            ),
        ),
    )

    val default = Frost
    // Phase 7: Material REMOVED from builtIn (Robert 2026-10-24 23:58) — it was
    // redundant with the App theme's Material You, and its pure-Material-3 look
    // (16dp corner, DOT hold marker) was effectively the same as Flat. Existing
    // users stored on "material" land on Frost via byId's fallback, as logged.
    // r20: Keycap REMOVED from builtIn and replaced in its slot by Edge (see above).
    // Stored "keycap" never reaches byId's fallback — CarSettings migrates it to
    // "edge" on load — so a Keycap user lands on Edge, not on Frost.
    // Classic = Flat / Tactile / Edge, kept unchanged for existing users.
    val builtIn = listOf(Frost, Flat, Tactile, Edge)

    fun byId(id: String?): GsTheme = builtIn.firstOrNull { it.id == id } ?: default
}

val LocalGsTheme = staticCompositionLocalOf { GsThemes.default }
