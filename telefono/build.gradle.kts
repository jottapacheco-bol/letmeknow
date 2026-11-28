// LetMeKnow del teléfono: listens to the notifications of the apps the pastor picks and sends them to the radio.
plugins {
    id("com.android.application")
}

android {
    namespace = "org.bolcr.letmeknow"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.bolcr.letmeknow"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "1.0.7"
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
