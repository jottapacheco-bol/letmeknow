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
        versionCode = 1
        versionName = "1.0.0"
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
