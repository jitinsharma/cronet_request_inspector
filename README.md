# Cronet Network Inspector

Android Studio's built-in Network Inspector only understands OkHttp,
`HttpURLConnection`, and gRPC -- apps built on [Cronet](https://developer.android.com/develop/connectivity/cronet)
(`org.chromium.net`) get no network visibility from Studio at all. This project
closes that gap: a Gradle plugin that auto-instruments Cronet traffic with zero app
code changes, plus a standalone Android Studio tool window that mirrors the built-in
Network Inspector's UI.

## How it works

- **`gradle-plugin`** -- applied as a single line in your app module. Uses AGP's
  public Instrumentation API (ASM bytecode weaving, debug builds only) to rewrite
  your app's own `UrlRequest.Callback`/`UploadDataProvider` subclasses and a handful
  of narrow Cronet `Builder` call sites, teeing request/response/body data into the
  runtime. No source changes in your app.
- **`runtime`** -- the in-app library the plugin wires in automatically. Buffers
  captured events and streams them over a local socket.
- **`proto`** -- the wire schema shared between the in-app runtime and the IDE
  plugin (length-prefixed protobuf).
- **`idea-plugin`** -- a standalone Android Studio tool window. Connects to a
  running device via `adb forward`, lets you pick any debuggable process, and
  renders a Connection View + detail pane (Overview/Response/Request/Call Stack)
  matching the built-in Network Inspector's layout.
- **`sample-app`** -- a minimal Cronet client used as the end-to-end test fixture
  for all of the above.

See [ROADMAP.md](ROADMAP.md) for planned enhancements (per-request DNS/connect/TLS
timing via `RequestFinishedInfo.Metrics`, call stack capture).

## Status

Functionally working end-to-end, validated live through the actual Android Studio
sandbox (not just unit tests) against both this repo's own `sample-app` and an
independent, externally-authored real app using `cronet-okhttp`. Not yet published
anywhere -- see below.

**Known limitations:**
- Debug builds only (by design -- matches how the built-in Network Inspector only
  works on debuggable/profileable processes).
- Instrumentation reaches `UrlRequest.Callback`/`UploadDataProvider` subclasses in
  your app's own module *and* in library dependencies (e.g. a Cronet-OkHttp bridge
  library), but deliberately excludes Cronet's own `org.chromium.net` package --
  see `CronetCallbackHookVisitorFactory`'s doc comment for why. Scanning every
  dependency's classes this way has a real, currently-unoptimized build-time cost
  (see ROADMAP.md).
- A known third-party quirk: at least one Cronet-OkHttp bridge library can report a
  request as canceled here even though the app itself received a normal successful
  response (see ROADMAP.md's "Known limitation" section).
- Call stack capture is not implemented yet (see ROADMAP.md).

## Building and trying it locally

Requires JDK 21 (used for the whole build; individual modules target 17 or 21
explicitly, see each `build.gradle.kts` for why).

```
./gradlew build
```

To try the Gradle plugin against your own Cronet-using app, point your project's
`settings.gradle.kts` at this repo's `gradle-plugin` via `includeBuild(...)`, and
apply `id("dev.cronetinspector.gradle")` in your app module.

To run the IDE plugin in a sandboxed Android Studio instance:

```
./gradlew :idea-plugin:runIde
```

This requires a local Android Studio install (see `idea-plugin/build.gradle.kts` --
`androidStudio(version)`'s remote resolution is currently broken for every recent
Android Studio release due to a bug in the upstream Gradle plugin, so this uses
`local(path)` against an installed copy instead; override the path with the
`androidStudio.localPath` Gradle property if yours isn't at the default location).

## License

Apache License 2.0 -- see [LICENSE](LICENSE).
