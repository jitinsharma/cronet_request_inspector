package com.jitinsharma.cronetinspector.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.jitinsharma.cronetinspector.proto.FrameWriter
import com.jitinsharma.cronetinspector.runtime.transport.LocalSocketServer

/**
 * Wires the runtime up at process start with zero app code (see runtime's
 * AndroidManifest.xml and the plan's "runtime" section): installs the async sink,
 * binds the local socket, and forwards each accepted connection's output stream as
 * the sink's transport.
 */
class CronetInspectorInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context ?: return false
        val sink = AsyncEventSink()
        CronetInspectorRuntime.sink = sink

        LocalSocketServer("cronetinspector_${context.packageName}").start { client ->
            val transport = Transport { event -> FrameWriter.write(client.outputStream, event) }
            sink.transport = transport

            // A LocalSocket write can silently succeed into an already-dead
            // connection: unlike a plain TCP socket, Android's LocalSocket (a Unix
            // domain socket under the hood) doesn't reliably surface the peer's
            // disconnect as a write failure -- confirmed live, where AsyncEventSink
            // kept treating events as successfully "delivered" into a socket whose
            // reader had actually been gone for 5+ seconds, so they never made it
            // into the replay buffer's gap for the next real connection. A blocking
            // read, by contrast, reliably unblocks with EOF (-1) or an exception the
            // instant the peer actually disconnects -- nothing is ever sent by the
            // IDE client on this stream, so this read exists purely as that
            // disconnect signal, letting the sink stop counting writes to it as
            // delivered.
            Thread({
                try {
                    while (client.inputStream.read() >= 0) {
                        // Discard; the IDE client never writes to this stream.
                    }
                } catch (_: Exception) {
                    // Expected once the client disconnects.
                }
                // Only detach if a newer connection hasn't already replaced this one.
                if (sink.transport === transport) sink.transport = null
            }, "CronetInspector-DisconnectWatcher").apply { isDaemon = true }.start()
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
