package io.github.austriancanvassociety.firehoseremote.ui

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A zone's rectangle, in the view's own coordinates.
 *
 * Deliberately not `android.graphics.Rect`. This file has to stay runnable on a
 * plain JVM — the hit-test is hand-rolled trigonometry and that is the only
 * reason it can be tested at all — and `Rect` is unmocked there: its
 * constructors return zero and `toString` throws. The view converts at the
 * boundary instead.
 */
internal data class ZoneBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}

/**
 * The D-pad's geometry: where the dial is drawn, and which control a touch lands
 * on, as arithmetic over the view's bounds.
 *
 * Kept out of `DpadView` because the view is only reachable on a device, and
 * this is hand-rolled trigonometry that needs a test. The view draws with these
 * same functions, so the dial that is drawn and the dial that is hit are one
 * dial — a shape drawn in one place and hit in another is a control that lies
 * about where it is.
 *
 * The dial is a disc, not a donut. An earlier shape drew four thick arcs with
 * the diagonals open between them, and the hit-test mirrored those gaps. The
 * design reference replaces it with one circle whose directions are shown by
 * arrow marks inside it — so there is no gap to be inert, and a touch anywhere
 * inside the circle that is not the centre belongs to a direction.
 */
internal object DpadGeometry {

    /** Outer diameter of the dial, which is also its touch target. */
    const val DONUT_SIZE_DP = 160

    /**
     * The centre circle's radius, as a fraction of the dial's.
     *
     * The reference insets the Select disc by 30% of the dial's width on every
     * side, leaving it 40% of the width — a radius of 40% of the dial's. It is
     * a fraction rather than a dp measurement because the disc is defined
     * against the control it sits in, and the two scale together.
     */
    const val CENTRE_RADIUS_FRACTION = 0.4f

    /**
     * Where an arrow mark's centre sits, as a fraction of the dial's radius.
     *
     * Measured off the rendered reference rather than derived from its CSS: on
     * its 150px dial the up-arrow's ink is centred 46.5px from the middle, and
     * 46.5/75 = 0.62. The CSS's `top:12%` positions the *glyph box*, and a `▲`
     * character's ink sits inside that box — deriving from the box put the
     * arrows visibly too far out.
     */
    const val ARROW_DISTANCE_FRACTION = 0.62f

    /**
     * An arrow mark's height, as a fraction of the dial's radius.
     *
     * Measured, not derived: the reference's ink is 7px tall on a 75px radius,
     * so 0.093. A 14px font does not give a 14px triangle — the glyph's ink is
     * roughly half its box, which is what the first attempt at this got wrong.
     */
    const val ARROW_HEIGHT_FRACTION = 0.093f

    /**
     * Half an arrow mark's base width, as a fraction of the dial's radius.
     *
     * Measured: the reference's base is 8px on a 75px radius, so a half-base of
     * 0.053.
     */
    const val ARROW_HALF_BASE_FRACTION = 0.053f

    fun outerRadius(width: Int, height: Int): Float = min(width, height) / 2f

    /** The Select disc's radius, which is its own touch target. */
    fun discRadius(width: Int, height: Int): Float =
        outerRadius(width, height) * CENTRE_RADIUS_FRACTION

    /**
     * The control at a point, or null for anywhere outside the dial.
     *
     * The disc is its own target and everything else inside the circle is a
     * direction. There is no dead ring between them: the drawn dial has none,
     * and a gap that is not drawn must not be inert.
     */
    fun controlAt(x: Float, y: Float, width: Int, height: Int): RemoteControl? {
        val dx = x - width / 2f
        val dy = y - height / 2f
        val reach = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (reach > outerRadius(width, height)) return null
        if (reach <= discRadius(width, height)) return DPAD_CENTRE
        return directionAt(Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())))
    }

    /**
     * The rectangle [control] occupies, for the accessibility node that stands
     * in for it.
     *
     * The touch path never needed this: a finger is tested against the real
     * shape by [controlAt]. An accessibility node has no such option — it is a
     * rectangle or it does not exist — so a direction gets the bounding box of
     * its whole quadrant. The boxes therefore overlap the centre box; that costs
     * nothing, because which node a touch belongs to is decided by [controlAt],
     * not by these boxes. They are only where the focus highlight is drawn.
     *
     * The bounding box of a 90-degree sector is fixed by the outer radius alone:
     * every point at the disc's radius is inside the hull the outer arc and the
     * centre already describe, so the centre circle cannot widen it.
     */
    fun boundsOf(control: RemoteControl, width: Int, height: Int): ZoneBounds {
        val centreX = width / 2f
        val centreY = height / 2f

        if (control == DPAD_CENTRE) {
            val radius = discRadius(width, height)
            return ZoneBounds(
                (centreX - radius).toInt(),
                (centreY - radius).toInt(),
                (centreX + radius).toInt(),
                (centreY + radius).toInt()
            )
        }

        // The quadrant the direction lives in, measured the way the hit-test
        // measures: clockwise from 3 o'clock, half a quadrant either side of its
        // cardinal.
        val cardinal = 90.0 * DPAD_DIRECTIONS.indexOf(control)
        val start = cardinal - 45.0
        val end = cardinal + 45.0
        val radius = outerRadius(width, height)

        // The sweep's two ends, plus every cardinal it crosses — the only angles
        // at which the box can touch the circle.
        val angles = mutableListOf(start, end)
        var crossing = ceil(start / 90.0) * 90.0
        while (crossing < end) {
            angles += crossing
            crossing += 90.0
        }

        var left = centreX
        var top = centreY
        var right = centreX
        var bottom = centreY
        angles.forEach { degrees ->
            val radians = Math.toRadians(degrees)
            val x = centreX + radius * cos(radians).toFloat()
            val y = centreY + radius * sin(radians).toFloat()
            left = min(left, x)
            right = max(right, x)
            top = min(top, y)
            bottom = max(bottom, y)
        }

        return ZoneBounds(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    /**
     * The direction at an angle inside the dial.
     *
     * [degrees] is measured the way `atan2` measures it: clockwise from 3
     * o'clock, because screen `y` grows downward. Each direction owns the 90
     * degrees centred on its cardinal, so the diagonal at exactly 45 degrees
     * belongs to the quadrant that opens clockwise from it.
     */
    fun directionAt(degrees: Double): RemoteControl =
        DPAD_DIRECTIONS[quadrantOf(degrees)]

    /**
     * The quadrant index for an angle, which indexes [DPAD_DIRECTIONS] directly.
     *
     * `+45` moves a quadrant's boundary onto zero, so the whole part of the
     * 90-degree division is the quadrant: 0 is right, then down, left, up.
     */
    private fun quadrantOf(degrees: Double): Int =
        floor((((degrees + 360.0) % 360.0) + 45.0) % 360.0 / 90.0).toInt()
}
