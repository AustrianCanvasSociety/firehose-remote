package app.burro.firehoseremote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawn marks, asserted off a device.
 *
 * A mark that draws outside its box is clipped at every size, and the only place
 * that shows is a screen. These assertions move that catch to the JVM: every
 * coordinate is checked against the grid the marks are authored in, and the
 * shapes that carry a mark's meaning — the cross on mute, the ring and dot on
 * Select — are asserted structurally, so a mark cannot quietly lose a part.
 *
 * What this cannot reach is whether a mark *reads* correctly at 18dp. That is a
 * device question, and it is checked on one.
 */
class ControlMarksTest {

    /**
     * Stroke is drawn centred on the path, so half of [ControlMarks.STROKE_WIDTH]
     * legitimately sits outside the authored coordinate. This is that half,
     * plus float slack.
     */
    private val slack = ControlMarks.STROKE_WIDTH

    private fun pointsOf(shape: ControlMarks.Shape): List<ControlMarks.Pt> = when (shape) {
        is ControlMarks.Stroke -> shape.points
        is ControlMarks.Polygon -> shape.points
        is ControlMarks.Disc -> listOf(
            ControlMarks.Pt(shape.cx - shape.radius, shape.cy - shape.radius),
            ControlMarks.Pt(shape.cx + shape.radius, shape.cy + shape.radius)
        )
        is ControlMarks.Ring -> listOf(
            ControlMarks.Pt(shape.cx - shape.radius, shape.cy - shape.radius),
            ControlMarks.Pt(shape.cx + shape.radius, shape.cy + shape.radius)
        )
        is ControlMarks.Bar -> listOf(
            ControlMarks.Pt(shape.left, shape.top),
            ControlMarks.Pt(shape.right, shape.bottom)
        )
    }

    @Test
    fun everyControlTheAppDrawsHasAMark() {
        assertEquals("eight marks ship", 8, ControlMarks.all.size)
        for (action in listOf(
            "home", "back", "play", "scan_back", "scan_forward", "sleep", "mute", "select"
        )) {
            assertNotNull("no mark for action '$action'", ControlMarks.forAction(action))
        }
    }

    @Test
    fun everyCoordinateSitsInsideTheAuthoredBox() {
        for (mark in ControlMarks.all) {
            assertTrue("${mark.name} carries no shapes", mark.shapes.isNotEmpty())
            for (shape in mark.shapes) {
                for (point in pointsOf(shape)) {
                    assertTrue(
                        "${mark.name}: x=${point.x} outside 0..${ControlMarks.VIEWBOX}",
                        point.x >= -slack && point.x <= ControlMarks.VIEWBOX + slack
                    )
                    assertTrue(
                        "${mark.name}: y=${point.y} outside 0..${ControlMarks.VIEWBOX}",
                        point.y >= -slack && point.y <= ControlMarks.VIEWBOX + slack
                    )
                }
            }
        }
    }

    @Test
    fun noMarkIsDegenerate() {
        for (mark in ControlMarks.all) {
            for (shape in mark.shapes) {
                val points = pointsOf(shape)
                assertTrue("${mark.name}: a shape has no points", points.isNotEmpty())
                // A path whose points all coincide draws nothing.
                val spread = points.maxOf { it.x } - points.minOf { it.x } +
                    points.maxOf { it.y } - points.minOf { it.y }
                assertTrue("${mark.name}: a shape has no extent", spread > 0.5f)
            }
        }
    }

    @Test
    fun theCurvedMarksCarryRealArcs() {
        // The two marks whose reference geometry includes an arc must tessellate
        // to more than their endpoints — a dropped arc would leave a straight
        // line, which no bounding-box check would notice.
        val back = ControlMarks.forAction("back")!!
        val backTail = back.shapes.filterIsInstance<ControlMarks.Stroke>().maxBy { it.points.size }
        assertTrue("the back arrow lost its curve", backTail.points.size > 6)

        val sleep = ControlMarks.forAction("sleep")!!
        val crescent = sleep.shapes.filterIsInstance<ControlMarks.Polygon>().single()
        assertTrue("the crescent lost its arcs", crescent.points.size > 12)
    }

    @Test
    fun theAdoptedShapesAreTheOnesThatWereChosen() {
        // Mute is speaker + cross, not speaker + slash: two crossing strokes.
        val mute = ControlMarks.forAction("mute")!!
        assertTrue("mute lost its speaker", mute.shapes.any { it is ControlMarks.Polygon })
        val strokes = mute.shapes.filterIsInstance<ControlMarks.Stroke>()
        assertEquals("mute should carry the two cross strokes", 2, strokes.size)
        // A cross descends twice and leans opposite ways — down-right, then
        // down-left. A slash would descend once and climb once, and the two
        // shapes share their four corner points, so only the direction tells
        // them apart.
        val leans = strokes.map { it.points.last().x - it.points.first().x }
        for (stroke in strokes) {
            assertTrue(
                "mute's cross should descend on both strokes",
                stroke.points.last().y - stroke.points.first().y > 1f
            )
        }
        assertTrue(
            "the two strokes should lean opposite ways; a slash leans both the same",
            leans[0] * leans[1] < 0f
        )

        // Select is ring + dot, not a check.
        val select = ControlMarks.forAction("select")!!
        assertEquals("select should carry a ring and a dot", 2, select.shapes.size)
        assertTrue(select.shapes.any { it is ControlMarks.Ring })
        assertTrue(select.shapes.any { it is ControlMarks.Disc })
    }

    @Test
    fun theTypedCharactersHaveNoMark() {
        // ≡, ＋, −, ⋯ and every word stay typed. A mark appearing here would mean
        // the drawn set had crept past the eight the reference chose.
        for (action in listOf("menu", "volume_up", "volume_down", "power",
            "dpad_up", "dpad_down", "dpad_left", "dpad_right")) {
            assertNull("'$action' should stay typed", ControlMarks.forAction(action))
        }
    }

    @Test
    fun theGlyphFitsItsFiftyTwoPointTarget() {
        // The marks are drawn at GLYPH_DP inside a larger target; the viewBox is
        // square and the draw scales by GLYPH_DP / VIEWBOX, so the check is that
        // neither value has drifted to something that would not fit.
        assertTrue(ControlMarks.GLYPH_DP > 0f)
        assertTrue(ControlMarks.GLYPH_DP <= 48f)
        assertTrue(ControlMarks.VIEWBOX > 0f)
        assertTrue(ControlMarks.STROKE_WIDTH > 0f && ControlMarks.STROKE_WIDTH < ControlMarks.VIEWBOX / 2f)
    }
}
