package com.jitinsharma.cronetinspector.ideaplugin.transport

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.tools.idea.adb.AdbService
import com.intellij.openapi.project.Project
import com.jitinsharma.cronetinspector.proto.Event
import com.jitinsharma.cronetinspector.proto.FrameWriter
import java.io.IOException
import java.net.ConnectException
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects to a single device/app's local socket and streams decoded [Event]
 * messages to a listener on a background thread. Same ddmlib-forward +
 * framed-protobuf transport validated headlessly in Milestone 5 (see the
 * now-superseded HeadlessClient), now driving the real tool window UI.
 *
 * Not bound to one application id: [start] can be called again with a different one
 * (e.g. the user picking a different process from the app picker), tearing down
 * whatever stream is currently running first.
 */
class EventStreamClient(
    private val project: Project,
    private val onEvent: (Event) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile
    private var socket: Socket? = null

    fun start(applicationId: String, localPort: Int = 17890) {
        stop()
        running.set(true)
        thread = Thread({
            try {
                run(applicationId, localPort)
            } catch (t: Throwable) {
                if (running.get()) onError(t)
            }
        }, "CronetInspector-EventStreamClient").apply {
            isDaemon = true
            start()
        }
    }

    /** Closing the socket is what actually unblocks the read loop below -- a plain
     * blocking java.net.Socket read does not respond to Thread.interrupt(). */
    fun stop() {
        running.set(false)
        try {
            socket?.close()
        } catch (_: Exception) {
            // Already closed/never opened -- nothing to do.
        }
        thread = null
    }

    /**
     * Blocking -- call from a background thread, never the EDT. Package names of
     * currently debuggable client processes on the first online device, for the app
     * picker dropdown.
     */
    fun listDebuggableProcesses(): List<String> {
        val bridge = getBridgeBlocking()
        val device = waitForDeviceBlocking(bridge, running = { true })
        return device.clients.mapNotNull { it.clientData.packageName }.distinct().sorted()
    }

    private fun run(applicationId: String, localPort: Int) {
        onStatus("Connecting to adb...")
        val bridge = getBridgeBlocking()

        val device = waitForDeviceBlocking(bridge, running = running::get)
        if (!running.get()) return
        onStatus("Using device ${device.serialNumber}")

        val socketName = "cronetinspector_$applicationId"
        device.createForward(localPort, socketName, IDevice.DeviceUnixSocketNamespace.ABSTRACT)
        onStatus("Forwarded tcp:$localPort -> localabstract:$socketName")

        // Retries the socket connection itself, not the adb/forward setup above:
        // the app process (and therefore its LocalServerSocket) can restart at any
        // time -- e.g. a fresh cold start after a force-stop -- which either refuses
        // the connection outright (nothing listening yet) or drops an established
        // one. Without this loop the client would give up permanently on the very
        // first such event instead of reconnecting once the new process comes up.
        while (running.get()) {
            try {
                Socket("127.0.0.1", localPort).use { s ->
                    socket = s
                    onStatus("Connected to $applicationId")
                    val input = s.getInputStream()
                    while (running.get()) {
                        val event = FrameWriter.read(input) ?: break
                        onEvent(event)
                    }
                }
            } catch (_: ConnectException) {
                // Nothing listening yet (app not started, or mid-restart) -- retry.
            } catch (_: IOException) {
                if (!running.get()) return
                // Connection dropped, most likely the app process restarted -- retry.
            }
            if (!running.get()) return
            onStatus("Disconnected -- waiting for the app to (re)start...")
            Thread.sleep(RECONNECT_DELAY_MS)
        }
    }

    // Running inside Android Studio itself: its own Android plugin owns
    // AndroidDebugBridge's static init/delegate lifecycle via AdbService (which,
    // depending on Studio version, backs it with the newer adblib rather than
    // classic ddmlib). Calling AndroidDebugBridge.init()/createBridge()/etc. directly
    // races that ownership -- first surfacing as "init() has already been called",
    // then as "delegate not set, pre init must be called" once fixed halfway. Going
    // through AdbService.getDebugBridge() instead is the same call Studio's own
    // device-list code (DdmlibAndroidDebugBridge) makes, so it always sees a
    // properly-initialized bridge.
    //
    // The Project overload (rather than resolving an adb executable path ourselves,
    // e.g. from the ANDROID_HOME environment variable) also sidesteps a real
    // reliability problem for a published plugin: Studio is frequently launched as a
    // GUI app that does not inherit the shell's environment variables at all
    // (routinely true on macOS), so an ANDROID_HOME-based fallback would silently
    // fail for a lot of real users. This asks Studio for whatever SDK path it is
    // actually configured with instead.
    private fun getBridgeBlocking(): AndroidDebugBridge =
        AdbService.getInstance().getDebugBridge(project).get(15, TimeUnit.SECONDS)

    private fun waitForDeviceBlocking(
        bridge: AndroidDebugBridge,
        running: () -> Boolean,
        timeoutMs: Long = 15_000,
    ): IDevice {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && running()) {
            if (bridge.hasInitialDeviceList()) {
                val online = bridge.devices.filter { it.isOnline }
                if (online.isNotEmpty()) return online.first()
            }
            Thread.sleep(200)
        }
        error("No online adb device found within ${timeoutMs}ms.")
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 1500L
    }
}
