import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.plugins.signing.Sign

plugins {
    // Must match runtime/sample-app's Kotlin version (2.2.10, AGP 9's KGP floor):
    // runtime depends on this module directly, and Kotlin metadata isn't forward
    // compatible across compiler versions (unlike gradle-plugin, which needs 2.4.20
    // for the unrelated reason of matching Gradle's own bundled Kotlin runtime).
    kotlin("jvm") version "2.2.10"
    id("com.google.protobuf") version "0.9.4"
    // `apply false`, applied conditionally below -- see that block's comment for
    // why. Declaring it here (rather than at the root, or omitting it and applying
    // imperatively with no plugins{} entry at all) keeps it in the SAME classloader
    // scope as this project's own kotlin("jvm") above, which
    // com.vanniktech.maven.publish's own plugin code needs (it reflectively checks
    // Kotlin's plugin classes on apply, and throws if they're not in the same
    // classloader as itself).
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

group = "com.jitinsharma.cronetinspector"
version = "0.1.0"

repositories {
    google()
    mavenCentral()
}

dependencies {
    // `api`, not `implementation`: both runtime (Android) and idea-plugin (plain
    // JVM) need the generated Event/RequestStarted/etc. types on their own
    // compile classpath, not just this module's internal implementation.
    api("com.google.protobuf:protobuf-javalite:3.25.3")

    testImplementation("junit:junit:4.13.2")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    generateProtoTasks {
        // Plain Java/Kotlin projects (unlike Android ones) already register a
        // default "java" builtin -- reconfigure it for lite rather than create()ing
        // a second one, which conflicts.
        all().forEach { task ->
            task.builtins.named("java") {
                option("lite")
            }
        }
    }
}

// Maven Central publishing, gated behind a Gradle property (`-PpublishProto=true`)
// instead of applied unconditionally.
//
// Why: com.vanniktech.maven.publish registers a shared MavenCentralBuildService.
// Applying it unconditionally in BOTH this module and runtime (which also
// publishes to Central) makes Gradle load that service under a SEPARATE
// classloader per project ("Cannot set the value of task ... using a provider ...
// loaded with [a different] InstrumentingVisitableURLClassLoader"), which breaks
// EVERY IDE sync/multi-project build regardless of whether you're actually trying
// to publish -- confirmed live, this is exactly what broke on IntelliJ project
// import. Root-level `apply false` + subproject `apply` (Gradle's own documented
// fix for that specific problem) was tried and rejected: it also requires the
// Kotlin plugin the publish plugin auto-detects to be centralized the SAME way,
// and this module's Kotlin version (2.2.10, an AGP 9 requirement, confirmed by a
// live `com.android.build.gradle.BaseExtension` failure when bumped) can't be
// unified with idea-plugin's separate, incompatible use of the same Kotlin plugin
// ID at 2.4.20.
//
// Gating the actual `apply()` call behind a flag means normal builds and IDE
// import never load this plugin in either module at all, so the cross-module
// classloader conflict never triggers. Publishing for real means running proto and
// runtime as two SEPARATE `./gradlew` invocations (each with only its own
// `-Ppublish<Module>=true`), e.g.:
//   ./gradlew -PpublishProto=true :proto:publishToMavenCentral
//   ./gradlew -PpublishRuntime=true :runtime:publishToMavenCentral
// -- not combined in one command, since Gradle configures the whole project graph
// per invocation regardless of which task is requested, so a single invocation
// with both flags set would still hit the same conflict.
if (providers.gradleProperty("publishProto").isPresent) {
    apply(plugin = "com.vanniktech.maven.publish")

    // configure<MavenPublishBaseExtension>, not the `mavenPublishing { }`
    // type-safe accessor: that accessor is only generated for a plugin declared
    // WITHOUT `apply false` -- since this one is conditionally applied, it has to
    // be looked up this way instead.
    configure<MavenPublishBaseExtension> {
        publishToMavenCentral(automaticRelease = true)
        signAllPublications()

        coordinates(group.toString(), "proto", version.toString())

        pom {
            name.set("Cronet Network Inspector -- proto")
            description.set(
                "Shared wire schema (length-prefixed protobuf) between the Cronet " +
                    "Network Inspector's in-app runtime and its Android Studio plugin."
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

    // signAllPublications() above makes EVERY publish task -- including
    // publishToMavenLocal, our fast local-iteration loop for testing against a
    // real external app (see PUBLISHING.md) -- fail outright with "no configured
    // signatory" unless a GPG key is present, since Gradle's signing plugin
    // doesn't otherwise distinguish "publishing to Central" from "publishing to
    // Local". Only actually sign when the Central signing key is configured;
    // Central itself still rejects unsigned artifacts on upload regardless of
    // this.
    tasks.withType<Sign>().configureEach {
        onlyIf { providers.gradleProperty("signingInMemoryKey").orNull != null }
    }
}

// `compilerOptions.jvmTarget`, not `jvmToolchain(17)`: jvmToolchain() also triggers
// Gradle's toolchain auto-detection to pick which JDK actually RUNS the compiler,
// and that auto-detection is unreliable on this machine (see idea-plugin's
// build.gradle.kts for the full story). This sets only the produced bytecode's
// target version -- the compiler itself just runs on the Gradle daemon's own JVM,
// already pinned to 17 via org.gradle.java.home in gradle.properties.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// compilerOptions.jvmTarget above only controls the Kotlin compiler's own output --
// it does NOT set Gradle's own org.gradle.jvm.version dependency-attribute (used for
// consumer/producer compatibility checks, e.g. by idea-plugin's compileClasspath
// resolution against this module). That attribute is derived from
// sourceCompatibility/targetCompatibility instead, which otherwise silently drifted
// to whatever JDK actually ran the build.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
