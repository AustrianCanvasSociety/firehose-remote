package io.github.austriancanvassociety.firehoseremote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Press-and-hold on the D-pad, timed against a fake clock.
 *
 * What a hold must never do is leave the TV moving: every press it sends is a
 * complete body-less press, a repeat never queues behind one still in flight,
 * and once the finger is gone nothing more goes out.
 */
class HoldRepeatTest {

    private val right = RemoteControl(0, "dpad_right", keyed = true, present = true, repeatsWhileHeld = true)
    private val ok = RemoteControl(0, "select", keyed = true, present = true)

    private val timer = FakeTimer()
    private val repeats = mutableListOf<Long>()
    private val hold = HoldRepeat(timer, THRESHOLD_MS)

    /** Records the time of each repeat, and reports it done at once, like a quick TV. */
    private val quickTv: (RemoteControl) -> Unit = {
        repeats += timer.now
        hold.repeatDone()
    }

    @Test
    fun theRepeatIntervalIsTheMeasured220Ms() {
        assertEquals(220L, HoldRepeat.INTERVAL_MS)
    }

    @Test
    fun aHoldPastTheThresholdRepeatsEveryIntervalAndSendsNoTapOnRelease() {
        hold.down(right, quickTv)
        timer.advanceTo(THRESHOLD_MS + 2 * HoldRepeat.INTERVAL_MS)

        assertEquals(
            "one at the threshold, then one per interval",
            listOf(THRESHOLD_MS, THRESHOLD_MS + 220, THRESHOLD_MS + 440),
            repeats
        )
        assertTrue(hold.isHolding)
        assertNull("release after a hold sends nothing more", hold.up())
        assertFalse(hold.isHolding)
    }

    @Test
    fun nothingIsSentAfterRelease() {
        hold.down(right, quickTv)
        timer.advanceTo(THRESHOLD_MS + 100)
        hold.up()
        val sent = repeats.size

        timer.advanceTo(THRESHOLD_MS + 10_000)

        assertEquals("the repeats stop with the finger", sent, repeats.size)
    }

    @Test
    fun aReleaseBeforeTheThresholdIsATap() {
        hold.down(right, quickTv)
        timer.advanceTo(THRESHOLD_MS - 1)

        assertSame("the control to tap — the keyed pair, as before", right, hold.up())
        timer.advanceTo(THRESHOLD_MS + 1_000)
        assertTrue("a tap never becomes repeats", repeats.isEmpty())
    }

    @Test
    fun aRepeatDueWhileOneIsInFlightIsSkippedNotQueued() {
        var inFlight = 0
        hold.down(right) { repeats += timer.now; inFlight++ }

        // The first press never answers: two ticks fall due behind it.
        timer.advanceTo(THRESHOLD_MS + 2 * HoldRepeat.INTERVAL_MS)
        assertEquals("only the first went out", listOf(THRESHOLD_MS), repeats)

        // It answers. The skipped ticks are not replayed as a burst; the next
        // press waits for the next tick.
        hold.repeatDone()
        assertEquals(1, repeats.size)
        timer.advanceTo(THRESHOLD_MS + 3 * HoldRepeat.INTERVAL_MS)
        assertEquals(listOf(THRESHOLD_MS, THRESHOLD_MS + 660), repeats)
        assertEquals(2, inFlight)
    }

    @Test
    fun stopEndsAHoldWithNothingFurtherSent() {
        hold.down(right, quickTv)
        timer.advanceTo(THRESHOLD_MS)
        hold.stop()
        timer.advanceTo(THRESHOLD_MS + 1_000)

        assertEquals("the threshold press only", 1, repeats.size)
        assertNull("a stopped hold has no tap to give", hold.up())
    }

    @Test
    fun stopBeforeTheThresholdCancelsTheTap() {
        hold.down(right, quickTv)
        timer.advanceTo(THRESHOLD_MS - 1)
        hold.stop()

        assertNull("a touch cancelled or dragged off is not a tap", hold.up())
        timer.advanceTo(THRESHOLD_MS + 1_000)
        assertTrue(repeats.isEmpty())
    }

    @Test
    fun aNewHoldWaitsForAPressStillInFlightFromTheLastOne() {
        hold.down(right) { repeats += timer.now }
        timer.advanceTo(THRESHOLD_MS)
        hold.up()

        // Straight back down while the last press is still out.
        hold.down(right) { repeats += timer.now }
        timer.advanceTo(2 * THRESHOLD_MS + HoldRepeat.INTERVAL_MS)
        assertEquals("nothing new while the old press is in flight", 1, repeats.size)

        hold.repeatDone()
        timer.advanceTo(2 * THRESHOLD_MS + 2 * HoldRepeat.INTERVAL_MS)
        assertEquals(2, repeats.size)
    }

    @Test
    fun aControlThatDoesNotRepeatHeldPastTheThresholdIsOneTap() {
        hold.down(ok, quickTv)
        timer.advanceTo(THRESHOLD_MS + 1_000)

        assertTrue("OK never repeats", repeats.isEmpty())
        assertFalse(hold.isHolding)
        assertSame("released, it is one tap", ok, hold.up())
    }

    @Test
    fun onlyTheFourDirectionsRepeat() {
        assertTrue(DPAD_DIRECTIONS.all { it.repeatsWhileHeld })
        assertFalse("a held OK is a long press on a Fire TV", DPAD_CENTRE.repeatsWhileHeld)
        assertFalse(
            "Rewind and Forward start a shuttle; nothing else repeats either",
            (REMOTE_ROWS.flatten() + ROCKER_CONTROLS + OPTIONS_CONTROL).any { it.repeatsWhileHeld }
        )
    }

    /** A timer driven by hand: tasks run when [advanceTo] passes their due time. */
    private class FakeTimer : HoldRepeat.Timer {
        var now = 0L
            private set

        private class Entry(val due: Long, val task: () -> Unit) {
            var cancelled = false
        }

        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMs: Long, task: () -> Unit): HoldRepeat.Cancellable {
            val entry = Entry(now + delayMs, task)
            entries += entry
            return HoldRepeat.Cancellable { entry.cancelled = true }
        }

        fun advanceTo(time: Long) {
            while (true) {
                val next = entries.filter { !it.cancelled && it.due <= time }.minByOrNull { it.due } ?: break
                entries.remove(next)
                now = next.due
                next.task()
            }
            now = time
        }
    }

    private companion object {
        const val THRESHOLD_MS = 400L
    }
}
