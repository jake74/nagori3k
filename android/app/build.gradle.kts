plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "gg.dekiru.nagori3k"
    compileSdk = 37

    defaultConfig {
        applicationId = "gg.dekiru.nagori3k"
        minSdk = 29          // Android 10
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    // The native libraries (the emulator core and the host layer) are built
    // by ../build-native.sh into src/main/jniLibs, not by Gradle.
    packaging {
        jniLibs { useLegacyPackaging = true } // on disk, so the host layer can load the core by path
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The same versions the Tetsubi app uses, so nothing new is downloaded.
    implementation("org.jetbrains.compose.runtime:runtime:1.12.2")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
    implementation("org.jetbrains.compose.ui:ui:1.12.2")
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("androidx.activity:activity-compose:1.13.0")
}
