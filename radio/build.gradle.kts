// LetMeKnow del radio (Atoto S8 Gen2, Android 10): shows the phone's notifications as floating bubbles, in big letters.
import java.util.Properties

plugins {
    id("com.android.application")
}

// The keys file of the pastor's computer (never in GitHub), read as Levite reads it.
val keys = Properties().apply {
    listOf(file("D:/Levite/claves.properties"), rootProject.file("claves.properties")).forEach { place ->
        providers.fileContents(objects.fileProperty().fileValue(place)).asText.orNull?.let { if (isEmpty) load(it.reader()) }
    }
}

android {
    namespace = "org.bolcr.letmeknow.radio"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.bolcr.letmeknow.radio"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "1.0.6"
        // Google's voice (Chirp 3 HD), the key Levite uses for its voices. Empty: the radio's own voice reads.
        // On GitHub it comes from the repository's secret of the same name.
        val ttsKey = keys.getProperty("TTS_KEY_LEVITE") ?: providers.environmentVariable("TTS_KEY_LEVITE").orNull ?: ""
        buildConfigField("String", "TTS_KEY_LEVITE", "\"${ttsKey.trim()}\"")
    }
    buildFeatures {
        buildConfig = true
    }

    // The fixed signature (the SIGNING_KEYSTORE secret, put in place by CI), so every version installs over the last.
    val fixed = file(System.getProperty("user.home") + "/.android/debug.keystore")
    signingConfigs {
        create("fixed") {
            storeFile = fixed
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (fixed.exists()) signingConfigs.getByName("fixed") else signingConfigs.getByName("debug")
        }
    }

    lint {
        // The minutes of the free runner are few; the release check would add several.
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // What the phone and the radio say to each other (shared/) is compiled into both.
    sourceSets {
        getByName("main") {
            kotlin.directories.add("../shared/src/main/java")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Today's emojis inside the app: Android 10 does not know the new ones and shows a box instead.
    implementation("androidx.emoji2:emoji2-bundled:1.4.0")
}
