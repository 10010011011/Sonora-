import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Kotlin itself comes built into AGP 9 (no org.jetbrains.kotlin.android plugin).
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.sonora.player"
    compileSdk = 36

    defaultConfig {
        // Same application id and signing key as the Capacitor build, with a higher versionCode,
        // so this native app installs over Sonora 1.2.x and migrates its library in place.
        applicationId = "app.sonora.player"
        minSdk = 24
        targetSdk = 36
        versionCode = 20200
        versionName = "2.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("sonora") {
            storeFile = file("sonora-debug.keystore")
            storePassword = "sonora-debug"
            keyAlias = "sonora"
            keyPassword = "sonora-debug"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sonora")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sonora")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += "UnsafeOptInUsageError"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.ui.text.ExperimentalTextApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    // Newest stable on Maven at the time of writing (1.10.1 was current in spring 2026; 1.11.1 is newer).
    val media3 = "1.11.1"
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-inspector:$media3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // FFmpeg audio decoders (ALAC and other software fallbacks), built from Media3 1.11.1 source.
    // Optional at build time: the app runs without it and picks it up at runtime when present.
    val ffmpegAar = file("libs/media3-ffmpeg-decoder.aar")
    if (ffmpegAar.exists()) implementation(files(ffmpegAar))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
}
