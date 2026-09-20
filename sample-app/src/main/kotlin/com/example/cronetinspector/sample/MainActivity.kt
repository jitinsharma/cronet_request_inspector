package com.example.cronetinspector.sample

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.chromium.net.CronetEngine
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.nio.ByteBuffer

/**
 * Minimal single-shot UploadDataProvider -- exercises setUploadDataProvider's
 * call-site rewrite and the read() exit-hook (see gradle-plugin's
 * CronetCallbackHookVisitorFactory) with a real request body.
 */
class JsonUploadDataProvider(private val payload: ByteArray) : UploadDataProvider() {
    private var offset = 0

    override fun getLength(): Long = payload.size.toLong()

    override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
        val remaining = payload.size - offset
        val toWrite = minOf(remaining, byteBuffer.remaining())
        byteBuffer.put(payload, offset, toWrite)
        offset += toWrite
        uploadDataSink.onReadSucceeded(false)
    }

    override fun rewind(uploadDataSink: UploadDataSink) {
        offset = 0
        uploadDataSink.onRewindSucceeded()
    }
}

/**
 * Named subclass of UrlRequest.Callback -- validates that PROJECT-scope
 * instrumentation reaches an ordinary top-level app class.
 */
class LoggingCallback : UrlRequest.Callback() {
    override fun onRedirectReceived(
        request: UrlRequest,
        info: UrlResponseInfo,
        newLocationUrl: String
    ) {
        request.followRedirect()
    }

    override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
        request.read(ByteBuffer.allocateDirect(32 * 1024))
    }

    override fun onReadCompleted(
        request: UrlRequest,
        info: UrlResponseInfo,
        byteBuffer: ByteBuffer
    ) {
        byteBuffer.clear()
        request.read(byteBuffer)
    }

    override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) = Unit
    override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: org.chromium.net.CronetException) = Unit
    override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) = Unit
}

class MainActivity : Activity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this).apply { text = "Tap the button to fire requests." }
        val fireButton = Button(this).apply {
            text = "Send GET + GET + POST"
            setOnClickListener { fireRequests() }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(fireButton)
            addView(statusView)
        }
        setContentView(layout)
    }

    // Deliberately NOT fired from onCreate(): REQUEST_STARTED is emitted
    // synchronously the instant .build() returns, with no network I/O in between --
    // firing automatically on process start means it always races ahead of the IDE
    // tool window's connection, which needs real wall-clock time (adb forward + TCP
    // connect) to be ready. A button lets you confirm "Connected" in the tool window
    // first, then trigger requests on demand.
    private fun fireRequests() {
        statusView.text = "Sending..."

        val engine = CronetEngine.Builder(this).build()
        val executor = java.util.concurrent.Executors.newCachedThreadPool()

        // Named-class callback.
        val namedRequest = engine.newUrlRequestBuilder(
            "https://httpbin.org/get",
            LoggingCallback(),
            executor
        ).build()
        namedRequest.start()

        // Anonymous-class callback -- validates that isInstrumentable's superclass
        // walk also matches synthetic/anonymous inner classes (e.g. MainActivity$1).
        val anonymousRequest = engine.newUrlRequestBuilder(
            "https://httpbin.org/headers",
            object : UrlRequest.Callback() {
                override fun onRedirectReceived(
                    request: UrlRequest,
                    info: UrlResponseInfo,
                    newLocationUrl: String
                ) {
                    request.followRedirect()
                }

                override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                    request.read(ByteBuffer.allocateDirect(32 * 1024))
                }

                override fun onReadCompleted(
                    request: UrlRequest,
                    info: UrlResponseInfo,
                    byteBuffer: ByteBuffer
                ) {
                    byteBuffer.clear()
                    request.read(byteBuffer)
                }

                override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) = Unit
                override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: org.chromium.net.CronetException) = Unit
                override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) = Unit
            },
            executor
        ).build()
        anonymousRequest.start()

        // POST with headers and an upload body -- validates setHttpMethod, addHeader,
        // and setUploadDataProvider's call-site rewrites, plus the
        // UploadDataProvider.read() exit-hook (see plan's Milestone 3/4 notes on why
        // that one specifically has to be an exit-hook, not an entry-hook).
        val postBody = """{"hello":"world"}""".toByteArray()
        val postRequest = engine.newUrlRequestBuilder(
            "https://httpbin.org/post",
            LoggingCallback(),
            executor
        )
            .setHttpMethod("POST")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Cronet-Inspector-Sample", "true")
            .setUploadDataProvider(JsonUploadDataProvider(postBody), executor)
            .build()
        postRequest.start()

        statusView.text = "Sent. Tap again to send another batch."
    }
}
