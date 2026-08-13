plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.x3dex.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.x3dex.app"
        minSdk = 29
        targetSdk = 32
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes { debug { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
