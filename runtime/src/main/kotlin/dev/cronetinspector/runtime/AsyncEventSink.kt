package dev.cronetinspector.runtime

import dev.cronetinspector.proto.Event
import java.util.concurrent.ArrayBlockingQueue

/**
 * The [EventSink] hook methods actually call. Never blocks the caller -- hook code
 * runs on whatever executor thread the app gave Cronet, so [offer] must be cheap and
 * non-blocking (see plan's "Executor/thread safety" risk). A bounded queue absorbs
 * bursts with a drop-oldest policy, and a single background thread drains it to
 * whichever [Transport] is currently attached.
 *
 * Newly attached transports are caught up from a bounded replay buffer rather than
 * only seeing events emitted from that point forward. This matters because
 * `RequestStarted` fires synchronously the instant `Builder.build()` returns, with no
 * network I/O in between -- an app that fires a request immediately at process start
 * (a very common pattern: an initial config fetch, analytics ping, etc.) will emit it
 * before any IDE client has had the real wall-clock time needed to establish the
 * socket connection (adb forward + TCP handshake). Silently dropping events with no
 * transport attached would make that request permanently invisible even though the
 * IDE connects moments later; replaying recent history on connect fixes that.
 *
 * Replay is keyed by a monotonic sequence number, not "replay the whole buffer on
 * every attach": the buffer only needs to be replayed in full to the very first
 * transport this process ever sees. A *reconnect* to a process that's been running
 * and emitting the whole time (e.g. the IDE plugin's app picker switching away and
 * back to the same still-running app) must only get events emitted since the
 * previous transport detached -- otherwise already-seen, already-cleared-in-the-UI
 * events reappear from the buffer, which is exactly what happened before this was
 * tracked (confirmed against a real app: Clear, switch the tool window to a
 * different app, switch back to the still-running first app -- its old requests
 * came back).
 */
class AsyncEventSink(capacity: Int = 1024, private val replayCapacity: Int = 200) : EventSink {

    // Guards replayBuffer/currentTransport/lastDeliveredSeq together so a transport
    // can never be set (and replayed to) mid-way through the worker thread appending
    // an event to the buffer and delivering it live -- that interleaving would
    // either duplicate or drop the event for the newly-connecting client.
    private val lock = Any()
    private var currentTransport: Transport? = null
    private val replayBuffer = ArrayDeque<Pair<Long, Event>>()
    private var nextSeq = 0L
    private var lastDeliveredSeq = -1L

    var transport: Transport?
        get() = synchronized(lock) { currentTransport }
        set(value) {
            synchronized(lock) {
                currentTransport = value
                if (value != null) {
                    // Only advance lastDeliveredSeq past events that were ACTUALLY
                    // written successfully -- LocalSocketServer never proactively
                    // nulls out a dead transport on disconnect, only replaces it when
                    // a new client connects, so events emitted in between get handed
                    // to an already-broken transport whose write() throws. Marking
                    // those "delivered" anyway (an earlier version of this code did)
                    // would silently drop them forever instead of catching the next
                    // real connection up on the gap.
                    replayBuffer.forEach { (seq, event) ->
                        if (seq <= lastDeliveredSeq) return@forEach
                        if (!deliver(value, event)) return@forEach
                        lastDeliveredSeq = seq
                    }
                }
            }
        }

    private val queue = ArrayBlockingQueue<Event>(capacity)

    private val worker = Thread({
        while (true) {
            val event = queue.take()
            synchronized(lock) {
                val seq = nextSeq++
                replayBuffer.addLast(seq to event)
                while (replayBuffer.size > replayCapacity) replayBuffer.removeFirst()
                val transport = currentTransport
                if (transport != null && deliver(transport, event)) {
                    lastDeliveredSeq = seq
                }
            }
        }
    }, "CronetInspector-Writer").apply { isDaemon = true }

    /** Caller must hold [lock]. Returns whether the write actually succeeded. */
    private fun deliver(transport: Transport, event: Event): Boolean =
        try {
            transport.write(event)
            true
        } catch (_: Exception) {
            // Broken/disconnected transport -- never take down the host app, and
            // never write to it again until a new one is explicitly attached.
            if (currentTransport === transport) currentTransport = null
            false
        }

    init {
        worker.start()
    }

    override fun offer(event: Event) {
        if (!queue.offer(event)) {
            queue.poll()
            queue.offer(event)
        }
    }
}
