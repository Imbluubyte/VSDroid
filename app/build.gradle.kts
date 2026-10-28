plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Fixed signing key, so every build can be installed over the previous one. CI provides it through these
// environment variables (see README, "Release signing"). Without them (local builds, forks, pull requests
// from forks) the build falls back to Android's debug key and still works.
val signingKeystore: File? = System.getenv("VSDROID_KEYSTORE_FILE")
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?.takeIf { it.isFile }

android {
    namespace = "com.vsdroid.ide"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vsdroid.ide"
        // API 30+ gives us "All files access".
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (signingKeystore != null) {
            create("vsdroid") {
                storeFile = signingKeystore
                storePassword = System.getenv("VSDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VSDROID_KEY_ALIAS")
                keyPassword = System.getenv("VSDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (signingKeystore != null) signingConfig = signingConfigs.getByName("vsdroid")
        }
        release {
            isMinifyEnabled = false
            // Fixed key when available, otherwise the debug key so the APK is installable out of the box.
            signingConfig = signingConfigs.getByName(if (signingKeystore != null) "vsdroid" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Lua interpreter for extensions (pure Java, no native code).
    implementation("org.luaj:luaj-jse:3.0.1")
}
