import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlinSerialization)
}

// The app's local database: Room for Kotlin Multiplatform with the bundled SQLite driver, so it
// behaves the same on Android and iOS. Tests run on the JVM against a temporary database file.
kotlin {
    compilerOptions {
        // Room's generated database constructor is an expect/actual object.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "io.github.rudicilibrasi.pianotrainer.database"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    iosArm64()
    iosSimulatorArm64()

    // Only used to run the tests on macOS, Windows and Linux (`./gradlew :database:jvmTest`).
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
        }
    }
}

room {
    // Committed, so migrations can be tested once the app is released.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    listOf("kspAndroid", "kspIosArm64", "kspIosSimulatorArm64", "kspJvm").forEach { add(it, libs.room.compiler) }
}
