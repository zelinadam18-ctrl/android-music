# 1. Tech stack: Kotlin Multiplatform + Compose Multiplatform

- Status: Accepted
- Date: 2026-10-25
- Issue: [#1](https://github.com/rudi-cilibrasi/musicprompt/issues/1)

## Context

The [specification](../SPEC.md) was first written for a single Android app built in Android Studio with Kotlin and Room. We now want one beginner piano trainer that runs on both Android and iOS **phones**. Tablets, desktop and web are out of scope.

Most development happens on macOS laptops, but some happens on Windows and Linux. iOS apps can only be built with Xcode on a Mac, whatever framework we pick, so the stack should let Windows and Linux developers do as much useful work as possible without a Mac.

Most of the app's difficult code is platform-independent: the song data model, piano synthesis, WAV encoding, FFT/STFT, pitch and onset detection, note alignment, scoring, progression rules and the UI. Only a few pieces must be written per platform: microphone recording, audio playback, the audio session, file locations and permissions.

## Decision

Use **Kotlin Multiplatform (KMP)** with **Compose Multiplatform** for the UI.

- **Shared code:** song data, synthesis, WAV, FFT, pitch detection, scoring, progression, UI, database.
- **Platform code**, behind small interfaces or `expect`/`actual`: microphone recording, audio playback, audio session, file locations, permissions.
- **Libraries:** Compose Multiplatform, JetBrains Navigation Compose and Lifecycle ViewModel, Room (KMP) with bundled SQLite, kotlinx.serialization, kotlinx.coroutines, kotlinx-datetime, Okio.
- **Targets:** Android and iOS phones only. The pure-logic `core` module also has a JVM target, used only so its tests run quickly on any OS; there is no desktop app.
- **Minimum OS versions:** Android 8.0 (API 26) and iOS 16.

## Alternatives considered

- **Flutter.** Good cross-platform tooling on every OS, but it means rewriting the spec's Kotlin and Room direction in Dart, and the audio analysis code would have no advantage over Kotlin.
- **React Native.** Real-time audio analysis in JavaScript is a poor fit; most of the hard code would end up in native modules anyway.
- **Two native apps (Kotlin + Swift).** The best platform fit, but every algorithm and screen would have to be written and tested twice.

## Consequences

What each kind of machine can do:

| Task | macOS | Windows / Linux |
| --- | --- | --- |
| Shared `core` code and its tests | ✅ | ✅ |
| Android app (build, run, debug) | ✅ | ✅ |
| Shared Compose UI (via the Android app) | ✅ | ✅ |
| iOS app (build, run, debug) | ✅ | ❌ needs Xcode |
| iOS-specific audio and file code | ✅ | ❌ |

- Issues that need a Mac are labelled `ios`.
- CI builds Android and runs the shared tests on Linux, and builds iOS on a macOS runner, so a change made on Windows or Linux can't silently break iOS.
- The iOS audio code (AVAudioSession, AVAudioEngine) can be written in Kotlin through Kotlin/Native interop, or in Swift behind a Kotlin interface. Either is acceptable.
- If Compose Multiplatform on iOS turns out to be inadequate for a specific screen, that screen can be written in SwiftUI while still using the shared logic.
