plugins {
    // Must be >= the Kotlin version Android Studio's own bundled Kotlin plugin
    // ships (2.4.0), which leaks onto this module's compile classpath the same way
    // Gradle's own bundled Kotlin runtime does for gradle-plugin -- see that
    // module's build.gradle.kts for the identical fix.
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        // Required for local()-declared platform dependencies to actually resolve
        // from disk rather than attempting (and failing) a remote Maven lookup for
        // the synthetic localIde:<productCode>:<build> coordinate local() creates.
        localPlatformArtifacts()
    }
}

// androidStudio(version) (remote resolution via androidStudioInstallers()'s Ivy
// patterns) is currently broken for every recent Android Studio release: Google's
// installer filenames now embed a marketing codename (e.g.
// "android-studio-quail4-mac_arm.dmg"), which matches none of the plugin's fixed
// [artifact]-[revision]-[classifier] / [artifact]-[classifier] Ivy patterns --
// confirmed directly against the plugin's own resolver source
// (IntelliJPlatformRepositoriesExtension.kt) and reproduced against every one of the
// last 40 entries in jb.gg/android-studio-releases-list.json. local() sidesteps that
// resolver entirely by pointing straight at an already-installed IDE.
val localAndroidStudioPath: String =
    providers.gradleProperty("androidStudio.localPath")
        .orElse("${System.getProperty("user.home")}/Applications/Android Studio.app")
        .get()

// local() itself fails on a missing path with a low-level, non-actionable Gradle
// resolution error (something like a generic "could not resolve localIde:..."), not
// a message that tells whoever's building this on a new machine what to actually do
// about it -- check up front and point them straight at the one property that fixes
// it.
require(file(localAndroidStudioPath).exists()) {
    "No Android Studio install found at '$localAndroidStudioPath'. Set the " +
        "androidStudio.localPath Gradle property to your install location, e.g. " +
        "in ~/.gradle/gradle.properties: androidStudio.localPath=/path/to/Android Studio.app"
}

dependencies {
    implementation(project(":proto"))

    intellijPlatform {
        local(localAndroidStudioPath)
        bundledPlugin("org.jetbrains.android")
    }
}

intellijPlatform {
    pluginConfiguration {
        version.set("0.1.0")
    }
}

// `compilerOptions.jvmTarget`, not `jvmToolchain(17)`: jvmToolchain() also triggers
// Gradle's toolchain auto-detection to pick which JDK actually runs the compiler,
// and that auto-detection got confused on this machine after the runIde sandbox
// session (started resolving JDK 17 to a truncated Cellar path missing the nested
// libexec/openjdk.jdk/Contents/Home bundle suffix, causing "No class roots are
// found in the JDK path"). This sets only the produced bytecode's target version --
// the compiler itself just runs on the Gradle daemon's own JVM, already pinned to
// 17 via org.gradle.java.home in gradle.properties.
// 21, not 17: the intellij-platform-gradle-plugin forces compileKotlin's target to
// 21 itself (matching Android Studio's own minRequiredJavaVersion), silently
// overriding a 17 request here -- so this module aligns with that instead of
// fighting it. proto (targeted at 17) is still a perfectly valid dependency of a
// 21-targeted consumer; only the reverse direction is a problem.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// See proto/build.gradle.kts: compilerOptions.jvmTarget alone doesn't set Gradle's
// own org.gradle.jvm.version dependency-attribute.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
