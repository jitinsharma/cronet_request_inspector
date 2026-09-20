plugins {
    `java-gradle-plugin`
    // Must be >= the Kotlin version Gradle itself bundles for its Kotlin DSL runtime
    // (2.4.0 as of Gradle 9.7.1), since this module -- being a Gradle-plugin project
    // itself -- inherits that onto its classpath and hits metadata-version conflicts
    // otherwise. Unrelated to AGP's own KGP floor (that applies to the Android
    // modules below, not to this standalone Gradle-plugin project's own compiler).
    kotlin("jvm") version "2.4.20"
}

dependencies {
    compileOnly("com.android.tools.build:gradle:9.1.1")
    compileOnly("com.android.tools.build:gradle-api:9.1.1")
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")
}

gradlePlugin {
    plugins {
        create("cronetInspector") {
            id = "com.jitinsharma.cronetinspector.gradle"
            implementationClass = "com.jitinsharma.cronetinspector.gradle.CronetInspectorPlugin"
        }
    }
}

// `compilerOptions.jvmTarget`, not `jvmToolchain(17)`: see proto/build.gradle.kts
// for why (toolchain auto-detection is unreliable here; this sets only the produced
// bytecode's target version, without triggering it).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// java-gradle-plugin's own compileJava task otherwise infers its target from
// whatever JDK ends up running the daemon, which on this machine has proven
// unreliable more than once already -- pin it explicitly rather than infer it,
// matching Kotlin's jvmTarget above (this module has no actual .java sources, but
// the task's declared target is still checked against compileKotlin's).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
