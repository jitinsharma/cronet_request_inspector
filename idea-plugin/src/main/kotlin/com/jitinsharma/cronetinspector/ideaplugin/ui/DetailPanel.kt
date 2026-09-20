package com.jitinsharma.cronetinspector.ideaplugin.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Overview/Response/Request/Call Stack tabs, mirroring the reference Network
 * Inspector screenshot's detail pane -- Response and Request each carry their own
 * Headers/Body sub-tabs, matching the screenshot's toggle between the two. */
class DetailPanel : JPanel(BorderLayout()) {
    private val overviewArea = readOnlyArea()
    private val responseHeadersArea = readOnlyArea()
    private val responseBodyArea = readOnlyArea()
    private val requestHeadersArea = readOnlyArea()
    private val requestBodyArea = readOnlyArea()
    private val callStackArea = readOnlyArea()

    init {
        val tabs = JBTabbedPane()
        tabs.addTab("Overview", JBScrollPane(overviewArea))
        tabs.addTab("Response", subTabs(responseHeadersArea, responseBodyArea))
        tabs.addTab("Request", subTabs(requestHeadersArea, requestBodyArea))
        tabs.addTab("Call Stack", JBScrollPane(callStackArea))
        add(tabs, BorderLayout.CENTER)

        callStackArea.text = "Call stack capture is not implemented yet."
        showEmpty()
    }

    fun showEmpty() {
        overviewArea.text = "Select a request to see its details."
        responseHeadersArea.text = ""
        responseBodyArea.text = ""
        requestHeadersArea.text = ""
        requestBodyArea.text = ""
    }

    fun show(row: ConnectionRow) {
        overviewArea.text = buildString {
            appendLine("Request: ${row.name}")
            appendLine("Method: ${row.method}")
            appendLine("Status: ${row.status}")
            appendLine("URL: ${row.url}")
            appendLine("Response type: ${row.type}")
            appendLine("Negotiated protocol: ${row.negotiatedProtocol}")
            appendLine("Initiating thread: ${row.threadName}")
            appendLine("Timing: ${formatLatency(row.elapsedMillis)}")
        }

        responseHeadersArea.text = row.responseHeaders.joinToString("\n") { "${it.name}: ${it.value}" }
        responseBodyArea.text = String(row.responseBody, Charsets.UTF_8)

        requestHeadersArea.text = row.requestHeaders.joinToString("\n") { "${it.name}: ${it.value}" }
        requestBodyArea.text = String(row.requestBody, Charsets.UTF_8)
    }

    private fun subTabs(headers: JBTextArea, body: JBTextArea): JComponent {
        val sub = JBTabbedPane()
        sub.addTab("Headers", JBScrollPane(headers))
        sub.addTab("Body", JBScrollPane(body))
        return sub
    }

    private fun readOnlyArea() = JBTextArea().apply {
        isEditable = false
        lineWrap = true
    }
}
