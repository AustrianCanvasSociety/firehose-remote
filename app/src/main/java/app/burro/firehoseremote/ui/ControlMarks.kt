package app.burro.firehoseremote.ui

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The eight control marks, as geometry.
 *
 * Symbol mode draws every control rather than only the volume rocker. All eight
 * are drawn — not just the two whose characters are ambiguous — because drawing
 * some and typing others puts two rendering paths on one screen, and a drawn
 * shape then has to match a font the app does not control. That is the same
 * class of problem as the one drawing was meant to solve: a character the device
 * font lacks draws as a tofu box, not as a fallback icon.
 *
 * Like [DpadGeometry] this file is plain Kotlin — no `android.*` import and no
 * drawable asset — so the coordinates below are data a JVM test can assert, and
 * the marks cost no bytes in the APK.
 *
 * Coordinates are SVG units on a [VIEWBOX]-square grid, decoded from the design
 * reference (an internal mockup, not published). The
 * reference's stroke weight is 1.8 against 15px text; [STROKE_WIDTH] keeps that
 * relationship at the size the app draws.
 */
internal object ControlMarks {

    /** The grid every coordinate below is expressed in. */
    const val VIEWBOX = 24f

    /** Stroke width in viewBox units, for the stroked shapes. */
    const val STROKE_WIDTH = 1.9f

    /** The size the marks are drawn at, matching the rocker's 18sp glyphs. */
    const val GLYPH_DP = 18f

    /** A point on the [VIEWBOX] grid. */
    data class Pt(val x: Float, val y: Float)

    /** One drawn primitive. Stroked shapes use [STROKE_WIDTH]; the rest fill. */
    sealed interface Shape

    /** An open path, stroked. */
    data class Stroke(val points: List<Pt>) : Shape

    /** A closed path, filled. */
    data class Polygon(val points: List<Pt>) : Shape

    /** A filled circle. */
    data class Disc(val cx: Float, val cy: Float, val radius: Float) : Shape

    /** A stroked circle outline. */
    data class Ring(val cx: Float, val cy: Float, val radius: Float) : Shape

    /** A filled axis-aligned box, for the square bars. */
    data class Bar(val left: Float, val top: Float, val right: Float, val bottom: Float) : Shape

    /**
     * One control's mark.
     *
     * [action] is the wire action the control sends — the same string
     * [RemoteControl.action] carries — so the lookup needs no second table.
     */
    data class Mark(val action: String, val name: String, val shapes: List<Shape>)

    /**
     * The marks, in the order the reference presents them.
     *
     * `≡`, `＋`, `−` and `⋯` are deliberately absent: they are unambiguous,
     * universally covered, and carry no weight worth reproducing. They stay
     * typed in both modes.
     */
    val all: List<Mark> = listOf(
        Mark("home", "Home", listOf(
            Stroke(listOf(Pt(4f, 10.8f), Pt(12f, 4.4f), Pt(20f, 10.8f))),
            Stroke(listOf(Pt(6.3f, 10f), Pt(6.3f, 19.2f), Pt(17.7f, 19.2f), Pt(17.7f, 10f)))
        )),

        // The arrow's tail runs into a near-semicircle and back out to the left.
        Mark("back", "Back", listOf(
            Stroke(listOf(Pt(8.6f, 7.6f), Pt(5.2f, 11f), Pt(8.6f, 14.4f))),
            Stroke(
                listOf(Pt(5.2f, 11f), Pt(13.6f, 11f)) +
                    arcPoints(Pt(13.6f, 11f), Pt(13.6f, 20.2f), 4.6f, largeArc = false, sweep = true) +
                    listOf(Pt(11f, 20.2f))
            )
        )),

        Mark("play", "Play/Pause", listOf(
            Polygon(listOf(Pt(6.6f, 5.6f), Pt(15.2f, 12f), Pt(6.6f, 18.4f))),
            Bar(17f, 5.6f, 19.6f, 18.4f)
        )),

        Mark("scan_back", "Rewind", listOf(
            Polygon(listOf(Pt(12.6f, 6.4f), Pt(12.6f, 17.6f), Pt(4.8f, 12f))),
            Polygon(listOf(Pt(20.2f, 6.4f), Pt(20.2f, 17.6f), Pt(12.4f, 12f)))
        )),

        Mark("scan_forward", "Forward", listOf(
            Polygon(listOf(Pt(11.4f, 6.4f), Pt(11.4f, 17.6f), Pt(19.2f, 12f))),
            Polygon(listOf(Pt(3.8f, 6.4f), Pt(3.8f, 17.6f), Pt(11.6f, 12f)))
        )),

        // The crescent: a shallow outer arc closed by a deeper inner one.
        Mark("sleep", "Sleep", listOf(
            Polygon(
                arcPoints(Pt(20.2f, 14.6f), Pt(9.4f, 3.8f), 8.5f, largeArc = false, sweep = true) +
                    arcPoints(Pt(9.4f, 3.8f), Pt(20.2f, 14.6f), 8.9f, largeArc = true, sweep = false)
            )
        )),

        // Adopted over the more common slash: the cross reads faster at this
        // size, which is what a remote wants. The slash survives being drawn
        // smaller, which is the argument against it here.
        Mark("mute", "Mute", listOf(
            Polygon(listOf(
                Pt(4f, 9.6f), Pt(7.4f, 9.6f), Pt(11.8f, 6f),
                Pt(11.8f, 18f), Pt(7.4f, 14.4f), Pt(4f, 14.4f)
            )),
            Stroke(listOf(Pt(15.4f, 9.4f), Pt(20.6f, 14.6f))),
            Stroke(listOf(Pt(20.6f, 9.4f), Pt(15.4f, 14.6f)))
        )),

        // Ring and dot: the conventional D-pad centre, needing no vocabulary.
        // A check would read as *done*, which is a different promise.
        Mark("select", "Select", listOf(
            Ring(12f, 12f, 7.2f),
            Disc(12f, 12f, 2.7f)
        ))
    )

    private val byAction: Map<String, Mark> = all.associateBy { it.action }

    /**
     * The drawn mark for [action], or null when the control has none and stays
     * typed. Null is the honest answer for `≡`, `＋`, `−`, `⋯` and every label.
     */
    fun forAction(action: String): Mark? = byAction[action]

    /**
     * The points along a circular arc from [from] to [to], SVG's endpoint
     * parameterisation with `rx == ry == radius`.
     *
     * Every arc in the reference is circular, so the elliptical case is not
     * implemented — a non-circular arc would need the full endpoint-to-centre
     * conversion, and silently approximating one as circular would put a subtly
     * wrong curve on screen with no test able to see it.
     *
     * [largeArc] and [sweep] are SVG's flags: which of the two possible centres
     * to take, and which way round to travel.
     */
    private fun arcPoints(
        from: Pt,
        to: Pt,
        radius: Float,
        largeArc: Boolean,
        sweep: Boolean,
        steps: Int = 16
    ): List<Pt> {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val chord = sqrt(dx * dx + dy * dy)
        if (chord == 0f) return emptyList()

        // A radius under half the chord cannot span it; SVG scales it up.
        val r = maxOf(radius, chord / 2f)
        val mx = (from.x + to.x) / 2f
        val my = (from.y + to.y) / 2f
        val hSq = r * r - (chord / 2f) * (chord / 2f)
        val h = if (hSq > 0f) sqrt(hSq) else 0f

        // The two candidate centres lie either side of the chord's midpoint.
        val ux = -dy / chord
        val uy = dx / chord
        val sign = if (largeArc != sweep) 1f else -1f
        val cx = mx + sign * h * ux
        val cy = my + sign * h * uy

        val startDegrees = Math.toDegrees(atan2((from.y - cy).toDouble(), (from.x - cx).toDouble())).toFloat()
        val endDegrees = Math.toDegrees(atan2((to.y - cy).toDouble(), (to.x - cx).toDouble())).toFloat()

        // Positive angles run clockwise on screen, because y grows downward —
        // which is what SVG's sweep flag means.
        var delta = endDegrees - startDegrees
        if (sweep) {
            while (delta < 0f) delta += 360f
            while (delta > 360f) delta -= 360f
        } else {
            while (delta > 0f) delta -= 360f
            while (delta < -360f) delta += 360f
        }

        return (1 until steps).map { step ->
            val degrees = startDegrees + delta * step / steps
            val radians = Math.toRadians(degrees.toDouble())
            Pt(cx + r * kotlin.math.cos(radians).toFloat(), cy + r * kotlin.math.sin(radians).toFloat())
        }
    }
}
