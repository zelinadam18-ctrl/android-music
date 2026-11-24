import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
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

    // Only used to run the tests quickly on macOS, Windows and Linux (`./gradlew :core:jvmTest`).
    // There is no desktop app.
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The JVM tests read the bundled songs from composeApp. Declare them as an input so that changing a
// song re-runs the tests instead of reusing a cached result.
tasks.named<Test>("jvmTest") {
    inputs.dir(rootProject.layout.projectDirectory.dir("composeApp/src/commonMain/composeResources/files/songs"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("bundledSongs")
}
