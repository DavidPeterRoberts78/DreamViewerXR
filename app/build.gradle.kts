plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dreamshell.vr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dreamshell.vr"
        minSdk = 33          // Android 13+: new Bluetooth GATT APIs
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Framework APIs only: no AndroidX, no third-party libraries.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
