pluginManagement {
    includeBuild("gradle-plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // No repositoriesMode override: Gradle's default (project-declared repositories
    // win when a subproject declares its own; these settings-level ones are the
    // fallback for subprojects that don't). PREFER_SETTINGS was tried earlier to
    // dodge FAIL_ON_PROJECT_REPOS conflicts, but it turned out to silently discard
    // idea-plugin's own repositories{} block entirely (its special local IntelliJ
    // Platform Ivy repository never got consulted, no matter what was added there --
    // every resolution attempt only ever searched these two).
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "cronet-network-inspector"

include(":sample-app")
include(":runtime")
include(":proto")
include(":idea-plugin")
