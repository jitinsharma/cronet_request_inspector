package dev.cronetinspector.runtime

import dev.cronetinspector.proto.Event
import dev.cronetinspector.proto.RequestStarted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AsyncEventSinkTest {

    @Test
    fun `delivers offered events to the attached transport in order`() {
        val received = CopyOnWriteArrayList<Event>()
        val latch = CountDownLatch(3)
        val sink = AsyncEventSink()
        sink.transport = Transport { event ->
            received.add(event)
            latch.countDown()
        }

        repeat(3) { i -> sink.offer(event("evt-$i")) }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("evt-0", "evt-1", "evt-2"), received.map { it.requestStarted.requestId })
    }

    @Test
    fun `offer never blocks the caller even with a full queue and no transport`() {
        val sink = AsyncEventSink(capacity = 4)
        // Overflows the bounded queue; if offer() blocked/deadlocked instead of
        // dropping the oldest entry, this test would hang and time out.
        repeat(100) { i -> sink.offer(event("evt-$i")) }
    }

    @Test
    fun `events emitted before a transport attaches are replayed once one connects`() {
        val sink = AsyncEventSink()
        sink.offer(event("early-1"))
        sink.offer(event("early-2"))

        // Give the worker thread time to process these into the replay buffer
        // before any transport attaches -- simulates an app firing a request well
        // before the IDE has had time to establish its socket connection.
        Thread.sleep(200)

        val received = CopyOnWriteArrayList<Event>()
        val latch = CountDownLatch(2)
        sink.transport = Transport { event ->
            received.add(event)
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("early-1", "early-2"), received.map { it.requestStarted.requestId })
    }

    @Test
    fun `replay buffer is capped and drops the oldest entries`() {
        val sink = AsyncEventSink(replayCapacity = 3)
        repeat(5) { i -> sink.offer(event("evt-$i")) }
        Thread.sleep(200)

        val received = CopyOnWriteArrayList<Event>()
        sink.transport = Transport { event -> received.add(event) }

        assertEquals(listOf("evt-2", "evt-3", "evt-4"), received.map { it.requestStarted.requestId })
    }

    @Test
    fun `reconnecting a transport does not replay events already delivered to a previous transport`() {
        // Reproduces a live bug: the IDE plugin's app picker disconnects and later
        // reconnects to the SAME still-running process (e.g. switching to another
        // app and back) -- that second attach must not resurrect events the first
        // transport already received and the user may have since cleared from the
        // UI, only whatever was emitted in between.
        val sink = AsyncEventSink()
        val firstReceived = CopyOnWriteArrayList<Event>()
        val firstLatch = CountDownLatch(2)
        sink.transport = Transport { event ->
            firstReceived.add(event)
            firstLatch.countDown()
        }
        sink.offer(event("evt-0"))
        sink.offer(event("evt-1"))
        assertTrue(firstLatch.await(2, TimeUnit.SECONDS))

        // First transport detaches (e.g. the IDE switched to a different app).
        sink.transport = null
        sink.offer(event("evt-2"))
        Thread.sleep(200)

        // Second transport attaches (e.g. the IDE switched back).
        val secondReceived = CopyOnWriteArrayList<Event>()
        sink.transport = Transport { event -> secondReceived.add(event) }

        assertEquals(listOf("evt-2"), secondReceived.map { it.requestStarted.requestId })
    }

    @Test
    fun `events written to a now-broken transport are not lost -- they replay to the next transport`() {
        // Reproduces a second live bug found while fixing the first one: nothing
        // proactively detaches a transport when its underlying connection dies (e.g.
        // the IDE process is killed, or the OS just hasn't reported it yet) --
        // LocalSocketServer only replaces it when a NEW client connects. Any event
        // emitted in that gap gets handed to a transport whose write() throws; an
        // earlier version of this fix still marked those events as "delivered"
        // anyway, which meant they were silently dropped forever instead of showing
        // up when the IDE reconnects.
        val sink = AsyncEventSink()
        sink.transport = Transport { throw java.io.IOException("broken pipe") }

        sink.offer(event("during-outage"))
        Thread.sleep(200)

        val received = CopyOnWriteArrayList<Event>()
        sink.transport = Transport { event -> received.add(event) }

        assertEquals(listOf("during-outage"), received.map { it.requestStarted.requestId })
    }

    private fun event(id: String): Event =
        Event.newBuilder().setRequestStarted(RequestStarted.newBuilder().setRequestId(id)).build()
}
