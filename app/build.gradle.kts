plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // This is the master ID for your app. 
    namespace = "com.example.manifestscanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.manifestscanner"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // This specific version is required to match the Kotlin 1.9.22 we set earlier
        kotlinCompilerExtensionVersion = "1.5.8" 
    }
}

dependencies {
    // Add this new Material library for the XML themes
    implementation("com.google.android.material:material:1.11.0")

    // 1. Jetpack Compose (The UI Framework)
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // 2. CameraX (To control the phone's hardware camera)
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    
    // 3. Google ML Kit (Strictly the Bundled/Offline versions)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    
    // 4. Coroutines (To keep the UI from freezing while the AI thinks)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
}
