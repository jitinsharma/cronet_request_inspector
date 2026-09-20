# Roadmap

Planned enhancements, not yet implemented.

## Filter `InstrumentationScope.ALL`'s per-class scan

Both ASM visitors moved from `InstrumentationScope.PROJECT` to `ALL` (see
`CronetInspectorPlugin`) to reach Cronet-OkHttp bridge libraries like
`com.google.net.cronet:cronet-okhttp`, whose own `OkHttpBridgeRequestCallback`/
`StreamingUploadDataProvider` classes implement `UrlRequest.Callback`/
`UploadDataProvider` inside the library's own compiled bytecode -- invisible at
`PROJECT` scope, confirmed against a real app (httpbench) that produced zero
captured events until this fix.

`CronetCallSiteVisitorFactory.isInstrumentable` is still unconditionally `true`,
meaning every class in every dependency (AndroidX, Compose, Kotlin stdlib, ...) now
gets enumerated and scanned, not just the handful of classes in the app's own
module. Real but currently-accepted cost for debug builds only. A worthwhile
follow-up: a cheap pre-filter (e.g. only classes whose package/class name looks
Cronet-related) to cut down what actually gets the full per-instruction
`MethodVisitor` treatment, without accidentally excluding the app's own
arbitrarily-named classes (which is why this wasn't just done inline -- `ClassData`
doesn't expose a simple "is this a project class" flag to build that filter on).

## Per-request network metrics via `RequestFinishedInfo.Metrics`

Wire up `CronetEngine.addRequestFinishedListener(...)` (the `attachToEngine` stub in
`CronetInspectorRuntime`, currently a no-op) to surface Cronet's own internal timing
and connection data, in addition to what we already derive from the callback stream.

`org.chromium.net.RequestFinishedInfo.Metrics` exposes:

```java
Long getTotalTimeMs()
Long getTtfbMs()                          // time to first byte
Long getSentByteCount()
Long getReceivedByteCount()
boolean getSocketReused()
Date getDnsStart() / getDnsEnd()
Date getConnectStart() / getConnectEnd()
Date getSslStart() / getSslEnd()
Date getSendingStart() / getSendingEnd()
Date getPushStart() / getPushEnd()        // HTTP/2 server push, rare
Date getRequestStart()
Date getResponseStart()
Date getRequestEnd()
```

What's worth surfacing beyond our current single latency number:

- **TTFB** (`getTtfbMs`) -- separates "server think time" from "body download time."
- **`getSocketReused`** -- explains *why* DNS/Connect/SSL phases are absent for some
  requests (a pooled connection skips all three); show this directly rather than
  only implying it from missing phases.
- **`getTotalTimeMs`** -- Cronet's own authoritative total (computed natively,
  without our JVM-callback-dispatch overhead) as a cross-check against
  `endTimeMillis - startTimeMillis`.
- **DNS / Connect / SSL phase breakdown** -- a conditional Overview section, present
  only for requests that didn't reuse a connection.
- Byte counts are already accurate on our side (tallied before the 1MB truncation
  cap applies) -- useful only as an independent cross-check, not new data.

### The correlation problem

`RequestFinishedInfo` has no `getRequestId()`/`getUrlRequest()` accessor -- nothing
maps a `RequestFinishedInfo` back to a specific `UrlRequest` instance. Matching by URL
is not reliable (concurrent requests to the same URL are common -- our own sample app
does this).

Fix: `UrlRequest.Builder.addRequestAnnotation(Object)` lets the app attach an
arbitrary object to a request, and that same object comes back unchanged in
`RequestFinishedInfo.getAnnotations()`. Needs one more ASM call-site rewrite (see
`CronetCallSiteVisitorFactory`): inject `builder.addRequestAnnotation(requestId)`
right before the existing `build()` rewrite, so our own UUID round-trips through
Cronet and lets `attachToEngine`'s listener join a `RequestFinishedInfo` back to the
correct `ConnectionRow`.

### Scope

- `runtime`: implement `attachToEngine`, register the listener, add proto fields for
  the phase timestamps/TTFB/socket-reuse.
- `gradle-plugin`: add the `addRequestAnnotation` call-site rewrite.
- `idea-plugin`: conditional DNS/Connect/SSL section in the Overview tab, TTFB and
  connection-reuse fields.

## Known limitation: cronet-okhttp bridge misreports status on some requests

Against a real app (httpbench) using `com.google.net.cronet:cronet-okhttp`, some
requests that the app sees as a normal 200 OkHttp `Response` still surface here as
`CANCELED` with a 0B body. Root cause (per the app author): the bridge library's own
internal `onCanceled` fires after it has already delivered a full successful response
to OkHttp -- this runtime's hooks are read-only and never call `UrlRequest.cancel()`,
so this is a genuine quirk in that third-party bridge, not our instrumentation.

Tried reclassifying `onCanceled` as `SUCCEEDED` when a prior `onResponseStarted` had
recorded a 2xx/3xx status, but the raw Cronet callback sequence for these requests
didn't actually include a captured `onResponseStarted` before the cancel, so the
reclassification never triggered -- reverted. Deliberately not pursuing further for
now: low value relative to effort, and any fix would need to special-case a specific
third-party library's internal behavior rather than general Cronet semantics.
