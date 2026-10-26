import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

// Pure Kotlin logic shared by every platform: music model, DSP, scoring, progression.
// No UI and no platform APIs, so everything here can be tested on any OS.
kotlin {
    android {
        namespace = "io.github.rudicilibrasi.pianotrainer.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()

    // Only used to run the tests quickly on macOS, Windows and Linux (`./gradlew :core:jvmTest`).
    // There is no desktop app.
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
