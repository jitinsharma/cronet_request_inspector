package com.jitinsharma.cronetinspector.ideaplugin.ui

import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import javax.swing.table.TableCellRenderer

/** Connection View columns, mirroring the reference Network Inspector screenshot
 * (Name/Size/Type/Status/Time/Timeline). */
private object NameColumn : ColumnInfo<ConnectionRow, String>("Name") {
    override fun valueOf(row: ConnectionRow): String = row.name
}

private object SizeColumn : ColumnInfo<ConnectionRow, String>("Size") {
    override fun valueOf(row: ConnectionRow): String = formatBytes(row.size)
}

private object TypeColumn : ColumnInfo<ConnectionRow, String>("Type") {
    override fun valueOf(row: ConnectionRow): String = row.type
}

private object StatusColumn : ColumnInfo<ConnectionRow, String>("Status") {
    override fun valueOf(row: ConnectionRow): String = row.status
}

private object TimeColumn : ColumnInfo<ConnectionRow, String>("Time") {
    override fun valueOf(row: ConnectionRow): String = formatLatency(row.elapsedMillis)
}

private val timelineRenderer = TimelineCellRenderer()

private object TimelineColumn : ColumnInfo<ConnectionRow, ConnectionRow>("Timeline") {
    override fun valueOf(row: ConnectionRow): ConnectionRow = row
    override fun getRenderer(row: ConnectionRow): TableCellRenderer = timelineRenderer
}

fun createConnectionTableModel(): ListTableModel<ConnectionRow> =
    ListTableModel(NameColumn, SizeColumn, TypeColumn, StatusColumn, TimeColumn, TimelineColumn)

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0.0 B"
    bytes < 1024 -> "%.1f B".format(bytes.toDouble())
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
