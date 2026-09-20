package dev.cronetinspector.runtime

import com.google.protobuf.ByteString
import dev.cronetinspector.proto.BodyChunk
import dev.cronetinspector.proto.Direction
import dev.cronetinspector.proto.Event
import dev.cronetinspector.proto.Header
import dev.cronetinspector.proto.RedirectReceived
import dev.cronetinspector.proto.RequestCompleted
import dev.cronetinspector.proto.RequestStarted
import dev.cronetinspector.proto.ResponseStarted
import dev.cronetinspector.proto.Status
import org.chromium.net.CronetException
import org.chromium.net.UploadDataSink
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Static entry points the ASM-instrumented bytecode calls into (Milestone 3 wires
 * the actual call sites inside the gradle-plugin's class visitor). For now these are
 * exercised directly by unit tests, standing in for the not-yet-built weaving.
 *
 * See plan's "Interception design": [attachToRequest] is called right after
 * `UrlRequest.Builder.build()` returns, with the exact [UrlRequest] instance that
 * will later be passed to every callback method below -- so header/URL/method
 * metadata recorded earlier against the *builder* (via [recordUrl], [recordMethod],
 * [recordHeader]) can be joined to the callback stream with a plain identity map, no
 * fragile correlation logic required. URL/method are threaded through separate
 * recordUrl/recordMethod calls (rather than as attachToRequest parameters) because
 * that's what the actual Cronet call sites look like: the URL comes from
 * `CronetEngine.newUrlRequestBuilder(url, callback, executor)` and the method from a
 * later, optional `Builder.setHttpMethod(method)` call -- neither is available at
 * `build()` time itself, only what was previously recorded against that builder
 * instance.
 */
object CronetInspectorRuntime {

    private const val MAX_BODY_BYTES = 1 * 1024 * 1024

    @Volatile
    var sink: EventSink = EventSink.NONE

    private val builderUrl =
        Collections.synchronizedMap(IdentityHashMap<Any, String>())
    private val builderMethod =
        Collections.synchronizedMap(IdentityHashMap<Any, String>())
    private val builderHeaders =
        Collections.synchronizedMap(IdentityHashMap<Any, MutableList<Header>>())
    private val builderUploadProvider =
        Collections.synchronizedMap(IdentityHashMap<Any, Any>())
    private val providerToRequest =
        Collections.synchronizedMap(IdentityHashMap<Any, UrlRequest>())
    private val requests =
        Collections.synchronizedMap(IdentityHashMap<UrlRequest, RequestState>())

    private class RequestState(val requestId: String) {
        var sentBytes = 0L
        var receivedBytes = 0L
        val requestBodyCap = SizeCap(MAX_BODY_BYTES)
        val responseBodyCap = SizeCap(MAX_BODY_BYTES)
    }

    /** Tracks how many more bytes of a size-capped stream may still be forwarded. */
    private class SizeCap(private val cap: Int) {
        private var total = 0

        @Synchronized
        fun admit(byteCount: Int): Int {
            if (total >= cap) return 0
            val allowed = minOf(byteCount, cap - total)
            total += allowed
            return allowed
        }
    }

    // --- Builder-time capture (call-site rewrites, see plan) -----------------------

    @JvmStatic
    fun recordUrl(builder: Any, url: String) {
        builderUrl[builder] = url
    }

    @JvmStatic
    fun recordMethod(builder: Any, method: String) {
        builderMethod[builder] = method
    }

    @JvmStatic
    fun recordHeader(builder: Any, name: String, value: String) {
        val headers = builderHeaders.getOrPut(builder) { mutableListOf() }
        synchronized(headers) {
            headers.add(Header.newBuilder().setName(name).setValue(value).build())
        }
    }

    @JvmStatic
    fun recordUploadProvider(builder: Any, provider: Any) {
        builderUploadProvider[builder] = provider
    }

    @JvmStatic
    fun attachToRequest(builder: Any, request: UrlRequest) {
        val url = builderUrl.remove(builder).orEmpty()
        val method = builderMethod.remove(builder) ?: "GET"
        val headers = builderHeaders.remove(builder).orEmpty()
        val requestId = UUID.randomUUID().toString()
        requests[request] = RequestState(requestId)

        builderUploadProvider.remove(builder)?.let { provider ->
            providerToRequest[provider] = request
        }

        emit(
            Event.newBuilder().setRequestStarted(
                RequestStarted.newBuilder()
                    .setRequestId(requestId)
                    .setUrl(url)
                    .setMethod(method)
                    .addAllHeaders(headers)
                    .setTimestampMillis(System.currentTimeMillis())
                    .setThreadName(Thread.currentThread().name)
            ).build()
        )
    }

    // --- UrlRequest.Callback lifecycle ----------------------------------------------

    @JvmStatic
    fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
        val state = requests[request] ?: return
        emit(
            Event.newBuilder().setRedirectReceived(
                RedirectReceived.newBuilder()
                    .setRequestId(state.requestId)
                    .setNewLocationUrl(newLocationUrl)
                    .setStatusCode(info.httpStatusCode)
                    .setTimestampMillis(System.currentTimeMillis())
            ).build()
        )
    }

    @JvmStatic
    fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
        val state = requests[request] ?: return
        val headers = info.allHeadersAsList.map {
            Header.newBuilder().setName(it.key).setValue(it.value).build()
        }
        emit(
            Event.newBuilder().setResponseStarted(
                ResponseStarted.newBuilder()
                    .setRequestId(state.requestId)
                    .setStatusCode(info.httpStatusCode)
                    .setStatusText(info.httpStatusText.orEmpty())
                    .addAllHeaders(headers)
                    .setNegotiatedProtocol(info.negotiatedProtocol.orEmpty())
                    .setTimestampMillis(System.currentTimeMillis())
            ).build()
        )
    }

    @JvmStatic
    fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
        val state = requests[request] ?: return
        emitBodyChunk(state, Direction.RESPONSE, byteBuffer, state.responseBodyCap) {
            state.receivedBytes += it
        }
    }

    @JvmStatic
    fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
        complete(request, Status.SUCCEEDED, null)
    }

    @JvmStatic
    fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
        complete(request, Status.FAILED, error.message)
    }

    @JvmStatic
    fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
        complete(request, Status.CANCELED, null)
    }

    // --- UploadDataProvider (request body) ------------------------------------------

    @JvmStatic
    fun onUploadRead(provider: Any, sink: UploadDataSink, byteBuffer: ByteBuffer) {
        val request = providerToRequest[provider] ?: return
        val state = requests[request] ?: return
        emitBodyChunk(state, Direction.REQUEST, byteBuffer, state.requestBodyCap) {
            state.sentBytes += it
        }
    }

    // ---------------------------------------------------------------------------------

    private fun emitBodyChunk(
        state: RequestState,
        direction: Direction,
        byteBuffer: ByteBuffer,
        cap: SizeCap,
        recordTotal: (Int) -> Unit,
    ) {
        // Duplicate BEFORE reading -- must never disturb the app's own position/limit,
        // since Cronet expects the app to keep consuming this exact buffer instance
        // (see plan's "Body capture" note).
        //
        // Cronet hands this buffer over in *write* mode, not read mode: per the
        // documented contract, "the buffer's position is updated to the end of the
        // received data [and] the buffer's limit is not changed" -- i.e. the new
        // bytes occupy [0, position), not [position, limit). A live device test
        // caught this: remaining() was reporting ~32KB of unused capacity as if it
        // were payload, instead of the few hundred actual response bytes. flip() on
        // the duplicate (never the original) converts it to the read-mode view this
        // code actually wants.
        val duplicate = byteBuffer.duplicate()
        duplicate.flip()
        val available = duplicate.remaining()
        recordTotal(available)

        val allowed = cap.admit(available)
        if (allowed == 0) return

        val bytes = ByteArray(allowed)
        duplicate.get(bytes)
        emit(
            Event.newBuilder().setBodyChunk(
                BodyChunk.newBuilder()
                    .setRequestId(state.requestId)
                    .setDirection(direction)
                    .setData(ByteString.copyFrom(bytes))
                    .setTruncated(allowed < available)
            ).build()
        )
    }

    private fun complete(request: UrlRequest, status: Status, errorMessage: String?) {
        val state = requests.remove(request) ?: return
        emit(
            Event.newBuilder().setRequestCompleted(
                RequestCompleted.newBuilder()
                    .setRequestId(state.requestId)
                    .setStatus(status)
                    .setErrorMessage(errorMessage.orEmpty())
                    .setTimestampMillis(System.currentTimeMillis())
                    .setSentByteCount(state.sentBytes)
                    .setReceivedByteCount(state.receivedBytes)
            ).build()
        )
    }

    private fun emit(event: Event) {
        sink.offer(event)
    }

    /** Test-only: clears all correlation state between test cases. */
    internal fun resetForTest() {
        builderUrl.clear()
        builderMethod.clear()
        builderHeaders.clear()
        builderUploadProvider.clear()
        providerToRequest.clear()
        requests.clear()
        sink = EventSink.NONE
    }
}
