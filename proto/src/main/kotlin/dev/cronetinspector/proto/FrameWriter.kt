package dev.cronetinspector.proto

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed protobuf framing: a 4-byte big-endian length followed by the
 * serialized [Event]. Chosen over newline-delimited JSON so multi-KB binary body
 * payloads stream cleanly with no escaping overhead (see plan's "proto" section).
 */
object FrameWriter {

    @Throws(IOException::class)
    fun write(out: OutputStream, event: Event) {
        val bytes = event.toByteArray()
        out.write(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            )
        )
        out.write(bytes)
        out.flush()
    }

    /** Returns null at a clean end-of-stream (no partial frame in flight). */
    @Throws(IOException::class)
    fun read(input: InputStream): Event? {
        val lengthBytes = input.readExactly(4) ?: return null
        val length =
            ((lengthBytes[0].toInt() and 0xFF) shl 24) or
                ((lengthBytes[1].toInt() and 0xFF) shl 16) or
                ((lengthBytes[2].toInt() and 0xFF) shl 8) or
                (lengthBytes[3].toInt() and 0xFF)
        val payload = input.readExactly(length)
            ?: throw IOException("Stream ended mid-frame (wanted $length bytes)")
        return Event.parseFrom(payload)
    }

    // java.io.InputStream#readNBytes needs API 33; minSdk here is 24.
    private fun InputStream.readExactly(n: Int): ByteArray? {
        if (n == 0) return ByteArray(0)
        val buffer = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = read(buffer, offset, n - offset)
            if (read == -1) return if (offset == 0) null else throw IOException("Stream ended mid-frame")
            offset += read
        }
        return buffer
    }
}
