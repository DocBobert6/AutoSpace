# Contributing to AutoSpace

Thanks for helping. Bug reports from real cars are the most useful thing you can send, so
start there if you aren't sure where to begin.

## Reporting a bug

Open an issue on [GitHub](https://github.com/DocBobert6/AutoSpace/issues) and include:

- your car's make, model, and year, and whether the head unit is the original one; and
- the log from the drive. Open Logs in the phone app's Settings (Help and tools) and tap Share log.

The log records your phone, its Android version, the AutoSpace version, the head unit, and
why the connection ended. Most car bugs can't be fixed without it. Head units often report
only a part number, so the log can't name your car for you.

## Building

You need Android Studio, or the Android SDK with JDK 21. Gradle downloads the NDK, CMake,
and the speech library on the first build.

```
./gradlew :gearslip:assembleDebug
```

CI (`.github/workflows/debug.yml`, on every push to `main`) builds the same debug APK and
publishes it as the `AutoSpace-debug-apk` artifact. It installs as AutoSpace (debug,
`app.autospace.dev`), next to any release build.

A release build needs a signing key. Copy `keystore.properties.example` to
`keystore.properties`, fill in your own values, and run
`./gradlew :gearslip:assembleRelease`. Git ignores both the properties file and the
keystore. Never commit a release key.

## Pull requests

Work happens on `main`. Base your branch on `main`, and open your pull request against it.

- Keep each pull request to one change, and explain the problem it solves.
- Say how you tested it: which car or head unit, which phone, and which apps.
- Code written with AI tools is welcome. Read it, test it, and stand behind it as you would
  your own.
- AutoSpace asks for no adb, root, or Shizuku. A feature must work with ordinary Android
  permissions and on-device consent dialogs.

## Writing

Text that users read, such as screens, release notes, and docs, follows Strunk's
[*The Elements of Style*](https://www.gutenberg.org/ebooks/37134). In short: use the active
voice, state things positively, cut needless words, and use the serial comma.

## License

AutoSpace is licensed under AGPL-3.0, and your contributions are too.

**This fork has no contributor license agreement.** The upstream Gearslip CLA — which
grants rights to Twink Software LLC, including for commercial relicensing — does not
apply to this fork and has been removed; do not sign it for AutoSpace work.
