plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "lt.kpukas.dragonradar"
    compileSdk = 35
    defaultConfig {
        applicationId = "lt.kpukas.dragonradar"
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "0.3"
    }
}
