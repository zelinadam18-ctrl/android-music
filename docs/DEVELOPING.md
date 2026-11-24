# Developing

How to go from a clean machine to a running app on macOS, Windows or Linux.

Everything (the `core` code and its tests, the UI tests and the Android app) works the same on all three.

## 1. Install the tools

1. **JDK 21.** The Android Gradle Plugin and CI both use JDK 21. Any distribution works; CI uses [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=21).
   - macOS: `brew install --cask temurin@21`
   - Windows: `winget install EclipseAdoptium.Temurin.21.JDK`
   - Linux: your package manager's `openjdk-21-jdk` (Debian/Ubuntu) or `java-21-openjdk-devel` (Fedora), or the Temurin tarball.

   Check with `java -version`. If you have several JDKs, point `JAVA_HOME` at the 21 one.
2. **Android Studio** (latest stable) from <https://developer.android.com/studio>. It installs the Android SDK for you.
3. **Kotlin Multiplatform plugin** for Android Studio: *Settings → Plugins → Marketplace →* "Kotlin Multiplatform".
4. **Android SDK packages.** The project compiles against the SDK version in `gradle/libs.versions.toml` (`android-compileSdk`). In *Settings → Languages & Frameworks → Android SDK*, install that SDK Platform and the latest Build-Tools. Gradle also downloads missing packages on the first build if you have accepted the licences (`sdkmanager --licenses`).
5. **An emulator image.** *Device Manager → Create Virtual Device →* a phone such as Pixel 8, with a recent system image. The app is phones only, portrait only, so don't pick a tablet.

### Point Gradle at the SDK

Android Studio writes `local.properties` for you when you open the project. From the command line, create it yourself in the repo root (it is git-ignored):

```properties
# macOS
sdk.dir=/Users/<you>/Library/Android/sdk
# Windows (escape the backslashes)
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
# Linux
sdk.dir=/home/<you>/Android/Sdk
```

Setting the `ANDROID_HOME` environment variable instead also works.

## 2. Commands

Run these from the repo root. On Windows use `gradlew.bat` (or `.\gradlew` in PowerShell) instead of `./gradlew`.

| Task | Command |
|---|---|
| Build the Android app | `./gradlew :androidApp:assembleDebug` |
| Install and run on a connected emulator or phone | `./gradlew :androidApp:installDebug`, then open "PianoTrainer" |
| `core` tests (any OS) | `./gradlew :core:jvmTest` |
| UI tests (any OS) | `./gradlew :composeApp:testAndroidHostTest` |
| Database tests | `./gradlew :database:jvmTest` (skipped on Intel Macs, see below) |
| Android lint | `./gradlew :androidApp:lintDebug` |
| Everything CI runs | `./gradlew :core:jvmTest :core:testAndroidHostTest :database:jvmTest :composeApp:testAndroidHostTest :androidApp:lintDebug :androidApp:assembleDebug` |

In Android Studio you can also run the `androidApp` configuration directly.

## 3. Microphone in the emulator

The app records the student playing a real piano, so you'll want sound input while developing:

- **Android emulator:** the virtual microphone is off by default. Open *Extended controls (⋯) → Microphone* and turn on **"Virtual microphone uses host audio input"**. It turns itself off again when the emulator restarts.

Emulator audio is resampled and filtered by the host, so it is only good enough for checking that recording works. **Judge recording quality and pitch detection on real phones** with a real piano; see [#40](https://github.com/rudi-cilibrasi/musicprompt/issues/40).

## 4. Troubleshooting

- **"SDK location not found"**: create `local.properties` as above, or set `ANDROID_HOME`.
- **"Unsupported class file major version" or a Gradle JVM error**: Gradle is running on the wrong JDK. Use JDK 21 (`JAVA_HOME`, or *Settings → Build Tools → Gradle → Gradle JDK* in Android Studio).
- **Licences not accepted**: run `sdkmanager --licenses` (in `<sdk>/cmdline-tools/latest/bin`) and answer `y`.
- **Database tests are skipped on an Intel Mac**: the bundled SQLite driver has no JVM binary for Intel Macs, so `:database:jvmTest` skips its tests there. They run on Linux (CI), Windows and Apple-silicon Macs.
