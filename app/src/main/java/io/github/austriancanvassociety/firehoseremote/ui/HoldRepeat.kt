package io.github.austriancanvassociety.firehoseremote.ui

/**
 * Press-and-hold on the D-pad: when a touch is a tap, and when it becomes a
 * hold that repeats.
 *
 * A hold is **repeated body-less presses**, never an open `keyDown`. A Fire TV
 * repeats a held key until `keyUp` arrives — 54 s was observed — so a hold
 * built on `keyDown` is one dead app away from a TV that scrolls forever. Each
 * body-less press is complete on its own (the test TV 2026-09-27, `.22` 2026-09-28),
 * so when this app stops sending, the TV stops moving.
 *
 * The rules, each one a way a hold could otherwise keep the TV moving after
 * the finger has gone:
 * - A touch released before [thresholdMs] is a tap: [up] hands back the control
 *   and the caller sends it the usual way. Past the threshold it is a hold, the
 *   first repeat goes out at once, and release sends nothing more.
 * - A repeat that falls due while the previous one is still in flight is
 *   **skipped, not queued**. A queue would drain after release — the TV
 *   would keep moving for as long as it took.
 * - [stop] ends everything with nothing further sent: a touch cancelled or
 *   dragged off, the screen going away, or a press that failed or had to
 *   wake the TV.
 *
 * Holds no Android types, so its timing is testable on the JVM; the Activity
 * supplies a [Timer] on the main thread's `Handler`. Not thread-safe — every
 * call, and every timer task, runs on that one thread.
 */
class HoldRepeat(
    private val timer: Timer,
    private val thresholdMs: Long,
    private val intervalMs: Long = INTERVAL_MS
) {

    /** Runs a task once after a delay. */
    fun interface Timer {
        fun schedule(delayMs: Long, task: () -> Unit): Cancellable
    }

    /** Cancels a scheduled task that has not run yet. */
    fun interface Cancellable {
        fun cancel()
    }

    private var control: RemoteControl? = null
    private var onRepeat: ((RemoteControl) -> Unit)? = null
    private var pending: Cancellable? = null

    /**
     * A repeat has gone out and not yet answered. Survives [stop], so a hold
     * started straight after another waits for that press instead of stacking
     * a second one behind it.
     */
    private var inFlight = false

    /** True from the threshold until the hold ends. */
    var isHolding: Boolean = false
        private set

    /** A finger has gone down on [control]; [onRepeat] sends one repeat of it. */
    fun down(control: RemoteControl, onRepeat: (RemoteControl) -> Unit) {
        stop()
        this.control = control
        this.onRepeat = onRepeat
        if (!control.repeatsWhileHeld) return
        pending = timer.schedule(thresholdMs) {
            isHolding = true
            tick()
        }
    }

    /**
     * The finger lifted on the control it went down on. Returns that control
     * when the touch was a tap, for the caller to send; null when it was a
     * hold, or was already stopped, since there is then nothing more to send.
     */
    fun up(): RemoteControl? {
        val tap = if (isHolding) null else control
        stop()
        return tap
    }

    /** End the hold, or the touch before it, with nothing further sent. */
    fun stop() {
        pending?.cancel()
        pending = null
        control = null
        onRepeat = null
        isHolding = false
    }

    /** The last repeat's press has answered, however it went. */
    fun repeatDone() {
        inFlight = false
    }

    private fun tick() {
        val held = control ?: return
        if (!inFlight) {
            inFlight = true
            onRepeat?.invoke(held)
        }
        // The press above may have stopped the hold synchronously; only a hold
        // still running books its next tick.
        if (control === held) {
            pending = timer.schedule(intervalMs) { tick() }
        }
    }

    companion object {
        /**
         * One repeat every 220 ms. Measured 2026-09-28 on `.22`: the TV's own
         * held-key scroll runs at 13+ tiles a second, faster than this, so the
         * interval is not the native rate — it is the fastest pace on record
         * from the vendor app (eleven body-less presses in 2.4 s,
         * `docs/protocol.md § 2`). A hold here steps slower than the vendor
         * remote's glide rather than sending faster than the vendor app ever
         * has.
         */
        const val INTERVAL_MS = 220L
    }
}
