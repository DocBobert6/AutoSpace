package app.autospace.ui

import android.content.Context
import app.autospace.AppSettings
import app.autospace.car.AppTheme
import app.autospace.car.DarkModeType
import app.autospace.car.PreviewThemeOverride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Car preview settings (Robert, 2026-10-25): the preview already
 * existed (CarPreviewScreen, fixed 800x480 @ 160dpi); these settings
 * let it render at other sizes, densities and themes. They affect
 * ONLY the preview — the real car UI reads CarSettings, never this
 * object — and the settings page says so in one line. Defaults
 * reproduce the old fixed preview exactly (800x480 @ 160dpi, car
 * theme, no touch markers), so an install that never opens the page
 * sees no change at all.
 */
object PreviewSettings {

    data class Size(val width: Int, val height: Int) {
        override fun toString(): String = "${width} × $height"
    }

    val SIZES = listOf(Size(800, 480), Size(1024, 600), Size(1280, 720), Size(1920, 1080))
    val DPIS = listOf(120, 160, 240)

    private const val KEY_SIZE = "preview_size"
    private const val KEY_DENSITY = "preview_density"
    private const val KEY_STYLE = "preview_style"
    private const val KEY_COLOURS = "preview_colours"
    private const val KEY_DARK_TYPE = "preview_dark_type"
    private const val KEY_TOUCH = "preview_touch_indicators"

    private val sizeFlow = MutableStateFlow(Size(800, 480))
    private val densityFlow = MutableStateFlow(160)
    /** GsTheme id, or null for "use the car's own style". */
    private val styleFlow = MutableStateFlow<String?>(null)
    /** AppTheme, or null for "use the car's own colours". */
    private val coloursFlow = MutableStateFlow<AppTheme?>(null)
    /** DarkModeType, or null for "use the car's own dark type". */
    private val darkTypeFlow = MutableStateFlow<DarkModeType?>(null)
    private val touchFlow = MutableStateFlow(false)

    val size: StateFlow<Size> = sizeFlow.asStateFlow()
    val densityDpi: StateFlow<Int> = densityFlow.asStateFlow()
    val styleId: StateFlow<String?> = styleFlow.asStateFlow()
    val colours: StateFlow<AppTheme?> = coloursFlow.asStateFlow()
    val darkType: StateFlow<DarkModeType?> = darkTypeFlow.asStateFlow()
    val touchIndicators: StateFlow<Boolean> = touchFlow.asStateFlow()

    @Volatile private var loaded = false

    /** Loads persisted values once; later calls are free. Safe to call from composition. */
    fun ensure(context: Context) {
        if (loaded) return
        loaded = true
        val app = context.applicationContext
        val storedSize = AppSettings.getString(app, KEY_SIZE, "800x480")
        val (w, h) = storedSize.split("x").mapNotNull { it.toIntOrNull() }
        if (w != null && h != null) {
            SIZES.firstOrNull { it.width == w && it.height == h }?.let { sizeFlow.value = it }
        }
        AppSettings.getString(app, KEY_DENSITY, "160").toIntOrNull()
            ?.let { d -> if (d in DPIS) densityFlow.value = d }
        // r20: a preview override stored on "keycap" migrates to "edge", same as the
        // car setting — otherwise the preview would silently fall back to Frost.
        AppSettings.getString(app, KEY_STYLE, "").ifEmpty { null }?.let {
            if (it == "keycap") {
                styleFlow.value = "edge"
                AppSettings.putString(app, KEY_STYLE, "edge")
            } else {
                styleFlow.value = it
            }
        }
        AppSettings.getString(app, KEY_COLOURS, "").ifEmpty { null }?.let {
            runCatching { AppTheme.valueOf(it) }.getOrNull()?.let { t -> coloursFlow.value = t }
        }
        AppSettings.getString(app, KEY_DARK_TYPE, "").ifEmpty { null }?.let {
            runCatching { DarkModeType.valueOf(it) }.getOrNull()?.let { t -> darkTypeFlow.value = t }
        }
        touchFlow.value = AppSettings.getString(app, KEY_TOUCH, "false") == "true"
    }

    fun setSize(context: Context, size: Size) {
        sizeFlow.value = size
        AppSettings.putString(context.applicationContext, KEY_SIZE, "${size.width}x${size.height}")
    }

    fun setDensity(context: Context, dpi: Int) {
        if (dpi !in DPIS) return
        densityFlow.value = dpi
        AppSettings.putString(context.applicationContext, KEY_DENSITY, dpi.toString())
    }

    fun setStyle(context: Context, id: String?) {
        styleFlow.value = id
        AppSettings.putString(context.applicationContext, KEY_STYLE, id ?: "")
    }

    fun setColours(context: Context, theme: AppTheme?) {
        coloursFlow.value = theme
        AppSettings.putString(context.applicationContext, KEY_COLOURS, theme?.name ?: "")
    }

    fun setDarkType(context: Context, type: DarkModeType?) {
        darkTypeFlow.value = type
        AppSettings.putString(context.applicationContext, KEY_DARK_TYPE, type?.name ?: "")
    }

    fun setTouchIndicators(context: Context, on: Boolean) {
        touchFlow.value = on
        AppSettings.putString(context.applicationContext, KEY_TOUCH, on.toString())
    }

    /**
     * The CarUi override for the preview, or null when every picker
     * sits on "use the car's setting" — in which case the preview
     * renders exactly as the real car UI would, as before.
     */
    fun themeOverride(): PreviewThemeOverride? {
        val style = styleFlow.value
        val colours = coloursFlow.value
        val dark = darkTypeFlow.value
        if (style == null && colours == null && dark == null) return null
        return PreviewThemeOverride(appTheme = colours, uiThemeId = style, darkType = dark)
    }
}
