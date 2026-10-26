package app.autospace.car

import app.autospace.car.theme.*
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.awaitPointerEventScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Whether a [CarKeyboard] is on screen right now, so [CarUi] can give it the row of height the
 * bottom nav bar normally holds instead of squeezing it in above - a driver typing has no use
 * for Home/Apps/Settings shortcuts anyway, and the screen is small enough that the space matters.
 */
object CarKeyboardVisibility {
    val state = mutableStateOf(false)
}

/**
 * A keyboard the host draws itself.
 *
 * Text entry in a car can't use the phone's IME: the car UI lives in a Presentation on a
 * virtual display, where a system keyboard has nowhere sensible to appear, and a phone keyboard
 * is the wrong shape for a screen a driver glances at anyway. Android Auto draws its own for
 * the same reasons, so this does too - large keys, no long-press, no prediction.
 *
 * It is a pure function of [text]: every key reports the new string and holds no state beyond
 * which layer is showing. Laid out like Gboard (a phone keyboard) rather than a PC's: the
 * second letter row is staggered half a key in, shift and backspace sit at the ends of the
 * bottom letter row, and the bottom row is the symbols toggle, comma, a wide space bar,
 * period, and submit — the shape a driver already knows from their phone. Shift shows the
 * Gboard arrow (⇧) and fills with the primary colour while active; letters render uppercase
 * while shifted. Backspace stays a row above the space bar, so a fumbled reach for space
 * never lands on delete instead. Keys stay large (44dp height floor), with clear Gboard-style
 * gaps between keys (8dp gutters, 12dp corners) and no prediction and no suggestion strip:
 * the Gboard shape is kept, the phone's small targets and smart features are not.
 *
 * Shortcuts (Robert, 2026-10-25, rebuilt to match Gboard 2026-10-25): letter keys carry
 * Gboard's shortcuts, painted small at the key's top-right corner — Q-P hold
 * 1-0, A-L hold @ # $ _ & - + ( ), Z-M hold * " ' : ; ! ?. Three mutually exclusive paths
 * insert exactly one character per press, decided by [KeyGestureTracker] (unit-tested):
 * a tap inserts the letter; a long-press inserts the hint; a quick swipe DOWN on the key
 * inserts the hint too (the flick direction; it must not conflict with tap, long-press, or
 * any scroll in the keyboard area). A swipe inserts ONLY the hint, never letter + hint,
 * and a tap inserts ONLY the letter. Shift rule, unchanged: a tap consumes shift as
 * before; both shortcut paths (long-press and swipe-down) leave shift exactly as it was,
 * because they insert the same hint character — the shortcut paths share one rule.
 * Symbols mode is unchanged (its keys carry no hints, so swipe and long-press do nothing
 * there), and there is deliberately no emoji key: the bottom row stays
 * symbols/comma/space/period/dismiss/submit, as decided 2026-10-25.
 *
 * Geometry, measured against Robert's phone Gboard (2026-10-25): Q row 10 equal keys;
 * A row 9 keys inset half a key each side; Z row shift ~1.1 keys + 7 letters + backspace
 * ~1.4 keys; bottom row symbols ~1.4 + comma 1 + space ~3.7 + period 1 + dismiss 1 (the
 * emoji slot, without an emoji key) + submit 1.6. The 44dp floor is a HEIGHT floor and
 * holds at every size; key WIDTHS hold it at every preview size (narrowest preview
 * 800dp wide gives ~71dp letter keys), but a frame narrower than ~528dp would shrink
 * letter widths below 44dp — no preview size is that narrow, so this is a stated
 * boundary, not a preview-size issue.
 *
 * What is NOT ported from Gboard, deliberately: the top toolbar row (features menu,
 * sticker, GIF, clipboard, theme, voice mic) — voice and settings already exist elsewhere
 * in the car UI, and GIF/clipboard/sticker are not car-appropriate. If Robert actually
 * wants the toolbar he can say so.
 *
 * [onDismiss] is null wherever there's no keyboard-shown state independent of the screen itself
 * (a search template, sign-in) - there, the header's own back action is the only way out and no
 * dismiss button is drawn.
 */
@Composable
fun CarKeyboard(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    submitIcon: Boolean = true,
    submitImage: ImageVector? = null,
    onDismiss: (() -> Unit)? = null,
) {
    var symbols by remember { mutableStateOf(false) }
    var shifted by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        CarKeyboardVisibility.state.value = true
        onDispose { CarKeyboardVisibility.state.value = false }
    }

    // The same field on the phone, for typing on its own keyboard: see [PhoneTyping].
    val latestChange by rememberUpdatedState(onTextChange)
    val latestSubmit by rememberUpdatedState(onSubmit)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val phoneField = remember {
        PhoneTyping.open(text, { latestChange(it) }, { latestSubmit() }, onDismiss?.let { { latestDismiss?.invoke() } })
    }
    DisposableEffect(phoneField) { onDispose { PhoneTyping.close(phoneField) } }
    LaunchedEffect(text) { PhoneTyping.carText(phoneField, text) }

    val rows = if (symbols) SYMBOL_ROWS else LETTER_ROWS
    val keyHeight = keyHeight(LocalConfiguration.current.screenHeightDp)

    CompositionLocalProvider(LocalKeyHeight provides keyHeight) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.dropLast(1).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Gboard stagger: the 9-key second letter row sits half a key
                        // in from each edge; symbol rows (10 keys) run full width.
                        val staggered = !symbols && row.size == 9
                        if (staggered) Spacer(Modifier.weight(0.5f))
                        row.forEach { key ->
                            val label = if (shifted && !symbols) key.uppercase() else key
                            val hint = if (!symbols) LONG_PRESS_HINTS[key] else null
                            LetterKey(label, Modifier.weight(1f), hint = hint,
                                onLetter = {
                                    onTextChange(text + label)
                                    shifted = false
                                },
                                onShortcut = hint?.let { { onTextChange(text + it) } },
                            )
                        }
                        if (staggered) Spacer(Modifier.weight(0.5f))
                    }
                }

                // The bottom letter row: shift and backspace bookend it, exactly
                // where Gboard puts them — shift bottom-left, backspace
                // bottom-right of the letter block, not down beside the space bar.
                // Gboard proportions (measured 2026-10-25): shift ~1.1 keys,
                // backspace ~1.4 keys — not the old 1.6/1.6, which made shift
                // read far wider than Gboard's.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!symbols) {
                        Key("⇧", Modifier.weight(1.1f), active = shifted) { shifted = !shifted }
                    }
                    rows.last().forEach { key ->
                        val label = if (shifted && !symbols) key.uppercase() else key
                        val hint = if (!symbols) LONG_PRESS_HINTS[key] else null
                        LetterKey(label, Modifier.weight(1f), hint = hint,
                            onLetter = {
                                onTextChange(text + label)
                                shifted = false
                            },
                            onShortcut = hint?.let { { onTextChange(text + it) } },
                        )
                    }
                    Key("⌫", Modifier.weight(1.4f)) {
                        if (text.isNotEmpty()) onTextChange(text.dropLast(1))
                    }
                }

                // Control row: Gboard's bottom row — symbols toggle, comma,
                // wide space, period, then dismiss (when offered) and submit
                // in the bottom-right corner, where a thumb expects it. Comma
                // and period are letters-mode only (the symbols layer already
                // carries them) and, like space, do not consume the shift.
                // Gboard proportions (measured 2026-10-25): symbols ~1.4,
                // comma 1, space ~3.7, period 1, dismiss 1 (the emoji slot,
                // without an emoji key), submit 1.6. No backspace here: it
                // stays with the letters above, not next to the key most
                // likely to be hit right after it by mistake.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Key(if (symbols) "abc" else "?123", Modifier.weight(1.4f)) { symbols = !symbols }
                    if (!symbols) {
                        Key(",", Modifier.weight(1f)) { onTextChange("$text,") }
                    }
                    Key("space", Modifier.weight(3.7f)) { onTextChange("$text ") }
                    if (!symbols) {
                        Key(".", Modifier.weight(1f)) { onTextChange("$text.") }
                    }
                    if (onDismiss != null) {
                        DismissKey(onDismiss, Modifier.weight(1f))
                    }
                    SubmitKey(Modifier.weight(1.6f), submitIcon, submitImage, onSubmit)
                }
            }
        }
    }
}

@Composable
private fun Key(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    hint: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    GsIconBox(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.height(LocalKeyHeight.current),
        colors = if (active) {
            GsColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        } else {
            GsColors(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface)
        },
        shape = RoundedCornerShape(12.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            // Gboard-style hint: small, top-right, never competing with
            // the letter itself. Shown only where a hint exists (letter
            // keys in letters mode); symbols keys carry none.
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 2.dp),
                )
            }
        }
    }
}

/**
 * A letter/symbol grid key with three mutually exclusive insert paths, owned by one gesture
 * stream so no press can ever insert twice: tap inserts the letter, long-press inserts the
 * hint, and a quick swipe DOWN inserts the hint too ("keyboard swipe-down shortcut").
 * [KeyGestureTracker] decides; this composable only feeds it pointer events and fires the
 * one outcome it returns. A swipe is recognised only while a hint exists and only before
 * the long-press timeout — a slow drag that crosses the timeout is a long-press, not a
 * swipe, so the two paths can never both fire on one press. After either shortcut fires,
 * the rest of the press is drained (consumed) until lift, so no tap follows it. A press
 * that slides up or sideways past the threshold inserts nothing: it is a cancelled
 * gesture, not a tap. Pressed feedback reuses the theme's own keycap surface
 * ([gsSurface]); the Gs hold-marker ring is not drawn here because the hint itself
 * already signals that the key holds a shortcut. Accessibility fallback: the semantics
 * onClick inserts the letter, so knob/D-pad activation still types (touch never invokes
 * the semantics action, so it cannot double-fire with a tap).
 */
@Composable
private fun LetterKey(
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    onLetter: () -> Unit,
    onShortcut: (() -> Unit)? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    val colors = GsColors(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .height(LocalKeyHeight.current)
            .gsSurface(colors.container, shape, colors.border, pressed)
            .semantics { role = Role.Button; onClick { onLetter(); true } }
            .pointerInput(hint, label) {
                val swipeThresholdPx = with(density) { 20.dp.toPx() }
                val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        pressed = true
                        val tracker = KeyGestureTracker(swipeThresholdPx, longPressTimeoutMs)
                        tracker.onDown()
                        // Shared with the timeout job: set before any insert
                        // on lift, so a job resuming at the same instant as a
                        // lift can never insert after the tap already did.
                        var gestureDone = false
                        var timeoutJob: Job? = null
                        if (hint != null && onShortcut != null) {
                            timeoutJob = launch {
                                delay(longPressTimeoutMs)
                                if (!gestureDone && tracker.onTimeout() == KeyPressOutcome.SHORTCUT) {
                                    onShortcut()
                                }
                            }
                        }
                        // Event loop for this press only: it ends on lift or
                        // cancel, and exactly one insert (or none) happens.
                        var outcome: KeyPressOutcome? = null
                        while (outcome == null) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                            if (!change.pressed) {
                                gestureDone = true
                                timeoutJob?.cancel()
                                outcome = if (change.changedToUpOrCancelled()) tracker.onUp() else KeyPressOutcome.NONE
                                if (outcome == KeyPressOutcome.TAP) onLetter()
                                break
                            }
                            val dy = change.position.y - down.position.y
                            val dx = change.position.x - down.position.x
                            val elapsed = change.uptimeMillis - down.uptimeMillis
                            val swipe = if (hint != null && onShortcut != null) tracker.onMove(dy, dx, elapsed) else null
                            if (swipe == KeyPressOutcome.SHORTCUT) {
                                gestureDone = true
                                timeoutJob?.cancel()
                                onShortcut?.invoke()
                                change.consume()
                                // Drain until lift; the lift itself inserts
                                // nothing further (the shortcut already did).
                                var lifted = false
                                while (!lifted) {
                                    val drain = awaitPointerEvent()
                                    val d = drain.changes.firstOrNull { it.id == down.id }
                                    if (d != null) {
                                        if (!d.pressed) lifted = true
                                        d.consume()
                                    }
                                }
                                outcome = KeyPressOutcome.NONE
                            }
                        }
                        timeoutJob?.cancel()
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 2.dp),
                )
            }
        }
    }
}

/** One outcome per press, ever. NONE means "already inserted (shortcut) or cancelled". */
enum class KeyPressOutcome { TAP, SHORTCUT, NONE }

/**
 * Pure gesture decision for [LetterKey]: tap vs long-press vs swipe-down shortcut vs
 * cancelled. No Compose, no time source of its own — the caller feeds displacements and
 * elapsed times, so every path is unit-testable.
 *
 * Rules: a swipe-down is dy past threshold with dy greater than |dx|, recognised only
 * before the long-press timeout; reaching the timeout while still down (and not cancelled)
 * is a long-press shortcut; lifting before either, without a cancelling move, is a tap;
 * a move past threshold upward or sideways cancels the press (nothing is inserted).
 * Once a shortcut has fired, later events return null — the caller must not insert again.
 */
class KeyGestureTracker(
    private val swipeThresholdPx: Float,
    private val longPressTimeoutMs: Long,
) {
    private var fired = false
    private var cancelled = false

    fun onDown() {
        fired = false
        cancelled = false
    }

    /** Returns SHORTCUT the instant a swipe-down is recognised, else null. */
    fun onMove(dy: Float, dx: Float, elapsedMs: Long): KeyPressOutcome? {
        if (fired || cancelled) return null
        if (elapsedMs >= longPressTimeoutMs) return null // timeout path owns it past here
        if (dy > swipeThresholdPx && dy > abs(dx)) {
            fired = true
            return KeyPressOutcome.SHORTCUT
        }
        if (dy < -swipeThresholdPx || abs(dx) > swipeThresholdPx) {
            cancelled = true
        }
        return null
    }

    /** Called when the long-press timeout elapses while still down. */
    fun onTimeout(): KeyPressOutcome? {
        if (fired || cancelled) return null
        fired = true
        return KeyPressOutcome.SHORTCUT
    }

    /** Called on lift. TAP only when nothing fired and nothing cancelled. */
    fun onUp(): KeyPressOutcome {
        if (fired || cancelled) return KeyPressOutcome.NONE
        return KeyPressOutcome.TAP
    }
}

@Composable
private fun DismissKey(onClick: () -> Unit, modifier: Modifier = Modifier) {
    GsIconBox(
        onClick = onClick,
        modifier = modifier.height(LocalKeyHeight.current),
        colors = GsColors(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Hide keyboard")
        }
    }
}

@Composable
private fun SubmitKey(modifier: Modifier, search: Boolean, image: ImageVector?, onClick: () -> Unit) {
    GsIconBox(
        onClick = onClick,
        modifier = modifier.height(LocalKeyHeight.current),
        colors = GsColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                image ?: if (search) Icons.Filled.Search else Icons.Filled.ArrowBack,
                contentDescription = "Submit",
            )
        }
    }
}

/**
 * Big enough to hit on a bumpy road, small enough that four rows plus a field plus a few results
 * still fit. A 480-tall screen sets the floor; a taller one (LIVI's 1280x720) gets bigger keys.
 */
private fun keyHeight(screenHeightDp: Int): Dp = (screenHeightDp * 0.09f).dp.coerceIn(44.dp, 64.dp)

private val LocalKeyHeight = compositionLocalOf { 44.dp }

private val LETTER_ROWS = listOf(
    "qwertyuiop".map { it.toString() },
    "asdfghjkl".map { it.toString() },
    "zxcvbnm".map { it.toString() },
)

/**
 * Gboard's long-press shortcuts for the letter keys, exactly as
 * Robert listed them 2026-10-25: Q-P hold digits 1-0, A-L hold
 * @ # $ _ & - + ( ), Z-M hold * " ' : ; ! ?. Keys are lowercase
 * here (the rows store lowercase); the hint is inserted verbatim,
 * never shifted.
 */
private val LONG_PRESS_HINTS = mapOf(
    "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
    "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
    "a" to "@", "s" to "#", "d" to "$", "f" to "_", "g" to "&",
    "h" to "-", "j" to "+", "k" to "(", "l" to ")",
    "z" to "*", "x" to "\"", "c" to "'", "v" to ":", "b" to ";",
    "n" to "!", "m" to "?",
)

private val SYMBOL_ROWS = listOf(
    "1234567890".map { it.toString() },
    listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\""),
    listOf(".", ",", "?", "!", "'", "#", "%", "*", "+", "="),
)
