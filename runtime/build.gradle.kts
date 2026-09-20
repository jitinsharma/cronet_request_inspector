plugins {
    id("com.android.library") version "9.1.1"
    id("org.jetbrains.kotlin.android") version "2.2.10"
    `maven-publish`
}

group = "dev.cronetinspector"
version = "0.1.0"

android {
    namespace = "dev.cronetinspector.runtime"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // debug only: this library is only ever meant to be added as
    // debugImplementation by the gradle-plugin, matching the whole project's
    // debug-only instrumentation scope.
    publishing {
        singleVariant("debug")
    }
}

publishing {
    publications {
        register<MavenPublication>("debug") {
            artifactId = "runtime"
            afterEvaluate {
                from(components["debug"])
            }
        }
    }
}

dependencies {
    implementation(project(":proto"))

    // Cronet's Java API types only appear as parameter types in the hook methods
    // below -- the consuming app already provides the real classes at runtime, so
    // this stays compileOnly to avoid bundling a duplicate copy into our AAR.
    compileOnly("org.chromium.net:cronet-api:500.0.2")

    testImplementation("org.chromium.net:cronet-api:500.0.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

// Gradle's toolchain auto-detection keeps finding and using some other JDK 25 on
// this machine for the unit-test launcher JVM (regardless of org.gradle.java.home
// and auto-download settings), which breaks Mockito's inline mock maker. A literal
// executable path bypasses toolchain resolution entirely.
tasks.withType<Test>().configureEach {
    executable = "/opt/homebrew/opt/openjdk@17/bin/java"
}
