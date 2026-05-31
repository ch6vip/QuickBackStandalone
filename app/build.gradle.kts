plugins {
    id("com.android.application")
}

android {
    namespace = "com.sevtinge.quickback"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sevtinge.quickback"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
