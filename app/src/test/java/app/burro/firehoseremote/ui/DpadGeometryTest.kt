package app.burro.firehoseremote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Which point on the D-pad is which control.
 *
 * The hit-test is hand-rolled trigonometry, and while it lived inside the view
 * nothing short of a device could reach it. Every point here is placed from the
 * same measurements the view draws with, so "inside the dial" means inside the
 * circle the screen shows. Points near a boundary sit half a pixel either side
 * of it, so an edge that moves by more than that fails a test instead of passing
 * unseen.
 *
 * The dial is one circle: a centre disc, and directions everywhere else inside
 * it. The shape it replaced was a donut — four thick arcs with the diagonals
 * open — and the tests that pinned those gaps are gone with them, deliberately.
 * [theWholeRingOutsideTheDiscIsLive] is what stops the gaps coming back by
 * accident, because a dead band inside a drawn circle is a control that ignores
 * a touch it looks like it should take.
 */
class DpadGeometryTest {

    // 420 dpi, so dp-to-px is a real multiplication rather than a no-op.
    private val density = 2.625f
    private val size = dpToPx(DpadGeometry.DONUT_SIZE_DP, density)

    private val outerEdge = DpadGeometry.outerRadius(size, size)
    private val discEdge = DpadGeometry.discRadius(size, size)

    /** Halfway between the disc's edge and the rim, where a direction lives. */
    private val midRing = (discEdge + outerEdge) / 2f

    private val HALF_PX = 0.5f

    /**
     * The four directions by the angle of their cardinal, measured the way
     * `atan2` measures: clockwise from 3 o'clock, because screen `y` grows
     * downward. Written out here rather than read from [DPAD_DIRECTIONS], so a
     * reordered list cannot supply its own expectation.
     */
    private val cardinals = listOf(
        0.0 to "dpad_right",
        90.0 to "dpad_down",
        180.0 to "dpad_left",
        270.0 to "dpad_up"
    )

    /** The action at [radius] px from the centre along [degrees], or null. */
    private fun actionAt(degrees: Double, radius: Float): String? {
        val radians = Math.toRadians(degrees)
        val x = size / 2f + (radius * cos(radians)).toFloat()
        val y = size / 2f + (radius * sin(radians)).toFloat()
        return DpadGeometry.controlAt(x, y, size, size)?.action
    }

    @Test
    fun eachCardinalResolvesToItsOwnDirection() {
        for ((degrees, action) in cardinals) {
            // Across the whole ring, not just its middle: an inner edge moved
            // outward would leave the middle live and part of the drawn circle dead.
            for (radius in listOf(discEdge + HALF_PX, midRing, outerEdge - HALF_PX)) {
                assertEquals("$degrees° at ${radius}px", action, actionAt(degrees, radius))
            }
        }
    }

    @Test
    fun theCentreDiscResolvesToSelect() {
        assertEquals("select", actionAt(0.0, 0f))
        assertEquals("select", actionAt(0.0, discEdge - HALF_PX))
    }

    @Test
    fun theWholeRingOutsideTheDiscIsLive() {
        // The regression guard for the shape this replaced. A donut drew its
        // diagonals open and the hit-test mirrored those gaps; a disc draws no
        // gap, so a touch anywhere between the disc and the rim is a direction
        // and nothing there may resolve to null.
        var degrees = 0.0
        while (degrees < 360.0) {
            for (radius in listOf(discEdge + HALF_PX, midRing, outerEdge - HALF_PX)) {
                assertNotNull(
                    "nothing at $degrees° ${radius}px — a dead gap inside the drawn dial",
                    actionAt(degrees, radius)
                )
            }
            degrees += 5.0
        }
    }

    @Test
    fun aDiagonalBelongsToTheQuadrantClockwiseFromIt() {
        // The boundaries between quadrants are still boundaries; what changed is
        // that they now resolve to a direction rather than to nothing.
        assertEquals("dpad_down", DpadGeometry.directionAt(45.0).action)
        assertEquals("dpad_right", DpadGeometry.directionAt(44.0).action)
        assertEquals("dpad_left", DpadGeometry.directionAt(135.0).action)
        assertEquals("dpad_up", DpadGeometry.directionAt(225.0).action)
        assertEquals("dpad_right", DpadGeometry.directionAt(315.0).action)
        // And the wrap: an angle just under a full turn is still the right
        // quadrant, as is one just past zero.
        assertEquals("dpad_right", DpadGeometry.directionAt(359.9).action)
        assertEquals("dpad_right", DpadGeometry.directionAt(0.1).action)
    }

    @Test
    fun aPointOutsideTheDialResolvesToNothing() {
        assertNull(actionAt(0.0, outerEdge + HALF_PX))
        assertNull(actionAt(90.0, outerEdge + HALF_PX))
        // A corner of the view is outside the circle even though it is inside
        // the view's rectangle.
        assertNull(DpadGeometry.controlAt(1f, 1f, size, size))
    }

    @Test
    fun theDiscIsFortyPercentOfTheDial() {
        // The reference insets the Select disc by 30% on every side, which
        // leaves it 40% of the width — a radius 40% of the dial's.
        assertEquals(0.4f, DpadGeometry.CENTRE_RADIUS_FRACTION, 0.0001f)
        assertEquals(outerEdge * 0.4f, discEdge, 0.001f)
    }

    @Test
    fun theArrowMarksSitBetweenTheDiscAndTheRim() {
        // An arrow drawn inside the disc would collide with the Select mark; one
        // drawn outside the rim would not be drawn at all.
        val distance = DpadGeometry.ARROW_DISTANCE_FRACTION
        val halfHeight = DpadGeometry.ARROW_HEIGHT_FRACTION / 2f
        assertTrue(distance - halfHeight > DpadGeometry.CENTRE_RADIUS_FRACTION)
        assertTrue(distance + halfHeight < 1f)
    }

    // --- accessibility bounds --------------------------------------------
    //
    // Each zone is an accessibility node, and a node is a rectangle or it is
    // nothing. The box is not what decides which zone a touch belongs to —
    // `controlAt` does that — but it is where the focus highlight is drawn, so
    // a box that misses its own quadrant points at empty screen.

    @Test
    fun `the centre's bounds are the disc's square`() {
        val bounds = DpadGeometry.boundsOf(DPAD_CENTRE, size, size)
        val radius = DpadGeometry.discRadius(size, size).toInt()
        assertEquals(size / 2 - radius, bounds.left)
        assertEquals(size / 2 - radius, bounds.top)
        assertEquals(size / 2 + radius, bounds.right)
        assertEquals(size / 2 + radius, bounds.bottom)
    }

    @Test
    fun `each direction's bounds contain its own quadrant`() {
        cardinals.forEachIndexed { quadrant, (degrees, action) ->
            val control = DPAD_DIRECTIONS[quadrant]
            val bounds = DpadGeometry.boundsOf(control, size, size)
            // Its cardinal, and both of the diagonals that open its quadrant.
            for (angle in listOf(degrees - 40, degrees, degrees + 40)) {
                val radians = Math.toRadians(angle)
                val x = (size / 2f + midRing * cos(radians).toFloat()).toInt()
                val y = (size / 2f + midRing * sin(radians).toFloat()).toInt()
                assertTrue("$action's box omits its own quadrant at ($x, $y): $bounds", bounds.contains(x, y))
            }
        }
    }

    /**
     * The four boxes meet at the centre and nowhere else. `down` is the one that
     * catches a cardinal walk started from an un-normalised angle: walking from
     * 410 would find no cardinal below 490, so the box would stop at the centre
     * line and highlight half the dial.
     */
    @Test
    fun `each direction's bounds lie on its own side of the centre`() {
        val centre = size / 2
        DPAD_DIRECTIONS.forEachIndexed { quadrant, control ->
            val bounds = DpadGeometry.boundsOf(control, size, size)
            val action = cardinals[quadrant].second
            val onItsOwnSide = when (action) {
                "dpad_right" -> bounds.left >= centre
                "dpad_left" -> bounds.right <= centre
                "dpad_down" -> bounds.top >= centre
                else -> bounds.bottom <= centre
            }
            assertTrue("$action is not on its own side of the centre: $bounds", onItsOwnSide)
        }
    }

    @Test
    fun `every zone's bounds stay inside the view`() {
        for (control in DPAD_DIRECTIONS + DPAD_CENTRE) {
            val bounds = DpadGeometry.boundsOf(control, size, size)
            assertTrue("${control.action} $bounds", bounds.left >= 0)
            assertTrue("${control.action} $bounds", bounds.top >= 0)
            assertTrue("${control.action} $bounds", bounds.right <= size)
            assertTrue("${control.action} $bounds", bounds.bottom <= size)
        }
    }
}
