import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// -Pcutover=true  -> real app id (app.sonora.player): installs OVER the old WebView app (one-way door).
// -PwithFfmpeg=true -> adds the FFmpeg audio decoder module (ALAC, AC3, DTS ...). GPL-3.0 licensed: see PLAN > Licensing.
val cutover = providers.gradleProperty("cutover").orNull == "true"
val withFfmpeg = providers.gradleProperty("withFfmpeg").orNull == "true"

android {
    namespace = "app.sonora.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.sonora.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 20000
        versionName = "2.0.0-native"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sonora")
            if (!cutover) applicationIdSuffix = ".next"
            resValue("string", "app_name", if (cutover) "Sonora" else "Sonora Next")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("sonora")
            if (!cutover) applicationIdSuffix = ".next"
            resValue("string", "app_name", if (cutover) "Sonora" else "Sonora Next")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    if (withFfmpeg) implementation(libs.jellyfin.ffmpeg)
    testImplementation(libs.junit)
}
