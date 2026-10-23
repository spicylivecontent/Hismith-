plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing: CI/local can supply their own key through environment variables (see README,
// "Signering"). Without them the build falls back to the key in keystore/ so updates keep
// installing over earlier versions – that key is public and should be rotated.
val envKeystore: String? = System.getenv("HISMITH_KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "com.hismith.session"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hismith.session"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "2.2.0"
    }

    signingConfigs {
        create("hismith") {
            if (envKeystore != null) {
                storeFile = file(envKeystore)
                storeType = System.getenv("HISMITH_KEYSTORE_TYPE") ?: "pkcs12"
                storePassword = System.getenv("HISMITH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HISMITH_KEY_ALIAS")
                keyPassword = System.getenv("HISMITH_KEY_PASSWORD") ?: System.getenv("HISMITH_KEYSTORE_PASSWORD")
            } else {
                storeFile = rootProject.file("keystore/hismith.p12")
                storeType = "pkcs12"
                storePassword = "hismith123"
                keyAlias = "hismith"
                keyPassword = "hismith123"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("hismith")
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
        viewBinding = false
        compose = false
        buildConfig = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    // WebSocket client for the remote link (the only third-party runtime dependency).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
