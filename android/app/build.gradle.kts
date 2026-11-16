plugins {
    id("com.android.application")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
