package app.autospace.car

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.collectAsState
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
 * for the car screen — full-width rows, large type, Frost styling.
 * Menus are touchscreen-navigable; a paired controller drives only an
 * open game and never moves UI focus or backs out of a screen (r95
 * scope lock). Round 2 wires playback:
 * a game's Play row downloads its core (verified by size and hash)
 * and opens the playback screen, whose engine seam waits on the
 * native runner — until then the screen says so plainly instead of
 * pretending to play.
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
    data class Playback(val game: GameEntry) : GamesPage
}

@Composable
fun GamesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<GamesPage>(GamesPage.Home) }
    var games by remember { mutableStateOf(GameImport.load(context)) }
    var moving by remember { mutableStateOf(CarMotion.moving) }
    var importing by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var gameActions by remember { mutableStateOf<GameEntry?>(null) }
    var removeTarget by remember { mutableStateOf<GameEntry?>(null) }
    var pickerNote by remember { mutableStateOf(false) }
    // r92 core state: the fetched manifest (null until first fetch),
    // the overlay game's core status, and the shared download progress.
    var manifest by remember { mutableStateOf<List<GameCoreEntry>?>(null) }
    var playCore by remember { mutableStateOf<GameCoreDownload.Status?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var biosTarget by remember { mutableStateOf<String?>(null) }
    var biosTick by remember { mutableStateOf(0) }
    var coreTick by remember { mutableStateOf(0) }
    var consoleCoreStatus by remember {
        mutableStateOf<GameCoreDownload.Status?>(null)
    }
    // r148: when a game goes fullscreen, this section's own
    // padding goes with the rail — the game surface fills the
    // whole screen, and shrinking or leaving the game restores
    // the framed padding exactly, because the published state
    // exits with the game.
    val gameImmersive by GameImmersiveState.immersive.collectAsState()

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

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        pickerNote = false
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
        pickerNote = false
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

    // The picker UI opens on the phone (its registry lives in the phone activity,
    // provided at the projector root). Say so while it is open: the car screen
    // otherwise shows nothing at all until the result comes back. A launch failure
    // never crashes — it reports honestly instead.
    fun launchFolderPicker() {
        pickerNote = true
        val ok = runCatching { folderPicker.launch(null) }.isSuccess
        if (!ok) {
            pickerNote = false
            resultText = "Import needs the file picker on your phone, and it could not be opened. Try again."
        }
    }

    fun launchSinglePicker() {
        pickerNote = true
        val ok = runCatching { singlePicker.launch(arrayOf("*/*")) }.isSuccess
        if (!ok) {
            pickerNote = false
            resultText = "Import needs the file picker on your phone, and it could not be opened. Try again."
        }
    }

    // r92: the third picker, for BIOS files. It rides the same
    // projector-root registry as the two import pickers, so it needs
    // no new owner. The picked stream is saved AS the expected file
    // name, regardless of the picked file's name.
    val biosPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val target = biosTarget
        biosTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = target?.let { GameBios.importFile(context, it, uri) } ?: false
            withContext(Dispatchers.Main) {
                biosTick++
                resultText = if (ok) {
                    "$target installed."
                } else {
                    "Could not import that file."
                }
            }
        }
    }

    fun launchBiosPicker(name: String) {
        biosTarget = name
        val ok = runCatching { biosPicker.launch(arrayOf("*/*")) }.isSuccess
        if (!ok) {
            biosTarget = null
            resultText = "Import needs the file picker on your phone, and it could not be opened. Try again."
        }
    }

    fun checkCore(system: String, onDone: (GameCoreDownload.Status) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val status = GameCoreDownload.status(context, system)
            withContext(Dispatchers.Main) { onDone(status) }
        }
    }

    /** Fetch the manifest once, then download one core, verified. */
    fun downloadCore(
        system: String,
        onDone: (Boolean, String?) -> Unit
    ) {
        downloading = true
        downloadProgress = 0
        scope.launch(Dispatchers.IO) {
            val man = manifest
                ?: GameCoreDownload.fetchManifest().getOrNull()?.also { manifest = it }
            val entry = man?.let { GameCores.entryForSystem(it, system) }
            if (entry == null) {
                withContext(Dispatchers.Main) {
                    downloading = false
                    onDone(false, "Could not reach the core download. Check the connection and try again.")
                }
                return@launch
            }
            val result = GameCoreDownload.download(context, entry) { pct ->
                downloadProgress = pct
            }
            withContext(Dispatchers.Main) {
                downloading = false
                onDone(
                    result.isSuccess,
                    result.exceptionOrNull()?.message ?: "Core download failed."
                )
            }
        }
    }

    /** Play a game: a verified core opens playback at once; a
     * missing or corrupt core downloads first, then opens playback. */
    fun playGame(game: GameEntry) {
        scope.launch(Dispatchers.IO) {
            val status = GameCoreDownload.status(context, game.system)
            withContext(Dispatchers.Main) { playCore = status }
            if (status == GameCoreDownload.Status.DOWNLOADED) {
                withContext(Dispatchers.Main) { page = GamesPage.Playback(game) }
                return@launch
            }
            downloadCore(game.system) { ok, reason ->
                if (ok) {
                    playCore = GameCoreDownload.Status.DOWNLOADED
                    page = GamesPage.Playback(game)
                } else {
                    resultText = reason
                }
            }
        }
    }
    // No key handler on this root: controller input is scoped
     // to an open game at the activity (r95), so no controller
     // key may back out of Games from here.
    Box(
        Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier.fillMaxSize().padding(
                if (gameImmersive) PaddingValues()
                else PaddingValues(horizontal = 24.dp, vertical = 16.dp)
            )
        ) {
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
                    onImportFolder = { launchFolderPicker() },
                    onControllers = { page = GamesPage.Controllers },
                )
                is GamesPage.Console -> {
                    LaunchedEffect(current.key, coreTick) {
                        checkCore(current.key) { consoleCoreStatus = it }
                    }
                    ConsolePage(
                        consoleKey = current.key,
                        games = games.filter { it.system == current.key },
                        importing = importing,
                        coreStatus = consoleCoreStatus,
                        downloading = downloading,
                        downloadProgress = downloadProgress,
                        biosTick = biosTick,
                        onImportRom = { launchSinglePicker() },
                        onImportFolder = { launchFolderPicker() },
                        onGame = { gameActions = it },
                        onDownloadCore = {
                            downloadCore(current.key) { ok, reason ->
                                coreTick++
                                checkCore(current.key) { consoleCoreStatus = it }
                                if (!ok) resultText = reason
                            }
                        },
                        onImportBios = { launchBiosPicker(it) },
                        onDeleteBios = {
                            GameBios.delete(context, it)
                            biosTick++
                        },
                    )
                }
                is GamesPage.Playback -> GamePlaybackScreen(
                    game = current.game,
                    onExit = { page = GamesPage.Home },
                )
                GamesPage.Controllers -> ControllersScreen()
            }
        }
        if (!moving) {
            if (pickerNote) {
                GameOverlay(onDismiss = { pickerNote = false }) {
                    Text("The file picker opened on your phone.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Pick there — your games will appear here when you are done.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    GameMenuRow("OK", null) { pickerNote = false }
                }
            }
            resultText?.let { text ->
                GameOverlay(onDismiss = { resultText = null }) {
                    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(16.dp))
                    GameMenuRow("OK", null) { resultText = null }
                }
            }
            gameActions?.let { game ->
                GameOverlay(onDismiss = { gameActions = null }) {
                    LaunchedEffect(game.id) {
                        playCore = null
                        checkCore(game.system) { playCore = it }
                    }
                    Text(game.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(game.fileName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    val playCaption = when {
                        downloading -> "Downloading core… $downloadProgress%"
                        playCore == null -> "Checking core…"
                        playCore == GameCoreDownload.Status.DOWNLOADED -> "Core ready"
                        playCore == GameCoreDownload.Status.CORRUPT -> "Core failed verification — tap to download again"
                        else -> "Downloads the core first, then plays"
                    }
                    GameMenuRow("Play", playCaption) {
                        if (!downloading) {
                            gameActions = null
                            playGame(game)
                        }
                    }
                    GameMenuRow(if (game.favorite) "Unfavorite" else "Favorite", null) {
                        GameImport.setFavorite(context, game.id, !game.favorite)
                        refresh()
                        gameActions = null
                    }
                    GameMenuRow("Remove from library", "Deletes the app's copy only") {
                        gameActions = null
                        removeTarget = game
                    }
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
                "Pair a Bluetooth controller for gameplay",
            ) { onControllers() }
        }
    }
}

@Composable
private fun ConsolePage(
    consoleKey: String,
    games: List<GameEntry>,
    importing: Boolean,
    coreStatus: GameCoreDownload.Status?,
    downloading: Boolean,
    downloadProgress: Int,
    biosTick: Int,
    onImportRom: () -> Unit,
    onImportFolder: () -> Unit,
    onGame: (GameEntry) -> Unit,
    onDownloadCore: () -> Unit,
    onImportBios: (String) -> Unit,
    onDeleteBios: (String) -> Unit,
) {
    val context = LocalContext.current
    val console = GameConsoles.byKey(consoleKey)
    // r147: mapping state lives here, in the @Composable body —
    // not in the LazyColumn scope below, which is not a
    // @Composable context and cannot call remember. The console
    // key rides the remember keys, so switching consoles never
    // shows the previous console's mapping, and the version bump
    // recomposes the rows with the fresh stored values after an
    // assignment or reset.
    var mappingVersion by remember { mutableStateOf(0) }
    var expandedAction by remember { mutableStateOf<Int?>(null) }
    val padMapping = remember(consoleKey, mappingVersion) {
        GamePadMapping.storedMapping(context, consoleKey)
    }
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
        item(key = "core") {
            GameMenuRow(
                "Core",
                when {
                    downloading -> "Downloading… $downloadProgress%"
                    coreStatus == null -> "Checking…"
                    coreStatus == GameCoreDownload.Status.DOWNLOADED ->
                        "Downloaded and verified"
                    coreStatus == GameCoreDownload.Status.CORRUPT ->
                        "Failed verification — tap to download again"
                    else -> "Not downloaded — tap to download"
                },
            ) { if (!downloading) onDownloadCore() }
        }
        item(key = "mapping-header") {
            Text(
                "Controller mapping",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item(key = "mapping-note") {
            Text(
                "Remap this console's buttons. The mapping is stored " +
                    "for this console only, and applies the next time " +
                    "you launch one of its games. Buttons you don't " +
                    "remap keep today's behavior.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (action in GamePadMapping.ACTIONS) {
            val assigned = padMapping.getValue(action.bit)
            item(key = "mapping-${action.bit}") {
                GameMenuRow(
                    title = action.label,
                    caption = "Assigned: " + GamePadMapping.buttonLabel(assigned),
                    trailing = {
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    },
                ) {
                    expandedAction = if (expandedAction == action.bit) null else action.bit
                }
            }
            if (expandedAction == action.bit) {
                items(GamePadMapping.CANDIDATE_CODES, key = { "mapping-${action.bit}-$it" }) { code ->
                    GameMenuRow(
                        title = GamePadMapping.buttonLabel(code),
                        caption = if (code == assigned) {
                            "Currently assigned to " + action.label
                        } else {
                            null
                        },
                    ) {
                        GamePadMapping.setKeyCode(context, consoleKey, action.bit, code)
                        mappingVersion++
                        expandedAction = null
                    }
                }
            }
        }
        item(key = "mapping-reset") {
            GameMenuRow(
                "Reset to defaults",
                "Restores this console's buttons to today's defaults",
            ) {
                GamePadMapping.resetConsole(context, consoleKey)
                mappingVersion++
                expandedAction = null
            }
        }
        item(key = "bios-header") {
            Text(
                "BIOS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item(key = "bios-ships-note") {
            Text(
                "The app ships no BIOS files — supply your own. Imported " +
                    "files are copied into the app's system folder, where " +
                    "the core looks for them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GameBios.noteFor(consoleKey)?.let { note ->
            item(key = "bios-note") {
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // The tick rides the row keys, so an import or delete remounts
        // the rows and re-reads installed state.
        val expected = GameBios.expectedFor(consoleKey)
        if (expected.isEmpty()) {
            item(key = "bios-none-$biosTick") {
                Text(
                    "This console doesn't need a BIOS — it boots without one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            for (name in expected) {
                val installed = GameBios.isInstalled(context, name)
                item(key = "bios-$name-$biosTick") {
                    GameMenuRow(
                        name,
                        if (installed) "Installed — tap to replace" else "Missing — tap to import",
                        trailing = {
                            if (installed) {
                                Text(
                                    "Delete",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { onDeleteBios(name) },
                                )
                            }
                        },
                    ) { onImportBios(name) }
                }
            }
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
        "A Bluetooth controller is required for gameplay inside cores. The " +
            "menus are touchscreen-navigable, and controllers navigate them " +
            "too: D-pad moves, A confirms, B goes back. Pairing happens in " +
            "the phone's own pairing dialog.",
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
