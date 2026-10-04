// Location in repo: app/build.gradle.kts  (the app module, not the root one)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mu.gamebot"          // change to your package
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mu.gamebot"  // must match namespace for simplicity
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Keep only real-phone CPUs so OpenCV doesn't bloat the APK
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signs release with the debug key so the APK installs without a keystore
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.activity:activity-ktx:1.9.2")

    // OpenCV (template matching + YOLO via DNN module)
    implementation("org.opencv:opencv:4.10.0")
}
