plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hismith.session"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hismith.session"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.1"
    }

    // A fixed signing key lives in the repo so every build (CI or local) produces
    // an APK with the same signature -> updates install over the old version
    // without having to uninstall first. This is a private, sideload-only app.
    signingConfigs {
        create("hismith") {
            storeFile = rootProject.file("keystore/hismith.p12")
            storeType = "pkcs12"
            storePassword = "hismith123"
            keyAlias = "hismith"
            keyPassword = "hismith123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("hismith")
            applicationIdSuffix = ""
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("hismith")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        compose = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
