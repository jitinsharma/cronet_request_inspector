package com.jitinsharma.cronetinspector.ideaplugin.ui

import com.intellij.util.ui.ListTableModel
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer

/** A waterfall bar, positioned/sized relative to the earliest visible request's
 * start time and the overall visible time span -- mirrors the reference Network
 * Inspector's Timeline column: a thin, two-tone bar (see design/ screenshots) split
 * at the response's own start time into a "waiting for response" phase (orange) and
 * a "receiving" phase (blue), the same split the reference screenshots' Overview tab
 * "Timing" bar uses. No per-phase DNS/connect/send breakdown within the "waiting"
 * phase itself yet -- this project doesn't capture that (see ROADMAP.md's
 * RequestFinishedInfo.Metrics item). */
class TimelineCellRenderer : DefaultTableCellRenderer() {
    private var row: ConnectionRow? = null
    private var windowStart: Long = 0
    private var windowSpan: Long = 1

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        rowIndex: Int,
        column: Int,
    ): Component {
        super.getTableCellRendererComponent(table, "", isSelected, hasFocus, rowIndex, column)
        row = value as? ConnectionRow

        @Suppress("UNCHECKED_CAST")
        val model = table.model as? ListTableModel<ConnectionRow>
        val items = model?.items.orEmpty()
        if (items.isNotEmpty()) {
            windowStart = items.minOf { it.startTimeMillis }
            val windowEnd = items.maxOf { it.endTimeMillis ?: it.startTimeMillis }
            windowSpan = (windowEnd - windowStart).coerceAtLeast(1)
        }
        return this
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val r = row ?: return
        val endTime = r.endTimeMillis ?: r.startTimeMillis
        val startFraction =
            ((r.startTimeMillis - windowStart).toDouble() / windowSpan).coerceIn(0.0, 1.0)
        val endFraction = ((endTime - windowStart).toDouble() / windowSpan).coerceIn(0.0, 1.0)

        val barX = (startFraction * width).toInt()
        // A real request's own duration is often a tiny fraction of the full visible
        // window (which spans every request since the table was last cleared), so a
        // proportionally-accurate bar would frequently round to a near-invisible
        // sliver -- a floor keeps every bar visible at the cost of slightly
        // overstating very short requests, same tradeoff most waterfall views make.
        val barWidth = ((endFraction - startFraction) * width).toInt().coerceAtLeast(4)
        // Thin and vertically centered, not filling most of the row -- matches the
        // reference screenshots' subtler bar, where even a selected/highlighted row's
        // bar reads as a slim accent rather than a dominant block.
        val barHeight = (height * 0.35).toInt().coerceIn(3, 8)
        val barY = (height - barHeight) / 2

        val responseStart = r.responseStartTimeMillis
        if (responseStart == null || responseStart >= endTime) {
            // No response yet (or it started right at the end, e.g. no body): a
            // single "waiting" bar for the whole span.
            g.color = WAITING_COLOR
            g.fillRect(barX, barY, barWidth, barHeight)
            return
        }

        val splitFraction = ((responseStart - windowStart).toDouble() / windowSpan).coerceIn(0.0, 1.0)
        val splitX = (splitFraction * width).toInt().coerceIn(barX, barX + barWidth)

        g.color = WAITING_COLOR
        g.fillRect(barX, barY, (splitX - barX).coerceAtLeast(1), barHeight)

        g.color = RECEIVING_COLOR
        g.fillRect(splitX, barY, (barX + barWidth - splitX).coerceAtLeast(0), barHeight)
    }

    companion object {
        val WAITING_COLOR: Color = Color(240, 180, 100)
        val RECEIVING_COLOR: Color = Color(100, 140, 220)
    }
}
