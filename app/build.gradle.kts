plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.livemic.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.stevendzama.livemic"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.5"
    }

    // The keystore comes from CI (see .github/workflows/build-apk.yml) and never lives in the repo.
    // Without it, e.g. in Android Studio, release builds fall back to the debug key.
    val keystore = System.getenv("LIVEMIC_KEYSTORE")
    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("LIVEMIC_KEYSTORE_PASSWORD")
                keyAlias = "livemic"
                keyPassword = System.getenv("LIVEMIC_KEYSTORE_PASSWORD")
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
