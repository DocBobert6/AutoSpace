package app.autospace

/**
 * What changed in AutoSpace, newest first, shown on the car screen
 * after an update (and on the phone). Only the changes a driver
 * would notice. Plain words, short lines: this is read in a parked
 * car.
 *
 * This list is AutoSpace's own: the upstream Gearslip notes that
 * used to sit here described another app's releases, so they were
 * replaced (2026-10-25) with what this fork actually ships.
 */
object Changelog {

    class Release(val versionCode: Int, val versionName: String, val changes: List<String>)

    val releases = listOf(
        Release(
            15, "0.4.4-alpha",
            listOf(
                "Car keyboard typing fixed: rolling taps on a key now type the letter instead of dropping it, slow downward rolls no longer insert a shortcut character, and the page search box follows the page's own edits instead of pushing stale text back over them. The passenger gate also waits for sustained motion and never pops up over an active typing session.",
            ),
        ),
        Release(
            14, "0.4.3-alpha",
            listOf(
                "Map chrome can now be frosted: an experimental switch gives the map pane, turn card, trip bar, and edge buttons the same frosted look as the floating media card, so the map shows through faintly.",
            ),
        ),
        Release(
            13, "0.4.2-alpha",
            listOf(
                "Calls on speaker hold better: the speaker setting is re-applied just after a call starts, and the call's audio route is now logged so speaker problems can be diagnosed from a trace.",
            ),
        ),
        Release(
            12, "0.4.1-alpha",
            listOf(
                "Fixed voice listening going silent until the app was restarted: a listen that gets stuck now times out and recovers on its own.",
            ),
        ),
        Release(
            11, "0.4.0-alpha",
            listOf(
                "First release build: updates now arrive through the app itself, and the first-run guide covers updates, AI answers, and the experimental features.",
            ),
        ),
        Release(
            10, "0.3.01-alpha",
            listOf(
                "AutoSpace is a fork of Gearslip, with a rebuilt look: Frost, Material You and plain styles, in light and dark, with a pure-black Pitch dark mode.",
                "The launcher is a flat alphabetical grid with a pinned row, drag-to-reorder, and sliders for icon, tile and overall size.",
                "The browser searches with no-AI DuckDuckGo, and has tabs, saved pages and history, a strict popup blocker, and a media finder that plays a page's video and audio in AutoSpace's own player.",
                "The video player has speed, sleep timer, audio tracks, subtitles and quality choices, pinch zoom and pan, and a passenger gate: video plays while driving only once you confirm you're a passenger, otherwise you hear the audio.",
                "Screen sharing fills or fits the car screen, with pinch zoom, a player-style chrome row, and exits that never trap you: the car's Back button, the exit button, or the phone's Stop.",
                "A 'Maps (mirrored)' tile mirrors Google Maps from the phone to the car, with touch.",
                "Resolution follows what your car actually offers: 720p, 1080p, 2K or 4K, falling back to 1080p if the higher size fails.",
                "Brightness is two taps away, from the nav bar or the dashboard, and dims the whole car screen.",
                "AutoSpace speaks with Kokoro-82M, entirely on the phone — 53 voices, American Adam by default — with Piper as fallback. No voice ships in the app; download one once in Settings.",
                "It listens with Kroko live words and Parakeet final words, and answers to 'Hey AutoSpace' hands-free. A broken car microphone can fall back to the phone's.",
                "Voice commands open apps, navigate, play music, read and reply to messages, and change brightness and volume. Say 'say that again' to hear the last reply repeated.",
                "AI answers now use the current stable Flash model, so they keep working for new keys.",
            "AI answers now use Groq by default, with Gemini kept as an experimental option you can switch back to.",
                "Subtitles in the video player follow the style you pick in the car's Settings.",
                "Voice downloads resume where they stopped instead of starting over, and a download that fails names the file and the reason.",
                "Color schemes are palettes now; light and dark is its own control, and the old scheme choices carry over exactly.",
                "The car preview on the phone has its own settings: screen size, density, theme, touch markers, simulated driving, and a test mode that fakes a car session on the couch.",
                "Crash reports now write themselves to Downloads/AutoSpace, so a failure leaves a readable record.",
            ),
        ),
    )

    /** The release that matches this build, if it has notes. */
    val current: Release? get() = releases.firstOrNull { it.versionCode == BuildConfig.VERSION_CODE }
}
