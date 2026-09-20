package dev.cronetinspector.ideaplugin.ui

/** "823 ms" below one second, "1.234 s" at or above -- matches how the reference
 * Network Inspector formats its Time column/timing detail. */
fun formatLatency(millis: Long): String =
    if (millis >= 1000) "%.3f s".format(millis / 1000.0) else "$millis ms"
