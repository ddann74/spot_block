plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.spotblock.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.spotblock.app"
        // 24 (Android 7.0) is the floor for AccessibilityService reliably
        // supporting performAction(ACTION_CLICK) the way this app depends on -
        // there's no meaningful fallback below that.
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Local-music-during-ads (docs/TODO.md): ExoPlayer + MediaSessionService,
    // not the legacy android.media.MediaPlayer - see AdMusicPlaybackService's
    // doc comment for why (Android's Background Audio Hardening + gapless
    // queue playback both point to media3 over hand-rolling both).
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    testImplementation("junit:junit:4.13.2")
}
