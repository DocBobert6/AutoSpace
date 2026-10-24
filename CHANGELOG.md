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
