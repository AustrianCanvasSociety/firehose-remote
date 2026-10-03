package io.github.austriancanvassociety.firehoseremote.ui

import io.github.austriancanvassociety.firehoseremote.protocol.FakeTransport
import io.github.austriancanvassociety.firehoseremote.protocol.FireTvClient
import io.github.austriancanvassociety.firehoseremote.protocol.TestClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The press queue's rules, on a real single-thread worker — the same kind the
 * Activity runs presses on — so the ordering and the interrupt are the real
 * ones rather than a simulation of them.
 */
class PressQueueTest {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    private fun drain() {
        worker.shutdown()
        assertTrue("worker drained", worker.awaitTermination(5, TimeUnit.SECONDS))
    }

    /**
     * The walkthrough's rapid-tap run sent 2 of 8. Eight taps made back to back
     * send eight keyed pairs, in the order they were tapped.
     */
    @Test
    fun eightQueuedTapsSendEightPairsInOrder() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        val client = FireTvClient(transport, clock)
        val queue = PressQueue(worker)
        val taps = listOf(
            "dpad_up", "dpad_down", "dpad_left", "dpad_right",
            "select", "back", "home", "menu"
        )

        for (action in taps) {
            queue.enqueue({ _ -> client.sendKey(HOST, TOKEN, action); true })
        }
        drain()

        val sent = transport.calls.map {
            it.url.substringAfter("action=") + ":" +
                (if ("keyDown" in (it.body ?: "")) "down" else "up")
        }
        assertEquals(taps.flatMap { listOf("$it:down", "$it:up") }, sent)
    }

    /**
     * After a failed press, each press behind it would run its own wake and
     * fail the same way. They are dropped instead, and a tap made after the
     * failure still runs.
     */
    @Test
    fun aFailedPressDropsThePressesQueuedBehindIt() {
        val queue = PressQueue(worker)
        val ran = Collections.synchronizedList(mutableListOf<Int>())
        val skipped = Collections.synchronizedList(mutableListOf<Int>())
        val go = CountDownLatch(1)

        queue.enqueue({ _ -> go.await(); ran += 1; true })
        queue.enqueue({ _ -> ran += 2; false }) // fails
        for (i in 3..5) queue.enqueue({ _ -> ran += i; true }, onSkipped = { skipped += i })
        go.countDown()
        // Queued once the failure has had its turn: a fresh tap.
        val later = CountDownLatch(1)
        worker.execute { later.countDown() }
        assertTrue(later.await(5, TimeUnit.SECONDS))
        queue.enqueue({ _ -> ran += 6; true })
        drain()

        assertEquals(listOf(1, 2, 6), ran.toList())
        assertEquals(listOf(3, 4, 5), skipped.toList())
    }

    /**
     * Cancel during a wake stops the waking press where it is — the interrupt
     * Step 1 made every press survive — drops the presses behind it, and the
     * next tap runs.
     */
    @Test
    fun cancelInterruptsTheWakingPressAndDropsTheQueue() {
        val queue = PressQueue(worker)
        val waking = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val skipped = Collections.synchronizedList(mutableListOf<Int>())
        val ranAfterCancel = AtomicBoolean(false)

        queue.enqueue({ startedWaking ->
            startedWaking()
            waking.countDown()
            try {
                Thread.sleep(10_000) // a wake that would run to its budget
            } catch (e: InterruptedException) {
                interrupted.set(true)
            }
            true
        })
        queue.enqueue({ _ -> true }, onSkipped = { skipped += 2 })
        queue.enqueue({ _ -> true }, onSkipped = { skipped += 3 })

        assertTrue(waking.await(5, TimeUnit.SECONDS))
        queue.cancelWake()
        queue.enqueue({ _ -> ranAfterCancel.set(true); true })
        drain()

        assertTrue("the waking press was interrupted", interrupted.get())
        assertEquals(listOf(2, 3), skipped.toList())
        assertTrue("a tap after Cancel runs", ranAfterCancel.get())
    }

    /** The interrupt belongs to the cancelled press; the next one starts clean. */
    @Test
    fun theInterruptDoesNotLeakIntoTheNextPress() {
        val queue = PressQueue(worker)
        val waking = CountDownLatch(1)
        val nextSawInterrupt = AtomicBoolean(true)

        queue.enqueue({ startedWaking ->
            startedWaking()
            waking.countDown()
            try {
                Thread.sleep(10_000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt() // what every press catch does
            }
            true
        })
        assertTrue(waking.await(5, TimeUnit.SECONDS))
        queue.cancelWake()
        queue.enqueue({ _ -> nextSawInterrupt.set(Thread.currentThread().isInterrupted); true })
        drain()

        assertFalse("the next press starts uninterrupted", nextSawInterrupt.get())
    }

    /**
     * The MAC search holds the worker for a whole SSDP window, so a press asks
     * whether more are waiting before queueing it — the last press of a burst
     * is the one that does.
     */
    @Test
    fun aPressKnowsWhetherOthersAreWaitingBehindIt() {
        val queue = PressQueue(worker)
        val go = CountDownLatch(1)
        val seen = Collections.synchronizedList(mutableListOf<Boolean>())

        queue.enqueue({ _ -> go.await(); seen += queue.hasQueuedBehind; true })
        queue.enqueue({ _ -> seen += queue.hasQueuedBehind; true })
        go.countDown()
        drain()

        assertEquals(listOf(true, false), seen.toList())
    }

    /** With nothing waking, Cancel still drops what is queued and interrupts nothing. */
    @Test
    fun cancelWithNothingWakingOnlyDropsTheQueue() {
        val queue = PressQueue(worker)
        val go = CountDownLatch(1)
        val firstInterrupted = AtomicBoolean(false)
        val skipped = Collections.synchronizedList(mutableListOf<Int>())

        queue.enqueue({ _ ->
            go.await()
            firstInterrupted.set(Thread.currentThread().isInterrupted)
            true
        })
        queue.enqueue({ _ -> true }, onSkipped = { skipped += 2 })
        queue.cancelWake()
        go.countDown()
        drain()

        assertFalse(firstInterrupted.get())
        assertEquals(listOf(2), skipped.toList())
    }

    /** Once the worker has shut down, a press is refused rather than thrown. */
    @Test
    fun aPressAfterShutdownIsRefused() {
        val queue = PressQueue(worker)
        worker.shutdownNow()

        assertFalse(queue.enqueue({ _ -> true }))
        assertFalse(queue.hasQueuedBehind)
    }

    private companion object {
        const val HOST = "192.0.2.10"
        const val TOKEN = "TOKEN_ABC"
    }
}
