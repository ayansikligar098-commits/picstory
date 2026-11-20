plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.picstory.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.picstory.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // HTTP for the PicStory backend (REST only, no WebSocket in v1).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
