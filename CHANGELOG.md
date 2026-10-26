# AutoSpace changelog

## 0.4.0-alpha (2026-10-26) — first prerelease
- First prerelease (not a stable release): version 0.4.0-alpha,
  versionCode 11, release package `app.autospace`, signed with the fork's
  own permanent release key, kept out of the repo. The release signing
  fingerprint is published with the first beta, not here.
- In-app updates active: Stable, Beta, and Alpha channels restored; the
  channel default follows the build stage (alpha builds default to
  Alpha; a saved channel always wins).
- Onboarding covers the new ground: an Updates page (channel state,
  auto-check, install permission), an AI answers page (Groq default,
  Gemini experimental, active provider's privacy line), both
  phone-microphone toggles on the Car microphone page, and an
  Experimental page last (floating media, home widgets, vehicle data as
  its own switch at the bottom; no master switch).
- AI overlay cleanup: the spoken and displayed "From AI:" prefix is gone
  entirely; every AI answer still carries the small AI badge, and
  grounded answers show a provider glyph instead of a source line (globe
  for Groq, Google G for Gemini), never spoken.
- Install note: uninstall any debug build first (different package and
  key); fresh app data — settings, voice shortcuts, AI key, and learned
  words reset.
- The 0.3.01-alpha debug line is ended and superseded by this
  prerelease; the first full public release after the alpha is still
  planned as a beta.

## Unreleased (Rename)
- Provisional name Frostslip replaced by AutoSpace (Robert, 2026-10-24) before any
  code was pushed: repo DocBobert6/AutoSpace, label AutoSpace, applicationId
  `app.autospace`. Launcher icon is Robert's supplied badge, cropped to its
  552x552 square (badge intact) and generated at all densities plus an adaptive
  foreground.

## Unreleased (Phase 0 — fork and baseline)
- Forked from twinksoftware/Gearslip @ e8f51f4947d6edeb3d3e9dc52ae6598d867f677a (0.3.01-alpha, AGPL-3.0).
- New identity: app label AutoSpace, applicationId `app.autospace` (debug `app.autospace.dev`).
  Never published under the upstream id; upstream signing key not reused.
- Updater repointed to DocBobert6/AutoSpace releases; usage-notes stats URL left empty.
- Upstream donation links/prompts removed (no donation recipient is configured for this fork).
- Added GitHub Actions debug build (`.github/workflows/debug.yml`) producing AutoSpace-debug.apk.

Note on speech-models/base_acft_q5_1.bin: this 59 MB upstream file is NOT in the
fork's git tree, because GitHub's blob API and Contents API both reject it (422,
"too large to process") and no AutoSpace code references speech-models/ — runtime
speech models are downloaded from Hugging Face by SpeechModels.kt. The file remains
in the local worktree; it can be added later by a git push from a local clone.

## Unreleased (Phase 1 — Frost / Material theme)
- New features: Material and Frost theme styles alongside Classic (Flat/Tactile/Keycap).
  Frost is the default for fresh installs; stored theme choices are kept. Frost uses
  translucency + scrim, not live blur over the video stream.
- Improvements: larger launcher tiles (136/148dp, 64dp icons) with group headers,
  a pinned row, and search via the car keyboard; larger music-player transport
  controls and queue/browse rows. Every host-owned surface follows the same
  Material You colours + Gs control finishes.

## Unreleased (Phase 1.5 — Quick brightness)
- New features: quick brightness control — a brightness icon in the nav bar and
  on the dashboard, at most 2 taps to a large slider sheet that also holds
  Light/Dark and NightMode shortcuts. Persisted per CarSettings.brightness
  (default 100%), implemented as a touch-transparent dim overlay so host UI and
  templated apps dim uniformly.
- Honest limits: 100% is the head unit's backlight maximum — the slider cannot
  brighten past it. A manual value always wins over night auto-dim and persists
  across drives. Mirror dimming is out of scope.

## Unreleased (Phase 2 — Custom resolutions + screencast Fill)
- New features: Settings > Display > Resolution (Auto / 720p / 1080p / 2K / 4K),
  stored per vehicle and resolved only against configs the head unit actually
  offers (nearest offered size at or below, never an invented config; wireless
  2K/4K capped at 1080p). Screen-sharing fit: Fill (crop) or Fit
  (letterbox), with a GL renderer that crops Fit frames to the frame, Fill touch
  remapping, and a Fill exit button in the top-right corner (Fill has no
  letterbox bars to tap). CORRECTION (2026-10-25): the screen-sharing
  default is now Fit, not Fill — Fill on a portrait phone reads as far too
  zoomed in, so Fit became the default and Fill stays one tap away.
  There is never a stretch mode.
- Bug fixes: the "Screen fit" text now shows the real negotiated frame height
  instead of a hardcoded 480.

## Unreleased (Phase 3 — Browser: no-AI search, media finder, own player)
- New features: default search is now no-AI DuckDuckGo (noai.duckduckgo.com,
  assist=false); typed text that isn't a URL searches there. A "Media on this
  page" finder lists playable media the page exposes (direct video/audio files,
  HLS, DASH), deduplicated, and opens it in AutoSpace's own Media3 player
  (HLS first, DASH included; play/pause, ±30s seek, seek bar for VOD, LIVE
  handling). Video picture while driving is passenger-gated (REVERSED 2026-10-25:
  the parked-only rule was lifted for the passenger case): while the car
  is moving, an "Are you driving?" gate asks first — "I'm a passenger"
  shows the video picture, "Yes" drops to the audio-only branch with the
  picture hidden and audio continuing; controls stay usable while driving.
- Improvements: browser chrome follows AutoSpace theming (Material You colours
  + Gs control finishes on every button/bar/sheet).
- What pages do NOT do: pages are never themed. No force-dark, no algorithmic
  darkening from the phone theme or from darkness outside (upstream's
  darkOutside darkening was removed). Pages render as authored.
- Honest limits: plays only media the page exposes as a playable URL. No DRM
  bypass, no DRM-locked streams, no YouTube playback through the finder, no
  sign-in-gated video (the player has no access to the WebView's cookies), and
  no background audio after leaving the player screen (closing it stops audio).

## Unreleased (Phase 3.4 — Browser quick wins)
- New features: address/search bar with Forward / Reload / Home (car keyboard
  for typing); progress bar in the browser header; friendly error page with
  Retry and Go back (replacing log-only errors); fullscreen video and JS
  dialogs (alert/confirm/prompt) via in-composition overlays; downloads routed
  to the phone Downloads folder with a started/failed notice; persisted
  bookmarks + history picker ("Saved & history", bookmark this page, clear
  history). CORRECTION: tabs shipped later (per-tab WebViews, switcher
  sheet, pill-swipe switching, popups in the originating tab) — they are
  no longer deferred.

## Unreleased (Phase 4 — Mirrored Google Maps)
- New features: "Maps (mirrored)" launcher tile (Maps group) that opens stock
  Google Maps on the phone and mirrors it to the car screen, with Fill + touch
  from Phase 2. The tile appears only when Maps is installed and launchable.
- Honest limits: this is mirroring, not a native car app — no native Maps
  template, no car-template chrome. The phone must stay unlocked and awake;
  the mirror shows whatever is on the phone, not just Maps; guidance audio
  follows the phone's live audio route. Native Maps support is NOT included:
  Google Maps only accepts Google's own Android Auto as its car host, so it
  can't run as a built-in car app in AutoSpace. No patched or re-signed copy
  of Maps is used. The native-promotion spike is still owed in a real car
  session (see BUILD_LOG Phase 4); until it passes, native Maps is "not proven".

## Unreleased (Phase 6 — Frost / modern UI refresh)
- Improvements: Frost is rebuilt on Notscape Mariner's final solid-frost
  recipe — near-solid neutral surfaces (light #FFFFFF/#F2F2F7, dark
  #000000/#1C1C1E/#2C2C2E), a lighter top edge, a soft shadow, and a
  hairline rim, with 20dp cards and 28dp sheets — instead of the flat
  translucent wash that read as pale peach. Frost no longer takes the
  wallpaper-dynamic colour at all; accent is reserved for primary/selected
  controls. Material, Flat, Tactile, and Keycap are unchanged, and browser
  pages stay unthemed.
- Improvements: the video player is rebuilt in Notscape's AVKit language —
  a top gradient bar (close, centred title, overflow with Restart and
  Close), a centre cluster of circular frosted buttons (−10s / big
  play-pause / +10s, replacing −30s/+30s), and a bottom scrubber with
  elapsed on the left and remaining on the right. Controls auto-hide
  ~3.5s after the last touch while playing, tap the video to bring them
  back, and never hide while paused or while driving. Video picture while driving is passenger-gated (see the Phase 3
  correction above); controls over video use gradient
  scrims, never blur, because the player surface cannot be captured.
- Improvements: browser chrome uses circular frosted nav buttons grouped
  around the URL pill, inset from the edges; Back/Forward/Reload/Home
  stay reachable at all times (nothing collapses while driving).
- Improvements: the apps page and the screen-sharing app-selection page
  use phone-drawer language scaled up for the car — search at the top,
  a clean icon grid with the label underneath, neutral Frost tiles.
  Launcher icons grow 64dp → 80dp (tiles 148dp → 176dp high, grid
  136dp → 168dp, pinned 96dp/44dp → 112dp/56dp); screen-sharing icons
  grow 52dp → 72dp (tiles 120dp → 152dp high, grid 108dp → 144dp).
  Grouping, search, the pinned row, and the touch-relay warning are
  unchanged.
- Improvements: user-visible strings now say AutoSpace throughout —
  onboarding, settings, help, error and notification text — instead of
  the upstream name. Upstream's version history in Changelog.kt is
  deliberately left untouched, as are class/log identifiers, the
  self-signed certificate name, the Android Auto host label, and
  request User-Agents.
- Honest limits: Frost here is solid-frost, not live backdrop blur.
  Notscape removed its Haze blur entirely (0.0.63) and a blur pass cannot
  capture the car video surfaces anyway; the gradient/shadow/hairline
  recipe is what reads as frost on a compressed car stream. Visual
  acceptance on the phone preview and in the car is still owed.
- New features: icon-size slider in car Settings (next to Size / App
  theme), 0%–100% (corrected 2026-10-24 22:10, superseding the first
  20%–100% ask). 100% is the large size above; the slider scales app
  icons down linearly on the apps page (including pinned tiles) and the
  screen-sharing page, and the grid reflows with it. Tiles keep their
  full height and never narrow below 96dp, so at 0% the icon is hidden
  but the tile, its label, and its large car tap area stay usable. The
  Normal / Large / Extra-large Size presets are unchanged — the slider
  is the finer, independent control.
- New features: five App theme options (Notscape 0.0.946's split): plain
  Light and plain Dark use the neutral scheme for every style (no wallpaper
  colours); Material You Light / Dark use the phone's wallpaper colours
  forced to that variant; Follow phone uses the wallpaper colours and
  follows the phone's light/dark (Frost stays neutral-only under Follow
  phone, as before). Offered in car Settings and in the brightness sheet.
  NightMode and browser pages are unchanged.
- New features: the video player is now Notscape Mariner's browser video
  player, ported into the car — top capsule (Close + Stop; no phone PiP,
  Close player is the car equivalent), centre circles (prev/next only with
  a queue, skip, big play-pause, car-sized 72/96/60dp), bottom title +
  options capsule over a slim scrubber with elapsed / negative remaining
  (LIVE kept). Options capsule, one row at a time: listen mode (headphones
  presentation, zoom reset, auto-hide off, audio continues while driving),
  audio-track picker, video-quality picker (player renditions, or HLS
  master variants with Auto + exact bitrate pin; a single file shows no
  picker), subtitle picker with Off, scaling pill row, speed cycle
  0.5/0.75/1/1.25/1.5/2x (persisted), sleep cycle 0/10/20/30/60 min
  (pauses at expiry). Scaling: Fit/Stretch/Fill/Crop (Crop = cover +
  1.25x) from an AspectRatio button tinted when not Fit, on a TextureView
  surface so transforms apply; pinch 1x–3x, drag-to-pan only while zoomed
  >1x, two-finger pinch always zooms, centre double-tap resets, zoom
  resets on track/mode change and on entering listen mode; hold-to-scan
  and side double-tap seek included. Buffering shows the cycling play
  fill (tween 1600, Reverse) with skip buttons hidden behind spacers, and
  an 8s stall watchdog re-prepares in place at the current position, max
  2 per item, then a named stall error. Skip seconds is a persisted
  setting (default 10, Settings row 5/10/15/30/60s; baked glyphs at 5/10/30,
  curved arrow + seconds otherwise). The finder now hands the player its
  whole media list as a queue. Subtitle styling plumbing is ported
  (font/size/colour/background/edge, system typefaces only). CORRECTION:
  the Settings page shipped in r9 (car Settings > Subtitles); all-System-
  default keeps the system caption style. Not ported, by car rule:
  downloader, phone PiP, phone brightness/volume drags, horizontal
  drag-seek (scrubber + skip cover it); the old overflow menu and its
  Restart row are gone (Close + Stop + scrubber cover them), and the
  driving branch shows transport only.
- Bug fix: screen-sharing Fill mirroring no longer destroys itself.
  PhoneMirror.beginFill created the GL renderer, assigned it, then called
  begin(), whose first line runs end() — and end() released that same
  renderer, leaving the virtual display on a dead surface with no frames
  while _active was already true (so no fallback ran either). Fill is the
  default, so every screen share hit this. beginFill now ends the previous
  session first, then creates the renderer, then creates the display
  through a new beginInternal() that assumes cleanup already happened;
  begin() keeps ending first for the Fit path. Stop/start ends each
  renderer exactly once (release is idempotent) and creates a fresh one.
  Fill geometry and the exit region are untouched; Fit is unchanged.
  Until this build, the workaround was Settings > Screen sharing > Fit.
- New features: a persisted "Use phone microphone for voice commands"
  toggle in car Settings (default off; car mic preferred when it works).
  When on, SpeechEngine skips the car's microphone entirely and listens
  through the phone's microphone for voice commands and voice replies.
  This does NOT affect phone calls, whose audio routing is untouched.
- Bug fix: the browser address pill now shows the address being typed,
  live, with a caret (placeholder when empty) while the car keyboard is
  open — previously it always showed the current URL, so typing looked
  like it did nothing — and tapping the pill mid-edit no longer wipes
  what was typed. Submit still navigates (URL loads, other text searches
  no-AI DuckDuckGo); dismiss keeps the current page.

## Unreleased (Phase 5 — Regression / release prep)
- First release will be a BETA (Robert, 2026-10-24), not a stable release:
  beta labelling in the release notes, prerelease marking on the GitHub
  release, no stable-release claim. Debug builds keep their current numbering
  (0.3.01-alpha, versionCode 10) until the beta release itself.
  CORRECTION (2026-10-26): the alpha prerelease came first — numbering
  moved to 0.4.0-alpha, versionCode 11 for the alpha; the first full
  public release is still planned as a beta.
- Release prep done here: changelog for Phases 1–4, a beta release checklist
  kept with the build log, and the two
  fork-specific user-visible strings updated (accessibility touch-relay
  description, built-in test page title). CORRECTION (2026-10-25): the in-app Changelog.kt was rewritten to
  AutoSpace's own notes (the upstream history that used to sit there
  described another app's releases, so it was replaced with what this
  fork actually ships).
- NOT yet done, and not claimed: the full in-car regression matrix (themes ×
  wired/wireless × resolutions × Fit/Fill × every surface), the Maps spike,
  a release signing key, and the beta version number itself — all need
  Robert (car testing on his 2019 Altima, a key kept out of the repo, and his
  go-ahead). No beta release has been published from this work.

## Unreleased (Voice — Kokoro-82M + Piper)
- New feature: AutoSpace now speaks with Kokoro-82M (hexgrad, Apache-2.0),
  entirely on the phone, for assistant replies, read-aloud messages, and
  spoken directions — the same voice for all three. System TTS is removed
  from the spoken path: there are no other voice options and no other
  engines. Piper (en_US-amy-medium, MIT) is the fallback when Kokoro
  isn't downloaded or a synthesis fails.
- Settings > Voice offers all 53 Kokoro voices from the downloaded
  kokoro-multi-lang-v1_0 model, grouped by language, with American Adam
  (am_adam, sid 11) as the default. A new choice takes effect on the next
  spoken prompt. Piper always speaks as Amy.
- Neither voice ships in the APK. Settings > Voice downloads Kokoro
  (model + voices + tokens + espeak-ng-data, about 350 MB) and Piper
  (about 68 MB) once, into the app's own storage, with progress and
  delete/re-download. If neither is downloaded, assistant replies show
  "No voice downloaded yet. Download Kokoro or Piper in Settings first."
  in the overlay, and a turn that can't be spoken is logged and skipped —
  AutoSpace stays silent rather than borrowing the phone's voice.
- Listening is unchanged (sherpa-onnx listening models, phone-mic toggle,
  Bluetooth audio, and calls are all untouched), and spoken directions
  still stream to the car's GUIDANCE directions sink, never the phone
  speaker. Synthesis-time figures are estimates, not a phone benchmark:
  on published sherpa-onnx CPU figures Kokoro runs around real time on
  older phones, so a short turn ("Turn left onto Main Street", roughly
  2 seconds of audio) should render in roughly 2–4 seconds on first use
  and faster once warm — the car test on Robert's Pixel 10a is the real
  check, and Piper renders far faster than Kokoro if Kokoro feels slow.

## Unreleased (Listening defaults — Parakeet v2)
- The default final listening model is now Parakeet TDT 0.6B v2
  ("Parakeet large", 631 MB, NVIDIA NeMo, CC BY 4.0) instead of Canary
  180M. Onboarding's one-tap voice pair is now Kroko (live words) +
  Parakeet v2 (final words), 699 MB together, and the "Recommended"
  label moves from Canary to Parakeet v2. Kroko live is unchanged.
- Canary 180M stays available as an option in Settings; it is no longer
  the default and it is not removed. A final-model choice you already
  made explicitly is never overridden — only drivers who never chose
  get the new default, including drivers who previously had Canary
  only because it was the old implicit default. Until Parakeet v2 is
  downloaded, the final read falls back to the live words; Canary can
  be re-picked in Settings at any time.

## Unreleased (Assistant commands expansion)
- The voice assistant understands more, in the same keyword style (no
  language model): answer / hang up a call, cancel / never mind,
  volume up / down / mute / unmute (media stream), brightness up /
  down / brightness percent, stop navigation, how far / ETA / arrival
  time, repeat the last direction, speak-directions and phone/car-mic
  toggles, start / stop screen sharing, search for X (no-AI DuckDuckGo
  in the browser), read my messages, text X that Y, phone battery,
  call emergency services, and remind me in X minutes (a local timer).
- Two honest limits, stated in the replies themselves: ending an
  active call needs the dialer — AutoSpace can answer a ringing call
  and decline one inside the call-screening window, but it cannot hang
  up an active call, so "hang up" opens the phone screen instead; and
  a new message can only be sent by replying to a thread that already
  exists, so "text X that Y" works when X has messaged before and says
  so plainly when there is no thread to reply to.
- Emergency calls never dial straight away: "call emergency services"
  asks out loud for confirmation, and only a spoken yes within
  30 seconds dials 911; anything else, or an expired window, cancels.
- "Stop" on its own still pauses the music. The assistant's cancel
  words are "cancel", "never mind", "stop speaking" and "stop talking";
  "stop navigation" and "stop screen sharing" are checked before the
  music command so they do not pause it by accident.
- The "what can you do" help list now includes every new command.

## Unreleased (Call speaker option)
- New toggle, off by default, in car Settings (next to the phone-mic
  row), phone Settings ("Calls: speaker"), and onboarding ("Calls on
  speaker."): when a call starts, its audio goes to the phone's speaker
  instead of the car's Bluetooth route, so the caller hears you through
  the phone's microphone. The speaker is restored when the call ends.
  Phone calls only — voice commands, the assistant, and music are
  untouched, and it applies from the next call.
- Honest limit, stated on the setting itself: the call's audio route
  belongs to Telecom and the system dialer, which AutoSpace is not, so
  if the car's Bluetooth takes the call back, this setting cannot
  override it. Whether it wins on a given car is a car-test result.

## Unreleased (Kokoro voice picker redesign)
- The Kokoro voice picker's main page now shows four featured voices —
  am_michael, bm_george, af_heart, bf_emma — with a "More voices"
  button opening a second page with the other 49. Nothing was removed:
  all 53 voices are still selectable, the default is still am_adam
  (on the More voices page), and the fp32 model is unchanged.
- Every voice row now has a Preview button that speaks "In 500 feet,
  turn left." in that voice without changing the stored selection;
  tapping again stops it, leaving the page stops it, and a new preview
  or assistant reply stops the one playing. The Piper row previews as
  Amy. A preview button is disabled until its engine is downloaded.

## Unreleased (Passenger gates — browser + video)
- REVERSAL (Robert, 2026-10-25): the locked "video parked-only,
  audio may continue" rule is reversed for the passenger case only.
  Entering the browser or the video player while the car is moving
  (CarMotion GPS speed over ~7 km/h) now shows an "Are you driving?"
  dialog with a smaller disclaimer that browsing or watching video
  while driving is dangerous and could result in an accident, injury,
  or even death, and not to drive and browse or watch video. "Yes"
  closes the browser, or falls back to the audio-only player branch
  with the picture hidden and audio continuing; "I'm a passenger"
  opens the browser normally, or shows the video picture while
  moving. CORRECTION (2026-10-25): the allowance now persists for the whole
  browser/player session — the per-entry rule was changed after reports
  of re-asking at every stop sign and red light. A stop only closes a
  gate still open and unanswered, never clears a granted allowance;
  a fresh entry re-asks while moving. There is still no setting and no
  "don't ask again" that survives a drive.
  When the car stops, video returns to the normal layout without
  re-asking. Honest limits: CarMotion fails open (no GPS fix or
  permission means "not moving", so the gate may not appear), and
  the app cannot verify the tapper is actually a passenger on a
  driver-visible screen — the gate is an honesty gate.

## Unreleased (Debug line through r40 — superseded by 0.4.0-alpha)

The in-app What's New page (`gearslip/src/main/java/app/autospace/Changelog.kt`,
rewritten 2026-10-25) is the short driver-facing list. In full, the debug
line shipped (through r40, before the 0.4.0-alpha prerelease):

- Button styles Frost, Flat, Tactile, Edge, Pitlane; color schemes
  Neutral, Material You, Night, Pitlane, Circuit, Heritage; independent
  Light / Dark / System appearance mode; Regular / Pitch dark surfaces
  (Pitch is pure black); flat alphabetical launcher with pinned row,
  drag-to-reorder, and icon / tile / UI-size sliders; quick brightness
  (nav bar or dashboard, at most 2 taps).
- Browser: no-AI DuckDuckGo default, floating frost bar, tabs with switcher
  and pill-swipe, saved pages and history ("Saved & history"), strict popup
  blocker, desktop-site toggle with per-host memory, media finder with its
  own Media3 player, text zoom, find-in-page, open-on-phone handoff,
  immersive mode and scroll-hiding bar; pages are never themed. Passenger
  gate on entry while moving (allowance lasts the browser session).
- Video player (Notscape-style chrome, theme-independent): speed, sleep
  timer, audio tracks, subtitles with a car Settings style picker, quality
  choices, pinch zoom and pan, stall watchdog, hardware skip buttons that
  seek; passenger gate while moving (allowance lasts the player session).
- Screen sharing: Fit default, Fill one tap away, never Stretch; GL
  player-style chrome (glyph buttons), pinch 1x–3x, pan only while zoomed,
  deliberate-tap exit, car Back exit, phone Stop.
- Navigation default Vela Maps (templated); "Maps (mirrored)" tile also
  present (only when Maps is installed; its removal is planned, not done);
  per-vehicle resolutions 720p / 1080p / 2K / 4K resolved only against
  configs the head unit offers, 2K/4K wired-first with 1080p fallback on
  encoder failure or black screen.
- Experimental (off by default): floating media card over the map and
  home widget column (weather, notifications digest, next event); the
  widget column hides while a nav template panel or turn card shows.
- Spoken voice: Kokoro-82M entirely on the phone (all 53 voices, American
  Adam default) with Piper fallback; downloads with resume and exact-size
  validation; load-time refusal of bad sets; playback drain fix; silent
  renders surfaced instead of played as nothing. Listening: Kroko live
  words, Parakeet TDT 0.6B v2 final words by default (Canary stays
  available); "Hey AutoSpace" / "Hey Gearslip" hotword (off by default,
  phone-mic option, split-spelling matching).
- Assistant: local keyword matching plus custom voice keywords, and an
  opt-in AI assistant (Groq default, Gemini experimental; grounded web
  search, confirmations before actions, emergency never via AI):
  navigation, media, calls (answer/decline in the screening window;
  active-call hang-up needs the dialer), messages (read; reply only to
  existing threads), brightness and volume, stop navigation, ETA/distance,
  repeat last direction, "say that again" for the last reply, guarded
  synonym matching, emergency calls with a spoken confirmation guard,
  local timer, call-speaker toggle (off by default, next call onward,
  Bluetooth may still win).
- Perfection round (2026-10-25): assistant-reply voice crash fixed via
  model validation; crash reporter writing to Downloads/AutoSpace;
  hotword phone-mic toggle and broken-car-mic silence fallback; mirror
  player-style GL chrome; map corner dots removed (HomeFrameOverlay is now
  the only frame; inner split corners are square by design); keyboard
  long-press hints (no emoji key); Saved & history sheet scrolling fix;
  passenger-gate session persistence; video gate in frost chrome over
  playing video; car skip buttons seeking in the player.
- Voice load/playback fix saga (2026-10-25): ZipVoice binding class-name
  fix, Pocket/Supertonic/ruleFars/silenceScale binding fields, 45-second
  launch trace, stage-aware failure reporting, download resume.
- Audit build r9 (2026-10-25): synth-timeout executor replacement, system-
  listen counter fix, exactly-once heard events, DirectionsVoice thread
  generation guard, last-spoken after render, live Settings revalidation,
  mirror Fit single source of truth and chrome clamp, 44dp breadcrumb Back,
  Subtitles Settings page, intro Liberapay/Matrix and About Matrix removal,
  dead Monero dialog removal, polite insult deflection, "say that again",
  browser text zoom + find-in-page, open on phone, one-shot hotword test,
  download resume, guarded synonym/fuzzy matching.
- Preview + couch testing (r12, 2026-10-25): car preview settings page
  (resolution, density, preview-only theme, touch markers, simulated
  driving, simulate-car-session test mode with TEST MODE banner and
  auto-off on real connect), in-app log viewer with share, 90-second and
  stop-and-export trace options, trace arming from car Settings.
- Phone-UI cleanup (r12): What's New rewritten to AutoSpace's own notes;
  "Which cars work" section and "Send usage notes" removed from the app
  (usage-notes backend kept for reversibility); in-app credits completed
  (Based on Gearslip, open-source libraries, voice credits).
- Removed from the app (2026-10-25): usage notes entirely (no stats server
  exists for this fork; the stats URL is empty, so the switch sent nothing).
  The repo docs keep no usage-notes pipeline either — see `CARS.md`.
