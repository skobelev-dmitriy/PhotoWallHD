import java.util.Properties

plugins {
    // AGP 9 has built-in Kotlin: no separate kotlin-android plugin
    id("com.android.application")
}

// ---- version: base from version.properties + git (see docs/VERSIONING.md) ----
val versionBase: String = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }.getProperty("versionBase")

/** Output of a git command run in the repository, or null if git is missing, this is not a repository, or the output is empty. */
fun git(vararg args: String): String? = try {
    providers.exec {
        workingDir = rootProject.projectDir
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }
} catch (e: Exception) {
    null
}

val commitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0
val shortSha = git("rev-parse", "--short", "HEAD") ?: "nogit"
val hasLocalChanges = git("status", "--porcelain", "--untracked-files=no") != null

// versionCode grows with every commit (100 + commits), so a newer build always replaces an older one on the device.
val appVersionCode = 100 + commitCount
// "1.4.0-abc1234" for development builds ("-dirty" if tracked files have uncommitted changes), plain "1.4.0" with -PreleaseBuild=true.
val appVersionName =
    if (providers.gradleProperty("releaseBuild").isPresent) versionBase
    else "$versionBase-$shortSha" + if (hasLocalChanges) "-dirty" else ""

tasks.register("printVersion") { doLast { println("name=$appVersionName code=$appVersionCode") } }

android {
    namespace = "app.photowall"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.photowall"
        minSdk = 23
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
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
