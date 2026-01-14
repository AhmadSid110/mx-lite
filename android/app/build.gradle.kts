plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mxlite.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mxlite.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // ✅ Correct for Kotlin 1.9.0 + Compose 1.5.x
        kotlinCompilerExtensionVersion = "1.5.2"
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

    // ─────────────────────────────────────────────
    // 🔒 CRITICAL: pin coroutines explicitly
    // (prevents GitHub Actions / Maven 403 failures)
    // ─────────────────────────────────────────────
    val coroutinesVersion = "1.7.3"

    // ─────────────────────────────────────────────
    // Compose (BOM controlled)
    // ─────────────────────────────────────────────
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.activity:activity-compose")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // ─────────────────────────────────────────────
    // AndroidX Core
    // ─────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.12.0")

    // ─────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")

    // ─────────────────────────────────────────────
    // Storage / SAF
    // ─────────────────────────────────────────────
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // ─────────────────────────────────────────────
    // Media3 (ONLY if you still use ExoPlayer)
    // Remove these if MediaCodec-only
    // ─────────────────────────────────────────────
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")

    // ─────────────────────────────────────────────
    // REQUIRED EXPLICIT DEPENDENCIES
    // DO NOT REMOVE
    // ─────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
}
