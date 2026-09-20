# Publishing

Three separate distribution channels, one per module that needs to be resolvable
outside this monorepo. `sample-app` never gets published (it's the test fixture).

| Module | Channel | Why |
|---|---|---|
| `gradle-plugin` | [Gradle Plugin Portal](https://plugins.gradle.org/) | The idiomatic channel for a Gradle plugin -- consumers resolve it via `plugins { id("com.jitinsharma.cronetinspector.gradle") version "..." }`, not a `classpath` dependency. |
| `runtime`, `proto` | Maven Central | Regular library dependencies. `gradle-plugin` auto-adds `debugImplementation("com.jitinsharma.cronetinspector:runtime:<version>")` when it detects a Cronet dependency and `:runtime` isn't available as a sibling project (i.e. for any consumer outside this repo) -- that coordinate needs to actually resolve. |
| `idea-plugin` | [JetBrains Marketplace](https://plugins.jetbrains.com/) | The standard channel for IntelliJ Platform / Android Studio plugins. |

## What's already wired up

- `proto` and `runtime` both have `maven-publish` configured (`group =
  "com.jitinsharma.cronetinspector"`, versioned independently of the other modules). Verified
  working end-to-end:
  ```
  ./gradlew :proto:publishToMavenLocal :runtime:publishToMavenLocal
  ```
  produces real artifacts under `~/.m2/repository/com/jitinsharma/cronetinspector/...`, and
  `runtime`'s generated POM correctly resolves its `project(":proto")` dependency to
  the `com.jitinsharma.cronetinspector:proto:<version>` coordinate -- exactly what an external
  consumer's dependency resolution would see.

## What's NOT done yet (needs your own accounts/credentials)

None of the following can be done without you creating and controlling the
respective accounts -- these aren't things that can be set up on your behalf.

### Gradle Plugin Portal (`gradle-plugin`)

1. Create an account at https://plugins.gradle.org/ and generate an API key/secret
   (Profile -> API Keys).
2. Add the `com.gradle.plugin-publish` plugin to `gradle-plugin/build.gradle.kts`,
   plus `website`/`vcsUrl`/`tags`/`description` metadata on the plugin declaration
   (the portal requires these).
3. `./gradlew publishPlugins` with the API key/secret supplied via
   `gradle.publish.key`/`gradle.publish.secret` (Gradle properties, or env vars) --
   **never commit these**.

### Maven Central (`runtime`, `proto`)

1. Create a Central Portal account (https://central.sonatype.com/) and register/
   verify ownership of the `com.jitinsharma.cronetinspector` namespace (or switch to a namespace
   you actually control, e.g. a `io.github.<username>`-style group id, which Central
   accepts without separate domain verification).
2. Generate a GPG signing key; Central requires all artifacts to be signed.
3. Add the `signing` and a Central-publishing plugin (`maven-publish` alone isn't
   enough anymore -- Central requires the newer Central Portal API, not the legacy
   OSSRH staging flow) to both modules' `build.gradle.kts`.
4. Supply credentials via `gradle.properties`/env vars, never committed.

### JetBrains Marketplace (`idea-plugin`)

1. Create a JetBrains Marketplace account and register a unique plugin ID (the
   current `com.jitinsharma.cronetinspector.ideaplugin` in `plugin.xml` is a placeholder --
   confirm it's actually available, or pick a different one).
2. Add a real plugin icon, a Marketplace-quality description, and a changelog.
3. Sign the plugin (`intellijPlatform.signing` in the IntelliJ Platform Gradle
   Plugin's DSL) with a certificate chain -- required for Marketplace uploads.
4. `./gradlew :idea-plugin:publishPlugin` with the token supplied via Gradle
   property/env var.

Worth doing before any of this: `idea-plugin/build.gradle.kts` depends on
`local(path)` against an installed Android Studio copy (path overridable via the
`androidStudio.localPath` Gradle property, with a clear error if it's missing --
see its own comments for why `androidStudio(version)`'s normal remote resolution is
used instead). That still means *some* real, licensed Android Studio install has to
exist on whatever machine runs the build -- a property override doesn't remove that
requirement, it only stops the path itself from being hardcoded to one machine. A
JetBrains Marketplace CI build agent won't have one pre-installed, so this needs
either a build step that downloads/installs Android Studio first, or `androidStudio(version)`
fixed upstream (see the comment in `idea-plugin/build.gradle.kts` for the specific
break: installer filenames now embed a marketing codename the plugin's resolver
patterns don't match).
