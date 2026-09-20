package com.jitinsharma.cronetinspector.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FrameWriterTest {

    @Test
    fun `round-trips a single event through length-prefixed framing`() {
        val event = requestStarted("req-1")

        val out = ByteArrayOutputStream()
        FrameWriter.write(out, event)

        val decoded = FrameWriter.read(ByteArrayInputStream(out.toByteArray()))

        assertEquals(event, decoded)
    }

    @Test
    fun `frames multiple events back to back on the same stream`() {
        val first = requestStarted("a")
        val second = requestStarted("b")

        val out = ByteArrayOutputStream()
        FrameWriter.write(out, first)
        FrameWriter.write(out, second)

        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(first, FrameWriter.read(input))
        assertEquals(second, FrameWriter.read(input))
    }

    @Test
    fun `read returns null at a clean end of stream`() {
        assertNull(FrameWriter.read(ByteArrayInputStream(ByteArray(0))))
    }

    private fun requestStarted(id: String): Event =
        Event.newBuilder().setRequestStarted(RequestStarted.newBuilder().setRequestId(id)).build()
}
