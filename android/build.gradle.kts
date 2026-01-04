plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    // 🔴 MEDIA3 — REQUIRED
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
}
