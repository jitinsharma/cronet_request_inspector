package com.jitinsharma.cronetinspector.runtime

import com.jitinsharma.cronetinspector.proto.Direction
import com.jitinsharma.cronetinspector.proto.Event
import com.jitinsharma.cronetinspector.proto.Status
import org.chromium.net.CronetException
import org.chromium.net.UploadDataSink
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

class CronetInspectorRuntimeTest {

    private val events = CopyOnWriteArrayList<Event>()

    @Before
    fun setUp() {
        CronetInspectorRuntime.sink = EventSink { events.add(it) }
    }

    @After
    fun tearDown() {
        CronetInspectorRuntime.resetForTest()
    }

    @Test
    fun `attachToRequest emits RequestStarted with url, method and headers recorded on the builder`() {
        val builder = Any()
        val request = mock<UrlRequest>()

        CronetInspectorRuntime.recordUrl(builder, "https://httpbin.org/get")
        CronetInspectorRuntime.recordMethod(builder, "GET")
        CronetInspectorRuntime.recordHeader(builder, "Accept", "application/json")
        CronetInspectorRuntime.recordHeader(builder, "X-Trace", "abc")
        CronetInspectorRuntime.attachToRequest(builder, request)

        val started = events.single().requestStarted
        assertEquals("https://httpbin.org/get", started.url)
        assertEquals("GET", started.method)
        assertEquals(
            listOf("Accept" to "application/json", "X-Trace" to "abc"),
            started.headersList.map { it.name to it.value }
        )
    }

    @Test
    fun `attachToRequest defaults method to GET when setHttpMethod was never called`() {
        val builder = Any()
        val request = mock<UrlRequest>()

        CronetInspectorRuntime.recordUrl(builder, "https://httpbin.org/get")
        CronetInspectorRuntime.attachToRequest(builder, request)

        assertEquals("GET", events.single().requestStarted.method)
    }

    @Test
    fun `onReadCompleted never disturbs the caller's ByteBuffer position or limit`() {
        val request = mock<UrlRequest>()
        val info = mock<UrlResponseInfo>()
        beginRequest(request)

        // Cronet hands the callback a buffer in *write* mode: position advanced past
        // the newly-read bytes, limit unchanged (== capacity here) -- confirmed
        // against the documented contract and a live device capture, which is also
        // why this fixture is built via allocate+put rather than wrap+position+limit.
        val buffer = ByteBuffer.allocate(32)
        buffer.put("hello world".toByteArray())
        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        CronetInspectorRuntime.onReadCompleted(request, info, buffer)

        assertEquals(positionBefore, buffer.position())
        assertEquals(limitBefore, buffer.limit())
    }

    @Test
    fun `onReadCompleted emits exactly the bytes written before the current position`() {
        val request = mock<UrlRequest>()
        val info = mock<UrlResponseInfo>()
        beginRequest(request)

        val buffer = ByteBuffer.allocate(32)
        buffer.put("world".toByteArray()) // position now 5, limit unchanged at capacity 32

        CronetInspectorRuntime.onReadCompleted(request, info, buffer)

        val chunk = events.single { it.hasBodyChunk() }.bodyChunk
        assertEquals("world", chunk.data.toStringUtf8())
        assertEquals(Direction.RESPONSE, chunk.direction)
        assertFalse(chunk.truncated)
    }

    @Test
    fun `body capture truncates once the per-request cap is exceeded`() {
        val request = mock<UrlRequest>()
        val info = mock<UrlResponseInfo>()
        beginRequest(request)

        val oneMebibyte = 1 * 1024 * 1024
        val firstChunk = ByteArray(oneMebibyte) { 'a'.code.toByte() }
        val secondChunk = ByteArray(10) { 'b'.code.toByte() }

        CronetInspectorRuntime.onReadCompleted(request, info, ByteBuffer.allocate(oneMebibyte).put(firstChunk))
        CronetInspectorRuntime.onReadCompleted(request, info, ByteBuffer.allocate(secondChunk.size).put(secondChunk))

        val chunks = events.filter { it.hasBodyChunk() }.map { it.bodyChunk }
        assertEquals(1, chunks.size)
        assertFalse(chunks[0].truncated)
        assertEquals(oneMebibyte, chunks[0].data.size())
    }

    @Test
    fun `onSucceeded reports byte counts and forgets the request`() {
        val request = mock<UrlRequest>()
        val info = mock<UrlResponseInfo>()
        beginRequest(request)

        val bytes = "abc".toByteArray()
        CronetInspectorRuntime.onReadCompleted(request, info, ByteBuffer.allocate(bytes.size).put(bytes))
        CronetInspectorRuntime.onSucceeded(request, info)

        val completed = events.single { it.hasRequestCompleted() }.requestCompleted
        assertEquals(Status.SUCCEEDED, completed.status)
        assertEquals(3, completed.receivedByteCount)

        // Cronet guarantees exactly one terminal callback per request, but the
        // runtime must be defensive about a stray second call rather than crash.
        events.clear()
        CronetInspectorRuntime.onSucceeded(request, info)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `onFailed reports the exception message`() {
        val request = mock<UrlRequest>()
        beginRequest(request)
        val error = mock<CronetException>()
        whenever(error.message).thenReturn("boom")

        CronetInspectorRuntime.onFailed(request, null, error)

        val completed = events.single { it.hasRequestCompleted() }.requestCompleted
        assertEquals(Status.FAILED, completed.status)
        assertEquals("boom", completed.errorMessage)
    }

    @Test
    fun `upload body is correlated back to its request via the provider instance`() {
        val builder = Any()
        val provider = Any()
        val request = mock<UrlRequest>()
        val uploadSink = mock<UploadDataSink>()

        CronetInspectorRuntime.recordUrl(builder, "https://httpbin.org/post")
        CronetInspectorRuntime.recordMethod(builder, "POST")
        CronetInspectorRuntime.recordUploadProvider(builder, provider)
        CronetInspectorRuntime.attachToRequest(builder, request)

        val payload = "payload".toByteArray()
        CronetInspectorRuntime.onUploadRead(provider, uploadSink, ByteBuffer.allocate(payload.size).put(payload))

        val chunk = events.single { it.hasBodyChunk() }.bodyChunk
        assertEquals(Direction.REQUEST, chunk.direction)
        assertEquals("payload", chunk.data.toStringUtf8())
    }

    private fun beginRequest(request: UrlRequest) {
        val builder = Any()
        CronetInspectorRuntime.recordUrl(builder, "https://httpbin.org/get")
        CronetInspectorRuntime.recordMethod(builder, "GET")
        CronetInspectorRuntime.attachToRequest(builder, request)
        events.clear()
    }
}
