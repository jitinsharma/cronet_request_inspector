package com.jitinsharma.cronetinspector.ideaplugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.TableView
import com.jitinsharma.cronetinspector.ideaplugin.transport.EventStreamClient
import com.jitinsharma.cronetinspector.proto.Direction
import com.jitinsharma.cronetinspector.proto.Event
import com.jitinsharma.cronetinspector.proto.Header
import com.jitinsharma.cronetinspector.proto.Status
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * The tool window's content: connection table + detail pane split -- mirrors the
 * reference Network Inspector screenshot's layout. Data flows in from
 * [EventStreamClient] on a background thread and is applied to the Swing models via
 * [SwingUtilities.invokeLater], same as any other Swing app.
 *
 * Uses [TableView], not plain `JBTable`: `ColumnInfo.getRenderer()` (used by
 * [TimelineColumn][ConnectionTableModel]) is only consulted by `TableView`'s
 * `ListTableModel`-aware cell rendering -- a plain `JBTable` falls back to calling
 * `toString()` on the cell value, which is precisely the "ConnectionRow@2da991d6"
 * text bug this fixes.
 */
class CronetToolWindowPanel(
    project: Project,
) : JPanel(BorderLayout()), Disposable {

    private val tableModel = createConnectionTableModel()
    private val table = TableView(tableModel)
    private val detailPanel = DetailPanel()
    private val statusLabel = JBLabel("Pick an app to inspect.")

    private val rowsById = LinkedHashMap<String, ConnectionRow>()

    private val client = EventStreamClient(
        project = project,
        onEvent = { event -> SwingUtilities.invokeLater { handleEvent(event) } },
        onStatus = { status -> SwingUtilities.invokeLater { statusLabel.text = status } },
        onError = { error ->
            SwingUtilities.invokeLater { statusLabel.text = "Error: ${error.message}" }
        },
    )

    init {
        val splitter = JBSplitter(false, 0.55f)
        splitter.firstComponent = JBScrollPane(table)
        splitter.secondComponent = detailPanel

        val appPicker = AppPickerPanel(client) { applicationId ->
            // Switching target app: old rows belong to whatever was previously
            // selected and would be misleading mixed in with the new app's traffic.
            clearAll()
            client.start(applicationId)
        }
        val clearButton = JButton("Clear").apply { addActionListener { clearAll() } }
        val toolbar = JPanel(BorderLayout()).apply {
            add(appPicker, BorderLayout.CENTER)
            add(clearButton, BorderLayout.EAST)
        }

        add(toolbar, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        table.selectionModel.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val viewRow = table.selectedRow
            if (viewRow < 0) {
                detailPanel.showEmpty()
                return@addListSelectionListener
            }
            val modelRow = table.convertRowIndexToModel(viewRow)
            detailPanel.show(tableModel.getItem(modelRow))
        }
    }

    private fun handleEvent(event: Event) {
        when (event.kindCase) {
            Event.KindCase.REQUEST_STARTED -> withRow(event.requestStarted.requestId) { row ->
                val started = event.requestStarted
                row.applyStarted(started.url, started.method)
                row.startTimeMillis = started.timestampMillis
                row.threadName = started.threadName
                row.requestHeaders = started.headersList
                row.status = "..."
            }

            Event.KindCase.RESPONSE_STARTED -> withRow(event.responseStarted.requestId) { row ->
                val started = event.responseStarted
                row.status = started.statusCode.toString()
                row.type = guessType(started.headersList)
                row.negotiatedProtocol = started.negotiatedProtocol
                row.responseHeaders = started.headersList
            }

            Event.KindCase.BODY_CHUNK -> withRow(event.bodyChunk.requestId) { row ->
                val chunk = event.bodyChunk
                val bytes = chunk.data.toByteArray()
                if (chunk.direction == Direction.RESPONSE) {
                    row.responseBody += bytes
                    row.size += bytes.size
                } else {
                    row.requestBody += bytes
                }
            }

            Event.KindCase.REQUEST_COMPLETED -> withRow(event.requestCompleted.requestId) { row ->
                val completed = event.requestCompleted
                row.endTimeMillis = completed.timestampMillis
                if (completed.status != Status.SUCCEEDED) {
                    row.status = completed.status.name
                }
            }

            else -> Unit
        }
    }

    /**
     * Looks up the row for [requestId], creating and adding a placeholder if this is
     * the first event seen for it. REQUEST_STARTED is not guaranteed to be the first
     * event actually processed here -- e.g. a tool-window reconnect after the app
     * process restarts can plausibly miss exactly that one event while still
     * catching later ones for the same request -- so every event type must be able
     * to originate a row, not just REQUEST_STARTED, or those requests would
     * otherwise vanish from the table entirely instead of just missing a few fields.
     */
    private fun withRow(requestId: String, update: (ConnectionRow) -> Unit) {
        var isNew = false
        val row = rowsById.getOrPut(requestId) {
            isNew = true
            ConnectionRow(requestId, url = "(unknown)", method = "?")
        }
        if (isNew) tableModel.addRow(row)

        update(row)

        val index = tableModel.items.indexOf(row)
        if (index >= 0) tableModel.fireTableRowsUpdated(index, index)
        if (table.selectedRow >= 0 && tableModel.getItem(table.convertRowIndexToModel(table.selectedRow)) == row) {
            detailPanel.show(row)
        }
    }

    /** Clears the visible list only -- capture itself (the EventStreamClient
     * connection and the on-device replay buffer) keeps running untouched, same as
     * the reference Network Inspector's own Clear action. */
    private fun clearAll() {
        rowsById.clear()
        // Not emptyList(): ListTableModel.addRow() mutates whatever list is
        // currently assigned, and Kotlin's emptyList() is an immutable singleton --
        // the very next addRow() after Clear would throw
        // UnsupportedOperationException, silently breaking the table for the rest
        // of the session (every request after that point gets tracked internally
        // but never actually added to the visible table).
        tableModel.items = mutableListOf()
        detailPanel.showEmpty()
    }

    private fun guessType(headers: List<Header>): String {
        val contentType = headers.firstOrNull { it.name.equals("content-type", ignoreCase = true) }
            ?.value
            ?: return ""
        return when {
            contentType.contains("json") -> "json"
            contentType.contains("html") -> "html"
            contentType.contains("image") -> "image"
            else -> contentType.substringBefore(';')
        }
    }

    override fun dispose() {
        client.stop()
    }
}
