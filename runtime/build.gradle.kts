import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.plugins.signing.Sign

plugins {
    id("com.android.library") version "9.1.1"
    id("org.jetbrains.kotlin.android") version "2.2.10"
    // `apply false`, applied conditionally below -- see proto/build.gradle.kts's
    // identical comment for the full reasoning (this module hits the same
    // cross-project MavenCentralBuildService classloader conflict when both it and
    // proto apply this plugin unconditionally).
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

group = "com.jitinsharma.cronetinspector"
version = "0.1.1"

android {
    namespace = "com.jitinsharma.cronetinspector.runtime"
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

    // No android { publishing { singleVariant("debug") } } block here: when
    // -PpublishRuntime=true, mavenPublishing's own
    // configure(AndroidSingleVariantLibrary("debug")) below registers that same
    // "debug" component itself -- registering it twice fails with "Using
    // singleVariant publishing DSL multiple times... is not allowed." This library
    // is only ever meant to be added as debugImplementation anyway, matching the
    // whole project's debug-only instrumentation scope, so there's no need for a
    // publishable component when not actually publishing.
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

// See proto/build.gradle.kts's identical block for the full reasoning behind
// gating this behind a Gradle property (`-PpublishRuntime=true`) instead of
// applying unconditionally.
if (providers.gradleProperty("publishRuntime").isPresent) {
    apply(plugin = "com.vanniktech.maven.publish")

    // configure<MavenPublishBaseExtension>, not the `mavenPublishing { }`
    // type-safe accessor -- see proto/build.gradle.kts's identical comment for
    // why.
    configure<MavenPublishBaseExtension> {
        // Android-specific form (this publishes an AAR, the "debug" variant
        // specifically) instead of the plain-JVM one proto uses.
        configure(
            AndroidSingleVariantLibrary(
                variant = "debug",
                sourcesJar = SourcesJar.Sources(),
                javadocJar = JavadocJar.None(),
            )
        )
        publishToMavenCentral(automaticRelease = true)
        signAllPublications()

        coordinates(group.toString(), "runtime", version.toString())

        pom {
            name.set("Cronet Network Inspector -- runtime")
            description.set(
                "In-app runtime the Cronet Network Inspector Gradle plugin auto-wires " +
                    "in (debugImplementation only): captures Cronet request/response " +
                    "events and streams them to the Android Studio plugin over a local " +
                    "socket."
            )
            url.set("https://github.com/jitinsharma/cronet_request_inspector")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("jitinsharma")
                    name.set("Jitin Sharma")
                    url.set("https://github.com/jitinsharma/")
                }
            }
            scm {
                url.set("https://github.com/jitinsharma/cronet_request_inspector/")
                connection.set("scm:git:git://github.com/jitinsharma/cronet_request_inspector.git")
                developerConnection.set(
                    "scm:git:ssh://git@github.com/jitinsharma/cronet_request_inspector.git"
                )
            }
        }
    }

    // See proto/build.gradle.kts's identical block for why this is needed:
    // without it, publishToMavenLocal (our fast local-iteration loop, e.g.
    // against httpbench) fails outright requiring a GPG key that has nothing to
    // do with local testing.
    tasks.withType<Sign>().configureEach {
        onlyIf { providers.gradleProperty("signingInMemoryKey").orNull != null }
    }
}

// Gradle's toolchain auto-detection keeps finding and using some other JDK 25 on
// this machine for the unit-test launcher JVM (regardless of org.gradle.java.home
// and auto-download settings), which breaks Mockito's inline mock maker. A literal
// executable path bypasses toolchain resolution entirely.
tasks.withType<Test>().configureEach {
    executable = "/opt/homebrew/opt/openjdk@17/bin/java"
}
