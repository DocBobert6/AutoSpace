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
    /**
     * r108 (Heirloom only): a dashed stitch line inset from the edge,
     * drawn as part of the button background in gsSurface (under
     * content, like the weave) — never an overlay widget. Alpha of
     * the stitch line; 0 for none. The line itself is warm cream on
     * dark containers and dark brass on light ones (see gsSurface),
     * so it stays visible on cream fills.
     */
    val stitchAlpha: Float = 0f,
    /** Inset of the stitch line from the control edge; 0 for none. */
    val stitchInset: Dp = 0.dp,
    /**
     * r110 (Blueprint only): a crisp 1px ring in onSurfaceVariant
     * around the control, drawn as part of the button background
     * in gsSurface (under content, like the stitch) — never an
     * overlay widget. Latched: the ring takes the scheme primary
     * (see gsSurface) plus the faint primary tint
     * ([latchedPrimaryTint]). Alpha of the ring line; 0 for none.
     * The line itself is a literal pixel wide (see gsSurface),
     * inset half a pixel so the full pixel shows inside the edge.
     */
    val ringAlpha: Float = 0f,
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
    /**
     * r24: when true, capsule and explicitly round button/tile containers
     * (launcher tiles, switches, keyboard keys, call buttons) render with
     * this style's corner instead of their capsule/round fallback, so no
     * button or tile renders as a pill under the style. Pitlane only; every
     * other style keeps its existing shapes exactly (see gsButtonShape).
     */
    val rectangularButtons: Boolean = false,
    /**
     * r79 (Horizon only): button containers on the [gsShape] path
     * ([GsButton], [GsIconButton], the nav bar, the side rail, the
     * transport row, the autoplay offer) render as full pills instead
     * of the style's
     * corner. False everywhere else, so no other style's shapes
     * change. Surfaces on the [gsButtonShape] path (switch knob,
     * safety dialog, media pill, full-player panels) keep their
     * call-site fallback — the pill rule is the button rule, not a
     * second card rule, so dialogs stay 28dp and panels keep 20dp.
     */
    val pillButtons: Boolean = false,
    /**
     * r48 (Verve only): state-change animations (press sink/lift,
     * toggle travel, morph) use [stateChangeMotionSpec] — the Verve
     * spring when parked, an instant snap while moving — instead of
     * the kit's fixed tweens. False everywhere else, so no other
     * style's motion changes.
     */
    val springMotion: Boolean = false,
    /**
     * r79: damping for the spring-motion spec ([stateChangeMotionSpec]).
     * Default 0.7 is the shared default; Horizon passes 0.65, and Verve
     * passes 1.0 since r100 (critically damped: the fastest settle
     * with no overshoot). Only read when [springMotion] is true.
     */
    val springDamping: Float = 0.7f,
    /**
     * r48 (Verve only): card/sheet surfaces ([GsGroupedCard],
     * [GsPanelCard], [GsSheetSurface], the floating media card, widget
     * cards, frosted map chrome) take the style's corner instead of
     * their fallback radius, via the look-fed [cardShapeForLook].
     * False everywhere else; buttons, tiles, keys, pills and bars keep
     * their fallback shapes under every style.
     */
    val cardsUseStyleCorner: Boolean = false,
    /**
     * r26 (Pit Lane only): carbon-fiber weave line alpha on control faces,
     * drawn with Canvas at low alpha under content and borders. 0 for none.
     * Panel cards (GsGroupedCard/GsPanelCard/GsSheetSurface) draw the same
     * weave stronger (see pitLanePanel) — panels are where the weave reads.
     */
    val weaveAlpha: Float = 0f,
    /** r26 (Pit Lane only): brushed-metal horizontal grain streak alpha. 0 for none. */
    val grainAlpha: Float = 0f,
    /**
     * r26 (Pit Lane only): corner screw dots on panel cards and sheets
     * (pitLanePanel), never on buttons/tiles — corners only, never scattered.
     */
    val rivets: Boolean = false,
    /**
     * r26 (Pit Lane only): a small lit LED dot on latched controls, drawn
     * at the top-end corner in ADDITION to the primary fill + bold label
     * (never a replacement). Not a checker: a single dot, no strip.
     */
    val ledDot: Boolean = false,
    /**
     * r26 (Pit Lane only): switches render as metal bat-lever toggles
     * (GsSwitch) instead of the squared track-and-knob. Travel and hit
     * area unchanged; only the drawing changes.
     */
    val batToggle: Boolean = false,
    /**
     * r26 (Pit Lane only): section headers (GsSectionHeader) render as
     * engraved panel labels — small, plain, letterspaced, inset. Headers
     * only; body/button/tile labels are untouched.
     */
    val engravedHeader: Boolean = false,
    /**
     * r26 (Pit Lane only): the hard bevel draws on latched controls too.
     * Other styles keep the old rule (bevel suppressed while latched, so
     * the pressed-in read stays clean); Pit Lane's bevel is the panel
     * edge, not a selection cue, so it stays on latched controls.
     */
    val bevelOnLatched: Boolean = false,
    /**
     * r100 (Verve only, Blueprint since r110): a faint primary tint
     * over a latched control's container, in addition to the spring
     * settle — so a selected option reads selected even where the
     * tone is not Primary. False everywhere else; other styles keep
     * their exact latched read (down state + call-site tone).
     * Blueprint pairs it with its primary ring (see gsSurface).
     */
    val latchedPrimaryTint: Boolean = false,
    /**
     * RETIRED in r24: formerly drew a small black-and-white checker strip
     * along the top edge of latched controls under Pitlane. Robert
     * disliked the checker under Pitlane and under Circuit, so r24 removes
     * the strip entirely — the rendering path in gsSurface is gone, and
     * this flag reads false on every built-in look. Kept only so the
     * retirement is assertable in GsThemeTest; nothing draws from it.
     */
    val checkerStrip: Boolean = false,
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

    /**
     * Pitlane (2026-10-26, r23; display name corrected 2026-10-26, r26;
     * renamed to one word 2026-10-26, r31):
     * the stock-car button style. Display name is "Pitlane" (one word,
     * Robert 2026-10-26 ~06:46); the id stays "pitlane" and stored values
     * never move — a display-string change only. Switch-panel language:
     * squared 6dp corners, solid color blocking (topLight 0, no gradient,
     * no shadow), and the thickest edge the kit can express — a full-alpha
     * hairline stacked with a strong 1px bevel. ControlLook has no
     * border-width field, so there is no wider stroke to reach for; the
     * stacked hairline + bevel IS the thick border, and it is reported
     * as such rather than claimed as a wider one. Bold labels are NOT
     * expressible through ControlLook (it carries no typeface or weight
     * — the style changes controls, not typefaces, per instruction), so
     * the bold feeling comes from the thick edge + solid containers +
     * pairing with the Pit Lane color scheme's high-contrast carbon
     * black/white/red/yellow, not from heavier text. The face is flat,
     * so the hold marker is the DOT. r24: the checker strip on latched
     * controls is REMOVED entirely (Robert disliked it under Pit Lane and
     * under Circuit) — no strip is drawn for any style, and
     * rectangularButtons (below) squares every capsule/round button and
     * tile container under Pit Lane; insetAlpha is 0 because Pit Lane
     * never had an inset shadow. r26 (fidelity): the flat face gains
     * material — carbon weave + brushed grain at low alpha under content
     * (controls faint, panels stronger), the hard bevel now also draws
     * on latched controls (it is the panel edge, not a selection cue),
     * switches become bat toggles with recessed wells, latched controls
     * gain a yellow LED dot beside their primary fill + bold, panel
     * cards/sheets gain corner screws, and section headers go engraved.
     * The light variant keeps the same squared, thick-edged language on
     * light surfaces (the style never forces carbon-black surfaces —
     * colors come from the color scheme, so Pit Lane-on-light reads
     * squared and thick-edged over the light scheme's surfaces).
     */
    val Pitlane = GsTheme(
        id = "pitlane",
        name = "Pitlane",
        control = ControlLook(
            corner = 6.dp,
            topLight = 0f,
            hairlineAlpha = 1f,
            bevelTop = 0.50f,
            bevelBottom = 0.60f,
            shadow = 0.dp,
            pressedDim = 0.20f,
            containerAlpha = 1f,
            holdMarker = HoldMarkerStyle.DOT,
            rectangularButtons = true,
            weaveAlpha = 0.05f,
            grainAlpha = 0.03f,
            rivets = true,
            ledDot = true,
            batToggle = true,
            engravedHeader = true,
            bevelOnLatched = true,
            light = ControlLook(
                corner = 6.dp,
                topLight = 0f,
                hairlineAlpha = 0.90f,
                bevelTop = 0.45f,
                bevelBottom = 0.55f,
                shadow = 0.dp,
                pressedDim = 0.20f,
                containerAlpha = 1f,
                holdMarker = HoldMarkerStyle.DOT,
                rectangularButtons = true,
                weaveAlpha = 0.04f,
                grainAlpha = 0.025f,
                rivets = true,
                ledDot = true,
                batToggle = true,
                engravedHeader = true,
                bevelOnLatched = true,
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

    /**
     * Verve (2026-10-26, r48; name decided 2026-10-26 ~06:07 over
     * Bloom/Bounce): Material 3 Expressive influence as its OWN pair,
     * never Google's naming in-app (no "Material", no "Expressive").
     * The button style: a clearly rounder corner than Frost's 12dp —
     * 24dp, not full pills — with a soft shadow and a light top edge,
     * and the DOT hold marker (the face is not raised enough for the
     * peel). r48 motion: springMotion (spring parked, snap moving)
     * and cardsUseStyleCorner (cards/sheets take the 24dp corner).
     * The color scheme lives in [app.autospace.car.ColorScheme.VERVE]
     * (palette tokens in VervePalette); style and scheme are
     * independently selectable, as with every other pair.
     * r100 (Verve Refined): the spring is retuned to a critically
     * damped 1.0 (no overshoot), latched controls carry a faint
     * primary tint, and the rail/pill app-bar surfaces take the
     * style's 24dp corner like cards and sheets.
     */
    val Verve = GsTheme(
        id = "verve",
        name = "Verve",
        control = ControlLook(
            corner = 24.dp,
            topLight = 0.05f,
            rimAlpha = 0.20f,
            hairlineAlpha = 0.30f,
            shadow = 4.dp,
            shadowAlpha = 0.28f,
            pressedDim = 0.16f,
            pressedScale = 0.98f,
            containerAlpha = 1f,
            holdMarker = HoldMarkerStyle.DOT,
            springMotion = true,
            cardsUseStyleCorner = true,
            springDamping = 1.0f,
            latchedPrimaryTint = true,
            light = ControlLook(
                corner = 24.dp,
                topLight = 0.04f,
                rimAlpha = 0.20f,
                hairlineAlpha = 0.30f,
                shadow = 4.dp,
                shadowAlpha = 0.22f,
                pressedDim = 0.12f,
                pressedScale = 0.98f,
                containerAlpha = 1f,
                holdMarker = HoldMarkerStyle.DOT,
                springMotion = true,
                cardsUseStyleCorner = true,
                springDamping = 1.0f,
                latchedPrimaryTint = true,
            ),
        ),
    )

    /**
     * Horizon (2026-10-27, r79; Verve's sibling): a Cielo-flavored
     * pair with ORIGINAL values, never "the Cielo look". The button
     * style: full-pill buttons (pillButtons) with a 28dp corner for
     * cards/sheets (cardsUseStyleCorner), a soft shadow and a light
     * top edge, and the DOT hold marker. r79 motion: springMotion
     * with a 0.65 damping spring when parked (Verve's shared spec
     * defaults to 0.7; Horizon passes its own damping — see
     * [stateChangeMotionSpec]), an instant snap while moving, and a
     * 0.97 pressed scale. The color scheme lives in
     * [app.autospace.car.ColorScheme.HORIZON] (palette tokens in
     * HorizonPalette); style and scheme are independently selectable,
     * as with every other pair.
     */
    val Horizon = GsTheme(
        id = "horizon",
        name = "Horizon",
        control = ControlLook(
            corner = 28.dp,
            topLight = 0.05f,
            rimAlpha = 0.20f,
            hairlineAlpha = 0.30f,
            shadow = 4.dp,
            shadowAlpha = 0.28f,
            pressedDim = 0.16f,
            pressedScale = 0.97f,
            containerAlpha = 1f,
            holdMarker = HoldMarkerStyle.DOT,
            springMotion = true,
            springDamping = 0.65f,
            cardsUseStyleCorner = true,
            pillButtons = true,
            light = ControlLook(
                corner = 28.dp,
                topLight = 0.04f,
                rimAlpha = 0.20f,
                hairlineAlpha = 0.30f,
                shadow = 4.dp,
                shadowAlpha = 0.22f,
                pressedDim = 0.12f,
                pressedScale = 0.97f,
                containerAlpha = 1f,
                holdMarker = HoldMarkerStyle.DOT,
                springMotion = true,
                springDamping = 0.65f,
                cardsUseStyleCorner = true,
                pillButtons = true,
            ),
        ),
    )

    /**
     * Meridian (2026-10-27, r107; picked by Robert from the style
     * options): the Meridian chrome's own button style, flat and
     * dark-navy in spirit. The corner is 16dp EVERYWHERE — the
     * unifying value: the Meridian turn/ETA cards already use a
     * fixed 16dp corner in GuidanceSurface, and the rail/pill and
     * cards/sheets take the style's corner through
     * cardShapeForLook (cardsUseStyleCorner), so the whole app
     * agrees with no routing change to GuidanceSurface. Flat
     * solid fills in the scheme's surface tones: no bevel, no
     * weave, no grain, no rivets, no hairline, no shadow
     * (elevation 0). Pressed dims the fill 0.14 and scales 0.98
     * on a critically damped spring (parked only, instant while
     * moving, via the shared stateChangeMotionSpec). Latched is
     * NOT a primary tint: it stays the white selection tile with
     * dark content that already lives in navColors, outside
     * ControlLook. Distinct from Frost (drops bevel/shadow/20dp),
     * Pitlane (drops weave/bolts/chamfer), and Verve (drops
     * springiness + tint). No light variant: the style's identity
     * is corner + flatness, colors come from the scheme, and the
     * Meridian scheme forces dark; on a light scheme the style
     * keeps its flat 16dp read. Watch-out, stated honestly: flat
     * + shadowless can look undifferentiated on dense screens;
     * the design relies on contrast + the white latched tile.
     */
    val Meridian = GsTheme(
        id = "meridian",
        name = "Meridian",
        control = ControlLook(
            corner = 16.dp,
            topLight = 0f,
            hairlineAlpha = 0f,
            bevelTop = 0f,
            bevelBottom = 0f,
            shadow = 0.dp,
            shadowAlpha = 0f,
            pressedDim = 0.14f,
            pressedScale = 0.98f,
            containerAlpha = 1f,
            holdMarker = HoldMarkerStyle.DOT,
            springMotion = true,
            springDamping = 1.0f,
            cardsUseStyleCorner = true,
            latchedPrimaryTint = false,
        ),
    )

    /**
     * Heirloom (2026-10-28, r108; picked by Robert 2026-10-27 ~17:57
     * as "Leather stitch", renamed Heirloom in the 2026-10-27 ~19:22
     * name review — the style ships named Heirloom, and no
     * "Leather stitch" label ever reached the code): the Heritage
     * scheme's own button style. A soft, leather-trim look: a
     * 22dp corner (inside the picked 20–24dp range), a soft
     * Tactile-class bevel (0.20 top / 0.25 bottom — Tactile itself
     * is 0.16/0.22 dark, 0.24/0.30 light, so this sits in its
     * class, not Edge's hard 0.35/0.45 or Pitlane's 0.50/0.60),
     * a soft-medium 4dp shadow, and the ONE new draw routine: a
     * dashed stitch hairline inset 2dp from the edge in warm
     * cream (see ControlLook.stitchAlpha / stitchInset and the
     * stitch block in gsSurface). No weave, no rivets, no
     * grain, no LED, no bat toggle — the stitch is the whole
     * material story. The face is flat (topLight 0), so the hold
     * marker is the DOT. The light variant steps the bevel up
     * exactly as Tactile's does (0.24/0.30) and softens the
     * shadow alpha, so the edge reads on cream without shouting.
     * Cards keep their fallback shapes (cardsUseStyleCorner is
     * false — the pick covers buttons, not cards).
     */
    val Heirloom = GsTheme(
        id = "heirloom",
        name = "Heirloom",
        control = ControlLook(
            corner = 22.dp,
            topLight = 0f,
            bevelTop = 0.20f,
            bevelBottom = 0.25f,
            shadow = 4.dp,
            shadowAlpha = 0.28f,
            stitchAlpha = 0.9f,
            stitchInset = 2.dp,
            pressedDim = 0.22f,
            pressedScale = 0.95f,
            containerAlpha = 1f,
            holdMarker = HoldMarkerStyle.DOT,
            light = ControlLook(
                corner = 22.dp,
                topLight = 0f,
                bevelTop = 0.24f,
                bevelBottom = 0.30f,
                shadow = 4.dp,
                shadowAlpha = 0.22f,
                stitchAlpha = 0.9f,
                stitchInset = 2.dp,
                pressedDim = 0.22f,
                pressedScale = 0.95f,
                containerAlpha = 1f,
                holdMarker = HoldMarkerStyle.DOT,
            ),
        ),
    )

    /**
     * Blueprint (2026-10-28, r110; picked by Robert 2026-10-27 ~18:28
     * as "Outline", renamed Blueprint in the 2026-10-27 ~19:22 name
     * review — the style ships named Blueprint, and no "Outline"
     * style label ever reached the code: GsTone.Outline is the
     * unrelated outlined-button tone and stays): the Neutral —
     * now Canvas — scheme's own button style. The outline IS the
     * style: a crisp 1px ring in onSurfaceVariant around every
     * control (see ControlLook.ringAlpha and the ring block in
     * gsSurface), on a flat 16dp fill — no bevel, no gradient, no
     * weave, no rivets, no hairline (the ring replaces it), and
     * only a minimal 2dp shadow. Pressed tints the fill slightly
     * (the kit's slight pressed dim) and the ring stays, dimmed
     * with the face like the stitch, never dropped. Latched: the
     * ring takes the scheme primary plus the faint primary tint
     * (latchedPrimaryTint, shared with Verve). Cards and sheets
     * keep their fallback corners (cardsUseStyleCorner is false —
     * the pick gives cards the ring, not the corner) and gain the
     * same 1px ring instead of fill contrast (see
     * blueprintCardRing); nav bar and rail buttons read as
     * outlined chips (16dp corner + ring through gsSurface), and
     * pills get the ring too. Keyboard keys keep the ring: at 1px
     * it is thinner than the 1dp dashed stitch that already draws
     * on keys under Heirloom, so no simpler key treatment was
     * needed. The face is flat, so the hold marker is the DOT.
     * Ring contrast, checked against the Canvas tokens at build
     * time (WCAG ratios, ring at full alpha): light mode 4.04:1
     * against the Neutral button fill, 5.07:1 / 4.54:1 against
     * card fills, 5.07:1 against the page — strong everywhere
     * except on Primary-tone fills (1.26:1, where the blue fill
     * itself carries the edge); dark OLED 11.75:1 / 12.47:1 and
     * dark Regular 4.13:1 / 5.13:1 on the same surfaces. The
     * Primary-tone weakness is the pick's own color choice,
     * reported honestly, not adjusted away.
     */
    val Blueprint = GsTheme(
        id = "blueprint",
        name = "Blueprint",
        control = ControlLook(
            corner = 16.dp,
            topLight = 0f,
            hairlineAlpha = 0f,
            bevelTop = 0f,
            bevelBottom = 0f,
            shadow = 2.dp,
            shadowAlpha = 0.20f,
            ringAlpha = 1f,
            pressedDim = 0.14f,
            pressedScale = 0.98f,
            containerAlpha = 1f,
            holdMarker = HoldMarkerStyle.DOT,
            latchedPrimaryTint = true,
            light = ControlLook(
                corner = 16.dp,
                topLight = 0f,
                hairlineAlpha = 0f,
                bevelTop = 0f,
                bevelBottom = 0f,
                shadow = 2.dp,
                shadowAlpha = 0.16f,
                ringAlpha = 1f,
                pressedDim = 0.14f,
                pressedScale = 0.98f,
                containerAlpha = 1f,
                holdMarker = HoldMarkerStyle.DOT,
                latchedPrimaryTint = true,
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
    // r23: Pitlane appended last, so existing users' stored styles are
    // untouched and nobody moves to it automatically.
    // r48: Verve appended last, same rule — nobody moves to it
    // automatically, and no existing stored style changes meaning.
    // r79: Horizon appended last, same rule — nobody moves to it
    // automatically, and no existing stored style changes meaning.
    // r107: Meridian appended last, same rule — nobody moves to it
    // automatically, and no existing stored style changes meaning.
    // r108: Heirloom appended last, same rule — nobody moves to it
    // automatically, and no existing stored style changes meaning.
    // r110: Blueprint appended last, same rule — nobody moves to it
    // automatically, and no existing stored style changes meaning.
    val builtIn = listOf(Frost, Flat, Tactile, Edge, Pitlane, Verve, Horizon, Meridian, Heirloom, Blueprint)

    fun byId(id: String?): GsTheme = builtIn.firstOrNull { it.id == id } ?: default
}

val LocalGsTheme = staticCompositionLocalOf { GsThemes.default }
