# Publishing

Three separate distribution channels, one per module that needs to be resolvable
outside this monorepo. `sample-app` never gets published (it's the test fixture).

| Module | Channel | Why |
|---|---|---|
| `gradle-plugin` | [Gradle Plugin Portal](https://plugins.gradle.org/) | The idiomatic channel for a Gradle plugin -- consumers resolve it via `plugins { id("com.jitinsharma.cronetinspector.gradle") version "..." }`, not a `classpath` dependency. |
| `runtime`, `proto` | Maven Central | Regular library dependencies. `gradle-plugin` auto-adds `debugImplementation("com.jitinsharma.cronetinspector:runtime:<version>")` when it detects a Cronet dependency and `:runtime` isn't available as a sibling project (i.e. for any consumer outside this repo) -- that coordinate needs to actually resolve. |
| `idea-plugin` | [JetBrains Marketplace](https://plugins.jetbrains.com/) | The standard channel for IntelliJ Platform / Android Studio plugins. |

## What's already wired up

- `proto` and `runtime` both use the
  [`com.vanniktech.maven.publish`](https://vanniktech.github.io/gradle-maven-publish-plugin/)
  plugin (`group = "com.jitinsharma.cronetinspector"`, versioned independently of
  the other modules), configured with `publishToMavenCentral(automaticRelease =
  true)`, `signAllPublications()`, and full POM metadata (license, developer, SCM
  -- Central validates these on upload). `runtime` additionally uses
  `configure(AndroidSingleVariantLibrary(variant = "debug", ...))` since it
  publishes an AAR, not a plain jar.
- Signing is conditional (`tasks.withType<Sign>().configureEach { onlyIf { ... } }`
  keyed on whether `signingInMemoryKey` is set) specifically so
  `publishToMavenLocal` keeps working for local iteration without a GPG key
  configured -- `signAllPublications()` on its own makes Gradle's signing plugin
  require a signatory for every publish task, local included, which would have
  broken the exact local-publish loop used to validate this tool end-to-end
  against a real external app (httpbench). Verified working end-to-end after this
  fix:
  ```
  ./gradlew :proto:publishToMavenLocal :runtime:publishToMavenLocal
  ```
  produces real artifacts under `~/.m2/repository/com/jitinsharma/cronetinspector/...`
  with `signMavenPublication` correctly `SKIPPED`, and `runtime`'s generated POM
  correctly resolves its `project(":proto")` dependency to the
  `com.jitinsharma.cronetinspector:proto:<version>` coordinate -- exactly what an
  external consumer's dependency resolution would see. `Sign` tasks stay live for
  an actual `publishToMavenCentral` once the signing key below is configured.
- `gradle-plugin` has the `com.gradle.plugin-publish` plugin configured
  (`group`/`version` set, `website`/`vcsUrl`/`displayName`/`description`/`tags` on
  the plugin declaration -- all required by the portal). Verified as far as
  possible without an account: `./gradlew :gradle-plugin:publishPlugins
  --validate-only` gets past all metadata validation and POM generation, failing
  only on the expected "Missing publishing keys" credential check.

## What's NOT done yet (needs your own accounts/credentials)

None of the following can be done without you creating and controlling the
respective accounts -- these aren't things that can be set up on your behalf. The
build-file side of each is already done (see above); what's left is account
creation and supplying credentials.

### Gradle Plugin Portal (`gradle-plugin`)

1. Create an account at https://plugins.gradle.org/ and generate an API key/secret
   (Profile -> API Keys).
2. `./gradlew :gradle-plugin:publishPlugins` with the API key/secret supplied via
   `gradle.publish.key`/`gradle.publish.secret` (Gradle properties, or env vars) --
   **never commit these**.

### Maven Central (`runtime`, `proto`)

1. Create a Central Portal account (https://central.sonatype.com/) and verify the
   `com.jitinsharma.cronetinspector` namespace against the `jitinsharma.com` domain
   (DNS TXT record) -- this repo already uses that namespace specifically so this
   step doesn't require buying a new domain.
2. Generate a GPG signing key; Central requires all artifacts to be signed.
3. Supply credentials as Gradle properties or env vars (never committed):
   ```
   ORG_GRADLE_PROJECT_mavenCentralUsername=<Central Portal user token username>
   ORG_GRADLE_PROJECT_mavenCentralPassword=<Central Portal user token password>
   ORG_GRADLE_PROJECT_signingInMemoryKey=<ASCII-armored GPG private key>
   ORG_GRADLE_PROJECT_signingInMemoryKeyId=<GPG key id>
   ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=<GPG key password>
   ```
   (The Central username/password are Central Portal user tokens, not your account
   login.)
4. `./gradlew :proto:publishAndReleaseToMavenCentral :runtime:publishAndReleaseToMavenCentral`
   (or whichever exact task the plugin version in use exposes -- check
   `./gradlew :proto:tasks` -- `publishToMavenCentral` was configured with
   `automaticRelease = true` above, so a single publish should be enough, no
   separate manual "release" step on the Central Portal website).

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
