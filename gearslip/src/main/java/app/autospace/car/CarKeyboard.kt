package app.autospace.car

import app.autospace.car.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.coerceIn
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import app.autospace.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

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
 *
 * r30 (Gboard look, inside the style system): a 44dp suggestion strip row on top of the keys
 * (down-arrow dismiss at its left end where Gboard puts its hide-keyboard chevron, up to 3
 * prefix candidates in the middle, copy/paste/settings-gear toolbar on the right), with the
 * strip's candidates drawn from learned words (on-device, never logged) plus a bundled
 * frequency wordlist. Strip off collapses the row to dismiss + gear only, so settings stay
 * reachable (a strip-off state with no gear would be a one-way door); strip off also returns
 * dismiss to the bottom row as a down-arrow in the same slot. The gear opens an inline
 * settings panel in place of the keys (no separate Dialog window, so it behaves identically
 * on Presentation displays) with strip on/off, clear-learned-words, and a pointer to the UI
 * size setting. Copy = whole field, paste = append at the end (there is no selection state
 * to do more with). Keys gain a crisp 1dp bottom edge (the Gboard keycap read) inside their
 * own clipped shape, and key heights round to whole dp — see the sharpness note at [keyHeight].
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

    // r30: strip state, word learning, and the inline settings panel. The
    // boundary watch learns completed words from BOTH car keys and phone
    // typing, since both flow through onTextChange into [text].
    val context = LocalContext.current
    var stripEnabled by remember { mutableStateOf(KeyboardSuggestions.isStripEnabled(context)) }
    var showKeyboardSettings by remember { mutableStateOf(false) }
    var learned by remember { mutableStateOf(KeyboardSuggestions.learnedWords(context)) }
    var clearedNotice by remember { mutableStateOf(false) }
    val wordlist = remember { KeyboardSuggestions.loadWordlist(context) }
    var prevText by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        KeyboardSuggestions.completedWord(prevText, text)?.let { word ->
            if (KeyboardSuggestions.learnWord(context, word)) {
                learned = KeyboardSuggestions.learnedWords(context)
            }
        }
        prevText = text
    }
    // Submit usually ends a word with no boundary character, so learn it here.
    val submitWithLearn = {
        KeyboardSuggestions.currentWord(text).takeIf { it.length >= 2 }?.let { word ->
            if (KeyboardSuggestions.learnWord(context, word.lowercase())) {
                learned = KeyboardSuggestions.learnedWords(context)
            }
        }
        onSubmit()
    }
    val word = KeyboardSuggestions.currentWord(text)
    val suggestions = if (stripEnabled && !showKeyboardSettings) {
        KeyboardSuggestions.candidates(word, learned, wordlist)
    } else {
        emptyList()
    }
    val clipboard = remember {
        runCatching {
            context.getSystemService(android.content.ClipboardManager::class.java)
        }.getOrNull()
    }

    CompositionLocalProvider(LocalKeyHeight provides keyHeight) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // r30: the strip row always leads (full row when the strip is
                // on, dismiss + gear only when it is off, so settings stay
                // reachable); the settings panel replaces the keys below it.
                if (stripEnabled) {
                    SuggestionStripRow(
                        word = word,
                        suggestions = suggestions,
                        title = if (showKeyboardSettings) "Keyboard settings" else null,
                        onDismiss = onDismiss,
                        onCandidate = { candidate ->
                            onTextChange(KeyboardSuggestions.replaceCurrentWord(text, candidate))
                        },
                        onCopy = {
                            if (text.isNotEmpty()) {
                                runCatching {
                                    clipboard?.setPrimaryClip(
                                        android.content.ClipData.newPlainText("Text", text)
                                    )
                                }
                            }
                        },
                        onPaste = {
                            val clip = runCatching {
                                clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                            }.getOrNull()
                            onTextChange(KeyboardSuggestions.pasteResult(text, clip))
                        },
                        onSettings = { showKeyboardSettings = !showKeyboardSettings },
                    )
                } else {
                    MinimalStripRow(
                        onDismiss = onDismiss,
                        onSettings = { showKeyboardSettings = !showKeyboardSettings },
                    )
                }
                if (showKeyboardSettings) {
                    KeyboardSettingsPanel(
                        stripEnabled = stripEnabled,
                        onStripChange = { enabled ->
                            KeyboardSuggestions.setStripEnabled(context, enabled)
                            stripEnabled = enabled
                        },
                        clearedNotice = clearedNotice,
                        onClearLearned = {
                            KeyboardSuggestions.clearLearnedWords(context)
                            learned = emptyList()
                            clearedNotice = true
                        },
                        onDone = { showKeyboardSettings = false },
                    )
                } else {
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
                // wide space, period, then submit in the bottom-right corner,
                // where a thumb expects it. Comma and period are letters-mode
                // only (the symbols layer already carries them) and, like
                // space, do not consume the shift. Gboard proportions
                // (measured 2026-10-25): symbols ~1.4, comma 1, space ~3.7,
                // period 1, submit 1.6. No backspace here: it stays with the
                // letters above, not next to the key most likely to be hit
                // right after it by mistake. r30: dismiss left this row for
                // the strip (or, with the strip off, returns here as a
                // down-arrow in the same slot); the freed weight widens the
                // space bar by redistribution.
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
                    if (!stripEnabled && onDismiss != null) {
                        DismissKey(onDismiss, Modifier.weight(1f))
                    }
                    SubmitKey(Modifier.weight(1.6f), submitIcon, submitImage, submitWithLearn)
                }
                } // !showKeyboardSettings
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
        shape = gsButtonShape(RoundedCornerShape(12.dp)),
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
        // r30: the Gboard keycap read — a crisp bottom edge inside the key's
        // own clipped shape (see [KeyEdge]).
        KeyEdge(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
    }
}

/**
 * A letter/symbol grid key with three mutually exclusive insert paths, owned by one gesture
 * stream so no press can ever insert twice: tap inserts the letter, long-press inserts the
 * hint, and a quick swipe DOWN inserts the hint too ("keyboard swipe-down shortcut").
 * [KeyGestureTracker] decides; this composable only feeds it pointer events and fires the
 * one outcome it returns. A swipe is recognised only while a hint exists, only before
 * the long-press timeout, and only as a quick flick — a slow downward roll is a tap,
 * not a swipe, and a slow drag that crosses the timeout is a long-press, not a
 * swipe, so the paths can never both fire on one press. After either shortcut fires,
 * the rest of the press is drained (consumed) until lift, so no tap follows it. A press
 * is cancelled only when the finger actually leaves the key (r47: the old
 * past-threshold cancel dropped ordinary rolling taps on car screens); a lift whose
 * event was consumed elsewhere is a cancellation too, never a tap. Symbol keys
 * carry no hints, so their moves are not tracked and they always tap on lift,
 * exactly as before. Pressed feedback reuses the theme's own keycap surface
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
    val gestureScope = rememberCoroutineScope()
    // r47: the key's own measured size, so the tracker cancels only when the
    // finger truly leaves the key — not on an in-key roll. Zero (unmeasured)
    // reads as inside: layout always precedes input, so this is only a guard.
    var keySize by remember { mutableStateOf(IntSize.Zero) }
    val colors = GsColors(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface)
    val shape = gsButtonShape(RoundedCornerShape(12.dp))
    Box(
        modifier
            .height(LocalKeyHeight.current)
            .onSizeChanged { keySize = it }
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
                            timeoutJob = gestureScope.launch {
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
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                            if (!change.pressed) {
                                gestureDone = true
                                timeoutJob?.cancel()
                                // A clean lift ends the press as a tap
                                // (tracker decides). A lift whose event was
                                // already consumed elsewhere — another
                                // detector or a torn-down stream — is a
                                // cancellation, never a tap: the old code
                                // inserted a phantom letter here (notably
                                // when the passenger gate popped mid-press).
                                outcome = if (change.isConsumed) {
                                    tracker.onCancel()
                                    KeyPressOutcome.NONE
                                } else {
                                    tracker.onUp()
                                }
                                if (outcome == KeyPressOutcome.TAP) onLetter()
                                break
                            }
                            val dy = change.position.y - down.position.y
                            val dx = change.position.x - down.position.x
                            val elapsed = change.uptimeMillis - down.uptimeMillis
                            // r47: cancel only on actually leaving the key.
                            val pos = change.position
                            val insideKey = keySize == IntSize.Zero ||
                                (pos.x >= 0f && pos.x <= keySize.width.toFloat() &&
                                    pos.y >= 0f && pos.y <= keySize.height.toFloat())
                            val swipe = if (hint != null && onShortcut != null) tracker.onMove(dy, dx, elapsed, insideKey) else null
                            if (swipe == KeyPressOutcome.SHORTCUT) {
                                gestureDone = true
                                timeoutJob?.cancel()
                                onShortcut?.invoke()
                                change.consume()
                                // Drain until lift; the lift itself inserts
                                // nothing further (the shortcut already did).
                                var lifted = false
                                while (!lifted) {
                                    val drain = awaitPointerEvent(PointerEventPass.Main)
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
        // r30: the Gboard keycap read (see [KeyEdge]).
        KeyEdge(colors.container)
    }
}

/** One outcome per press, ever. NONE means "already inserted (shortcut) or cancelled". */
enum class KeyPressOutcome { TAP, SHORTCUT, NONE }

/**
 * Pure gesture decision for [LetterKey]: tap vs long-press vs swipe-down shortcut vs
 * cancelled. No Compose, no time source of its own — the caller feeds displacements and
 * elapsed times, so every path is unit-testable.
 *
 * Rules (r47 — the car-typing fix; the old cancel rule is what broke real typing):
 * a swipe-down is dy past threshold with dy greater than |dx|, recognised only
 * before the long-press timeout AND only as a quick flick ([flickWindowMs]) — a
 * slow downward roll of the finger is a press, not a swipe, so it still taps;
 * reaching the timeout while still down (and not cancelled) is a long-press
 * shortcut; lifting before either, without leaving the key, is a tap. A press
 * is cancelled ONLY when the finger actually leaves the key ([insideKey] false):
 * the old rule cancelled on any move past threshold upward or sideways, and on
 * a car screen — coarse touch, a moving car, scaled preview coordinates — an
 * ordinary tap rolls that far inside the key, so letters silently vanished.
 * Once a shortcut has fired, later events return null — the caller must not
 * insert again. A cancelled press stays cancelled even if the finger comes
 * back onto the key before lifting.
 */
class KeyGestureTracker(
    private val swipeThresholdPx: Float,
    private val longPressTimeoutMs: Long,
    /** A deliberate flick crosses the threshold fast; a press-roll does not. */
    private val flickWindowMs: Long = 300L,
) {
    private var fired = false
    private var cancelled = false

    fun onDown() {
        fired = false
        cancelled = false
    }

    /**
     * Returns SHORTCUT the instant a swipe-down flick is recognised, else null.
     * [insideKey] is the caller's read of whether the pointer is still over
     * the key (its own measured bounds); leaving the key cancels the press.
     */
    fun onMove(dy: Float, dx: Float, elapsedMs: Long, insideKey: Boolean = true): KeyPressOutcome? {
        if (fired || cancelled) return null
        if (!insideKey) {
            cancelled = true
            return null
        }
        if (elapsedMs >= longPressTimeoutMs) return null // timeout path owns it past here
        if (elapsedMs < flickWindowMs && dy > swipeThresholdPx && dy > abs(dx)) {
            fired = true
            return KeyPressOutcome.SHORTCUT
        }
        return null
    }

    /** Called when the long-press timeout elapses while still down. */
    fun onTimeout(): KeyPressOutcome? {
        if (fired || cancelled) return null
        fired = true
        return KeyPressOutcome.SHORTCUT
    }

    /** Called when the press is cancelled (consumed lift, stream torn down). */
    fun onCancel() {
        cancelled = true
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
        shape = gsButtonShape(RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Hide keyboard")
        }
        // r30: down-arrow (was X) + the Gboard keycap read (see [KeyEdge]).
        KeyEdge(MaterialTheme.colorScheme.surfaceContainerHighest)
    }
}

@Composable
private fun SubmitKey(modifier: Modifier, search: Boolean, image: ImageVector?, onClick: () -> Unit) {
    GsIconBox(
        onClick = onClick,
        modifier = modifier.height(LocalKeyHeight.current),
        colors = GsColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary),
        shape = gsButtonShape(RoundedCornerShape(12.dp)),
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
        // r30: the Gboard keycap read (see [KeyEdge]); submit stays primary by construction.
        KeyEdge(MaterialTheme.colorScheme.primary)
    }
}

/**
 * r30: the suggestion strip row — [onDismiss] chevron at the left end (Gboard's
 * hide-keyboard place), candidates in the middle, copy/paste/settings toolbar on
 * the right. All buttons are 44dp targets; the row itself is 44dp tall. When
 * [title] is set (settings panel open) it replaces the candidates.
 */
@Composable
private fun SuggestionStripRow(
    word: String,
    suggestions: List<String>,
    title: String?,
    onDismiss: (() -> Unit)?,
    onCandidate: (String) -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(44.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onDismiss != null) {
            StripToolButton(onDismiss, "Hide keyboard") {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
        }
        Row(
            Modifier.weight(1f).fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                suggestions.forEach { candidate ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .clip(gsButtonShape(RoundedCornerShape(12.dp)))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { onCandidate(candidate) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            KeyboardSuggestions.matchCase(word, candidate),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        StripToolButton(onCopy, "Copy") { Icon(Icons.Filled.ContentCopy, contentDescription = null) }
        StripToolButton(onPaste, "Paste") { Icon(Icons.Filled.ContentPaste, contentDescription = null) }
        StripToolButton(onSettings, "Keyboard settings") { Icon(Icons.Filled.Settings, contentDescription = null) }
    }
}

/**
 * r30: the strip-off row — dismiss + gear only. The gear MUST stay reachable
 * with the strip off, or turning the strip off from the keyboard would be a
 * one-way door (no other screen offers keyboard settings); copy/paste hide
 * with the strip, as specified.
 */
@Composable
private fun MinimalStripRow(
    onDismiss: (() -> Unit)?,
    onSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(44.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onDismiss != null) {
            StripToolButton(onDismiss, "Hide keyboard") {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
        }
        Spacer(Modifier.weight(1f))
        StripToolButton(onSettings, "Keyboard settings") { Icon(Icons.Filled.Settings, contentDescription = null) }
    }
}

@Composable
private fun StripToolButton(
    onClick: () -> Unit,
    description: String,
    icon: @Composable () -> Unit,
) {
    GsIconBox(
        onClick = onClick,
        modifier = Modifier.width(44.dp).height(44.dp),
        colors = GsColors(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurface,
        ),
        shape = gsButtonShape(RoundedCornerShape(12.dp)),
    ) {
        // The description is the semantics label; the icon itself carries none
        // so screen readers announce it exactly once.
        icon()
    }
}

/**
 * r30: keyboard settings as an inline panel in place of the keys (NOT a
 * separate Dialog window — a Dialog created from a Presentation context
 * risks landing on the wrong display, while this panel is just more keyboard
 * Column content, so it behaves identically on every car surface). The switch
 * row follows the app's settings-page precedent: the switch itself is the
 * target, not the whole row.
 */
@Composable
private fun KeyboardSettingsPanel(
    stripEnabled: Boolean,
    onStripChange: (Boolean) -> Unit,
    clearedNotice: Boolean,
    onClearLearned: () -> Unit,
    onDone: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Suggestions", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Show word suggestions above the keys",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            GsSwitch(stripEnabled, onStripChange)
        }
        GsButton(onClick = onClearLearned, tone = GsTone.Neutral, modifier = Modifier.fillMaxWidth()) {
            Text("Clear learned words")
        }
        if (clearedNotice) {
            Text(
                "Learned words cleared.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        Text(
            "Key size follows the UI size setting (Settings > Appearance).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        GsButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
    }
}

/**
 * r30 sharpness, part 2 (part 1 is whole-dp [keyHeight]): the evidence for
 * why the keyboard read soft while the rest of the UI read sharp. Key labels
 * are vector text and key shapes are clip-crisp, so no label or shape is
 * itself blurry — but under Frost (the default style) every key also draws a
 * 6dp blurred halo (drawSoftShadow: blur = 2x elevation, alpha 0.30), a
 * top-light gradient, and a 0.97-alpha fill over a board only one surface
 * step darker. The rest of the UI is flat fills with hard edges, so the
 * keyboard's silhouette alone dissolved into its board — and the car's video
 * stream smears soft edges further (the shadow code's own comment says it is
 * "already soft enough that the car's video compression has nothing crisp
 * to smear"). The fix is therefore a hard silhouette, not a heavier font:
 * this 1dp bottom edge inside the key's own clipped shape — the Gboard
 * keycap read — gives every key a crisp lower edge whatever style's finish
 * sits behind it, while fills, surfaces, and label styles stay exactly as
 * specified. Verification is Robert's eye on the head unit, never a
 * screenshot claim.
 */
@Composable
private fun BoxScope.KeyEdge(container: Color) {
    val edge = if (container.luminance() > 0.5f) {
        Color.Black.copy(alpha = 0.20f)
    } else {
        Color.Black.copy(alpha = 0.35f)
    }
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(edge)
    )
}

/**
 * Big enough to hit on a bumpy road, small enough that four rows plus a field plus a few results
 * still fit. A 480-tall screen sets the floor; a taller one (LIVI's 1280x720) gets bigger keys.
 * r30 sharpness: heights round to whole dp — fractional row positions (e.g. 57.6dp on a
 * 640-tall screen) put every key edge and label on a subpixel boundary, which the car's
 * video stream then smears; whole-dp rows keep edges on pixel boundaries.
 */
private fun keyHeight(screenHeightDp: Int): Dp =
    (screenHeightDp * 0.09f).roundToInt().dp.coerceIn(44.dp, 64.dp)

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
