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
        versionCode = 3
        versionName = "1.1.1"
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        named("main") {
            resources.srcDir("src/main/resources")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:101.0.1")
}
