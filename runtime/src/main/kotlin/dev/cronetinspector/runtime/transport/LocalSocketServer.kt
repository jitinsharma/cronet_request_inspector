package dev.cronetinspector.runtime.transport

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.IOException

/**
 * Binds an abstract-namespace local socket (Stetho's pattern -- see plan's
 * "idea-plugin" section) that the IDE plugin reaches via `adb forward`. Accepts one
 * client at a time; each new connection simply replaces whichever transport the
 * caller currently has attached, so an IDE reconnect self-heals without any
 * explicit teardown handshake.
 */
class LocalSocketServer(private val socketName: String) {

    @Volatile
    private var running = false
    private var serverSocket: LocalServerSocket? = null

    fun start(onClientConnected: (LocalSocket) -> Unit) {
        if (running) return
        running = true
        Thread({
            try {
                val server = LocalServerSocket(socketName)
                serverSocket = server
                while (running) {
                    try {
                        onClientConnected(server.accept())
                    } catch (e: IOException) {
                        if (running) Log.w(TAG, "accept() failed on '$socketName'", e)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Failed to bind local socket '$socketName'", e)
            }
        }, "CronetInspector-Accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // Already closed/unbound -- nothing to do.
        }
    }

    private companion object {
        const val TAG = "CronetInspector"
    }
}
