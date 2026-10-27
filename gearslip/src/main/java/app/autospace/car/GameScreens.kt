package app.autospace.car

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.FrostedPanelSurface
import app.autospace.car.theme.gsButtonShape
import app.autospace.notify.CarMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * r89: the Games section, ported from Mariner's games suite home: one row per
 * console, each console's page holding its games and its import, refined only
 * for the car screen — full-width rows, large type, Frost styling, and
 * controller navigation (D-pad moves focus, A confirms, B goes back, exactly
 * like the touchscreen rows they sit beside). Round 1 has no playback: a
 * game's row says plainly that play arrives in a later round.
 *
 * Hard parked gate: the section is usable only while parked, on CarMotion's
 * motion signal, rechecked every second while the screen is open — driving
 * off mid-browse swaps the whole screen for the blocked notice. There is no
 * passenger bypass here, ever: this is not the video passenger gate.
 * Overlays are in-composition, never real windows: real popup windows crash
 * on this virtual display.
 */
private sealed interface GamesPage {
    data object Home : GamesPage
    data class Console(val key: String) : GamesPage
    data object Controllers : GamesPage
}

@Composable
fun GamesScreen() {
    val context = LocalContext.current
    val navigator = LocalCarNavigator.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<GamesPage>(GamesPage.Home) }
    var games by remember { mutableStateOf(GameImport.load(context)) }
    var moving by remember { mutableStateOf(CarMotion.moving) }
    var importing by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var gameActions by remember { mutableStateOf<GameEntry?>(null) }
    var removeTarget by remember { mutableStateOf<GameEntry?>(null) }

    // Parked gate, live: a plain property, not a flow, so it is polled.
    LaunchedEffect(Unit) {
        while (true) {
            moving = CarMotion.moving
            delay(1000)
        }
    }

    fun refresh() {
        games = GameImport.load(context)
    }

    fun goBack() {
        if (page != GamesPage.Home) page = GamesPage.Home else navigator.back()
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = (page as? GamesPage.Console)?.key
        importing = true
        scope.launch(Dispatchers.IO) {
            val result = GameImport.importFolder(context, uri, target)
            withContext(Dispatchers.Main) {
                importing = false
                refresh()
                resultText = "Imported ${result.imported} games (${result.skipped} skipped)."
            }
        }
    }

    val singlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val target = (page as? GamesPage.Console)?.key ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch(Dispatchers.IO) {
            val result = GameImport.importSingle(context, uri, target)
            withContext(Dispatchers.Main) {
                importing = false
                refresh()
                resultText = "Imported ${result.imported} games (${result.skipped} skipped)."
            }
        }
    }

    Box(
        Modifier.fillMaxSize().onKeyEvent { event ->
            // Gamepad B goes back.
            if (event.type == KeyEventType.KeyDown && event.key == Key.ButtonB) {
                goBack()
                true
            } else {
                false
            }
        },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            if (moving) {
                ScreenHeader("Games")
                Spacer(Modifier.height(16.dp))
                Text(
                    "Games need the car parked.",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "The Games section opens only while parked, and closes itself " +
                        "if the car starts moving. There is no passenger bypass: " +
                        "park first, then come back.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                return@Column
            }
            when (val current = page) {
                GamesPage.Home -> GamesHome(
                    games = games,
                    importing = importing,
                    onConsole = { page = GamesPage.Console(it) },
                    onImportFolder = { folderPicker.launch(null) },
                    onControllers = { page = GamesPage.Controllers },
                )
                is GamesPage.Console -> ConsolePage(
                    consoleKey = current.key,
                    games = games.filter { it.system == current.key },
                    importing = importing,
                    onImportRom = { singlePicker.launch(arrayOf("*/*")) },
                    onImportFolder = { folderPicker.launch(null) },
                    onGame = { gameActions = it },
                )
                GamesPage.Controllers -> ControllersScreen()
            }
        }
        if (!moving) {
            resultText?.let { text ->
                GameOverlay(onDismiss = { resultText = null }) {
                    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(16.dp))
                    GameMenuRow("OK", null) { resultText = null }
                }
            }
            gameActions?.let { game ->
                GameOverlay(onDismiss = { gameActions = null }) {
                    Text(game.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(game.fileName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    GameMenuRow(if (game.favorite) "Unfavorite" else "Favorite", null) {
                        GameImport.setFavorite(context, game.id, !game.favorite)
                        refresh()
                        gameActions = null
                    }
                    GameMenuRow("Remove from library", "Deletes the app's copy only") {
                        gameActions = null
                        removeTarget = game
                    }
                    Text(
                        "Play arrives in a later round.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GameMenuRow("Cancel", null) { gameActions = null }
                }
            }
            removeTarget?.let { game ->
                GameOverlay(onDismiss = { removeTarget = null }) {
                    Text("Remove ${game.title}?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "This deletes the app's copy and its library entry. Your original file is untouched.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    GameMenuRow("Remove", null) {
                        GameImport.remove(context, game.id)
                        refresh()
                        removeTarget = null
                    }
                    GameMenuRow("Keep", null) { removeTarget = null }
                }
            }
        }
    }
}

/** One full-width car row: touchscreen tap, D-pad focus with the car ring,
 * and gamepad A all confirm. Clickable already handles D-pad center and
 * Enter; the key handler adds only button A, so nothing fires twice. */
@Composable
private fun GameMenuRow(
    title: String,
    caption: String?,
    trailing: @Composable RowScope.() -> Unit = {},
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = gsButtonShape(MaterialTheme.shapes.large),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.ButtonA) {
                        onClick()
                        true
                    } else {
                        false
                    }
                }
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (caption != null) {
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing()
        }
    }
}

/** In-composition overlay card: a dimmed scrim plus a centered frosted panel.
 * Never a real Dialog window — those crash on this virtual display. */
@Composable
private fun GameOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        FrostedPanelSurface(
            shape = gsButtonShape(MaterialTheme.shapes.large),
            elevation = 4.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).clickable {},
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun GamesHome(
    games: List<GameEntry>,
    importing: Boolean,
    onConsole: (String) -> Unit,
    onImportFolder: () -> Unit,
    onControllers: () -> Unit,
) {
    ScreenHeader("Games")
    Text(
        "Your library, by console. Bring your own ROMs: imports are copies, " +
            "and removing a game deletes only the app's copy.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (console in GameConsoles.ALL) {
            val count = games.count { it.system == console.key }
            item(key = "console-${console.key}") {
                GameMenuRow(
                    title = console.label,
                    caption = "Plays " + console.extensions.joinToString(", ") { ".$it" } +
                        " · $count game" + if (count == 1) "" else "s",
                    trailing = {
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    },
                ) { onConsole(console.key) }
            }
        }
        item(key = "import-folder") {
            GameMenuRow(
                "Import a folder of ROMs",
                if (importing) "Importing…" else "Walks a folder, sorts by console, skips duplicates",
            ) { if (!importing) onImportFolder() }
        }
        item(key = "controllers") {
            GameMenuRow(
                "Controllers",
                "Pair a Bluetooth controller for menu navigation",
            ) { onControllers() }
        }
    }
}

@Composable
private fun ConsolePage(
    consoleKey: String,
    games: List<GameEntry>,
    importing: Boolean,
    onImportRom: () -> Unit,
    onImportFolder: () -> Unit,
    onGame: (GameEntry) -> Unit,
) {
    val console = GameConsoles.byKey(consoleKey)
    ScreenHeader(console?.label ?: consoleKey)
    Text(
        "Plays " + (console?.extensions?.joinToString(", ") { ".$it" } ?: ""),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "import-rom") {
            GameMenuRow(
                "Import a ${console?.label ?: consoleKey} ROM",
                if (importing) "Importing…" else "One file, copied into the library",
            ) { if (!importing) onImportRom() }
        }
        item(key = "import-folder") {
            GameMenuRow(
                "Import a folder of ROMs",
                if (importing) "Importing…" else "Walks a folder, sorts by console, skips duplicates",
            ) { if (!importing) onImportFolder() }
        }
        if (games.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No games yet. Import a ROM or a folder of ROMs to fill this page.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(games.sortedByDescending { it.addedAt }, key = { it.id }) { game ->
                GameMenuRow(
                    title = game.title,
                    caption = game.fileName,
                    trailing = {
                        Icon(
                            if (game.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (game.favorite) "Favorite" else null,
                        )
                    },
                ) { onGame(game) }
            }
        }
    }
}

@Composable
private fun ControllersScreen() {
    val context = LocalContext.current
    ScreenHeader("Controllers")
    var bonded by remember { mutableStateOf(GameControllers.bonded(context)) }
    // A state list, not a snapshot: discovery callbacks arrive later, and a
    // captured list snapshot would drop every device but the last.
    val found = remember { mutableStateListOf<ControllerDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var pairingMessage by remember { mutableStateOf<String?>(null) }
    var discovery by remember { mutableStateOf<GameControllers.Discovery?>(null) }

    fun refreshBonded() {
        bonded = GameControllers.bonded(context)
    }

    DisposableEffect(discovery) {
        onDispose { discovery?.stop() }
    }

    Text(
        "Controllers navigate the Games menus: D-pad moves, A confirms, B goes " +
            "back. Pairing happens in the phone's own pairing dialog.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!GameControllers.hasConnectPermission(context)) {
            item(key = "connect-permission") {
                Text(
                    "Bluetooth permission is granted on the phone, not here. " +
                        "Grant it in the phone's settings, then come back.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@LazyColumn
        }
        item(key = "paired-header") {
            Text(
                "Paired",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (bonded.isEmpty()) {
            item(key = "paired-empty") {
                Text(
                    "No paired devices yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(bonded, key = { it.address }) { device ->
                GameMenuRow(
                    title = device.name,
                    caption = device.address + if (device.looksLikeController) " · Controller" else "",
                ) { refreshBonded() }
            }
        }
        item(key = "scan") {
            if (!GameControllers.hasScanPermission(context)) {
                Text(
                    "Scanning needs the Bluetooth scan permission, granted on the phone.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                GameMenuRow(
                    if (scanning) "Scanning…" else "Scan for controllers",
                    if (scanning) "Looking for nearby controllers" else "Finds unpaired controllers nearby",
                ) {
                    if (scanning) return@GameMenuRow
                    found.clear()
                    val session = GameControllers.Discovery(
                        context,
                        onFound = { device ->
                            if (found.none { it.address == device.address } &&
                                bonded.none { it.address == device.address }
                            ) {
                                found += device
                            }
                        },
                        onFinished = { scanning = false },
                    )
                    discovery = session
                    scanning = session.start()
                }
            }
        }
        if (pairingMessage != null) {
            item(key = "pairing-message") {
                Text(
                    pairingMessage!!,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (found.isNotEmpty()) {
            item(key = "found-header") {
                Text(
                    "Found",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(found, key = { it.address }) { device ->
                GameMenuRow(
                    title = device.name,
                    caption = device.address +
                        (if (device.looksLikeController) " · Controller" else "") +
                        " · Tap to pair",
                ) {
                    if (GameControllers.pair(context, device.address)) {
                        pairingMessage = "Pairing dialog opened on the phone. Accept it there, " +
                            "then tap a paired row above to refresh."
                    } else {
                        pairingMessage = "Could not start pairing. Check Bluetooth on the phone and try again."
                    }
                }
            }
        }
        item(key = "refresh") {
            GameMenuRow("Refresh paired list", null) { refreshBonded() }
        }
    }
}
