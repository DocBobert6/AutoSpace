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
            36, "0.4.25-alpha",
            listOf(
                "Update status shows on the phone's main page, next to What's new.",
            ),
        ),
        Release(
            35, "0.4.24-alpha",
            listOf(
                "The assistant's listening glow can span the full width of the screen, as an experimental option.",
            ),
        ),
        Release(
            34, "0.4.23-alpha",
            listOf(
                "Floating bars and panels cast a softer shadow over the map.",
            ),
        ),
        Release(
            33, "0.4.22-alpha",
            listOf(
                "Meridian map chrome is now a single switch in About & experimental.",
                "That one switch turns on the whole Meridian look: turn card, trip pill, map panes, and edge controls.",
                "Meridian panes are dark with white text, and the Go button is green.",
            ),
        ),
        Release(
            32, "0.4.21-alpha",
            listOf(
                "Album art on the full Now Playing screen is bigger.",
            ),
        ),
        Release(
            31, "0.4.20-alpha",
            listOf(
                "Map chrome is now called Meridian, and it is experimental: turn on experimental features to use it.",
                "Themed is the map chrome default again.",
                "In Meridian mode, the turn card now shows the distance directly over the street name.",
            ),
        ),
        Release(
            30, "0.4.19-alpha",
            listOf(
                "Autoplay on connect now skips a media app you have not used in a while, instead of starting it anyway.",
                "If you keep stopping autoplay right after it starts, the car now offers to turn autoplay off in one tap.",
            ),
        ),
        Release(
            29, "0.4.18-alpha",
            listOf(
                "Map chrome in Appearance: Google recreation matches Google's navigation look with a solid green turn card and a dark trip pill, and it is the default.",
                "Themed map chrome keeps the turn card and trip pill in the current theme's colors and frosted treatment.",
            ),
        ),
        Release(
            28, "0.4.17-alpha",
            listOf(
                "The media card now shows a soft, blurred version of the album art behind it.",
            ),
        ),
        Release(
            27, "0.4.16-alpha",
            listOf(
                "Widgets stay up while navigating now, instead of hiding whenever the map's own guidance or panels appear: the column moves to the passenger side so the turn card stays clear.",
                "The column still hides for full-screen screens like search, where there is no corner for it, and it still hides while driving until you glance at it with the nav-bar button.",
            ),
        ),
        Release(
            26, "0.4.15-alpha",
            listOf(
                "Widget controls, experimental: a nav-bar button brings the widget column up, the column hides while driving until you glance at it, and each widget can be hidden on its own.",
                "When widgets are on but nothing shows, the column now says why instead of vanishing silently.",
            ),
        ),
        Release(
            25, "0.4.14-alpha",
            listOf(
                "The assistant keeps listening for a few seconds after it answers, so you can keep talking without waking it again.",
                "The overlay shows it is still listening while the window is open; stay quiet and it closes quietly on its own.",
            ),
        ),
        Release(
            24, "0.4.13-alpha",
            listOf(
                "Floating surfaces are a little more see-through throughout: map chrome, the floating media card, widget cards, and the floating nav bar now sit at a lighter frost level, so the map reads through more.",
                "Buttons and tiles on the nav bar stay slightly less see-through than the bar itself, so they keep standing out against it.",
            ),
        ),
        Release(
            23, "0.4.12-alpha",
            listOf(
                "The assistant answers faster: replies now speak sentence by sentence as they arrive instead of waiting for the whole answer, and the reply text appears on screen as it streams.",
                "The assistant connection and voice warm up while you talk, so less of each answer is dead time.",
            ),
        ),
        Release(
            22, "0.4.11-alpha",
            listOf(
                "Map panes, dialogs, and content cards now follow the button style's corners everywhere, so Pitlane's square corners apply consistently instead of only in some places.",
                "Map header buttons now use the same see-through frosted treatment as the rest of the map chrome, in neutral colors, with the search magnifier allowed its vibrant color.",
                "The onboarding Experimental page now lists the frosted map chrome switch, so a fresh install sees all three floating-look extras.",
            ),
        ),
        Release(
            21, "0.4.10-alpha",
            listOf(
                "A new \"All experimental features\" switch at the top of the experimental settings turns on the whole floating look at once: floating media, home widgets, and frosted map chrome. Turning it off turns floating media and home widgets back off; vehicle data stays its own switch at the bottom of the list and is never touched by the master.",
            ),
        ),
        Release(
            20, "0.4.9-alpha",
            listOf(
                "Glide typing fixed in the app launcher: tracing a word no longer scrolls the keyboard away before you lift, so glides insert on lift when confident and offer strip candidates when weak, as they should. Tap typing, flick and long-press hints are unchanged.",
            ),
        ),
        Release(
            19, "0.4.8-alpha",
            listOf(
                "Music player redesigned toward the video player's layout: Previous, 10-second skip back and forward, play, and next in one centred cluster, with the mode row beneath and the seek bar last at the bottom. The seek bar is now waveform-styled bars, and the progress tint and play button pick up the album art's color when the cover has one, falling back to the scheme color when it does not. The play button also morphs shape when play and pause swap. No swipe between sources, and the floating card is unchanged.",
            ),
        ),
        Release(
            18, "0.4.7-alpha",
            listOf(
                "Glide typing on the car keyboard: trace a word across the keys without lifting and it inserts on lift when the trace is confident, or offers candidates on the strip when it is weak. A thin trace line follows your finger (its own switch), and the strip previews the live best guess while you trace. Tap typing, flick and long-press hints all work exactly as before below three keys.",
            ),
        ),
        Release(
            17, "0.4.6-alpha",
            listOf(
                "Car keyboard typing fixed for good: fast typing no longer overwrites recent letters, and one tap can no longer wipe a whole search — every key now appends to the current text. Every keyboard field also shows a caret line at the typing position, so it is always visible where the next character will land.",
            ),
        ),
        Release(
            16, "0.4.5-alpha",
            listOf(
                "New Verve look: a vivid violet color scheme and a rounder button style, each picked on its own in Appearance. Switches spring softly when parked and snap instantly while driving, and cards take Verve's rounder corner.",
            ),
        ),
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
