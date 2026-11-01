// Runs the storage / playlist logic of the app on a plain JVM (no Android SDK, no emulator):
// the framework classes come from Robolectric's android-all jar. Usage: gradle run
plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories { mavenCentral() }

dependencies {
    compileOnly("org.robolectric:android-all:17-robolectric-15733970")
    runtimeOnly("org.robolectric:android-all:17-robolectric-15733970")
}

// the app's own sources, compiled unchanged
sourceSets { main { kotlin.srcDir("../../app/src/main/java") } }

application { mainClass.set("StorageTestKt") }
