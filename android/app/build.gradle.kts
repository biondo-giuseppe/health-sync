plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.healthsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.healthsync"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.0"
    }

    signingConfigs {
        getByName("debug") {
            val localKeystore = file("