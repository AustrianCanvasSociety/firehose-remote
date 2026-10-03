package app.burro.firehoseremote.ui

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The app's skin tables.
 *
 * Everything here is plain Kotlin — no `android.*` import — for the same reason
 * [DpadGeometry] carries none: the decisions are data, and data can be asserted
 * on the JVM. The persisted selection deliberately does *not* live here; reading
 * and writing it needs `SharedPreferences`, and pulling that in would drag the
 * platform onto the classpath and take [ThemePresetTest] off the JVM. The
 * preference stays in `MainActivity`, beside `word_labels`, and the resolved
 * skin is passed down.
 *
 * Colours come from the launcher icon, decoded from its PNGs. The design
 * reference — and the measured ratios quoted below — is an internal mockup,
 * not published.
 */

/** The two directions: the icon's flat plate, or the teardrop's vertical fall. */
internal enum class SkinDirection { PLATE, GRADIENT }

/** The mode the user picks, independent of the system's. */
internal enum class SkinMode { DARK, LIGHT }

/**
 * One accent hue, in a dark value and a light one, on the same ground.
 *
 * Both values are pre-measured against [Skin.darkGround] / [Skin.lightGround];
 * a pair that does not clear its bar fails [ThemePresetTest] rather than
 * reaching a user. There is no free colour picker for exactly this reason — a
 * palette the app cannot measure is one whose legibility nobody has verified.
 */
internal data class AccentPreset(
    val id: String,
    val label: String,
    val dark: Int,
    val light: Int,
)

/** One resolved palette, shared by the screen, controls and window. */
internal data class SkinPalette(
    val ground: Int,
    val panel: Int,
    val ink: Int,
    val dim: Int,
    val edge: Int,
    val ring: Int,
    val select: Int,
    val onSelect: Int,
    val keyFill: Int,
    val keyEdge: Int,
    val rockerFill: Int,
    val dialFill: Int,
    val divider: Int,
    val stateLayer: Int,
    val gradientColors: List<Int>,
    val gradientPositions: List<Float>
)

internal object Skin {

    /** The ground every dark accent is measured against. Sampled from the icon. */
    const val DARK_GROUND = 0xFF010F2E.toInt()

    /** The ground every light accent is measured against. */
    const val LIGHT_GROUND = 0xFFEEF4F9.toInt()

    /** The accent bar, dark: AAA. */
    const val DARK_BAR = 7.0

    /**
     * The accent bar, light: AA, deliberately.
     *
     * Darkening further to reach AAA turns amber brown and coral brick — it
     * loses the hue that made the preset worth offering. AA is the ceiling this
     * palette accepts, and it is a choice rather than a shortfall.
     */
    const val LIGHT_BAR = 4.5

    /**
     * The presets, in menu order. Aqua is first and is the default, so a user
     * who never opens the menu gets the skin closest to the icon.
     *
     * Violet earned its dark value: its first pass measured 7.45:1 and missed
     * AAA, so it was lightened to #CDAEFF at 10.00:1.
     */
    val presets: List<AccentPreset> = listOf(
        AccentPreset("aqua", "Aqua", 0xFF17E8FF.toInt(), 0xFF0A6078.toInt()),
        AccentPreset("amber", "Amber", 0xFFFFB454.toInt(), 0xFF7E5210.toInt()),
        AccentPreset("mint", "Mint", 0xFF5AF0B0.toInt(), 0xFF0E6244.toInt()),
        AccentPreset("violet", "Violet", 0xFFCDAEFF.toInt(), 0xFF543490.toInt()),
        AccentPreset("coral", "Coral", 0xFFFF8E7A.toInt(), 0xFF96341E.toInt()),
    )

    val defaultPreset: AccentPreset get() = presets.first()

    /** The ground a mode's accents are measured against. */
    fun groundFor(mode: SkinMode): Int =
        if (mode == SkinMode.DARK) DARK_GROUND else LIGHT_GROUND

    /** The bar a mode's accents must clear. */
    fun barFor(mode: SkinMode): Double =
        if (mode == SkinMode.DARK) DARK_BAR else LIGHT_BAR

    /** The preset's Select color; the other roles come from [paletteFor]. */
    fun accentFor(preset: AccentPreset, mode: SkinMode): Int =
        if (mode == SkinMode.DARK) preset.dark else preset.light

    /**
     * Recolor every role to the selected hue, keeping each role's lightness
     * and saturation. Aqua preserves the reviewed icon colors.
     * Gradient surfaces retain their alpha so the background shows through.
     */
    fun paletteFor(preset: AccentPreset, mode: SkinMode, direction: SkinDirection): SkinPalette {
        val dark = mode == SkinMode.DARK
        val gradient = direction == SkinDirection.GRADIENT
        val original = preset.id == "aqua"
        val hue = hueOf(preset.dark)
        fun color(darkValue: Long, lightValue: Long): Int {
            val base = (if (dark) darkValue else lightValue).toInt()
            return if (original) base else withHue(base, hue)
        }
        val ground = color(0xFF010F2E, 0xFFEEF4F9)
        val panel = color(0xFF011A3D, 0xFFFFFFFF)
        // White has no hue to change. A very pale tint gives light keys the
        // same selected hue as the ground without losing their separation.
        val tintedPanel = if (!dark && !original) blend(panel, preset.light, 0.025f) else panel
        val ink = color(0xFFE8F6FF, 0xFF0A1B2E)
        val stops = if (dark) {
            listOf(color(0xFF0B6FA8, 0), color(0xFF0A4A7C, 0), color(0xFF04234B, 0), ground)
        } else {
            listOf(color(0, 0xFFCFE9F5), color(0, 0xFFE2F0F8), ground)
        }.map { stop ->
            // Yellow and green at the blue gradient's lightness can be much
            // brighter. Keep the chosen hue and darken just enough for text.
            if (dark && !original) readableBackground(stop, ink) else stop
        }
        val edge = color(0xFF123C6B, 0xFFC9DBE8)
        val select = accentFor(preset, mode)
        val highlight = if (original) color(0xFF5AF4FD, 0xFF0A6078) else select
        return SkinPalette(
            ground = ground,
            panel = tintedPanel,
            ink = ink,
            dim = color(0xFF8FB4D4, 0xFF4E6A82),
            edge = if (gradient && dark) alpha(highlight, 0.34f) else edge,
            ring = if (gradient && dark && original) alpha(highlight, 0.6f)
                else if (original) color(0xFF3EF2FD, 0xFF0A6078) else select,
            select = select,
            onSelect = if (dark) ground else 0xFFFFFFFF.toInt(),
            keyFill = if (gradient) alpha(if (dark) ground else tintedPanel, if (dark) 0.62f else 0.8f) else tintedPanel,
            keyEdge = if (gradient && dark) alpha(highlight, 0.26f) else edge,
            rockerFill = if (gradient) alpha(tintedPanel, if (dark) 0.55f else 0.72f) else tintedPanel,
            dialFill = if (gradient) 0 else tintedPanel,
            divider = alpha(if (dark) 0xFFFFFFFF.toInt() else ink, if (dark) 0.07f else 0.1f),
            stateLayer = alpha(select, 0.12f),
            gradientColors = stops,
            gradientPositions = if (dark) listOf(0f, 0.22f, 0.55f, 1f) else listOf(0f, 0.4f, 1f)
        )
    }

    fun alpha(color: Int, opacity: Float): Int =
        (color and 0x00FFFFFF) or ((255 * opacity).roundToInt().coerceIn(0, 255) shl 24)

    /** Composite a translucent surface over an opaque background. */
    fun composite(foreground: Int, background: Int): Int =
        blend(background, foreground, (foreground ushr 24) / 255f)

    fun blend(from: Int, to: Int, fraction: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from ushr shift) and 255
            val b = (to ushr shift) and 255
            return (a + (b - a) * fraction).roundToInt().coerceIn(0, 255)
        }
        return 0xFF000000.toInt() or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun gradientAt(palette: SkinPalette, position: Float): Int {
        val p = position.coerceIn(0f, 1f)
        val index = palette.gradientPositions.indexOfFirst { it >= p }.coerceAtLeast(1)
        val start = palette.gradientPositions[index - 1]
        val end = palette.gradientPositions[index]
        return blend(palette.gradientColors[index - 1], palette.gradientColors[index], (p - start) / (end - start))
    }

    private fun readableBackground(background: Int, ink: Int): Int {
        for (step in 0..100) {
            val candidate = blend(background, 0xFF000000.toInt(), step / 100f)
            if (Contrast.ratio(ink, candidate) >= 4.5) return candidate
        }
        return 0xFF000000.toInt()
    }

    private fun hueOf(color: Int): Float {
        val r = ((color ushr 16) and 255) / 255f
        val g = ((color ushr 8) and 255) / 255f
        val b = (color and 255) / 255f
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        if (delta == 0f) return 0f
        val hue = when (max) {
            r -> (g - b) / delta
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        }
        return ((hue * 60f) + 360f) % 360f
    }

    private fun withHue(color: Int, hue: Float): Int {
        val channels = listOf(16, 8, 0).map { ((color ushr it) and 255) / 255f }
        val max = channels.maxOrNull()!!
        val min = channels.minOrNull()!!
        val lightness = (max + min) / 2f
        val chroma = max - min
        val h = hue / 60f
        val x = chroma * (1f - kotlin.math.abs(h % 2f - 1f))
        val rgb = when (h.toInt()) {
            0 -> listOf(chroma, x, 0f)
            1 -> listOf(x, chroma, 0f)
            2 -> listOf(0f, chroma, x)
            3 -> listOf(0f, x, chroma)
            4 -> listOf(x, 0f, chroma)
            else -> listOf(chroma, 0f, x)
        }.map { ((it + lightness - chroma / 2f) * 255).roundToInt().coerceIn(0, 255) }
        return (color and 0xFF000000.toInt()) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }

    /** Older builds named their cyan accents after the styles. */
    fun presetFor(id: String?): AccentPreset {
        val canonical = if (id == "plate" || id == "gradient") "aqua" else id
        return presets.firstOrNull { it.id == canonical } ?: defaultPreset
    }

    /** The mode named by a stored value, or [fallback] when it names none. */
    fun modeFor(stored: String?, fallback: SkinMode): SkinMode =
        when (stored) {
            "dark" -> SkinMode.DARK
            "light" -> SkinMode.LIGHT
            else -> fallback
        }

    /** The direction named by a stored value, or [fallback] when it names none. */
    fun directionFor(stored: String?, fallback: SkinDirection): SkinDirection =
        when (stored) {
            "plate" -> SkinDirection.PLATE
            "gradient" -> SkinDirection.GRADIENT
            else -> fallback
        }
}

/**
 * The WCAG relative-luminance contrast ratio.
 *
 * This is the same computation the palette was built with, which is the point:
 * a preset that fails [ThemePresetTest] cannot be added without failing the
 * build, so the guarantee is held by the test rather than by the review.
 */
internal object Contrast {

    /** The WCAG relative luminance of an opaque ARGB colour, in 0..1. */
    fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = channel((color shr 16) and 0xFF)
        val g = channel((color shr 8) and 0xFF)
        val b = channel(color and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** The contrast ratio between two opaque colours, always >= 1. */
    fun ratio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
