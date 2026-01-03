plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}


android {

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    ndk {
        abiFilters += listOf("arm64-v8a", "x86_64")
    }

    namespace = "com.mxlite.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mxlite.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.activity:activity-compose")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
