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
    // Not org.chromium.net:cronet:500.0.2 (used previously): that "consolidated"
    // artifact's own dependency graph pulls in org.chromium.net:httpengine-native-
    // provider instead of any artifact that actually bundles a native .so --
    // confirmed by inspecting that AAR directly (just a manifest + a 506-byte
    // classes.jar, no lib/*.so at all). It delegates to Android's OS-level
    // android.net.http.HttpEngine platform API, and when that's unavailable/unusable
    // on the test device, Cronet's provider selection has nowhere else in this
    // dependency graph to fall back to except org.chromium.net:cronet-fallback --
    // the pure Java/HttpURLConnection-based implementation ("JavaCronetEngine" in
    // logcat) -- silently, with no visible error. That's genuinely misleading for
    // this project's own fixture: this app's whole purpose is exercising real
    // Cronet, and Android Studio's stock Network Inspector can actually see
    // JavaCronetEngine traffic (it's really HttpsURLConnection under the hood),
    // which is precisely the confusing symptom that surfaced this.
    //
    // play-services-cronet instead: the same dependency httpbench (this repo's
    // other, externally-authored validation fixture) uses, already confirmed live
    // to deliver genuine native Cronet on this exact emulator setup.
    implementation("com.google.android.gms:play-services-cronet:18.1.1")
}

configurations.all {
    resolutionStrategy {
        // See httpbench/app/build.gradle.kts's identical force -- cronet-api 141.x+
        // splits out org.chromium.net:cronet-shared, which declares the same
        // AndroidManifest namespace ("org.chromium.net") as cronet-api itself and
        // fails AGP's unique-namespace check. Pin to the last single-artifact
        // release instead.
        force("org.chromium.net:cronet-api:119.6045.31")
    }
}
