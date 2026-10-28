package app.autospace.car

import android.content.Context
import app.autospace.AppSettings

/**
 * r149: games presenter options, ported from Mariner's
 * ShaderPresets (Phase J/L, beta.4-6, beta.22) for the car
 * consoles. One look per console, behind a per-console
 * shader toggle that defaults OFF, so a user who changes
 * nothing keeps exactly the plain picture the presenter drew
 * before. The UI shows the preset NAME ONLY - never the
 * numbers below, and never what a preset is made of. The
 * numbers are internal starting values for on-device tuning.
 *
 * Mariner's consoles map one-to-one onto this app's except
 * SG-1000, which Mariner has no preset for: it falls back to
 * the Master System preset (Cobalt, the same GX hardware
 * family, legacy pass) rather than Mariner's generic
 * Game Boy fallback, whose LCD pass would be wrong for a
 * home console. An unknown console key falls back the same
 * way, for the same reason.
 *
 * Two deliberate deviations from Mariner's beta.6 defaults,
 * both required by this round's rule that defaults preserve
 * today's presentation exactly: curvature and rounded corners
 * default OFF here (Mariner's presets default them on), so
 * the picture stays flat and square until the user opts in.
 * Their STRENGTHS still default to the preset values, so
 * switching either on gives the preset's gentle strength.
 * The color profile rides with the shader toggle like
 * Mariner's, but the plain program does not grade, so it
 * changes nothing while shaders are off.
 *
 * Per-game overrides (Mariner Phase 3) are NOT ported: the
 * console-page set below is the presenter options set for
 * this round.
 */
data class PresenterColorProfile(
    val saturation: Float = 1f,
    val warm: Float = 0f,
    val gamma: Float = 1f,
    val bright: Float = 1f,
    val black: Float = 0f,
    val segaFix: Boolean = false
)

data class PresenterPreset(
    val name: String,
    val scan: Float,
    val grid: Float,
    val blur: Float,
    val lcd: Boolean = false,
    val brightScan: Float = 16f,
    val brightLcd: Float = 4f,
    val crt: Boolean = false,
    val curveOn: Boolean = false,
    val curveAmount: Int = 30,
    val roundOn: Boolean = false,
    val roundAmount: Int = 20,
    val color: PresenterColorProfile = PresenterColorProfile()
)

object GamePresenterOptions {
    private val presets = mapOf(
        "nes" to PresenterPreset("Ember", 0.42f, 0.00f, 0.15f, crt = true,
            curveOn = true, roundOn = true,
            color = PresenterColorProfile(saturation = 1.05f, warm = 25f)),
        "snes" to PresenterPreset("Violet", 0.30f, 0.00f, 0.10f, crt = true,
            curveOn = true, roundOn = true,
            color = PresenterColorProfile(warm = 10f)),
        "genesis" to PresenterPreset("Blast", 0.35f, 0.00f, 0.20f, crt = true,
            curveOn = true, roundOn = true,
            color = PresenterColorProfile(segaFix = true)),
        "sms" to PresenterPreset("Cobalt", 0.42f, 0.00f, 0.15f,
            roundOn = true),
        "ps1" to PresenterPreset("Fog", 0.35f, 0.00f, 0.15f, crt = true,
            curveOn = true, roundOn = true,
            color = PresenterColorProfile(bright = 1.03f)),
        "segacd" to PresenterPreset("Orbit", 0.35f, 0.00f, 0.15f, crt = true,
            curveOn = true, roundOn = true,
            color = PresenterColorProfile(warm = 10f)),
        "32x" to PresenterPreset("Vertex", 0.35f, 0.00f, 0.15f, crt = true,
            curveOn = true, roundOn = true,
            color = PresenterColorProfile(saturation = 1.05f))
    )

    /** SG-1000 has no Mariner preset: the Master System preset. */
    fun presetFor(console: String): PresenterPreset =
        presets[console] ?: presets.getValue("sms")

    /** Shader mode the native presenter takes: 0 plain, 1 legacy,
     * 2 LCD, 3 CRT chain. Off always means plain. */
    fun shaderModeFor(preset: PresenterPreset, shadersOn: Boolean): Int {
        if (!shadersOn) return 0
        return when {
            preset.lcd -> 2
            preset.crt -> 3
            else -> 1
        }
    }

    /** Curved-corner pull: CRT consoles take the full strength,
     * LCD consoles half, anything else none. Slider percent
     * 0..100; the toggle off forces zero. */
    fun curvePull(
        preset: PresenterPreset,
        enabled: Boolean,
        amountPct: Int
    ): Float {
        if (!enabled) return 0f
        val base = when {
            preset.crt -> 0.020f
            preset.lcd -> 0.010f
            else -> 0f
        }
        return base * amountPct.coerceIn(0, 100) / 100f
    }

    private fun boolLookup(
        lookup: (String) -> String,
        perConsoleKey: String,
        sharedKey: String?,
        default: Boolean
    ): Boolean {
        val per = lookup(perConsoleKey)
        if (per != "") return per == "true"
        if (sharedKey != null) {
            val shared = lookup(sharedKey)
            if (shared != "") return shared == "true"
        }
        return default
    }

    private fun intLookup(
        lookup: (String) -> String,
        perConsoleKey: String,
        sharedKey: String?,
        default: Int
    ): Int {
        val per = lookup(perConsoleKey).toIntOrNull()
        if (per != null) return per.coerceIn(0, 100)
        if (sharedKey != null) {
            val shared = lookup(sharedKey).toIntOrNull()
            if (shared != null) return shared.coerceIn(0, 100)
        }
        return default.coerceIn(0, 100)
    }

    /** Lookup-first resolvers, so tests pass a map instead of a
     * Context. The Context wrappers below read AppSettings. */
    fun shadersOn(lookup: (String) -> String, console: String): Boolean =
        boolLookup(lookup, "gameShaders:$console", "gameShaders", false)

    fun curveOn(lookup: (String) -> String, console: String): Boolean =
        // Deviation from Mariner's preset defaults, documented
        // above: off until the user opts in.
        boolLookup(lookup, "gameCurvature:$console", "gameCurvature", false)

    fun curveAmount(lookup: (String) -> String, console: String): Int =
        intLookup(
            lookup, "gameCurvatureAmount:$console", "gameCurvatureAmount",
            presetFor(console).curveAmount
        )

    fun roundOn(lookup: (String) -> String, console: String): Boolean =
        // Deviation from Mariner's preset defaults, documented
        // above: off until the user opts in.
        boolLookup(lookup, "gameRoundCorners:$console", null, false)

    fun roundAmount(lookup: (String) -> String, console: String): Int =
        intLookup(
            lookup, "gameRoundAmount:$console", null,
            presetFor(console).roundAmount
        )

    fun fpsOn(lookup: (String) -> String, console: String): Boolean =
        boolLookup(lookup, "gameFpsCounter:$console", null, false)

    fun fastForwardEnabled(
        lookup: (String) -> String,
        console: String
    ): Boolean =
        boolLookup(lookup, "gameFastForward:$console", null, false)

    fun integerScale(lookup: (String) -> String, console: String): Boolean =
        boolLookup(lookup, "gameIntegerScale:$console", null, false)

    private fun appLookup(context: Context): (String) -> String =
        { key -> AppSettings.getString(context, key, "") }

    fun shadersOn(context: Context, console: String): Boolean =
        shadersOn(appLookup(context), console)

    fun curveOn(context: Context, console: String): Boolean =
        curveOn(appLookup(context), console)

    fun curveAmount(context: Context, console: String): Int =
        curveAmount(appLookup(context), console)

    fun roundOn(context: Context, console: String): Boolean =
        roundOn(appLookup(context), console)

    fun roundAmount(context: Context, console: String): Int =
        roundAmount(appLookup(context), console)

    fun fpsOn(context: Context, console: String): Boolean =
        fpsOn(appLookup(context), console)

    fun fastForwardEnabled(context: Context, console: String): Boolean =
        fastForwardEnabled(appLookup(context), console)

    fun integerScale(context: Context, console: String): Boolean =
        integerScale(appLookup(context), console)

    fun setShaders(context: Context, console: String, on: Boolean) {
        AppSettings.putString(context, "gameShaders:$console", on.toString())
    }

    fun setCurveOn(context: Context, console: String, on: Boolean) {
        AppSettings.putString(context, "gameCurvature:$console", on.toString())
    }

    fun setCurveAmount(context: Context, console: String, amount: Int) {
        AppSettings.putString(
            context, "gameCurvatureAmount:$console",
            amount.coerceIn(0, 100).toString()
        )
    }

    fun setRoundOn(context: Context, console: String, on: Boolean) {
        AppSettings.putString(context, "gameRoundCorners:$console", on.toString())
    }

    fun setRoundAmount(context: Context, console: String, amount: Int) {
        AppSettings.putString(
            context, "gameRoundAmount:$console",
            amount.coerceIn(0, 100).toString()
        )
    }

    fun setFps(context: Context, console: String, on: Boolean) {
        AppSettings.putString(context, "gameFpsCounter:$console", on.toString())
    }

    fun setFastForward(context: Context, console: String, on: Boolean) {
        AppSettings.putString(context, "gameFastForward:$console", on.toString())
    }

    fun setIntegerScale(context: Context, console: String, on: Boolean) {
        AppSettings.putString(context, "gameIntegerScale:$console", on.toString())
    }

    /** Writes this console's display defaults. AppSettings has
     * no remove, so reset writes defaults rather than deleting
     * keys - the r147 pattern; a stored default reads back
     * identically to no stored value. */
    fun resetConsole(context: Context, console: String) {
        val preset = presetFor(console)
        setShaders(context, console, false)
        setCurveOn(context, console, false)
        setCurveAmount(context, console, preset.curveAmount)
        setRoundOn(context, console, false)
        setRoundAmount(context, console, preset.roundAmount)
        setFps(context, console, false)
        setFastForward(context, console, false)
        setIntegerScale(context, console, false)
    }

    /** Applies the resolved session-start presentation to the
     * engine, in Mariner's order: shader pass, color profile,
     * curve, round, integer scale. Called once at launch, like
     * core options and the r147 mapping; a change applies the
     * next time one of this console's games launches. */
    fun applyToEngine(engine: GameEngine, context: Context, console: String) {
        val lookup = appLookup(context)
        val preset = presetFor(console)
        val on = shadersOn(lookup, console)
        if (preset.lcd) {
            engine.setLcdShader(on, preset.brightScan, preset.brightLcd)
        } else if (preset.crt) {
            engine.setCrtShader(on)
        } else {
            engine.setShader(on, preset.scan, preset.grid, preset.blur)
        }
        engine.setColorProfile(
            preset.color.saturation,
            preset.color.warm,
            preset.color.gamma,
            preset.color.bright,
            preset.color.black,
            preset.color.segaFix
        )
        engine.setCurve(
            curvePull(preset, curveOn(lookup, console), curveAmount(lookup, console))
        )
        engine.setRound(
            if (roundOn(lookup, console)) roundAmount(lookup, console) / 100f else 0f
        )
        engine.setIntegerScale(integerScale(lookup, console))
    }
}
