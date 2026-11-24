# 1. Tech stack: Kotlin Multiplatform + Compose Multiplatform

- Status: Accepted
- Date: 2026-10-25
- Issue: [#1](https://github.com/rudi-cilibrasi/musicprompt/issues/1)

## Context

The [specification](../SPEC.md) was first written for a single Android app built in Android Studio with Kotlin and Room. The app targets Android **phones**. Tablets, desktop and web are out of scope.

Development happens on macOS, Windows and Linux, so every part of the project must build and test on all three.

Most of the app's difficult code is platform-independent: the song data model, piano synthesis, WAV encoding, FFT/STFT, pitch and onset detection, note alignment, scoring, progression rules and the UI. Only a few pieces need platform APIs: microphone recording, audio playback, the audio session, file locations and permissions.

## Decision

Use **Kotlin Multiplatform (KMP)** with **Compose Multiplatform** for the UI.

- **Platform-independent code:** song data, synthesis, WAV, FFT, pitch detection, scoring, progression, UI, database.
- **Platform code**, behind small interfaces or `expect`/`actual`: microphone recording, audio playback, audio session, file locations, permissions.
- **Libraries:** Compose Multiplatform, JetBrains Navigation Compose and Lifecycle ViewModel, Room (KMP) with bundled SQLite, kotlinx.serialization, kotlinx.coroutines, kotlinx-datetime, Okio.
- **Targets:** Android phones only. The pure-logic `core` and `database` modules also have a JVM target, used only so their tests run quickly on any OS; there is no desktop app.
- **Minimum OS version:** Android 8.0 (API 26).

## Alternatives considered

- **Flutter.** Good tooling on every OS, but it means rewriting the spec's Kotlin and Room direction in Dart, and the audio analysis code would have no advantage over Kotlin.
- **React Native.** Real-time audio analysis in JavaScript is a poor fit; most of the hard code would end up in native modules anyway.
- **A plain Android project (no KMP).** Simpler Gradle setup, but the pure-logic modules would lose their fast JVM test target.

## Consequences

- Everything (the `core` code and its tests, the Compose UI, and the Android app) builds, runs and debugs on macOS, Windows and Linux.
- CI builds the Android app and runs all tests on Linux.
