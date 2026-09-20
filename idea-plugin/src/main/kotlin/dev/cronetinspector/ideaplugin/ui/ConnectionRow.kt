package dev.cronetinspector.ideaplugin.ui

import dev.cronetinspector.proto.Header

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
    var size: Long = 0
    var startTimeMillis: Long = 0
    var endTimeMillis: Long? = null
    var negotiatedProtocol: String = ""
    var threadName: String = ""

    var requestHeaders: List<Header> = emptyList()
    var responseHeaders: List<Header> = emptyList()
    var requestBody: ByteArray = ByteArray(0)
    var responseBody: ByteArray = ByteArray(0)

    val name: String
        get() = url.substringAfterLast('/').ifEmpty { url }

    val elapsedMillis: Long
        get() = (endTimeMillis ?: System.currentTimeMillis()) - startTimeMillis
}
