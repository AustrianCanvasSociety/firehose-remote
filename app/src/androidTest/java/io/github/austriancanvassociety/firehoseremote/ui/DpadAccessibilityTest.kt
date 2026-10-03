package io.github.austriancanvassociety.firehoseremote.ui

import android.content.Intent
import android.graphics.Rect
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import junit.framework.Assert.assertTrue
import junit.framework.Assert.fail

/**
 * The D-pad is five controls to the accessibility tree, and each one answers a
 * press.
 *
 * Runs on a device because it has to: the thing under test is a virtual view
 * hierarchy, which exists only once a real view is attached to a real window
 * and the framework is asked for the tree. Nothing on the JVM can stand in for
 * that, and a fake would prove only that the fake works.
 *
 * This is the half the shell cannot reach. `uiautomator dump` shows the nodes
 * and their bounds, but dispatching an accessibility action needs a service,
 * and the phones here have none. `performAction` is that dispatch: it is what
 * TalkBack sends for a double-tap, and before the fix there was no node to
 * send it to and no branch to receive it — so this asserts the difference
 * rather than restating the code.
 *
 * It asserts the tree and the dispatch. It does not assert that the labels are
 * intelligible out loud, which is what a human listening to TalkBack buys and
 * no automated check can.
 */
class DpadAccessibilityTest : InstrumentationTestCase() {

    /**
     * Every zone the D-pad draws, by the description its node must carry.
     *
     * Written out rather than read from the resource table, so a renamed
     * control cannot quietly satisfy its own expectation.
     *
     * Words, not glyphs. This is what a screen reader says, and a symbol
     * announces a character rather than a direction — the four directions were
     * `▲ ▼ ◀ ▶` and this list held the same symbols, so the test passed while
     * the announcement was unusable. Naming the word here is the guard.
     */
    private val zoneDescriptions = listOf("Up", "Down", "Left", "Right", "Select")

    private lateinit var activity: android.app.Activity

    override fun setUp() {
        super.setUp()
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(
                instrumentation.targetContext.packageName,
                "io.github.austriancanvassociety.firehoseremote.ui.MainActivity"
            )
            // CLEAR_TASK, so every test gets a genuinely new activity.
            // startActivitySync waits for one to be created, and a launch that
            // finds MainActivity already resumed creates none — it then waits
            // forever, which is what parked two earlier runs.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()

        // Poll rather than sleep a fixed interval. The paired screen is not
        // drawn on the first frame: the activity reads the TV's capabilities
        // before it commits to a layout, and with the TV unreachable that read
        // runs the wake path — the same few seconds a person sees. A single
        // sleep long enough for the worst case would waste it on every run,
        // and one short enough to be quick reads the wrong screen.
        repeat(POLL_ATTEMPTS) {
            if (zones().isNotEmpty()) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    /**
     * Close the activity this test opened.
     *
     * Leaving it up is what broke the second test of a run: its
     * `startActivitySync` found MainActivity already resumed, nothing new was
     * created for the call to wait on, and it blocked with no timeout.
     */
    override fun tearDown() {
        try {
            if (this::activity.isInitialized) {
                activity.finish()
                instrumentation.waitForIdleSync()
            }
        } finally {
            super.tearDown()
        }
    }

    private fun zoneDiagnostic(): String {
        val all = flatten(instrumentation.uiAutomation.rootInActiveWindow)
        val described = all.mapNotNull { it.contentDescription?.toString() }.filter { it.isNotEmpty() }
        val window = instrumentation.uiAutomation.rootInActiveWindow
        return "window=$window nodes=${all.size} descriptions=$described " +
            "activity=${activity.javaClass.name} resumed=${!activity.isFinishing}"
    }

    /**
     * The whole tree, flattened.
     *
     * Depth-first and unbounded: the virtual nodes sit under the D-pad, which
     * sits under a scrolling screen, and a depth limit here would be a guess
     * about a layout that is free to change.
     */
    private fun flatten(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        val out = mutableListOf(node)
        for (i in 0 until node.childCount) {
            out += flatten(node.getChild(i))
        }
        return out
    }

    private fun zones(): List<AccessibilityNodeInfo> =
        flatten(instrumentation.uiAutomation.rootInActiveWindow)
            .filter { it.contentDescription?.toString() in zoneDescriptions }

    fun testEachZoneIsItsOwnClickableNode() {
        val found = zones().map { it.contentDescription.toString() }
        zoneDescriptions.forEach { description ->
            assertTrue(
                "no accessibility node for the $description zone; found $found — " +
                    "the D-pad is one node again, which is what made it unusable " +
                    "without sight. ${zoneDiagnostic()}",
                description in found
            )
        }
    }

    private companion object {
        const val POLL_ATTEMPTS = 40
        const val POLL_INTERVAL_MS = 500L
    }

    /**
     * A zone carries its own box, and the boxes are in the right places.
     *
     * Left and right are the pair worth pinning: they are the two a single node
     * standing for the whole donut could not have told apart.
     */
    fun testZonesAreLaidOutAroundTheCentre() {
        val bounds = mutableMapOf<String, Rect>()
        zones().forEach { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            bounds[node.contentDescription.toString()] = r
        }
        val left = bounds["Left"] ?: fail("no left zone in the tree").let { return }
        val right = bounds["Right"] ?: fail("no right zone in the tree").let { return }
        assertTrue(
            "left zone $left is not left of right zone $right",
            left.centerX() < right.centerX()
        )
    }

    /**
     * The assertion this class exists for.
     *
     * `performAction(ACTION_CLICK)` is the call TalkBack makes on a double-tap.
     * It reaching the provider and returning true is the thing that was broken:
     * the old tree offered one clickable node whose `performClick` fired
     * nothing, so a press from the accessibility layer went nowhere.
     */
    fun testEveryZoneAnswersAClick() {
        val found = zones()
        assertTrue("no D-pad zones in the tree at all", found.isNotEmpty())
        found.forEach { node ->
            val description = node.contentDescription
            assertTrue(
                "$description is not clickable to the accessibility layer, so a " +
                    "double-tap has nothing to send",
                node.isClickable
            )
            assertTrue(
                "$description did not accept ACTION_CLICK — a double-tap on it " +
                    "would be swallowed exactly as it was before the fix",
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            )
        }
    }
}
