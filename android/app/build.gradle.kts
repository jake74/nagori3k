plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release key is outside the repository; its password is in the macOS
// Keychain (or the environment). Without them the release build is unsigned.
val releaseKeystore = file(System.getProperty("user.home") + "/.android-keys/nagori3k-release.jks")
val releaseKeyPassword: String? = providers.environmentVariable("NAGORI3K_KEYSTORE_PASSWORD").orNull
    ?: providers.exec {
        commandLine("security", "find-generic-password", "-a", "nagori3k-release", "-s", "nagori3k Android release key", "-w")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }

android {
    namespace = "gg.dekiru.nagori3k"
    compileSdk = 37

    defaultConfig {
        applicationId = "gg.dekiru.nagori3k"
        minSdk = 29          // Android 10
        targetSdk = 37
        versionCode = 3
        versionName = "0.2"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore.exists()) {
                storeFile = releaseKeystore
                storePassword = releaseKeyPassword
                keyAlias = "nagori3k"
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Not shrunk: the app is small beside the emulator core, and the
            // native code finds Kotlin methods by name.
            isMinifyEnabled = false
            if (releaseKeystore.exists() && releaseKeyPassword != null) signingConfig = signingConfigs.getByName("release")
        }
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
