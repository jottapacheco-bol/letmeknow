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
        versionCode = 1
        versionName = "1.0.0"
        // Google's voice (Chirp 3 HD), the key Levite uses for its voices. Empty: the radio's own voice reads.
        buildConfigField("String", "TTS_KEY_LEVITE", "\"${keys.getProperty("TTS_KEY_LEVITE", "").trim()}\"")
    }
    buildFeatures {
        buildConfig = true
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
