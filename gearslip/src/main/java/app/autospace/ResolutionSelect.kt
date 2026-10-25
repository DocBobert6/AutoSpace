package app.autospace

/**
 * Resolution selection, kept pure so unit tests can cover it without Android.
 *
 * Rules, in order: (1) an exact vehicle-profile resolution name wins when offered;
 * (2) a driver preference (720p/1080p/2k/4k) picks the largest offered landscape
 * config at or below its cap — never an invented config, and on a wireless link
 * 2k/4k are capped at 1080p first, because the wireless bitrate cap cannot feed
 * them; (3) when the preference names nothing offered at or below, or is "auto",
 * the caller's fit fallback decides. Every outcome carries a note for the
 * connection log and Settings, so a driver can see why a size was picked.
 */
data class OfferedConfig(val name: String, val width: Int, val height: Int) {
    val pixels: Int get() = width * height
    val landscape: Boolean get() = width >= height
}

data class ResolutionChoice(val index: Int, val note: String)

object ResolutionSelect {
    val CAPS = mapOf(
        "720p" to 1280 * 720,
        "1080p" to 1920 * 1080,
        "2k" to 2560 * 1440,
        "4k" to 3840 * 2160,
    )
    const val WIRELESS_CAP_PIXELS = 1920 * 1080

    /**
     * [fitFallback] maps the offered list to an index (the old fitToScreen choice)
     * or null. Returns null when nothing is offered at all.
     */
    fun select(
        offered: List<OfferedConfig>,
        exactName: String?,
        preference: String?,
        wireless: Boolean,
        fitFallback: (List<OfferedConfig>) -> Int?,
    ): ResolutionChoice? {
        if (offered.isEmpty()) return null
        exactName?.let { want ->
            val at = offered.indexOfFirst { it.name == want }
            if (at >= 0) return ResolutionChoice(at, "vehicle profile resolution $want")
        }
        val pref = preference?.lowercase()?.takeIf { it != "auto" }
        val cap0 = pref?.let { CAPS[it] }
        if (pref != null && cap0 != null) {
            var cap = cap0
            val capped = wireless && cap > WIRELESS_CAP_PIXELS
            if (capped) cap = WIRELESS_CAP_PIXELS
            val candidates = offered.withIndex()
                .filter { (_, c) -> c.landscape && c.pixels <= cap }
            val best = candidates.maxByOrNull { (_, c) -> c.pixels }
            if (best != null) {
                val note = buildString {
                    append("preference $pref -> ${best.value.name}")
                    if (capped) append(" (wireless link caps $pref at 1080p)")
                }
                return ResolutionChoice(best.index, note)
            }
            val fit = fitFallback(offered)
            if (fit != null) {
                return ResolutionChoice(fit, "preference $pref not offered at or below; using fit choice ${offered[fit].name}")
            }
        }
        val fit = fitFallback(offered)
        if (fit != null) return ResolutionChoice(fit, "fit choice ${offered[fit].name}")
        return ResolutionChoice(0, "first offered ${offered[0].name}")
    }
}
