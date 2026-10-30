plugins {
    // AGP 9 has built-in Kotlin: no separate kotlin-android plugin
    id("com.android.application")
}

android {
    namespace = "app.photowall"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.photowall"
        minSdk = 23
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.1.0"
    }

    // A fixed, committed key so that every CI build is signed identically
    // and a new APK can be installed over the old one without uninstalling.
    signingConfigs {
        create("shared") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    // Kotlin's jvmTarget follows these
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
