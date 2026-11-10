package app.autospace.car

import android.content.Context
import app.autospace.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * r220: durable AI memory, the agreed shape only (open-decisions item 8,
 * Robert 2026-11-10). Opt-in, default OFF, on-phone only. When on, the
 * assistant remembers two kinds of durable fact the user states — where
 * home is, and their preferred name — plus a short summary of recent
 * conversations (turn count, last-seen instant, places mentioned; never
 * full text). Facts are visible in Settings > AI assistant and deletable
 * one by one or all at once. Turning memory off deletes everything
 * remembered.
 *
 * What this is NOT: no always-on variant, no transcript storage, no
 * cloud or network sync, no recording of emergency confirmations or
 * proposals (the r73 rule stands), and memory never supplies, confirms,
 * or substitutes for an emergency decision — the emergency guard is
 * untouched. Facts are never sent to any AI provider: they are used
 * only by local commands (answering "what's my name", "where is home",
 * "what do you remember") and by the local weather branch ("weather at
 * home"). The in-memory session (r117) is unchanged: memory is a
 * durable layer beside it, not a replacement — a lone one-shot command
 * still never starts a session.
 *
 * Storage follows the BookAutoShelf pattern: plain strings in the shared
 * AppSettings prefs file (no new CarSettings KEY_, so SettingsHygieneTest
 * is untouched). The codecs are pure Kotlin (org.json is a stub on the
 * JVM test classpath); malformed input reads empty, never a crash.
 */
object AiMemory {

    const val KEY_ENABLED = "car_ai_memory_enabled"
    const val KEY_FACTS = "car_ai_memory_facts"
    const val KEY_SUMMARY = "car_ai_memory_summary"

    /** Places kept in the summary: most recent first. */
    const val MAX_SUMMARY_PLACES = 5

    enum class FactKind { HOME_PLACE, NAME }

    data class Fact(val kind: FactKind, val value: String)

    data class Summary(val turns: Int, val lastMs: Long, val places: List<String>) {
        companion object {
            val EMPTY = Summary(0, 0L, emptyList())
        }
    }

    private val enabledFlow = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = enabledFlow.asStateFlow()
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        enabledFlow.value = AppSettings.getString(context, KEY_ENABLED, "false") == "true"
    }

    fun isEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return enabledFlow.value
    }

    /**
     * Turns durable memory on or off. Turning it OFF deletes everything
     * remembered — facts and summary alike — so opting out leaves no
     * silent store behind; the settings toggle says so plainly.
     */
    fun setEnabled(context: Context, on: Boolean) {
        ensureLoaded(context)
        val appContext = context.applicationContext
        if (!on) {
            AppSettings.putString(appContext, KEY_FACTS, "")
            AppSettings.putString(appContext, KEY_SUMMARY, "")
        }
        AppSettings.putString(appContext, KEY_ENABLED, on.toString())
        enabledFlow.value = on
    }

    fun facts(context: Context): List<Fact> =
        decodeFacts(AppSettings.getString(context.applicationContext, KEY_FACTS, ""))

    fun homePlace(context: Context): String? =
        facts(context).firstOrNull { it.kind == FactKind.HOME_PLACE }?.value

    fun preferredName(context: Context): String? =
        facts(context).firstOrNull { it.kind == FactKind.NAME }?.value

    fun summary(context: Context): Summary =
        decodeSummary(AppSettings.getString(context.applicationContext, KEY_SUMMARY, ""))

    fun deleteFact(context: Context, kind: FactKind) {
        val kept = facts(context).filter { it.kind != kind }
        AppSettings.putString(context.applicationContext, KEY_FACTS, encodeFacts(kept))
    }

    fun clearAll(context: Context) {
        AppSettings.putString(context.applicationContext, KEY_FACTS, "")
        AppSettings.putString(context.applicationContext, KEY_SUMMARY, "")
    }

    /**
     * Learns one durable fact from a normalized user utterance, when
     * memory is on. Returns the fact learned, or null when the
     * utterance states no fact. One fact per kind: the latest wins.
     */
    fun learnFact(context: Context, normalized: String): Fact? {
        if (!isEnabled(context)) return null
        val fact = extractFact(normalized) ?: return null
        val kept = facts(context).filter { it.kind != fact.kind } + fact
        AppSettings.putString(context.applicationContext, KEY_FACTS, encodeFacts(kept))
        return fact
    }

    /**
     * Notes one completed assistant turn in the summary: the turn count
     * grows, the last-seen instant moves, and a named place (other than
     * "me" or "home") joins the recent-places list. No conversation text
     * is stored — only the count, the instant, and the place names.
     */
    fun noteTurn(context: Context, normalized: String, nowMs: Long = System.currentTimeMillis()) {
        if (!isEnabled(context)) return
        val prev = summary(context)
        val place = AiAssistant.placePhrase(normalized)?.takeIf { it != "me" && it != "home" }
        val places = if (place == null) prev.places
            else (listOf(place) + prev.places.filter { it != place }).take(MAX_SUMMARY_PLACES)
        val next = Summary(prev.turns + 1, nowMs, places)
        AppSettings.putString(context.applicationContext, KEY_SUMMARY, encodeSummary(next))
    }

    /** One completed AI turn: learn what it states, then note it in the summary. */
    fun learnFromAiTurn(context: Context, normalized: String) {
        learnFact(context, normalized)
        noteTurn(context, normalized)
    }

    // ---- Pure helpers: extraction, weather-at-home, summary text, codecs. ----

    private val NAME_STOPWORDS = setOf(
        "a", "an", "the", "when", "if", "later", "back", "again", "now", "today", "tomorrow",
    )

    /**
     * The durable fact a normalized utterance states, or null. Home:
     * "my home is in Dry Ridge", "I live in Cincinnati", "home is in
     * Florence" — the place comes from the same [AiAssistant.placePhrase]
     * the session place rule uses, so time words are stripped the same
     * way. Name: "my name is Robert", "call me Bob" — the first word
     * only, and only when the utterance is short and the word is not a
     * stopword, so "call me a taxi" and "call me when you get home"
     * never store "a" or "when" as a name. Anything else — "navigate
     * home", "weather in Cincinnati", "call mom" — states no fact.
     */
    fun extractFact(normalized: String): Fact? {
        val t = normalized.trim()
        if (t.startsWith("my home is in ") || t.startsWith("i live in ") || t.startsWith("home is in ")) {
            val place = AiAssistant.placePhrase(t) ?: return null
            if (place == "me" || place == "home") return null
            return Fact(FactKind.HOME_PLACE, place)
        }
        val nameMatch = Regex("""^(?:my name is|call me)\s+([a-z][a-z'\-]*)""").find(t)
        if (nameMatch != null) {
            val words = t.split(Regex("\\s+"))
            if (words.size > 5) return null
            val first = nameMatch.groupValues[1]
            if (first in NAME_STOPWORDS) return null
            return Fact(FactKind.NAME, first)
        }
        return null
    }

    /**
     * The home place a weather question asks for, or null. Only when the
     * question is a local-weather question whose place is exactly "home"
     * ("weather at home", "how humid is it at home") — a bare question,
     * a named place, and "home depot" all read null, so the shared place
     * resolution decides them exactly as before.
     */
    fun homeWeatherPlace(normalized: String, homePlace: String?): String? {
        if (homePlace.isNullOrBlank()) return null
        if (!AiAssistant.isLocalWeatherQuestion(normalized)) return null
        if (AiAssistant.placePhrase(normalized) != "home") return null
        return homePlace
    }

    /** The spoken answer to "what do you remember": facts first, then the turn count. */
    fun summarize(facts: List<Fact>, summary: Summary): String {
        if (facts.isEmpty() && summary.turns == 0) return "I don't remember anything yet."
        val parts = mutableListOf<String>()
        facts.firstOrNull { it.kind == FactKind.HOME_PLACE }?.let {
            parts += "Home is in ${it.value}."
        }
        facts.firstOrNull { it.kind == FactKind.NAME }?.let {
            parts += "Your name is ${it.value}."
        }
        if (summary.turns > 0) {
            parts += "From ${summary.turns} ${if (summary.turns == 1) "turn" else "turns"}."
        }
        return parts.joinToString(" ")
    }

    // ---- Codecs: pure Kotlin, malformed input reads empty, never a crash. ----

    fun encodeFacts(facts: List<Fact>): String =
        facts.joinToString("\n") { "${it.kind.name}=${escapeValue(it.value)}" }

    fun decodeFacts(encoded: String): List<Fact> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split('\n').mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val kind = FactKind.entries.firstOrNull { it.name == line.substring(0, eq) } ?: return@mapNotNull null
            val value = unescapeValue(line.substring(eq + 1)).trim()
            if (value.isEmpty()) null else Fact(kind, value)
        }
    }

    fun encodeSummary(summary: Summary): String =
        "${summary.turns}|${summary.lastMs}|${summary.places.joinToString(",")}"

    fun decodeSummary(encoded: String): Summary {
        if (encoded.isBlank()) return Summary.EMPTY
        val parts = encoded.split('|')
        if (parts.size != 3) return Summary.EMPTY
        val turns = parts[0].toIntOrNull() ?: return Summary.EMPTY
        val lastMs = parts[1].toLongOrNull() ?: return Summary.EMPTY
        val places = parts[2].split(',').filter { it.isNotBlank() }.take(MAX_SUMMARY_PLACES)
        return Summary(turns.coerceAtLeast(0), lastMs.coerceAtLeast(0L), places)
    }

    private fun escapeValue(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '=' -> append("\\=")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
    }

    private fun unescapeValue(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> append('\\')
                    '=' -> append('=')
                    'n' -> append('\n')
                    else -> { append('\\'); append(s[i + 1]) }
                }
                i += 2
            } else {
                append(c)
                i += 1
            }
        }
    }
}
