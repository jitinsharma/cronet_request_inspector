package dev.cronetinspector.runtime

import dev.cronetinspector.proto.Event

fun interface EventSink {
    fun offer(event: Event)

    companion object {
        val NONE = EventSink { }
    }
}

fun interface Transport {
    fun write(event: Event)
}
