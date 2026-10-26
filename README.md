# AutoSpace

AutoSpace is an Android Auto alternative for the phone: a frosted-glass
Material 3 interface, a no-AI browser with its own media player, Vela
navigation, on-device spoken voice, an opt-in AI assistant, and custom
screen resolutions.

AutoSpace is a fork of [Gearslip](https://github.com/twinksoftware/Gearslip)
by Twink Software LLC, rebuilt around that interface and feature set. It is
licensed under AGPL-3.0 (see `LICENSE`). This fork is not affiliated with,
endorsed by, or sponsored by Twink Software LLC or Google LLC.

> Upstream baseline: twinksoftware/Gearslip commit
> `e8f51f4947d6edeb3d3e9dc52ae6598d867f677a` (0.3.01-alpha).
> AutoSpace's own changes are listed in `CHANGELOG.md`.

---

## What AutoSpace is

AutoSpace speaks the Android Auto protocol directly to a car head unit, over
USB or wirelessly, with no Google software in the path. It acts as a Car App
Library host: an app that ships a car interface hands AutoSpace a template,
and AutoSpace renders that template natively on the car screen. For apps
without a car interface, AutoSpace offers a separate screen-sharing mode
that mirrors the phone.

## Features

- **Car interface.** Frost, Flat, Tactile, Edge, and Pitlane control styles,
  with color schemes Neutral, Material You, Night, Pitlane, Circuit, and
  Heritage, an independent Light / Dark / System appearance mode, and
  Regular / Pitch dark surfaces (Pitch is pure black).
  A flat alphabetical launcher with a pinned row, drag-to-reorder, and
  sliders for icon, tile, and overall size. Quick brightness is two taps
  away, from the nav bar or the dashboard, and dims the whole car screen.
- **Phone calls.** Dial from a keypad, or search your contacts, then confirm
  before the call goes out. When a call comes in, answer or decline it on
  the car screen. An optional call-speaker toggle (off by default) tries
  the phone's speaker for calls; the car's Bluetooth route may still win,
  because call routing belongs to the system dialer.
- **Navigation.** AutoSpace hosts real Car App Library map apps on the car
  screen. Vela Maps is the default navigation app, with rough edges.
- **Music.** Browse, play, and queue tracks from a car-enabled media app,
  with a Now Playing view, an Up Next queue, and lyrics. Type a search, and
  apps that accept one play the best match. Car skip buttons seek within
  the video player and skip tracks outside it.
- **The web.** A browser with a floating frost bar, tabs, saved pages and
  history, a strict popup blocker, text zoom, find-in-page, and an
  open-on-phone handoff. The default search is no-AI DuckDuckGo
  (`noai.duckduckgo.com`). A media finder lists playable media the page
  exposes (direct files, HLS, DASH) and opens it in AutoSpace's own Media3
  player. Browser pages are never themed: no force-dark, no algorithmic
  darkening. Pages render as authored.
- **Video player.** Speed, sleep timer, audio tracks, subtitles (with a
  style picker in car Settings), quality choices, pinch zoom and pan, and
  a stall watchdog. While driving, video shows its picture only after you
  confirm you are a passenger; otherwise you hear the audio.
- **Passenger gates.** Entering the browser or the video player while the
  car is moving asks "Are you driving?" "I'm a passenger" opens it
  normally; "Yes" closes the browser or drops video to audio-only. The
  allowance lasts for that browser/player session. This is an honesty
  gate: the app cannot verify who tapped.
- **Screen sharing.** Fit is the default, Fill is one tap away, and there
  is never a stretch mode. Pinch zooms 1x–3x, drag pans while zoomed, and
  a player-style chrome row (glyph buttons, no labels) auto-hides.
  Exits never trap you: the car's Back button, the on-screen exit button,
  or the phone's Stop. Touch relay is single-finger while mirroring.
- **Mirrored Maps (also present, not the default).** A "Maps (mirrored)"
  tile mirrors stock Google Maps from the phone to the car, with touch.
  It appears only when Maps is installed. This is mirroring, not a native
  car app: Google Maps only accepts Google's own Android Auto as its car
  host, so native Maps support is not proven. No patched or re-signed
  copy of Maps is used.
- **Resolutions.** Per-vehicle resolution (Auto / 720p / 1080p / 2K / 4K),
  resolved only against configurations the head unit actually offers —
  nearest offered size at or below, never an invented one. 2K/4K are
  wired-first; on encoder failure or a black screen it falls back to
  1080p.
- **Voice, spoken.** AutoSpace speaks with Kokoro-82M (Hexgrad, Apache 2.0),
  entirely on the phone, for assistant replies, read-aloud messages, and
  spoken directions — the same voice for all three. All 53 Kokoro voices
  are offered, with American Adam (`am_adam`) the default, and Piper
  (MIT) as fallback. No voice ships in the APK: Kokoro (~350 MB) and
  Piper (~68 MB) download once from Settings, with resume and
  exact-size validation. If neither is downloaded, replies stay silent
  rather than borrowing the phone's voice.
- **Voice, listening.** Speech turns into text on the phone, and nothing
  you say leaves it. Live words come from Kroko; final words from
  Parakeet TDT 0.6B v2 by default (Canary stays available). A "Hey
  AutoSpace" / "Hey Gearslip" hotword (off by default) listens during a
  car session, with an option to listen on the phone microphone.
- **Voice commands.** Local commands run on keyword matching (plus your
  own custom voice keywords): navigate, play music, open apps, read and
  reply to messages, change brightness and volume, stop navigation, ETA
  and distance, repeat the last direction, "say that again" for the last
  reply, and more, including guarded synonym matching. Two honest limits,
  stated in the replies themselves: ending an active call needs the
  dialer, and a new message can only be sent by replying to a thread that
  already exists. Emergency calls always ask for a spoken confirmation
  first.
- **AI assistant (opt-in, bring your own key).** When local commands
  cannot answer, an AI assistant can: Groq is the default provider
  (`openai/gpt-oss-20b`), Gemini stays as an experimental provider.
  Answers are short, grounded in web search when the question needs it,
  and shown with a small AI badge plus a provider glyph (globe for Groq,
  Google G for Gemini) — never a spoken "From AI:" prefix. Actions the AI
  suggests (navigate, open an app, search, call, reply, media, volume,
  brightness, toggles, timers, readouts) are confirmed with you before
  they run, and emergency calls never go through the AI. The key is
  stored on the phone in the Android Keystore and never leaves it except
  to the provider you chose.
- **Messages and notifications.** New messages appear as a small card on
  the car screen. AutoSpace reads each one aloud, and you reply by voice
  or with a quick reply. Only messages, missed calls, alarms, and
  reminders interrupt you; the rest wait on the dashboard.
- **Dashboard.** The week's calendar (read locally from the device, not
  from any cloud account) next to the current and coming weather.
- **Car controls.** Steering wheel buttons, control knobs, and D-pads
  all work.
- **Experimental (off by default).** A floating media card over the full
  map (bottom passenger side, dismissible for the session) and a home
  widget column (weather, notifications digest, next event) on the driver
  side. The widget column hides while a navigation panel or turn card is
  showing, and returns on its own when it clears.
- **Car preview and couch testing.** The phone app previews the car UI,
  with its own settings page: screen size, density, preview-only theme,
  touch markers, simulated driving, and a test mode that fakes a car
  session on the couch (clearly bannered, never persisted, auto-off when
  a real car connects). A log viewer shares the session log and the last
  launch trace; crash reports write themselves to Downloads/AutoSpace.

## Status

- **Wired or wireless.** Plug the phone into the car's USB port, or, in a
  car with wireless Android Auto, tap Connect wirelessly and pick the
  car. Pair the phone with the car over Bluetooth first.
- **Setup walks you through it.** First launch asks for the Android Auto
  certificate, then one permission at a time, each with a plain reason
  and an Allow or Skip.
- **Templated apps work.** AutoSpace renders the template types the Car
  App Library defines, and it draws its own keyboard for search, sign-in,
  and text fields.
- **Some apps refuse AutoSpace as a host.** Apps whose car library accepts
  only Android Auto itself (upstream, this included Spotify, Waze, and
  Google Messages) still refuse. Spotify still plays through AutoSpace's
  own player. This is inherited upstream behaviour, not re-tested app by
  app for this fork.
- **Calls use the car's Bluetooth,** as they do in Android Auto. Pair the
  phone with the car to hear calls through its speakers.
- **Music reaches the car over Bluetooth or USB.** Setup asks which.
  Bluetooth is the default. USB sounds a little clearer, with no delay,
  but Android shows a screen-share consent dialog once per drive.
  AutoSpace never reads the screen; capturing playback is the only way an
  ordinary app can pass another app's audio along.
- **AutoSpace needs no adb, root, or Shizuku.** Every feature relies on an
  ordinary Android permission or an on-device consent dialog.
- **First prerelease published: 0.4.0-alpha.** The first public release
  after the alpha will be a beta, not a stable release. In-app updates
  are active: Stable, Beta, and Alpha channels, with the channel default
  following the build stage (alpha builds default to Alpha; a channel
  you save yourself always wins). Release builds ship as GitHub releases
  (see below); debug builds still ship only as GitHub Actions artifacts,
  and the in-app updater never auto-checks a debug build — it checks only
  when you tap Check now.

## Verify your download

The first prerelease (0.4.0-alpha) is published on the
[releases page](https://github.com/DocBobert6/AutoSpace/releases), but
its signing fingerprint is not published here yet: per the fork's plan
it is published with the first beta release, under its own key. The
block that used to sit here (a fingerprint for `app.autospace`) did
**not** match any signing key used by this fork and has been removed,
not carried forward.

Debug builds are signed with the committed debug key in
`gearslip/debug.keystore` (standard debug credentials — a shared debug
key, never a release key). Its certificate SHA-256 fingerprint, verified
against both the keystore and the shipped debug APK, is:

```
AB:7B:DB:B2:1E:F9:B3:5A:C9:26:11:5A:F5:C6:62:32:25:16:4A:9C:53:83:A1:A0:23:B6:FA:1E:33:62:06:2E
```

Debug builds install as `app.autospace.dev`. The release fingerprint will
be published here with the first beta release, under its own key.

## Building

You need Android Studio, or the Android SDK with JDK 21. Gradle downloads
the NDK, CMake, and the speech library on the first build.

```
./gradlew :gearslip:assembleDebug
```

CI (`.github/workflows/debug.yml`, on every push to `main` and every pull
request) runs the unit tests only — since 0.4.1-alpha it no longer builds or
publishes a debug APK, because every build ships as a prerelease. Tagged `v*`
pushes run `.github/workflows/release.yml`, which builds the signed release
APK and publishes it as a prerelease.

A release build needs a signing key. Copy `keystore.properties.example`
to `keystore.properties`, fill in your own values, and run
`./gradlew :gearslip:assembleRelease`. Git ignores both the properties
file and the keystore. Never commit a release key.

Current line: 0.4.0-alpha, versionCode 11 (release package
`app.autospace`; debug builds install as `app.autospace.dev`).

## How AutoSpace talks to cars

AutoSpace is an independent program, written to work with car head units.
What it knows of the protocol comes from public open-source projects such
as [aasdk](https://github.com/opencardev/aasdk) and open-android-auto,
and it contains none of Google's Android Auto code.

AutoSpace ships without a certificate. On first launch you choose where
yours comes from: download the one the aasdk project publishes, or import
a file of your own. The certificate stays on your phone. AutoSpace never
uploads, backs up, or logs it.

## Contributing

Bug reports from real cars help most, and pull requests are welcome.
Open an issue on
[GitHub](https://github.com/DocBobert6/AutoSpace/issues) and include your
car's make, model, and year, whether the head unit is the original one,
and the log from the drive (phone app: Settings, then Logs, then Share log).

Pull requests go against `main`, one change each, with how you tested
it: which car or head unit, which phone, and which apps. Contributions
are under AGPL-3.0. **This fork has no contributor license agreement.**
The upstream Gearslip CLA — which grants rights to Twink Software LLC,
including for commercial relicensing — does not apply to this fork and
has been removed; do not sign it for AutoSpace work.

## Writing apps for AutoSpace

[docs/BUILDING_APPS.md](docs/BUILDING_APPS.md) specifies how to write apps
that AutoSpace can render: templated apps, media apps, and the app
protocol. The document is inherited from upstream and still names
Gearslip throughout; its protocol content applies to this fork, and a
fork header note at the top of the document says so.

## Trademarks

Android, Android Auto, Google Maps, Google Messages, and Waze are
trademarks of Google LLC. Spotify is a trademark of Spotify AB. Gearslip
is a project of Twink Software LLC. AutoSpace is not affiliated with,
endorsed by, or sponsored by any of these companies. All other names
belong to their owners.

## Upstream credit and licence

AutoSpace is based on Gearslip by Twink Software LLC, licensed under
AGPL-3.0; see [LICENSE](LICENSE). The upstream project's separate
commercial licence covers upstream Gearslip only — it does not cover
this fork, and buying it buys nothing for AutoSpace. This fork's code
and changes are under AGPL-3.0 as well.

In-app credits (Settings → About) name the upstream project, the
open-source libraries (Media3, AndroidX including Compose Material 3 and
the car-app libraries, and Google Material components, all Apache 2.0),
and the voice stack: speech recognition by sherpa-onnx (Apache 2.0),
with Kroko ASR by Banafo (CC BY-SA 4.0) and Parakeet and Canary by NVIDIA
(CC BY 4.0); spoken voice by Kokoro-82M (Hexgrad, Apache 2.0), with Piper
voices (MIT) as fallback, and espeak-ng phonemization (GPL-3.0).
