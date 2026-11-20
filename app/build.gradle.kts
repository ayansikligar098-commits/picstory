import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Persistent signing: the Android debug keystore lives at /root/.android and
// is wiped whenever the VM is replaced, which breaks installing updates
// ("package conflicts"). Debug builds are signed with this project's own
// keystore in keystore/ (never committed; passwords in keystore.properties).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.picstory.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.picstory.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "2.0"
    }

    signingConfigs {
        create("persistent") {
            storeFile = rootProject.file("keystore/picstory.keystore")
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("persistent")
        }
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
