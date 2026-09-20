import org.gradle.plugins.signing.Sign

plugins {
    // Must match runtime/sample-app's Kotlin version (2.2.10, AGP 9's KGP floor):
    // runtime depends on this module directly, and Kotlin metadata isn't forward
    // compatible across compiler versions (unlike gradle-plugin, which needs 2.4.20
    // for the unrelated reason of matching Gradle's own bundled Kotlin runtime).
    kotlin("jvm") version "2.2.10"
    id("com.google.protobuf") version "0.9.4"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "com.jitinsharma.cronetinspector"
version = "0.1.0"

repositories {
    google()
    mavenCentral()
}

// Supersedes the plain `maven-publish` + `publishing{}` block this used to have:
// this plugin builds on top of maven-publish (publishToMavenLocal etc. still work
// exactly as before, verified against real httpbench consumption) and adds what
// Central actually requires beyond that -- signing, the newer Central Portal
// upload API (not the legacy OSSRH staging flow `maven-publish` alone can't do),
// and the POM metadata (license/developer/scm) Central validates on upload. See
// PUBLISHING.md for the account/credential setup this still needs.
mavenPublishing {
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

// signAllPublications() above makes EVERY publish task -- including
// publishToMavenLocal, our fast local-iteration loop for testing against a real
// external app (see PUBLISHING.md) -- fail outright with "no configured
// signatory" unless a GPG key is present, since Gradle's signing plugin doesn't
// otherwise distinguish "publishing to Central" from "publishing to Local". Only
// actually sign when the Central signing key is configured; Central itself still
// rejects unsigned artifacts on upload regardless of this.
tasks.withType<Sign>().configureEach {
    onlyIf { providers.gradleProperty("signingInMemoryKey").orNull != null }
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
