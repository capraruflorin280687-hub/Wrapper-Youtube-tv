plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yourtoba.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yourtoba.tv"
        // Fire OS 7 is Android 9 / API 28. Nothing here needs anything newer,
        // and there are no native libraries, so ABI never comes into it.
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("sideload") {
            storeFile = file("sideload.keystore")
            storePassword = "yourtoba"
            keyAlias = "yourtoba"
            keyPassword = "yourtoba"
        }
    }

    buildTypes {
        release {
            // Keep R8 off. The app is a few hundred lines with no size pressure,
            // and an obfuscated WebView bridge is harder to debug on a TV where
            // the only diagnostic channel is `adb logcat`.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        // yourtoba.js must reach the device byte-for-byte.
        noCompress += "js"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Supplies WebViewCompat.addDocumentStartJavaScript, the Android equivalent
    // of an Electron preload: guaranteed to run before any page script.
    implementation("androidx.webkit:webkit:1.12.1")
}
