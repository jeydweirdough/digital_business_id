plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ph.getmeds.card"
    compileSdk = 35

    defaultConfig {
        applicationId = "ph.getmeds.card"
        // Android 7.0+. Phone-tap sharing (HCE) exists on all of these;
        // phones without NFC still get the QR and link.
        minSdk = 24
        targetSdk = 35
        versionCode = 19
        versionName = "1.14.0"

        buildConfigField("String", "API_BASE", "\"https://getmeds-admin.vercel.app\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Release signing comes from the deploy workflow (.github/workflows/deploy.yml).
    // Without these variables the release APK is built unsigned.
    val keystore = System.getenv("SIGNING_KEYSTORE")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
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
}

dependencies {
    // QR encoding only. Everything else (HTTP, JSON, NFC, UI) is the
    // Android framework, which keeps the APK at a few MB.
    implementation("com.google.zxing:core:3.5.3")
}
