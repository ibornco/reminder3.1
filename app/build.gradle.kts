plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.iborn.reminder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iborn.reminder.preview"
        minSdk = 26
        targetSdk = 34
        versionCode = 18
        versionName = "3.0.2.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Android System WebView renders the bundled offline HTML/CSS/JS.
}
