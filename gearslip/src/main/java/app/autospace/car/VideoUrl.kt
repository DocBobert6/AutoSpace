package app.autospace.car

/**
 * Pure URL classification shared by the browser's media finder and the video
 * player (Phase 3.2/3.3), so the two can never disagree about what is playable.
 *
 * Deliberately free of Media3 types: this file is unit-tested on the JVM, where
 * android.net.Uri is stubbed, so the player maps [mimeTypeFor] to a Media3
 * MimeTypes constant itself. Playable means: a direct video/audio file, or an
 * HLS/DASH manifest. A YouTube watch/shorts URL, a blob:/data: URL, and an
 * extensionless page are NOT playable — the finder never offers them, and the
 * player never receives one through the finder. No DRM, no extraction, no
 * downloader: a DRM manifest fails in the player with a plain error message.
 */
object VideoUrl {

    enum class Kind { HLS, DASH, VIDEO, AUDIO }

    private val VIDEO_EXTS = setOf("mp4", "webm", "mkv", "mov", "avi")
    private val AUDIO_EXTS = setOf("mp3", "m4a", "wav", "ogg", "oga", "flac", "aac")

    /** Extension of the URL's path, lowercased, without query or fragment. */
    fun extensionOf(raw: String): String {
        val path = raw.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        return if ('.' in last) last.substringAfterLast('.').lowercase() else ""
    }

    fun kindOf(raw: String): Kind? {
        if (raw.startsWith("blob:") || raw.startsWith("data:") || raw.startsWith("javascript:")) return null
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) return null
        return when (extensionOf(raw)) {
            "m3u8" -> Kind.HLS
            "mpd" -> Kind.DASH
            in VIDEO_EXTS -> Kind.VIDEO
            in AUDIO_EXTS -> Kind.AUDIO
            else -> null
        }
    }

    /** True when the finder may list this URL and the player may open it. */
    fun isPlayableUrl(raw: String): Boolean = kindOf(raw) != null

    /** The manifest MIME type for HLS/DASH, null for direct files (ExoPlayer infers). */
    fun mimeTypeFor(raw: String): String? = when (kindOf(raw)) {
        Kind.HLS -> "application/x-mpegURL"
        Kind.DASH -> "application/dash+xml"
        else -> null
    }

    /** Short label for the finder sheet, e.g. "HLS", "Video", "Audio". */
    fun labelFor(raw: String): String = when (kindOf(raw)) {
        Kind.HLS -> "HLS"
        Kind.DASH -> "DASH"
        Kind.VIDEO -> "Video"
        Kind.AUDIO -> "Audio"
        null -> "Media"
    }
}
