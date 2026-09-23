package com.jitinsharma.cronetinspector.ideaplugin.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.jitinsharma.cronetinspector.proto.Header
import com.jitinsharma.cronetinspector.proto.StackFrame
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.BorderLayout
import java.awt.Graphics
import javax.swing.JComponent
import javax.swing.JPanel

/** Overview/Response/Request/Call Stack tabs, mirroring the reference Network
 * Inspector screenshots (see design/ folder): bold field labels and a clickable URL
 * in Overview, a two-tone proportional Timing bar, and bold-key/monospace header
 * rendering in Response/Request -- Response's sub-tab is "Headers", Request's is
 * "Application Headers", matching the reference exactly. */
class DetailPanel : JPanel(BorderLayout()) {
    private val requestValue = JBLabel()
    private val methodValue = JBLabel()
    private val statusValue = JBLabel()
    private val responseTypeValue = JBLabel()
    private val protocolValue = JBLabel()
    private val threadValue = JBLabel()
    private val urlValue = HyperlinkLabel()
    private val timingBar = TimingBarComponent()

    private val responseHeadersPanel = JPanel(GridBagLayout())
    private val responseBodyArea = readOnlyArea()
    private val requestHeadersPanel = JPanel(GridBagLayout())
    private val requestBodyArea = readOnlyArea()
    private val callStackPanel = JPanel(GridBagLayout())

    private var currentUrl: String = ""

    init {
        val overviewPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(boldLabel("Request:"), requestValue)
            .addLabeledComponent(boldLabel("Method:"), methodValue)
            .addLabeledComponent(boldLabel("Status:"), statusValue)
            .addLabeledComponent(boldLabel("Response type:"), responseTypeValue)
            .addLabeledComponent(boldLabel("Negotiated protocol:"), protocolValue)
            .addLabeledComponent(boldLabel("Initiating thread:"), threadValue)
            .addLabeledComponent(boldLabel("URL:"), urlValue)
            .addVerticalGap(16)
            .addComponent(boldLabel("Timing"))
            .addComponent(timingBar)
            .panel

        urlValue.addHyperlinkListener { if (currentUrl.isNotEmpty()) BrowserUtil.browse(currentUrl) }

        val tabs = JBTabbedPane()
        tabs.addTab("Overview", JBScrollPane(overviewPanel))
        tabs.addTab("Response", subTabs("Headers", responseHeadersPanel, responseBodyArea))
        // "Application Headers", not "Headers": matches the reference Network
        // Inspector's own Request tab label exactly (see design/ screenshots) --
        // Response and Request use different labels there, not a shared one.
        tabs.addTab("Request", subTabs("Application Headers", requestHeadersPanel, requestBodyArea))
        tabs.addTab("Call Stack", JBScrollPane(callStackPanel))
        add(tabs, BorderLayout.CENTER)

        showEmpty()
    }

    fun showEmpty() {
        requestValue.text = ""
        methodValue.text = ""
        statusValue.text = ""
        responseTypeValue.text = ""
        protocolValue.text = ""
        threadValue.text = ""
        urlValue.setHyperlinkText("")
        currentUrl = ""
        timingBar.clear()

        populateHeaders(responseHeadersPanel, emptyList())
        responseBodyArea.text = ""
        populateHeaders(requestHeadersPanel, emptyList())
        requestBodyArea.text = ""
        populateCallStack(emptyList())
    }

    fun show(row: ConnectionRow) {
        requestValue.text = row.name
        methodValue.text = row.method
        statusValue.text = row.status
        responseTypeValue.text = row.contentType
        protocolValue.text = row.negotiatedProtocol
        threadValue.text = row.threadName
        currentUrl = row.url
        urlValue.setHyperlinkText(row.url)
        timingBar.update(row)

        populateHeaders(responseHeadersPanel, row.responseHeaders)
        responseBodyArea.text = String(row.responseBody, Charsets.UTF_8)
        populateHeaders(requestHeadersPanel, row.requestHeaders)
        requestBodyArea.text = String(row.requestBody, Charsets.UTF_8)
        populateCallStack(row.callStack)
    }

    private fun subTabs(headersLabel: String, headers: JComponent, body: JBTextArea): JComponent {
        val sub = JBTabbedPane()
        sub.addTab(headersLabel, JBScrollPane(headers))
        sub.addTab("Body", JBScrollPane(body))
        return sub
    }

    private fun readOnlyArea() = JBTextArea().apply {
        isEditable = false
        lineWrap = true
    }

    private fun boldLabel(text: String): JBLabel = JBLabel(text).apply {
        font = font.deriveFont(Font.BOLD)
    }

    /** Rebuilds a headers panel's rows in place: bold, monospace name on the left,
     * plain monospace value on the right -- mirrors the reference screenshots'
     * "name:    value" layout, where all values line up in a shared column
     * regardless of name length. */
    private fun populateHeaders(panel: JPanel, headers: List<Header>) {
        panel.removeAll()
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.NORTHWEST
            insets = Insets(1, 4, 1, 12)
        }
        headers.forEachIndexed { index, header ->
            gbc.gridy = index
            gbc.gridx = 0
            gbc.weightx = 0.0
            panel.add(monoLabel("${header.name}:", bold = true), gbc)
            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(monoLabel(header.value, bold = false), gbc)
        }
        // Filler so rows pack to the top-left instead of spreading across the panel.
        gbc.gridy = headers.size
        gbc.gridx = 0
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.VERTICAL
        panel.add(JPanel(), gbc)
        panel.revalidate()
        panel.repaint()
    }

    private fun monoLabel(text: String, bold: Boolean): JBLabel = JBLabel(text).apply {
        font = Font(Font.MONOSPACED, if (bold) Font.BOLD else Font.PLAIN, font.size)
    }

    /** Rebuilds the Call Stack tab's rows: one line per frame, "methodName:line,
     * ClassName (package.name)" with the package name in italics -- matches the
     * reference Network Inspector's own Call Stack tab (see design/ screenshots).
     * Swing's JLabel renders basic HTML natively, which is what gets the mixed
     * plain/italic styling within a single line without a heavier text component. */
    private fun populateCallStack(frames: List<StackFrame>) {
        callStackPanel.removeAll()
        val gbc = GridBagConstraints().apply {
            gridx = 0
            anchor = GridBagConstraints.NORTHWEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(1, 4, 1, 4)
        }
        if (frames.isEmpty()) {
            gbc.gridy = 0
            callStackPanel.add(JBLabel("No call stack captured for this request."), gbc)
        } else {
            frames.forEachIndexed { index, frame ->
                gbc.gridy = index
                callStackPanel.add(callStackFrameLabel(frame), gbc)
            }
        }
        gbc.gridy = frames.size.coerceAtLeast(1)
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.VERTICAL
        callStackPanel.add(JPanel(), gbc)
        callStackPanel.revalidate()
        callStackPanel.repaint()
    }

    private fun callStackFrameLabel(frame: StackFrame): JBLabel {
        val location = "${frame.methodName}:${frame.lineNumber}, ${frame.className}"
        val html = "<html>${escapeHtml(location)} <i>(${escapeHtml(frame.packageName)})</i></html>"
        return JBLabel(html, AllIcons.Nodes.Method, JBLabel.LEFT)
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** A thin, full-width, two-tone bar showing this request's own "waiting for
     * response" (orange) vs "receiving" (blue) split -- the same phase split and
     * colors as TimelineCellRenderer's per-row bar, just scaled to 0-100% of this
     * one request's own duration instead of the whole table's visible time window. */
    private class TimingBarComponent : JComponent() {
        private var waitingFraction: Double = 0.0
        private var receivingFraction: Double = 0.0

        init {
            preferredSize = Dimension(100, 20)
        }

        override fun getPreferredSize(): Dimension =
            Dimension(super.getPreferredSize().width.coerceAtLeast(1), 20)

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, 20)

        fun clear() {
            waitingFraction = 0.0
            receivingFraction = 0.0
            repaint()
        }

        fun update(row: ConnectionRow) {
            val end = row.endTimeMillis ?: row.startTimeMillis
            val total = (end - row.startTimeMillis).coerceAtLeast(1)
            val responseStart = row.responseStartTimeMillis
            if (responseStart == null) {
                waitingFraction = 1.0
                receivingFraction = 0.0
            } else {
                waitingFraction =
                    ((responseStart - row.startTimeMillis).toDouble() / total).coerceIn(0.0, 1.0)
                receivingFraction = (1.0 - waitingFraction).coerceIn(0.0, 1.0)
            }
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val waitingWidth = (width * waitingFraction).toInt()
            val receivingWidth = width - waitingWidth
            g.color = TimelineCellRenderer.WAITING_COLOR
            g.fillRect(0, 0, waitingWidth, height)
            g.color = TimelineCellRenderer.RECEIVING_COLOR
            g.fillRect(waitingWidth, 0, receivingWidth, height)
        }
    }
}
