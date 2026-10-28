package app.autospace.car

import android.content.Context
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.autospace.car.theme.FrostedPanelSurface
import app.autospace.car.theme.gsButtonShape
import app.autospace.notify.CarMotion
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * r92: the playback screen shell for the Games section, following the
 * video-player pattern: a SurfaceView hosted through AndroidView in
 * car composition, with the game surface filling the frame.
 *
 * Touch rule, non-negotiable: during on-screen gameplay the only
 * touch-accessible controls are the expand button, then the start
 * button, then the pause menu within that. There is no virtual
 * gamepad anywhere here. The menus stay touchscreen-navigable, and
 * actual gameplay takes a Bluetooth controller, routed through
 * GameKeyRouter into the engine.
 *
 * The engine here is RetroEngine (r93): the native libretro runner
 * behind this same seam, so a verified core renders frames and
 * plays audio on the SurfaceView below. Saves, disc swap, and
 * options were already wired through this seam, so they light up
 * unchanged now the runner is real.
 */
interface GameEngine {
    /** Start a game. Returns null on success, or the honest reason
     * the game cannot start. */
    fun create(
        corePath: String,
        romPath: String,
        systemDir: String,
        saveDir: String,
        options: Array<String>
    ): String?

    fun setSurface(surface: Surface?)
    fun start()
    fun stop()
    fun setPaused(paused: Boolean)
    fun setInput(mask: Int)
    fun setAnalog(lx: Int, ly: Int, rx: Int, ry: Int)
    fun setControllerDevice(device: Int)
    fun saveState(path: String): Boolean
    fun loadState(path: String): Boolean
    fun reset(): Boolean
    fun diskCount(): Int
    fun diskIndex(): Int
    fun diskSwap(index: Int): Boolean
    fun destroy()

    /** r149: presenter options (see RetroNative). The setters
     * are session-start except setSpeed, which is live. */
    fun setTopInset(px: Int)
    fun setShader(on: Boolean, scan: Float, grid: Float, blur: Float)
    fun setLcdShader(on: Boolean, brightScan: Float, brightLcd: Float)
    fun setCrtShader(on: Boolean)
    fun setColorProfile(
        saturation: Float,
        warm: Float,
        gamma: Float,
        bright: Float,
        black: Float,
        segaFix: Boolean
    )
    fun setCurve(k: Float)
    fun setRound(strength: Float)
    fun setSpeed(mul: Int)
    fun setIntegerScale(on: Boolean)

    /** Latest RGBA frame grab (8-byte header + pixels), or null
     * before the first frame. Feeds state thumbnails. */
    fun frameGrab(): ByteArray?

    /** Fresh frames the presenter has consumed this session. */
    fun presentedFrames(): Long
}

/**
 * Watches for a gamepad-class device disappearing and reports it
 * once, so playback pauses the moment a controller disconnects.
 * Devices that appear later join the baseline, so a reconnected
 * controller does not re-trigger the pause.
 */
private class GamepadDisconnectMonitor(
    context: Context,
    private val onDisconnect: () -> Unit
) {
    private val inputManager =
        context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val baseline = HashSet<Int>()

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            val device = InputDevice.getDevice(deviceId) ?: return
            if (isGamepad(device)) baseline.add(deviceId)
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            if (baseline.remove(deviceId)) onDisconnect()
        }

        override fun onInputDeviceChanged(deviceId: Int) = Unit
    }

    fun start() {
        for (deviceId in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(deviceId) ?: continue
            if (isGamepad(device)) baseline.add(deviceId)
        }
        inputManager.registerInputDeviceListener(listener, null)
    }

    fun stop() {
        inputManager.unregisterInputDeviceListener(listener)
    }

    private fun isGamepad(device: InputDevice): Boolean {
        val sources = device.sources
        return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }
}

/**
 * r148: games immersive fullscreen, following the browser's
 * BrowserImmersiveState pattern (WebApp.kt) and the player's
 * PlayerImmersiveState pattern (VideoPlayerScreen.kt): the
 * playback screen publishes here, CarUi hides the rail/pill
 * with it — but only while Games itself is the active screen,
 * and leaving the game clears it, so a hidden bar can never
 * outlive the game. Session state only, never persisted.
 *
 * Mariner has no games-playback fullscreen to port: its
 * GamesScreen is menus and options, and its one "immersive"
 * flag is a settings value, not a playback mode. So this
 * follows the tree's own immersive pattern instead.
 */
object GameImmersiveState {
    val immersive = MutableStateFlow(false)
    fun enter() { immersive.value = true }
    fun exit() { immersive.value = false }
}

/**
 * r148: the pure chrome rule CarUi applies — the rail/pill hide
 * only when Games is the active screen AND the game is
 * fullscreen. Pinned by GameImmersiveTest: a fullscreen game on
 * any other screen, or a framed game on the Games screen, hides
 * nothing.
 */
fun gameImmersiveHidesChrome(screenIsGames: Boolean, immersive: Boolean): Boolean =
    screenIsGames && immersive

@Composable
fun GamePlaybackScreen(game: GameEntry, onExit: () -> Unit) {
    val context = LocalContext.current
    var moving by remember { mutableStateOf(CarMotion.moving) }
    var expanded by remember { mutableStateOf(false) }
    // r148: immersive fullscreen follows expanded exactly —
    // entering fullscreen publishes, shrinking exits, and the
    // dispose below exits too, so a hidden rail can never
    // outlive the game. The pause menu does not touch it:
    // opening the menu over a fullscreen game keeps the game
    // fullscreen behind it, and closing the menu returns to
    // fullscreen.
    LaunchedEffect(expanded) {
        if (expanded) GameImmersiveState.enter() else GameImmersiveState.exit()
    }
    var menuOpen by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var engineNote by remember { mutableStateOf<String?>(null) }
    var menuNote by remember { mutableStateOf<String?>(null) }
    var optionsTick by remember { mutableStateOf(0) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var inputMask by remember { mutableStateOf(0) }

    // RetroEngine for r93: the native runner behind this same seam.
    val engine: GameEngine = remember { RetroEngine }

    // r147: the stored per-console mapping, read once at launch
    // like core options — a mapping changed while a game is open
    // applies the next time one of this console's games launches.
    val padMapping = GamePadMapping.storedMapping(context, game.system)
    val router = remember {
        GameKeyRouter(
            setBit = { bit, on ->
                inputMask = if (on) {
                    inputMask or (1 shl bit)
                } else {
                    inputMask and (1 shl bit).inv()
                }
                engine.setInput(inputMask)
            },
            onActivity = {},
            keyToBit = { code -> GamePadMapping.bitForKeyCode(padMapping, code) },
        ).also { it.analogMode = GameInput.analogCapable(game.system) }
    }
    router.onAnalog = { lx, ly, rx, ry -> engine.setAnalog(lx, ly, rx, ry) }

    // r149: portrait top inset, ported from Mariner (J7): in
    // portrait the picture is top-anchored just below the camera
    // cutout (the native side reads this inset); in landscape
    // it stays centred and the inset is zero. The car screen is
    // landscape in practice, so this is usually zero - but the
    // plumbing is ported, not assumed away.
    val configuration = LocalConfiguration.current
    val portrait =
        configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val density = LocalDensity.current
    val topInsetPx = if (portrait) {
        WindowInsets.displayCutout.getTop(density) +
            with(density) { 8.dp.toPx().toInt() }
    } else {
        0
    }
    LaunchedEffect(topInsetPx) {
        engine.setTopInset(topInsetPx)
    }

    // r149: FPS readout, ported from Mariner: per-console
    // setting, off by default; in-game the number is its own
    // overlay. The readout diffs fresh-frame counts twice a
    // second, so it shows the game's real output rate, not
    // redraws. Top-start here, not Mariner's top-right: the
    // fullscreen Shrink/Pause row already owns the top end.
    val fpsOn = remember { GamePresenterOptions.fpsOn(context, game.system) }
    var fpsText by remember { mutableStateOf("--") }
    LaunchedEffect(fpsOn) {
        if (!fpsOn) return@LaunchedEffect
        var prev = engine.presentedFrames()
        while (true) {
            delay(500)
            val cur = engine.presentedFrames()
            fpsText = ((cur - prev) * 2).toString()
            prev = cur
        }
    }

    // r149: fast-forward, ported from Mariner (J9): per-console
    // setting, off by default; the pause-menu row below toggles
    // it live, and audio silences while it runs.
    val ffEnabled = remember {
        GamePresenterOptions.fastForwardEnabled(context, game.system)
    }
    var ffOn by remember { mutableStateOf(false) }
    LaunchedEffect(ffOn) {
        engine.setSpeed(if (ffOn) 4 else 1)
    }

    // Parked gate, live, like the menus: driving swaps the whole
    // screen for the blocked notice. There is no passenger bypass.
    LaunchedEffect(Unit) {
        while (true) {
            moving = CarMotion.moving
            delay(1000)
        }
    }

    // Disconnect-pause: losing a controller pauses at once and opens
    // the pause menu, where Resume clears the pause.
    DisposableEffect(Unit) {
        val monitor = GamepadDisconnectMonitor(context) {
            paused = true
            engine.setPaused(true)
            menuOpen = true
        }
        monitor.start()
        onDispose { monitor.stop() }
    }

    // Motion-axis hook (r93): a phone-paired controller's sticks
    // and hat arrive at the activity as generic motion events and
    // land here through GameMotionSink while this screen is up.
    // The handler is cleared on leave, so motion never leaks into
    // another screen. Keys travel the same activity path (see
    // GearslipActivity) into this Box's onKeyEvent below. The
    // handler takes every joystick or gamepad move, including the
    // return to center, which is what clears the router's bits.
    DisposableEffect(router) {
        val handler: (MotionEvent) -> Boolean = { event ->
            if (event.action == MotionEvent.ACTION_MOVE) {
                router.onAxes(
                    event.getAxisValue(MotionEvent.AXIS_X),
                    event.getAxisValue(MotionEvent.AXIS_Y),
                    isStick = true,
                )
                router.onRightStick(
                    event.getAxisValue(MotionEvent.AXIS_Z),
                    event.getAxisValue(MotionEvent.AXIS_RZ),
                )
                router.onAxes(
                    event.getAxisValue(MotionEvent.AXIS_HAT_X),
                    event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                    isStick = false,
                )
                true
            } else {
                false
            }
        }
        GameMotionSink.handler = handler
        onDispose {
            if (GameMotionSink.handler === handler) {
                GameMotionSink.handler = null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            GameImmersiveState.exit()
            engine.stop()
            engine.destroy()
        }
    }

    LaunchedEffect(surface) {
        engine.setSurface(surface)
    }

    val gameId = GameSaves.gameIdFor(game.id, game.fileName)
    val romFile = File(GameImport.romsDir(context, game.system), game.fileName)

    LaunchedEffect(game.id) {
        val options = GameCoreOptions.storedPairs(context, game.system)
        val corePath = withContext(Dispatchers.IO) {
            GameCoreDownload.downloadedPath(context, game.system)
        }
        if (corePath == null) {
            engineNote = "The core for this console is not downloaded. " +
                "Go back and download it first."
            return@LaunchedEffect
        }
        val error = withContext(Dispatchers.IO) {
            engine.create(
                corePath,
                romFile.absolutePath,
                GameBios.systemDir(context).absolutePath,
                File(context.filesDir, "saves").absolutePath,
                options
            )
        }
        engineNote = error
        if (error == null) {
            engine.setControllerDevice(GameInput.controllerDeviceFor(game.system))
            // r149: session-start presentation, like core options
            // and the r147 mapping - read once at launch.
            GamePresenterOptions.applyToEngine(engine, context, game.system)
            engine.setTopInset(topInsetPx)
            engine.start()
        }
    }

    Box(
        Modifier.fillMaxSize().onKeyEvent { event ->
            // Scope lock (r95): controller input never leaves this
            // screen. B is a game button while playing, and closes
            // the pause menu back to the game while it is open —
            // it never walks back through the Games root. While the
            // menu is open, D-pad and A still belong to the menu.
            if (event.key == Key.ButtonB && menuOpen) {
                if (event.type == KeyEventType.KeyDown) menuOpen = false
                true
            } else if (menuOpen) {
                false
            } else if (event.type == KeyEventType.KeyDown ||
                event.type == KeyEventType.KeyUp
            ) {
                // Every mapped key, B included, is a game button.
                router.onKey(
                    event.nativeKeyEvent.keyCode,
                    event.type == KeyEventType.KeyDown
                )
            } else {
                false
            }
        },
    ) {
        if (moving) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    game.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Games need the car parked.",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Playback stopped because the car started moving. " +
                        "Park first, then come back.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(16.dp))
                GamePlaybackRow("Back to library", null) { onExit() }
            }
            return@Box
        }

        Column(Modifier.fillMaxSize()) {
            if (!expanded) {
                Text(
                    game.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (GameConsoles.byKey(game.system)?.label ?: game.system) +
                        if (paused) " · Paused" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            Box(Modifier.weight(1f)) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    surface = holder.surface
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) = Unit

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    surface = null
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (fpsOn) {
                    Box(
                        Modifier.fillMaxSize().padding(12.dp),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = gsButtonShape(MaterialTheme.shapes.large),
                        ) {
                            Text(
                                "$fpsText FPS",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(
                                    horizontal = 12.dp,
                                    vertical = 8.dp
                                ),
                            )
                        }
                    }
                }
                if (expanded) {
                    // r148: in fullscreen the touch reachables are
                    // Shrink and Pause — the same expand/start/pause
                    // pattern as the framed view, minus the frame.
                    // Shrink restores the framed view exactly;
                    // Pause opens the pause menu over the fullscreen
                    // game (closing it returns to fullscreen, and
                    // Quit to library inside it leaves the game).
                    // No virtual gamepad is added: gameplay still
                    // takes a Bluetooth controller, and the parked
                    // gate is untouched.
                    Box(
                        Modifier.fillMaxSize().padding(16.dp),
                        contentAlignment = Alignment.TopEnd,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                onClick = { expanded = false },
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = gsButtonShape(MaterialTheme.shapes.large),
                            ) {
                                Text(
                                    "Shrink",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 12.dp
                                    ),
                                )
                            }
                            Surface(
                                onClick = { menuOpen = true },
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = gsButtonShape(MaterialTheme.shapes.large),
                            ) {
                                Text(
                                    "Pause",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 12.dp
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            if (!expanded) {
                engineNote?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        GamePlaybackRow("Expand", "Full-screen game surface") {
                            expanded = true
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        GamePlaybackRow("Start", "Pause menu") { menuOpen = true }
                    }
                }
            }
        }

        if (menuOpen) {
            GamePlaybackOverlay(onDismiss = { menuOpen = false }) {
                val consoleLabel =
                    GameConsoles.byKey(game.system)?.label ?: game.system
                item(key = "title") {
                    Text(
                        if (paused) "Paused" else game.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                    )
                }
                if (paused) {
                    item(key = "paused-note") {
                        Text(
                            "A controller disconnected, so playback paused.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item(key = "resume") {
                    GamePlaybackRow("Resume", "Back to the game") {
                        paused = false
                        engine.setPaused(false)
                        menuOpen = false
                    }
                }
                if (ffEnabled) {
                    item(key = "fast-forward") {
                        GamePlaybackRow(
                            "Fast-forward",
                            if (ffOn) {
                                "On — running at 4x speed, audio silenced"
                            } else {
                                "Off — tap to run at 4x speed"
                            },
                        ) {
                            ffOn = !ffOn
                        }
                    }
                }
                if (menuNote != null) {
                    item(key = "menu-note") {
                        Text(
                            menuNote!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item(key = "saves-header") {
                    Text(
                        "Save states",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val slots = listOf(GameSaves.AUTO_SLOT) +
                    (1..GameSaves.SLOT_COUNT).toList()
                items(slots, key = { "save-$it" }) { slot ->
                    val info = GameSaves.slotInfo(context, gameId, slot)
                    val title = if (slot == GameSaves.AUTO_SLOT) {
                        "Auto save"
                    } else {
                        "Save slot $slot"
                    }
                    val caption = if (!info.exists) {
                        "Empty — tap to save"
                    } else {
                        "Saved" + if (info.hasThumb) " · Thumbnail" else ""
                    }
                    GamePlaybackRow(title, caption) {
                        val ok = if (info.exists) {
                            GameSaves.loadVia(context, gameId, slot) { file ->
                                engine.loadState(file.absolutePath)
                            }
                        } else {
                            GameSaves.saveVia(
                                context, gameId, slot,
                                grabFrame = { engine.frameGrab() },
                            ) { file ->
                                engine.saveState(file.absolutePath)
                            }
                        }
                        menuNote = if (ok) {
                            if (info.exists) "Loaded." else "Saved."
                        } else if (info.exists) {
                            "Could not load the save state."
                        } else {
                            "Could not save the state."
                        }
                    }
                }
                val discs = runCatching {
                    if (game.fileName.endsWith(".m3u", ignoreCase = true)) {
                        GameLibrary.m3uFiles(romFile.readText())
                    } else {
                        listOf(game.fileName)
                    }
                }.getOrDefault(listOf(game.fileName))
                if (game.system == "ps1" || game.system == "segacd") {
                    item(key = "disc-header") {
                        Text(
                            "Disc swap",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (discs.size <= 1) {
                        item(key = "disc-single") {
                            Text(
                                "This game has one disc.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(discs.indices.toList(), key = { "disc-$it" }) { index ->
                            val current = engine.diskCount() > 0 &&
                                engine.diskIndex() == index
                            GamePlaybackRow(
                                DiscSwap.displayLabel(index, null),
                                discs[index] + if (current) " · Current" else "",
                            ) {
                                menuNote = if (engine.diskSwap(index)) {
                                    "Disc ${index + 1} selected."
                                } else {
                                    "Disc swap failed."
                                }
                            }
                        }
                    }
                }
                item(key = "options-header") {
                    Text(
                        "Options · $consoleLabel",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item(key = "options-note") {
                    Text(
                        "Options are saved and apply the next time the game starts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The tick rides the row keys, so a value change
                // remounts the rows and re-reads the stored values.
                val optionsTickRead = optionsTick
                val options = GameCoreOptions.forConsole(game.system)
                items(options, key = { "${it.key}-$optionsTickRead" }) { option ->
                    val current = GameCoreOptions.storedValue(context, game.system, option)
                    GamePlaybackRow(option.label, option.display(current)) {
                        val at = option.values.indexOf(current)
                        val next = option.values[(at + 1) % option.values.size]
                        GameCoreOptions.setValue(context, game.system, option.key, next)
                        optionsTick++
                    }
                }
                item(key = "reset") {
                    GamePlaybackRow("Reset game", null) {
                        menuNote = if (engine.reset()) {
                            "Game reset."
                        } else {
                            "Reset failed."
                        }
                    }
                }
                item(key = "quit") {
                    GamePlaybackRow("Quit to library", null) { onExit() }
                }
            }
        }
    }
}

/** A menu row in the playback screen's own styling, matching the
 * Games menus: touchscreen tap, D-pad focus, gamepad A confirms. */
@Composable
private fun GamePlaybackRow(
    title: String,
    caption: String?,
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
        }
    }
}

/** In-composition overlay card for the pause menu: a dimmed scrim
 * plus a centered frosted panel. Never a real Dialog window — those
 * crash on this virtual display. The panel scrolls, because the
 * options list is long. */
@Composable
private fun GamePlaybackOverlay(
    onDismiss: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        FrostedPanelSurface(
            shape = gsButtonShape(MaterialTheme.shapes.large),
            elevation = 4.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
                .clickable {},
        ) {
            LazyColumn(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}
