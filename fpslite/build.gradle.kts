plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing comes from environment variables (set by CI from GitHub secrets).
// The keystore itself is never committed.
val releaseKeystore: String? = System.getenv("FPS_KEYSTORE")

android {
    namespace = "com.merlin.fpslite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.merlin.fpslite"
        minSdk = 29
        targetSdk = 34
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0-local"
    }

    signingConfigs {
        if (releaseKeystore != null && file(releaseKeystore).exists()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("FPS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FPS_KEY_ALIAS")
                keyPassword = System.getenv("FPS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "/kotlin/**", "DebugProbesKt.bin")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":fpscore"))
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
