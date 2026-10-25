# AutoSpace changelog

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
  2K/4K capped at 1080p). Screen-sharing fit: Fill (crop, new default) or Fit
  (letterbox), with a GL renderer that crops Fit frames to the frame, Fill touch
  remapping, and a Fill exit button in the top-right corner (Fill has no
  letterbox bars to tap).
- Bug fixes: the "Screen fit" text now shows the real negotiated frame height
  instead of a hardcoded 480.

## Unreleased (Phase 3 — Browser: no-AI search, media finder, own player)
- New features: default search is now no-AI DuckDuckGo (noai.duckduckgo.com,
  assist=false); typed text that isn't a URL searches there. A "Media on this
  page" finder lists playable media the page exposes (direct video/audio files,
  HLS, DASH), deduplicated, and opens it in AutoSpace's own Media3 player
  (HLS first, DASH included; play/pause, ±30s seek, seek bar for VOD, LIVE
  handling). Video is parked-only: while the car is moving the video blanks
  and audio continues; controls stay usable while driving.
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
  history). Tabs are deferred, not included.

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
  back, and never hide while paused or while driving. Video stays
  parked-only with audio continuing; controls over video use gradient
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
  (font/size/colour/background/edge, system typefaces only); the Settings
  page that would set those values is the one follow-up, so until then
  subtitles keep the system caption style. Not ported, by car rule:
  downloader, phone PiP, phone brightness/volume drags, horizontal
  drag-seek (scrubber + skip cover it); the old overflow menu and its
  Restart row are gone (Close + Stop + scrubber cover them), and the
  driving branch shows transport only.
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
- Release prep done here: changelog for Phases 1–4, a beta release checklist
  kept with the build log, and the two
  fork-specific user-visible strings updated (accessibility touch-relay
  description, built-in test page title). Upstream's version history in
  Changelog.kt is deliberately left as upstream history, not rewritten.
- NOT yet done, and not claimed: the full in-car regression matrix (themes ×
  wired/wireless × resolutions × Fit/Fill × every surface), the Maps spike,
  a release signing key, and the beta version number itself — all need
  Robert (car testing on his 2019 Altima, a key kept out of the repo, and his
  go-ahead). No beta release has been published from this work.
