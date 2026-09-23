package com.jitinsharma.cronetinspector.ideaplugin.ui

import com.jitinsharma.cronetinspector.proto.Header
import com.jitinsharma.cronetinspector.proto.StackFrame

/** Mutable per-request row backing the Connection View table, updated in place as
 * each event for that request arrives (see CronetToolWindowPanel.handleEvent). */
class ConnectionRow(val requestId: String, url: String, method: String) {
    // Mutable: a row may be created as a placeholder from an out-of-order event
    // (see CronetToolWindowPanel.withRow) before REQUEST_STARTED itself arrives with
    // the real url/method.
    var url: String = url
        private set
    var method: String = method
        private set

    fun applyStarted(url: String, method: String) {
        this.url = url
        this.method = method
    }

    var status: String = ""
    var type: String = ""
    // Full response Content-Type, e.g. "text/plain" -- distinct from `type` above
    // (a short guess like "plain"/"json" for the table's Type column): mirrors the
    // real Network Inspector, which shows the short guess in the Connection View
    // table but the full MIME type in the Overview detail tab's "Response type"
    // field (see design/ screenshots).
    var contentType: String = ""
    var size: Long = 0
    var startTimeMillis: Long = 0
    var endTimeMillis: Long? = null
    // When RESPONSE_STARTED arrived -- splits the Timeline/Overview timing bars into
    // a "waiting for response" phase (start -> this) and a "receiving" phase (this ->
    // end), matching the two-tone bar in the reference screenshots. Null until (or
    // unless) a response actually starts.
    var responseStartTimeMillis: Long? = null
    var negotiatedProtocol: String = ""
    var threadName: String = ""

    var requestHeaders: List<Header> = emptyList()
    var responseHeaders: List<Header> = emptyList()
    var requestBody: ByteArray = ByteArray(0)
    var responseBody: ByteArray = ByteArray(0)
    var callStack: List<StackFrame> = emptyList()

    val name: String
        get() = url.substringAfterLast('/').ifEmpty { url }

    val elapsedMillis: Long
        get() = (endTimeMillis ?: System.currentTimeMillis()) - startTimeMillis
}
