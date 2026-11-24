# Piano Trainer

[![CI](https://github.com/rudi-cilibrasi/musicprompt/actions/workflows/ci.yml/badge.svg)](https://github.com/rudi-cilibrasi/musicprompt/actions/workflows/ci.yml)

A beginner piano trainer for Android phones. Students learn real songs phrase by phrase: they listen to a reference, record themselves on a real piano, and get a score and feedback based on pitch detection and timing analysis.

- [Specification](docs/SPEC.md): what the app must do.
- [Tech-stack decision](docs/decisions/0001-tech-stack.md): Kotlin Multiplatform + Compose Multiplatform.
- [Roadmap](https://github.com/rudi-cilibrasi/musicprompt/issues/71): every issue, grouped by milestone, with "blocked by" links between them.

## Project layout

- `core/`: pure Kotlin logic (music model, DSP, scoring, progression). No UI or platform APIs.
- `database/`: the local database (Room for Kotlin Multiplatform with bundled SQLite). Schema files are committed in `database/schemas/`.
- `composeApp/`: the Compose UI, plus Android-specific code in `androidMain`.
- `androidApp/`: the Android app shell that hosts the UI.

## Building

Requires JDK 21 and the Android SDK (point `sdk.dir` in `local.properties` at it). See the [developer setup guide](docs/DEVELOPING.md) for step-by-step instructions on macOS, Windows and Linux.

- Android app: `./gradlew :androidApp:assembleDebug`
- Core tests (any OS): `./gradlew :core:jvmTest`
- UI tests (any OS): `./gradlew :composeApp:testAndroidHostTest`
