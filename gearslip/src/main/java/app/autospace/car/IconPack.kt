package app.autospace.car

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * r81 icon packs (approved 2026-10-26, "I approve icon pack support").
 *
 * An icon pack is an installed app that carries an `appfilter.xml` resource
 * mapping app components to drawable names, in the long-standing launcher
 * convention:
 *
 *     <item component="ComponentInfo{com.spotify.music/com.spotify.music.MainActivity}" drawable="spotify" />
 *
 * The mapping keys are component flattenToStrings ("package/class"), exactly
 * what [Entry.componentId] holds for templated and media apps. Packs map
 * LAUNCHER activities, not car service components, so an exact component hit
 * is the exception, not the rule: the package fallback below is what makes
 * packs work for car apps at all — it takes the first entry in file order
 * for the app's package.
 *
 * v1 scope, per the approval: NO masking, no uniform shapes, no icon-shape
 * normalization — a pack drawable is drawn as-is. Built-in glyph tiles keep
 * their glyphs (packs only ever replace an installed app's bitmap icon).
 * Notifications keep the app's real icon (their path never touches this).
 *
 * Fallback chain, everywhere a pack icon can appear:
 * per-app override -> pack auto-map (exact component, then package) ->
 * the app's own system icon. A missing or uninstalled pack, an unmapped
 * app, or a drawable that fails to load all fall through to the system
 * icon silently — never a blank, never an error surface.
 *
 * The Android functions here do package-manager and disk work and must be
 * called off the main thread; [LauncherCache.load] already runs on a
 * background thread, and the settings/picker call sites use IO dispatchers.
 * The pure functions ([parseAppFilter], [packDrawableFor],
 * [resolvePackDrawable], [encodeOverrides], [decodeOverrides]) carry no
 * Android dependency and are pinned by IconPackTest.
 */

/** An installed icon pack: just enough to list it; the mapping loads on demand. */
data class IconPackInfo(val packageName: String, val label: String)

/**
 * The component -> drawable mapping in a pack's appfilter.xml, in file
 * order (a LinkedHashMap, so the package fallback is deterministic). Only
 * well-formed `<item>` elements with both a ComponentInfo{} component and
 * a drawable land here; iconback/iconmask/iconupon entries and malformed
 * items are skipped — v1 draws pack drawables as-is and ignores masks.
 */
fun parseAppFilter(xml: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val itemTag = Regex("""<item\b[^>]*>""")
    val componentAttr = Regex("""component\s*=\s*["']([^"']*)["']""")
    val drawableAttr = Regex("""drawable\s*=\s*["']([^"']*)["']""")
    for (tag in itemTag.findAll(xml)) {
        val component = componentAttr.find(tag.value)?.groupValues?.get(1) ?: continue
        val drawable = drawableAttr.find(tag.value)?.groupValues?.get(1) ?: continue
        if (drawable.isEmpty()) continue
        val inner = component.removePrefix("ComponentInfo{").removeSuffix("}")
        if (inner == component || inner.isEmpty()) continue
        out[inner] = drawable
    }
    return out
}

/**
 * The pack drawable for an app: the exact component first, then the first
 * entry in file order for the app's package. Null when the pack maps
 * nothing for the app.
 */
fun packDrawableFor(mapping: Map<String, String>, componentId: String?, packageName: String?): String? {
    if (componentId != null) mapping[componentId]?.let { return it }
    if (packageName != null) {
        for ((component, drawable) in mapping) {
            if (component.substringBefore("/") == packageName) return drawable
        }
    }
    return null
}

/**
 * The drawable an app should show under a pack: a per-app override wins
 * over everything, then the pack's own auto-map. Null means "system icon".
 */
fun resolvePackDrawable(
    overrides: Map<String, String>,
    mapping: Map<String, String>,
    componentId: String?,
    packageName: String?,
): String? {
    if (componentId != null) overrides[componentId]?.let { return it }
    return packDrawableFor(mapping, componentId, packageName)
}

/** Overrides on disk: "componentId=drawable" entries joined by commas. */
fun encodeOverrides(overrides: Map<String, String>): String =
    overrides.entries.joinToString(",") { (componentId, drawable) -> "$componentId=$drawable" }

/** The inverse of [encodeOverrides]; malformed segments are skipped. */
fun decodeOverrides(stored: String): Map<String, String> {
    if (stored.isBlank()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (part in stored.split(",")) {
        val eq = part.indexOf("=")
        if (eq <= 0) continue
        val key = part.substring(0, eq)
        val value = part.substring(eq + 1)
        if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
    }
    return out
}

/**
 * r101: labels that are never real icon packs. Some apps on the phone
 * carry an appfilter resource but are system theme entries, not packs a
 * driver can use — on the reporter's phone these showed up as "Samsung"
 * and "One UI" picker entries that did nothing. Those labels come from
 * apps on the phone, not from anything shipped in code, so the fix
 * excludes them by label here, at the single choke point every picker
 * list flows through. Only exact labels match (whitespace- and
 * case-insensitive, so "OneUI" counts): a real pack that merely mentions
 * Samsung in a longer name is untouched.
 */
fun isExcludedPackLabel(label: String): Boolean {
    val normalized = label.trim().lowercase().replace(Regex("\\s+"), "")
    return normalized == "samsung" || normalized == "oneui"
}

/**
 * r101: whether a pack's mapping has at least one drawable that resolves
 * in the pack's resources. A pack whose appfilter exists but maps nothing,
 * or whose drawables all fail to resolve, is a dead entry: it must not
 * appear in the picker at all. This generalizes the Samsung / One UI
 * exclusion above — dead entries vanish whatever their label. The
 * [drawableExists] check is a resource-id lookup, not a bitmap decode,
 * so the scan stays cheap; [packIconBitmap] still does the real decode
 * (and its own fallback) when an icon is actually drawn.
 */
fun packHasLoadableDrawable(
    mapping: Map<String, String>,
    drawableExists: (String) -> Boolean,
): Boolean = mapping.values.any(drawableExists)

/**
 * Installed packs: every visible app carrying an appfilter.xml resource
 * with at least one loadable drawable, by label, minus the dead
 * system-theme entries (see [isExcludedPackLabel]). Call off the main
 * thread. A pack with neither a launcher activity nor a theme intent
 * declaration stays invisible under package visibility rules — the same
 * limit any launcher without QUERY_ALL_PACKAGES has; the manifest
 * declares the standard theme intents so packs that declare them are
 * visible.
 *
 * A stored pack that no longer appears here (uninstalled, excluded, or
 * dead) reads as System icons in the picker, which is what is actually
 * showing — the launcher scan falls back the same way — so the
 * highlighted chip never lies, and the stored value needs no rewrite.
 */
fun installedIconPacks(context: Context): List<IconPackInfo> {
    val pm = context.packageManager
    return runCatching {
        pm.getInstalledApplications(0)
            .mapNotNull { app ->
                val label = pm.getApplicationLabel(app).toString()
                if (isExcludedPackLabel(label)) return@mapNotNull null
                val res = runCatching { pm.getResourcesForApplication(app.packageName) }.getOrNull()
                    ?: return@mapNotNull null
                val filterId = res.getIdentifier("appfilter", "xml", app.packageName)
                if (filterId == 0) return@mapNotNull null
                val xml = runCatching {
                    res.openRawResource(filterId).use { it.readBytes().decodeToString() }
                }.getOrNull() ?: return@mapNotNull null
                val mapping = parseAppFilter(xml)
                val loadable = packHasLoadableDrawable(mapping) { drawable ->
                    runCatching { res.getIdentifier(drawable, "drawable", app.packageName) != 0 }
                        .getOrDefault(false)
                }
                if (!loadable) return@mapNotNull null
                IconPackInfo(app.packageName, label)
            }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}

/**
 * A pack's full component -> drawable mapping. Null when the pack is
 * missing, uninstalled, or its appfilter.xml cannot be read — the caller
 * falls back to system icons.
 */
fun loadIconPackMapping(context: Context, packPkg: String): Map<String, String>? =
    runCatching {
        val res = context.packageManager.getResourcesForApplication(packPkg)
        val id = res.getIdentifier("appfilter", "xml", packPkg)
        if (id == 0) return null
        val xml = res.openRawResource(id).use { it.readBytes().decodeToString() }
        parseAppFilter(xml)
    }.getOrNull()

/** One pack drawable as a bitmap, or null when it cannot be loaded. */
fun packIconBitmap(context: Context, packPkg: String, drawable: String, sizePx: Int = 128): ImageBitmap? =
    runCatching {
        val res = context.packageManager.getResourcesForApplication(packPkg)
        val id = res.getIdentifier(drawable, "drawable", packPkg)
        if (id == 0) return null
        res.getDrawable(id, null)?.toBitmap(sizePx, sizePx)?.asImageBitmap()
    }.getOrNull()

/**
 * Picker-session cache for pack drawables, so scrolling the picker grid
 * does not re-decode bitmaps. Keyed by pack + drawable; cleared wholesale
 * past 512 entries rather than tracking recency — the picker only ever
 * fills it with cells the driver actually scrolled through.
 */
internal object IconPackBitmaps {
    private val cache = LinkedHashMap<String, ImageBitmap?>()

    fun bitmapFor(context: Context, packPkg: String, drawable: String, sizePx: Int = 128): ImageBitmap? {
        val key = "$packPkg/$drawable"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val bitmap = packIconBitmap(context, packPkg, drawable, sizePx)
        synchronized(cache) {
            if (cache.size > 512) cache.clear()
            cache[key] = bitmap
        }
        return bitmap
    }
}
