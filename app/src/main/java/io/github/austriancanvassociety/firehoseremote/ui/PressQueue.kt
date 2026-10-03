package io.github.austriancanvassociety.firehoseremote.ui

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Taps on the remote, sent one after another in the order they were made.
 *
 * The worker is already first-in, first-out; what this adds are the rules for
 * when queued presses stop being wanted:
 * - A press that fails drops every press queued behind it. Each of them would
 *   otherwise run its own wake — up to 40 s apiece — and fail the same way.
 * - Cancel, while a press is waking the TV, interrupts that press and drops the
 *   ones behind it. The interrupt is the one Step 1 made every press survive: a
 *   press stopped after its keyDown still sends the keyUp.
 * - A tap made after either of those runs as normal.
 *
 * Hold repeats come through here too, so Cancel covers a wake a hold started.
 * They are never stacked: [HoldRepeat] skips a repeat while one is in flight,
 * and a skipped repeat reports back through `onSkipped` so the hold can go on.
 *
 * Holds no Android types, so its rules are testable on the JVM. [enqueue] and
 * [cancelWake] are called on the main thread; presses run on [worker].
 */
class PressQueue(private val worker: Executor) {

    /** One press, run on the worker. */
    fun interface Press {
        /**
         * Send the press. Call [startedWaking] as the press starts to wake the
         * TV, which is what makes it cancellable. Return false when the press
         * failed, which drops the presses queued behind it.
         */
        fun run(startedWaking: () -> Unit): Boolean
    }

    /** Moves on a failure or a Cancel; a press queued under an older value is dropped. */
    private val generation = AtomicInteger()

    /** Presses queued or running. */
    private val pending = AtomicInteger()

    /** The press that is waking the TV, while it is. */
    @Volatile
    private var waking: FutureTask<Unit>? = null

    /**
     * True, when read from inside a running press, if more presses are queued
     * behind it.
     */
    val hasQueuedBehind: Boolean
        get() = pending.get() > 1

    /**
     * Queue [press]. [onSkipped] runs on the worker instead, if the press is
     * dropped before its turn. False when the worker has shut down, and nothing
     * was queued.
     */
    fun enqueue(press: Press, onSkipped: () -> Unit = {}): Boolean {
        val queuedAt = generation.get()
        lateinit var task: FutureTask<Unit>
        task = object : FutureTask<Unit>(Callable {
            try {
                if (generation.get() != queuedAt) {
                    onSkipped()
                } else if (!press.run { waking = task }) {
                    // Moved here, on the worker, so the next press in line
                    // sees it before it starts.
                    generation.incrementAndGet()
                }
            } finally {
                if (waking === task) waking = null
                pending.decrementAndGet()
            }
        }) {
            /**
             * A `FutureTask` keeps whatever its work threw. `execute` did not,
             * and a fault that took the app down is not one to lose silently,
             * so it goes on to the worker thread's handler as before. An
             * interrupt that escaped is only a stop.
             */
            override fun done() {
                if (isCancelled) return
                try {
                    get()
                } catch (e: ExecutionException) {
                    val cause = e.cause ?: e
                    if (cause !is InterruptedException) throw cause
                }
            }
        }
        pending.incrementAndGet()
        return try {
            worker.execute(task)
            true
        } catch (e: RejectedExecutionException) {
            pending.decrementAndGet()
            false
        }
    }

    /**
     * Leave the wake: drop what is queued and interrupt the press that is
     * waking, if one still is. The caller gives the controls back itself — the
     * interrupted press ends without drawing anything.
     */
    fun cancelWake() {
        generation.incrementAndGet()
        waking?.cancel(true)
    }
}
