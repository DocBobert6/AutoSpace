package app.autospace.car

import app.autospace.car.theme.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Close
import app.autospace.host.KnownApps
import app.autospace.host.CarAppCatalog
import app.autospace.host.TemplateApp
import app.autospace.media.MediaApp
import app.autospace.media.MediaCatalog
import app.autospace.notify.MessagingApp
import app.autospace.notify.MessagingCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One tile on the launcher: either a phone app's own icon or a built-in glyph. */
private class Tile(
    val label: String,
    val icon: ImageBitmap? = null,
    val glyph: ImageVector? = null,
    /** Known not to work; drawn with a red X instead of being left off the launcher. */
    val broken: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** A launcher app's data, kept apart from [Tile] so it can be cached without capturing click state. */
internal class Entry(
    val label: String,
    val icon: ImageBitmap?,
    val template: TemplateApp? = null,
    val media: MediaApp? = null,
    val builtIn: BuiltInApp? = null,
    val messaging: MessagingApp? = null,
) {
    /** What a pin or a nav-bar shortcut remembers this entry as; null for nothing pinnable. */
    val componentId: String?
        get() = builtIn?.let { BUILT_IN_PREFIX + it.id }
            ?: messaging?.let { MESSAGING_PREFIX + it.packageName }
            ?: (template?.component ?: media?.component)?.flattenToString()

    val packageName: String?
        get() = template?.component?.packageName ?: media?.component?.packageName ?: messaging?.packageName
}

/** A messaging app has no component of its own to open, so its pin carries the package instead. */
internal const val MESSAGING_PREFIX = "messages:"

/**
 * The installed car and media apps with their icons. Scanning the package manager and decoding
 * icons is slow, so [Prefetch] does it ahead of time and again at the start of each car session,
 * which is when new installs are picked up.
 */
internal object LauncherCache {
    /** Observed, not read once: the nav bar is drawn before the car session's rescan finishes,
     * and an app installed since the last scan has to show up there when it does. */
    private val flow = MutableStateFlow<List<Entry>?>(null)
    val state: StateFlow<List<Entry>?> = flow.asStateFlow()

    val entries: List<Entry>? get() = flow.value

    /** Returns the scan if there is one; [force] rescans. Synchronized so two callers share one scan. */
    @Synchronized
    fun load(context: Context, force: Boolean = false): List<Entry> {
        if (!force) entries?.let { return it }
        fun iconOf(pkg: String) = runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap(128, 128).asImageBitmap()
        }.getOrNull()
        // r81 icon packs: the selected pack's mapping, loaded once per
        // scan. Null when no pack is selected or the pack is gone — the
        // fallback below then draws system icons, never blanks.
        val packPkg = CarSettings.iconPack.value
        val packMapping = packPkg?.let { loadIconPackMapping(context, it) }
        val overrides = CarSettings.iconOverrides.value
        // Per-app override first, then the pack's auto-map, then the
        // app's own icon; a pack drawable that fails to load falls
        // through to the system icon the same way.
        fun packedIcon(componentId: String?, pkg: String): ImageBitmap? {
            val drawable = packMapping?.let { resolvePackDrawable(overrides, it, componentId, pkg) }
            if (drawable != null && packPkg != null) {
                packIconBitmap(context, packPkg, drawable)?.let { return it }
            }
            return iconOf(pkg)
        }

        val navApps = CarAppCatalog.installed(context)
        val mediaApps = MediaCatalog.installed(context)
        // Some apps (Spotify among them) ship both a Car App Library template and a legacy
        // MediaBrowserService, which otherwise land as two identically-labeled tiles with no
        // way to tell them apart. They're genuinely different screens - browsing versus Now
        // Playing - so only the label needs disambiguating, not the tile itself.
        val mediaPackages = mediaApps.map { it.component.packageName }.toSet()

        val messagingApps = MessagingCatalog.installed(context)
        // Google Messages ships its own car app, which won't start under Gearslip. Gearslip's
        // own Messages screen does the same job, so a messenger gets only that tile.
        val messagingPackages = messagingApps.map { it.packageName }.toSet()

        val nav = navApps
            .filter { it.component.packageName !in messagingPackages }
            // An app whose template screen is rejected but whose player works through Gearslip
            // (Spotify) shows only its player tile - a "· Browse" tile beside it would just report
            // that the car screen isn't supported.
            .filter { !(KnownApps.screenBroken(it.component.packageName) && KnownApps.playerWorks(it.component.packageName)) }
            .map {
                val label = if (it.component.packageName in mediaPackages) "${it.label} · Browse" else it.label
                Entry(label, packedIcon(it.component.flattenToString(), it.component.packageName), template = it)
            }
        val media = mediaApps.map { Entry(it.label, packedIcon(it.component.flattenToString(), it.component.packageName), media = it) }
        // A messenger that is also a car media app keeps only its player tile.
        val messaging = messagingApps
            .filter { it.packageName !in mediaPackages }
            .map { Entry(it.label, packedIcon(MESSAGING_PREFIX + it.packageName, it.packageName), messaging = it) }
        return (nav + media + messaging).sortedBy { it.label.lowercase() }.also { flow.value = it }
    }
}

/** Every app the launcher knows: Gearslip's own and the installed ones, rescans included. */
@Composable
internal fun rememberAllEntries(): List<Entry> {
    val context = LocalContext.current
    val installed by LauncherCache.state.collectAsState()
    LaunchedEffect(context) {
        if (LauncherCache.entries == null) withContext(Dispatchers.IO) { LauncherCache.load(context) }
    }
    return remember(installed) {
        BuiltInApps.entries + installed.orEmpty()
    }
}

/**
 * The launcher's order, Phase 7 (Robert 2026-10-24 23:58): NO categories.
 * One flat list, alphabetical by label by default. The driver's own moves
 * from [custom] are kept as a stored order; an app installed later (or one
 * not in the stored order) is inserted at its alphabetical position in the
 * current list, so a moved app stays moved and a new app lands alphabetically.
 * After any Move/drag, the full current order is stored, so later loads
 * reproduce it exactly. The pinned row is NOT a category — pinning is a user
 * choice shown as its own row, kept deliberately (see CarLauncher).
 */
internal fun launcherOrder(all: List<Entry>, showVehicleData: Boolean, custom: List<String>): List<Entry> {
    val shown = all.filter { it.builtIn !== BuiltInApps.VehicleData || showVehicleData }
    val alpha = shown.sortedBy { it.label.lowercase() }
    if (custom.isEmpty()) return alpha
    val byId = alpha.associateBy { it.componentId }
    val customIds = custom.toSet()
    val ordered = custom.mapNotNull { byId[it] }.toMutableList()
    // New or unlisted apps slot in alphabetically against the current list.
    for (entry in alpha) {
        if (entry.componentId in customIds) continue
        val at = ordered.indexOfFirst { it.label.lowercase() > entry.label.lowercase() }
        if (at < 0) ordered += entry else ordered.add(at, entry)
    }
    // Entries with no component id cannot be stored in an order; they keep
    // their alphabetical place at the end of the stored list's tail.
    return ordered
}

/** An app by the id a pin or the running list knows it by, built-in or installed. */
internal fun findEntry(id: String): Entry? =
    BuiltInApps.entries.find { it.componentId == id } ?: LauncherCache.entries?.find { it.componentId == id }

/** Rescans the car and media apps now, replacing the cached list; the launcher keeps showing the old one meanwhile. */
fun warmLauncherApps(context: Context) { LauncherCache.load(context, force = true) }

/**
 * Connects or opens [entry] - what tapping its tile does, shared with the nav bar's pinned
 * shortcuts and its open-app icon so there is exactly one place that knows how to launch either
 * kind of app.
 */
internal fun launchEntry(entry: Entry, navigator: CarNavigator, frame: CarEnvironment.Frame) {
    entry.builtIn?.let { it.open(navigator); return }
    entry.template?.let {
        if (it.isNavigation) {
            CarServices.connectNav(it, frame)
            navigator.home()
        } else {
            CarServices.connectBrowse(it, frame)
            navigator.browse(entry.label)
        }
    }
    entry.media?.let { CarServices.openMedia(it); navigator.media() }
    entry.messaging?.let { navigator.messages(it.packageName, it.label) }
}

/** Long-press-drag state for one launcher tile: which tile is held, how far the finger has moved it. */
private class LauncherDragState {
    var draggedId: String? by mutableStateOf(null)
    var offset: Offset by mutableStateOf(Offset.Zero)
    var moved: Boolean by mutableStateOf(false)
    fun reset() {
        draggedId = null
        offset = Offset.Zero
        moved = false
    }

    /** Index in the grid of the visible tile nearest the dragged tile's finger position, or -1. */
    fun dropTarget(gridState: androidx.compose.foundation.lazy.grid.LazyGridState): Int {
        val id = draggedId ?: return -1
        val visible = gridState.layoutInfo.visibleItemsInfo
        val held = visible.firstOrNull { it.key == id } ?: return -1
        val finger = Offset(
            held.offset.x + held.size.width / 2f + offset.x,
            held.offset.y + held.size.height / 2f + offset.y,
        )
        return visible.minByOrNull { item ->
            val c = Offset(item.offset.x + item.size.width / 2f, item.offset.y + item.size.height / 2f)
            (c - finger).getDistance()
        }?.index ?: -1
    }
}

/** The app launcher: every app as one flat alphabetical grid (Phase 7 — no categories). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CarLauncher() {
    val context = LocalContext.current
    val navigator = LocalCarNavigator.current
    val frame by CarEnvironment.frame.collectAsState()

    // Painted from the cache at once; scanned only if [Prefetch] has not got there first.
    val all = rememberAllEntries()
    val running = rememberRunningApps()

    // r116: the Browser tile's Mariner circle, decoded once here; tileOf hands it to the Web
    // tile. Decoded with the framework loader and remembered, so this is one decode, not one
    // per tile. (The Compose resource helper was tried first and does not resolve in this
    // project's Compose version, so the framework loader it is.)
    val browserCircle = remember(context) {
        BuiltInApps.Web.circleIconRes?.let { BitmapFactory.decodeResource(context.resources, it)?.asImageBitmap() }
    }

    fun tileOf(entry: Entry): Tile {
        // Only apps that don't work get a mark. The X rates an app's own car screens; a messenger
        // shown on Gearslip's own Messages screen has none, so it never gets one.
        val pkg = entry.packageName?.takeIf { entry.messaging == null }
        val id = entry.componentId
        // r116: a built-in with a circle bitmap draws the bitmap, not its glyph — TileIcon
        // prefers the bitmap, so the Web tile shows the Mariner circle and the magnifier
        // never draws for it. Every other built-in still has a null icon and keeps its glyph,
        // and installed apps keep their own icons, so icon packs never touch this tile.
        val icon = entry.icon ?: entry.builtIn?.circleIconRes?.let { browserCircle }
        return Tile(
            entry.label, icon, entry.builtIn?.glyph,
            broken = pkg != null && (KnownApps.isBroken(context, pkg) || (entry.template != null && KnownApps.screenBroken(pkg))),
            onLongClick = id?.let { { navigator.showAppMenu(it) } },
        ) { openApp(entry, id?.let { running[it] }, navigator, frame) }
    }

    val appOrder by CarSettings.appOrder.collectAsState()
    // Icon-size slider (Phase 6): 1.0 is the Phase 6 large size, 0 hides the
    // icon; the tile, its label and its tap area stay, so a tile is never lost
    // by shrinking it. Tile-size slider (Phase 7): 1.0 is the Phase 6 large
    // tile size; the grid width and tile height follow it (floors below keep
    // every tile a car target), while the icon keeps its own size.
    val iconFrac by CarSettings.iconSize.collectAsState()
    val tileFrac by CarSettings.tileSize.collectAsState()
    // Phase 9 pills: the grid is no longer a 4-5 column square-tile
    // grid — pills are wide, so the column width comes from the car
    // width alone (2 columns on an 800px screen, 1 on a narrow one),
    // not from the tile-size slider. Tile size scales pill HEIGHT
    // (see AppTile), icon size scales the pill icon.
    // r97 graduation: vehicle data is regular now, so its tile always shows.
    val ordered = launcherOrder(all, true, appOrder)

    // Search, through the car's own keyboard: tapping the search row opens it, typing
    // filters tiles by label, submitting or dismissing closes it again.
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val filtered = if (query.isBlank()) ordered else ordered.filter { it.label.contains(query, ignoreCase = true) }

    // Long-press-drag reorder (Phase 7): a tile held and moved lifts and
    // follows the finger; on drop it takes the nearest tile's place and the
    // full order is persisted, so it stays moved across restarts. A long
    // press released without moving still opens the app's menu (pin, move,
    // close). Dragging is off while searching, so a filtered tile cannot be
    // dropped at a filtered index — long-press there opens the menu instead.
    // The grid does not scroll mid-drag; a drop lands on a visible tile.
    val gridState = rememberLazyGridState()
    val drag = remember { LauncherDragState() }
    val dragThresholdPx = with(LocalDensity.current) { 12.dp.toPx() }
    val canDrag = query.isBlank()
    fun commitDrop() {
        val id = drag.draggedId
        val target = drag.dropTarget(gridState)
        drag.reset()
        if (id == null || target < 0) return
        val ids = ordered.mapNotNull { it.componentId }.toMutableList()
        val from = ids.indexOf(id)
        if (from < 0 || from == target) return
        ids.removeAt(from)
        ids.add(target.coerceIn(0, ids.size), id)
        CarSettings.setAppOrder(ids)
    }

    // Pinned apps (from the nav bar's pin set) get their own row on top, so the
    // driver's favourites are one tap away without scrolling the grid.
    val pinnedIds by CarSettings.pinnedApps.collectAsState()
    val pinned = remember(pinnedIds, all) { pinnedIds.mapNotNull { findEntry(it) } }

    Column(Modifier.fillMaxSize()) {
        // Closed search pill: height 56dp with horizontal margins only. GsButton
        // enforces a 48dp minimum height plus 20dp of vertical content padding,
        // so an extra 12dp of vertical padding on top of a fixed 56dp height
        // squeezed the content slot below what its text needs and clipped it.
        GsButton(
            onClick = { searching = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(56.dp),
            tone = GsTone.Neutral,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            KeyboardFieldText(
                text = query,
                placeholder = "Search apps",
                style = MaterialTheme.typography.titleMedium,
                showCaret = searching,
            )
        }
        if (pinned.isNotEmpty()) {
            Text(
                "Pinned",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(pinned) { entry -> PinnedTile(tileOf(entry), iconFrac, tileFrac) }
            }
        }
        // Phase 9 pills: search at the top, then capsule pill rows
        // (icon + label on one horizontal line), 2 columns on a wide
        // car screen — no square tiles anywhere, except under Pitlane
        // (r24), where gsButtonShape squares the tiles. Phase 7's flat list,
        // no group headers, search and pinned behaviour are unchanged.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 320.dp),
            state = gridState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(filtered, key = { it.componentId ?: it.label }) { entry ->
                val tile = tileOf(entry)
                val id = entry.componentId
                val dragging = drag.draggedId == id && id != null
                AppTile(
                    tile = tile,
                    iconFrac = iconFrac,
                    tileFrac = tileFrac,
                    modifier = Modifier.animateItem(),
                    dragEnabled = canDrag && id != null,
                    dragOffset = if (dragging) drag.offset else Offset.Zero,
                    dragAlpha = if (dragging) 0.85f else 1f,
                    onDragStart = { if (id != null) { drag.draggedId = id; drag.offset = Offset.Zero; drag.moved = false } },
                    onDrag = { amount ->
                        drag.offset += amount
                        if (drag.offset.getDistance() > dragThresholdPx) drag.moved = true
                    },
                    onDragEnd = {
                        if (drag.moved) commitDrop()
                        else {
                            drag.reset()
                            tile.onLongClick?.invoke()
                        }
                    },
                    onDragCancel = { drag.reset() },
                )
            }
        }
        if (searching) {
            CarKeyboard(
                text = query,
                onTextChange = { query = it },
                onSubmit = { searching = false },
                onDismiss = { searching = false },
            )
        }
    }
}

/** A pinned-app pill for the launcher's pinned row. Phase 9: a capsule
 * pill row (icon + label on one line), not a square tile — height
 * scales with the tile-size slider (56dp floor), icon with the
 * icon-size slider, label fully readable on one line. r24: under
 * Pitlane the pill is squared by gsButtonShape (the style's 6dp
 * corner); every other style keeps the capsule. */
@Composable
private fun PinnedTile(tile: Tile, iconFrac: Float = 1f, tileFrac: Float = 1f) {
    GsIconBox(
        onClick = tile.onClick,
        onLongClick = tile.onLongClick,
        modifier = Modifier.height((72f * tileFrac).coerceAtLeast(56f).dp),
        colors = GsColors(
            MaterialTheme.colorScheme.surfaceContainerLow,
            MaterialTheme.colorScheme.onSurface,
            // r29: pinned tiles only — a 1dp scheme-primary ring marking the
            // driver's own favourites. AppTile (the grid) is deliberately
            // untouched: every-tile accents were rejected, and fills are out.
            MaterialTheme.colorScheme.primary,
        ),
        shape = gsButtonShape(androidx.compose.foundation.shape.RoundedCornerShape(50)),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (iconFrac > 0f) {
                Box {
                    TileIcon(tile, 48.dp * iconFrac)
                    if (tile.broken) {
                        BrokenBadge(
                            20.dp,
                            Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 4.dp)
                                .border(2.dp, MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
                        )
                    }
                }
            }
            TileLabel(tile, maxLines = 1)
        }
    }
}

/** A red disc with a white X: marks a tile known not to work with Gearslip yet. */
@Composable
private fun BrokenBadge(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(Color(0xFFD32F2F), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, contentDescription = "Doesn't work with AutoSpace", tint = Color.White, modifier = Modifier.size(size * 0.66f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    tile: Tile,
    iconFrac: Float = 1f,
    tileFrac: Float = 1f,
    modifier: Modifier = Modifier,
    dragEnabled: Boolean = false,
    dragOffset: Offset = Offset.Zero,
    dragAlpha: Float = 1f,
    onDragStart: () -> Unit = {},
    onDrag: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
) {
    // Phase 9 pills: the square tile is gone — a fully rounded stadium
    // pill row, icon + label on ONE horizontal line, frost gradient +
    // hairline via GsIconBox (in Frost; other GsThemes keep their own
    // finish through the same control). r24: the capsule was the defect
    // under Pitlane — gsButtonShape squares the tile to the style's
    // 6dp corner there and keeps the capsule everywhere else.
    // Height scales with the
    // tile-size slider (56dp floor, so a small pill is still a car
    // target); the icon scales with the icon-size slider and is hidden
    // at 0% with the label (and the whole tap area) intact.
    val shape = gsButtonShape(androidx.compose.foundation.shape.RoundedCornerShape(50))
    // When drag is enabled, the tile's own long-press is NOT wired into
    // GsIconBox: the drag detector owns the long press, and a release without
    // movement opens the menu through onDragEnd instead. Otherwise a drag
    // would also pop the menu open mid-move.
    val dragModifier = if (dragEnabled) {
        Modifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { onDragStart() },
                onDrag = { change, amount -> change.consume(); onDrag(amount) },
                onDragEnd = { onDragEnd() },
                onDragCancel = { onDragCancel() },
            )
        }
    } else Modifier
    Box(
        dragModifier.graphicsLayer {
            translationX = dragOffset.x
            translationY = dragOffset.y
            alpha = dragAlpha
        },
    ) {
        GsIconBox(
            onClick = tile.onClick,
            onLongClick = if (dragEnabled) null else tile.onLongClick,
            modifier = modifier.height((72f * tileFrac).coerceAtLeast(56f).dp),
            colors = GsColors(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.onSurface),
            shape = shape,
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (iconFrac > 0f) {
                    Box {
                        TileIcon(tile, 48.dp * iconFrac)
                        if (tile.broken) {
                            BrokenBadge(
                                22.dp,
                                Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 4.dp)
                                    .border(2.dp, MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
                            )
                        }
                    }
                }
                // One line, left-aligned, fills the pill — a long label
                // ellipsises, never wraps mid-word or crowds the icon.
                TileLabel(tile, maxLines = 1, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            }
        }
    }
}

@Composable
private fun TileIcon(tile: Tile, size: androidx.compose.ui.unit.Dp = 80.dp) {
    when {
        tile.icon != null -> Image(tile.icon, contentDescription = null, modifier = Modifier.size(size))
        tile.glyph != null -> Icon(tile.glyph, contentDescription = null, modifier = Modifier.size(size))
    }
}

@Composable
private fun TileLabel(
    tile: Tile,
    maxLines: Int = 2,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
) {
    Text(
        tile.label,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
