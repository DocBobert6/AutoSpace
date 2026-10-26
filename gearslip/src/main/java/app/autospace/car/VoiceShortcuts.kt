package app.autospace.car

import android.content.Context
import app.autospace.AppSettings
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A user voice shortcut: one spoken keyword that stands for a saved target. */
enum class VoiceShortcutType { NAVIGATION, OPEN_APP, BROWSER_SEARCH }

data class VoiceShortcut(
    val keyword: String,
    val type: VoiceShortcutType,
    /** NAVIGATION: free-text address/place. OPEN_APP: the app's component id. BROWSER_SEARCH: search term. */
    val target: String,
    /** Display label for OPEN_APP entries (the app label at save time); blank otherwise. */
    val label: String = "",
)

/**
 * Custom voice keywords (2026-10-25, r19): a user table stored on-device only, plain text in
 * the same SharedPreferences [AppSettings] already uses — no contacts access, no cloud sync,
 * no new permission. Three types only in v1 (see [VoiceShortcutType]); matching is exact
 * only, never fuzzy, and [AssistantCommands] checks exact built-ins before these and its
 * synonym/fuzzy matcher after them. Only canonical type names are ever logged, never a
 * keyword, target, or raw heard phrase.
 */
object VoiceShortcuts {

    private const val KEY = "car_voice_shortcuts"
    const val MAX_ENTRIES = 50

    private val flow = MutableStateFlow<List<VoiceShortcut>>(emptyList())
    val shortcuts: StateFlow<List<VoiceShortcut>> = flow.asStateFlow()

    private var loaded = false

    /** Normalizes a keyword the way matching does: lowercased, trimmed, single spaces. */
    fun normalize(keyword: String): String =
        keyword.lowercase().trim().replace(Regex("\\s+"), " ")

    /** Reads the stored table once (later saves update the flow directly). Safe to call repeatedly. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        flow.value = decode(AppSettings.getString(context.applicationContext, KEY, ""))
    }

    /** Current entries, loading from [context] first if nothing has loaded yet. */
    fun entries(context: Context): List<VoiceShortcut> {
        ensureLoaded(context)
        return flow.value
    }

    fun findNavigation(context: Context, phrase: String): VoiceShortcut? {
        val wanted = normalize(phrase)
        return entries(context).firstOrNull { it.type == VoiceShortcutType.NAVIGATION && it.keyword == wanted }
    }

    /** Exact keyword match for the bare / "open <keyword>" / "search <keyword>" forms. */
    fun findKeyword(context: Context, phrase: String): VoiceShortcut? {
        val wanted = normalize(phrase)
        return entries(context).firstOrNull { it.keyword == wanted }
    }

    /**
     * Save-time validation. Returns a plain reason when the entry must be refused, null when
     * it may be saved. [existingIndex] is the entry being edited, if any, so an edit that
     * keeps its own keyword is not flagged as a duplicate.
     */
    fun validate(
        keywordRaw: String,
        targetRaw: String,
        current: List<VoiceShortcut>,
        existingIndex: Int? = null,
    ): String? {
        val keyword = normalize(keywordRaw)
        if (keyword.isEmpty()) return "Enter a keyword."
        if (targetRaw.trim().isEmpty()) return "Enter a target."
        if (keyword in RESERVED) return "“$keyword” is a built-in command word, so it can't be a shortcut."
        if (current.withIndex().any { (i, e) -> i != existingIndex && e.keyword == keyword }) {
            return "You already have a shortcut called “$keyword”."
        }
        if (existingIndex == null && current.size >= MAX_ENTRIES) {
            return "Too many voice shortcuts — the limit is $MAX_ENTRIES."
        }
        return null
    }

    /** Adds or replaces an entry; callers must have run [validate] first. Returns the new list. */
    fun save(context: Context, entry: VoiceShortcut, existingIndex: Int? = null): List<VoiceShortcut> {
        ensureLoaded(context)
        val next = flow.value.toMutableList()
        if (existingIndex != null && existingIndex in next.indices) next[existingIndex] = entry
        else next += entry
        persist(context, next)
        return next
    }

    fun delete(context: Context, index: Int) {
        ensureLoaded(context)
        val next = flow.value.toMutableList()
        if (index !in next.indices) return
        next.removeAt(index)
        persist(context, next)
    }

    private fun persist(context: Context, entries: List<VoiceShortcut>) {
        flow.value = entries.sortedBy { it.keyword }
        AppSettings.putString(context.applicationContext, KEY, encode(flow.value))
    }

    /** One line per entry: type, URL-encoded keyword, target, label — tabs/newlines in a target can't break it. */
    fun encode(entries: List<VoiceShortcut>): String = entries.joinToString("\n") { e ->
        listOf(e.type.name, enc(e.keyword), enc(e.target), enc(e.label)).joinToString("|")
    }

    fun decode(raw: String): List<VoiceShortcut> {
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 3) return@mapNotNull null
            val type = runCatching { VoiceShortcutType.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
            val keyword = normalize(dec(parts[1]))
            if (keyword.isEmpty()) return@mapNotNull null
            VoiceShortcut(keyword, type, dec(parts[2]).trim(), if (parts.size > 3) dec(parts[3]) else "")
        }.sortedBy { it.keyword }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /**
     * Words and phrases a keyword must never be, because an exact built-in command already
     * claims them (so the keyword would be unreachable) or because claiming them would be
     * dangerous (emergency, call, message). This is the save-time guard; the matching order
     * in [AssistantCommands] is the second guard — a built-in always wins even if a
     * conflicting keyword somehow reaches storage.
     */
    private val RESERVED: Set<String> = setOf(
        // The minimum set from the r19 brief.
        "emergency", "911", "call", "dial", "text", "message", "stop", "cancel", "hang up",
        "navigate", "search", "open",
        // Emergency + call/message handling (r9 guard untouched; these must never be shadowed).
        "call emergency services", "call 911", "call nine one one", "dial 911",
        "dial emergency services", "answer", "answer the call", "answer it", "end the call",
        "hang up the call", "text that", "read my messages", "read my notifications",
        "any messages", "check my messages", "read messages",
        // Assistant cancel / stop-speaking.
        "never mind", "cancel that", "stop speaking", "stop talking",
        // Navigation control, not destinations.
        "stop navigation", "cancel navigation", "end navigation", "stop navigating",
        "cancel the route", "end the route", "stop the route", "how far", "how much further",
        "how far to go", "when will i arrive", "when do we arrive", "eta", "arrival time",
        "repeat that", "say that again", "repeat the last direction", "repeat the direction",
        "what was that direction",
        // Media / volume / brightness exact commands.
        "play", "pause", "resume", "next", "previous", "mute", "unmute", "louder", "quieter",
        "volume up", "volume down", "turn it up", "turn it down", "brightness up",
        "brightness down", "mute the music", "unmute the music",
        // Help / exit / timer / info exact commands.
        "help", "commands", "voice commands", "list commands", "show commands",
        "exit", "quit", "exit gearslip", "close gearslip", "quit gearslip", "open exit",
        "go back", "back", "cancel timer", "cancel the timer",
        "what's my phone battery", "phone battery", "battery level",
        "how much battery", "how much battery is left",
        // Settings toggles by voice.
        "turn speak directions on", "turn spoken directions on", "turn speak directions off",
        "turn spoken directions off", "use phone mic", "use the phone microphone",
        "use phone microphone", "use car mic", "use the car microphone", "use car microphone",
        "start screen sharing", "start mirroring", "stop screen sharing", "stop mirroring",
        // Emergency-confirmation answers: only meaningful while a confirmation is pending.
        "yes", "yeah", "yep", "yup", "sure", "confirm", "do it", "no", "nope", "nah", "don't",
    )
}
