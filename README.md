# Piano Trainer

[![CI](https://github.com/rudi-cilibrasi/musicprompt/actions/workflows/ci.yml/badge.svg)](https://github.com/rudi-cilibrasi/musicprompt/actions/workflows/ci.yml) [![iOS](https://github.com/rudi-cilibrasi/musicprompt/actions/workflows/ios.yml/badge.svg)](https://github.com/rudi-cilibrasi/musicprompt/actions/workflows/ios.yml)

A beginner piano trainer for Android and iOS phones. Students learn real songs phrase by phrase: they listen to a reference, record themselves on a real piano, and get a score and feedback based on pitch detection and timing analysis.

- [Specification](docs/SPEC.md): what the app must do.
- [Tech-stack decision](docs/decisions/0001-tech-stack.md): Kotlin Multiplatform + Compose Multiplatform.
- [Roadmap](https://github.com/rudi-cilibrasi/musicprompt/issues/71): every issue, grouped by milestone, with "blocked by" links between them.

## Project layout

- `core/`: pure Kotlin logic shared by every platform (music model, DSP, scoring, progression). No UI or platform APIs.
- `database/`: the local database (Room for Kotlin Multiplatform with bundled SQLite). Schema files are committed in `database/schemas/`.
- `composeApp/`: the shared Compose Multiplatform UI, plus Android- and iOS-specific code in `androidMain` and `iosMain`. Built as the `ComposeApp` framework for iOS.
- `androidApp/`: the Android app shell that hosts the shared UI.
- `iosApp/`: the Xcode project that hosts the shared UI on iOS.

## Building

Requires JDK 21 and the Android SDK (point `sdk.dir` in `local.properties` at it). Building iOS also requires a Mac with Xcode. See the [developer setup guide](docs/DEVELOPING.md) for step-by-step instructions on macOS, Windows and Linux.

- Android app: `./gradlew :androidApp:assembleDebug`
- iOS app: open `iosApp/iosApp.xcodeproj` in Xcode and run.
- Core tests (any OS): `./gradlew :core:jvmTest`
- Shared UI tests: `./gradlew :composeApp:testAndroidHostTest` (any OS) or `./gradlew :composeApp:iosSimulatorArm64Test` (Mac).
