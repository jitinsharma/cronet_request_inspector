plugins {
    id("com.android.application") version "9.1.1"
    id("org.jetbrains.kotlin.android") version "2.2.10"
    id("com.jitinsharma.cronetinspector.gradle")
}

android {
    namespace = "com.example.cronetinspector.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.cronetinspector.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
    // 119.6045.31's cronet-embedded/-common/-api AARs all declare the same
    // 'org.chromium.net' namespace, which AGP 9 now hard-fails on (was only a
    // warning under AGP 8). The consolidated 'cronet' artifact is the current
    // replacement for cronet-embedded.
    implementation("org.chromium.net:cronet:500.0.2")
}
