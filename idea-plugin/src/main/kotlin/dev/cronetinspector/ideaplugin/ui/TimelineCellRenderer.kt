package dev.cronetinspector.ideaplugin.ui

import com.intellij.util.ui.ListTableModel
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer

/** A minimal waterfall bar, positioned/sized relative to the earliest visible
 * request's start time and the overall visible time span -- mirrors the reference
 * screenshot's Timeline column at a first-pass level of fidelity (no per-phase
 * DNS/connect/send/wait/receive breakdown yet). */
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
        val startFraction =
            ((r.startTimeMillis - windowStart).toDouble() / windowSpan).coerceIn(0.0, 1.0)
        val endFraction =
            (((r.endTimeMillis ?: r.startTimeMillis) - windowStart).toDouble() / windowSpan)
                .coerceIn(0.0, 1.0)

        val barX = (startFraction * width).toInt()
        // A real request's own duration is often a tiny fraction of the full visible
        // window (which spans every request since the table was last cleared), so a
        // proportionally-accurate bar would frequently round to a near-invisible
        // sliver -- a larger floor keeps every bar clickable/visible at the cost of
        // slightly overstating very short requests, same tradeoff most waterfall
        // views make.
        val barWidth = ((endFraction - startFraction) * width).toInt().coerceAtLeast(6)
        val barY = 4
        val barHeight = (height - 8).coerceAtLeast(6)

        g.color = Color(240, 180, 100)
        g.fillRect(barX, barY, barWidth, barHeight)
    }
}
