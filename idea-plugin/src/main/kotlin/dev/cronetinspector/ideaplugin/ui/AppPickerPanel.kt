package dev.cronetinspector.ideaplugin.ui

import dev.cronetinspector.ideaplugin.transport.EventStreamClient
import java.awt.BorderLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Dropdown of currently-debuggable processes on the connected device, with a Refresh
 * button -- lets the tool window target any app rather than one hardcoded package
 * name. Listing (`EventStreamClient.listDebuggableProcesses`) is a blocking adb call,
 * always run on a background thread here, never the EDT.
 */
class AppPickerPanel(
    private val client: EventStreamClient,
    private val onAppSelected: (String) -> Unit,
) : JPanel(BorderLayout()) {
    private val comboBox = JComboBox<String>()
    private val refreshButton = JButton("Refresh")

    // Guards against re-triggering onAppSelected (which restarts the capture
    // connection) when refresh() reassigns the combo box model and happens to
    // reselect the same app that was already active.
    private var activeApp: String? = null

    init {
        add(comboBox, BorderLayout.CENTER)
        add(refreshButton, BorderLayout.EAST)

        refreshButton.addActionListener { refresh() }
        comboBox.addActionListener {
            val selected = comboBox.selectedItem as? String
            if (selected != null && selected != activeApp) {
                activeApp = selected
                onAppSelected(selected)
            }
        }

        refresh()
    }

    private fun refresh() {
        refreshButton.isEnabled = false
        Thread({
            val processes = try {
                client.listDebuggableProcesses()
            } catch (_: Exception) {
                emptyList()
            }
            SwingUtilities.invokeLater {
                val previouslySelected = comboBox.selectedItem as? String
                comboBox.model = DefaultComboBoxModel(processes.toTypedArray())
                when {
                    previouslySelected != null && processes.contains(previouslySelected) ->
                        comboBox.selectedItem = previouslySelected
                    processes.isNotEmpty() -> comboBox.selectedItem = processes.first()
                }
                refreshButton.isEnabled = true
            }
        }, "CronetInspector-ProcessLister").apply { isDaemon = true }.start()
    }
}
