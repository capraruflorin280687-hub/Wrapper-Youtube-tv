plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yourtoba"
    compileSdk = 35

    defaultConfig {
        // Fire OS 6 is Android 7.1 / API 25, and that is what a first-generation
        // Fire TV Stick 4K actually runs (6.7.1.1 confirmed on the target
        // device). An earlier build assumed Fire OS 7 / API 28 and simply could
        // not be installed: sideload installers report that as "split or bad
        // apk" rather than naming the SDK mismatch.
        //
        // 21 rather than 25, because every API used here landed in 21 and it
        // costs nothing to also cover Fire OS 5 devices. There are no native
        // libraries, so ABI never comes into it.
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "device"

    productFlavors {
        // Fire TV / Android TV: Leanback interface, remote control.
        create("tv") {
            dimension = "device"
            applicationId = "com.yourtoba.tv"
        }
        // Phone: the ordinary mobile YouTube site, touch driven. Separate
        // applicationId so both can coexist on one device if wanted.
        create("phone") {
            dimension = "device"
            applicationId = "com.yourtoba.mobile"
        }
    }

    signingConfigs {
        create("sideload") {
            storeFile = file("sideload.keystore")
            storePassword = "yourtoba"
            keyAlias = "yourtoba"
            keyPassword = "yourtoba"
            // v2 alone is enough for Android 9+, and AGP omits v1 by default at
            // this minSdk. But the Fire TV sideload path is rarely the platform
            // installer: it is Downloader, or a file manager, and several of
            // those parse the APK themselves and reject anything with no v1 JAR
            // signature block as "bad" or as a split. Signing all three costs
            // nothing and makes the APK acceptable to every installer.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    // Strips the Play-store dependency metadata blob AGP otherwise embeds in the
    // APK signing block. Useless for a sideloaded personal build, and an unknown
    // signing-block entry is another thing a third-party installer can trip on.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
