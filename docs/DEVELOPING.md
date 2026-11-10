# Developing

How to go from a clean machine to a running app on macOS, Windows or Linux.

| What | macOS | Windows | Linux |
|---|---|---|---|
| Shared `core` code and its tests | ✅ | ✅ | ✅ |
| Shared UI tests (Android host) | ✅ | ✅ | ✅ |
| Android app (emulator or phone) | ✅ | ✅ | ✅ |
| iOS app, iOS simulator tests | ✅ | ❌ | ❌ |

Issues labelled or titled for iOS need a Mac. Everything else can be done on any of the three.

## 1. Install the tools (all OSes)

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

## 2. macOS only: iOS

1. **Xcode** (latest stable) from the Mac App Store. Open it once to install the extra components.
2. **Command-line tools:** `xcode-select --install`, then make sure they point at Xcode, not the standalone tools: `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`.
3. **An iOS simulator runtime:** *Xcode → Settings → Components*, install the newest iOS.
4. **Running on a real iPhone with a free Apple ID:**
   1. *Xcode → Settings → Accounts →* add your Apple ID. This creates a free "Personal Team".
   2. Find your team ID (*Accounts →* your team) and put it after `TEAM_ID=` in `iosApp/Configuration/Config.xcconfig`. Don't commit that change.
   3. Plug in the iPhone, trust the computer, and turn on *Settings → Privacy & Security → Developer Mode* on the phone.
   4. Pick the phone as the run destination in Xcode and run. The first time, approve the developer certificate on the phone under *Settings → General → VPN & Device Management*.

   Apps installed with a free Personal Team **expire after 7 days**; run from Xcode again to reinstall.

## 3. Commands

Run these from the repo root. On Windows use `gradlew.bat` (or `.\gradlew` in PowerShell) instead of `./gradlew`.

| Task | Command |
|---|---|
| Build the Android app | `./gradlew :androidApp:assembleDebug` |
| Install and run on a connected emulator or phone | `./gradlew :androidApp:installDebug`, then open "PianoTrainer" |
| Run iOS (Mac) | Open `iosApp/iosApp.xcodeproj` in Xcode, pick a simulator or phone, press Run |
| `core` tests (any OS) | `./gradlew :core:jvmTest` |
| Shared UI tests (any OS) | `./gradlew :composeApp:testAndroidHostTest` |
| Database tests | `./gradlew :database:jvmTest` (skipped on Intel Macs, see below) |
| Android lint | `./gradlew :androidApp:lintDebug` |
| Everything CI runs on Linux | `./gradlew :core:jvmTest :core:testAndroidHostTest :database:jvmTest :composeApp:testAndroidHostTest :androidApp:lintDebug :androidApp:assembleDebug` |
| iOS simulator tests (Mac) | `./gradlew :core:iosSimulatorArm64Test :database:iosSimulatorArm64Test :composeApp:iosSimulatorArm64Test` |

In Android Studio you can also run the `androidApp` configuration directly.

## 4. Microphone in emulators and simulators

The app records the student playing a real piano, so you'll want sound input while developing:

- **Android emulator:** the virtual microphone is off by default. Open *Extended controls (⋯) → Microphone* and turn on **"Virtual microphone uses host audio input"**. It turns itself off again when the emulator restarts.
- **iOS simulator:** uses the Mac's microphone (or whichever input is selected in macOS *System Settings → Sound*). macOS asks for microphone permission the first time.

Emulator audio is resampled and filtered by the host, so it is only good enough for checking that recording works. **Judge recording quality and pitch detection on real phones** with a real piano; see [#40](https://github.com/rudi-cilibrasi/musicprompt/issues/40).

## 5. Troubleshooting

- **"SDK location not found"**: create `local.properties` as above, or set `ANDROID_HOME`.
- **"Unsupported class file major version" or a Gradle JVM error**: Gradle is running on the wrong JDK. Use JDK 21 (`JAVA_HOME`, or *Settings → Build Tools → Gradle → Gradle JDK* in Android Studio).
- **Licences not accepted**: run `sdkmanager --licenses` (in `<sdk>/cmdline-tools/latest/bin`) and answer `y`.
- **`xcodebuild` says the active developer directory is a command-line tools instance**: run the `xcode-select -s` command from section 2.
- **Database tests are skipped on an Intel Mac**: the bundled SQLite driver has no JVM binary for Intel Macs, so `:database:jvmTest` skips its tests there. They run on Linux (CI), Windows and Apple-silicon Macs, and `:database:iosSimulatorArm64Test` runs them on the iOS simulator.
- **First iOS build is slow**: Kotlin/Native downloads its toolchain into `~/.konan` once. Later builds are faster.
