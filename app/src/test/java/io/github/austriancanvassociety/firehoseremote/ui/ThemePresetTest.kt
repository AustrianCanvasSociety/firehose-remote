package io.github.austriancanvassociety.firehoseremote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contrast guarantee, held by the build rather than by review.
 *
 * The step's promise is that no accent a user can reach is unchecked. That is
 * only true if the check runs every time the table changes, so the ratios are
 * recomputed here from the same formula the palette was built with — not
 * transcribed from the reference. The reference's own published figures are
 * asserted separately, which is what catches a formula that has drifted away
 * from the numbers it produced.
 */
class ThemePresetTest {

    /** Ratios are published to two decimals; this is the rounding slack. */
    private val tolerance = 0.02

    @Test
    fun everyPresetAccentClearsItsBarInBothModes() {
        for (preset in Skin.presets) {
            val darkRatio = Contrast.ratio(preset.dark, Skin.DARK_GROUND)
            assertTrue(
                "${preset.id}: dark ${preset.dark} on ground measured $darkRatio, " +
                    "below the ${Skin.DARK_BAR} bar",
                darkRatio >= Skin.DARK_BAR
            )

            val lightRatio = Contrast.ratio(preset.light, Skin.LIGHT_GROUND)
            assertTrue(
                "${preset.id}: light ${preset.light} on ground measured $lightRatio, " +
                    "below the ${Skin.LIGHT_BAR} bar",
                lightRatio >= Skin.LIGHT_BAR
            )
        }
    }

    @Test
    fun theMeasuredRatiosMatchTheReference() {
        // The design reference's published figures, recomputed here. A formula
        // that drifts from these has stopped describing the palette it made.
        assertEquals(12.64, Contrast.ratio(0xFF17E8FF.toInt(), 0xFF010F2E.toInt()), tolerance)
        assertEquals(10.00, Contrast.ratio(0xFFCDAEFF.toInt(), 0xFF010F2E.toInt()), tolerance)
        assertEquals(7.93, Contrast.ratio(0xFF8FB4D4.toInt(), 0xFF011A3D.toInt()), tolerance)
        assertEquals(6.41, Contrast.ratio(0xFF0A6078.toInt(), 0xFFEEF4F9.toInt()), tolerance)
        assertEquals(15.67, Contrast.ratio(0xFF0A1B2E.toInt(), 0xFFEEF4F9.toInt()), tolerance)
    }

    @Test
    fun theIconsCyanFailsOnALightGround() {
        // The measurement the whole light half exists to answer: the icon's own
        // cyan only works near-black. If this ever passes, the light accents
        // could simply reuse the sampled values and the derivations are moot.
        val onLight = Contrast.ratio(0xFF17E8FF.toInt(), Skin.LIGHT_GROUND)
        assertEquals(1.35, onLight, tolerance)
        assertTrue("the icon's cyan should fail on a light ground", onLight < Skin.LIGHT_BAR)
    }

    @Test
    fun lightAccentsAreDerivationsRatherThanReuses() {
        for (preset in Skin.presets) {
            assertNotEquals(
                "${preset.id}: the light accent is the dark one — a reuse, not a derivation",
                preset.dark,
                preset.light
            )
        }
    }

    @Test
    fun theTableIsFullyPopulated() {
        assertEquals(listOf("Aqua", "Amber", "Mint", "Violet", "Coral"), Skin.presets.map { it.label })
        assertEquals("ids are unique", 5, Skin.presets.map { it.id }.toSet().size)
    }

    @Test
    fun theDefaultIsTheSkinClosestToTheIcon() {
        assertEquals("aqua", Skin.defaultPreset.id)
        assertEquals(Skin.presets.first(), Skin.defaultPreset)
    }

    @Test
    fun savedCyanAccentsResolveToAqua() {
        for (id in listOf("plate", "gradient", "aqua")) {
            assertEquals("aqua", Skin.presetFor(id).id)
            assertEquals("Aqua", Skin.presetFor(id).label)
        }
    }

    @Test
    fun anUnknownPresetOrModeFallsBackRatherThanFailing() {
        // A preference written by an older build, or by hand, must not crash the
        // screen — it resolves to the default.
        assertEquals(Skin.defaultPreset, Skin.presetFor("no-such-preset"))
        assertEquals(Skin.defaultPreset, Skin.presetFor(null))
        assertEquals(SkinMode.DARK, Skin.modeFor("nonsense", SkinMode.DARK))
        assertEquals(SkinMode.LIGHT, Skin.modeFor(null, SkinMode.LIGHT))
        assertEquals(SkinDirection.PLATE, Skin.directionFor("nonsense", SkinDirection.PLATE))
    }

    @Test
    fun anAccentChangesTheWholePaletteInEveryModeAndStyle() {
        for (mode in SkinMode.values()) for (direction in SkinDirection.values()) {
            val baseline = Skin.paletteFor(Skin.defaultPreset, mode, direction)
            for (preset in Skin.presets.filter { it != Skin.defaultPreset }) {
                val palette = Skin.paletteFor(preset, mode, direction)
                assertNotEquals("${preset.id}: ground", baseline.ground, palette.ground)
                assertNotEquals("${preset.id}: panel", baseline.panel, palette.panel)
                assertNotEquals("${preset.id}: ink", baseline.ink, palette.ink)
                assertNotEquals("${preset.id}: border", baseline.edge, palette.edge)
                assertNotEquals("${preset.id}: dial", baseline.ring, palette.ring)
                assertNotEquals("${preset.id}: gradient", baseline.gradientColors, palette.gradientColors)
            }
        }
    }

    @Test
    fun everyPaletteIsReadableAcrossItsActualBackgrounds() {
        for (preset in Skin.presets) for (mode in SkinMode.values()) for (direction in SkinDirection.values()) {
            val palette = Skin.paletteFor(preset, mode, direction)
            val backgrounds = if (direction == SkinDirection.GRADIENT) palette.gradientColors else listOf(palette.ground)
            for (ground in backgrounds) {
                val key = Skin.composite(palette.keyFill, ground)
                val name = "${preset.id}/$mode/$direction"
                assertTrue("$name: header", Contrast.ratio(palette.ink, ground) >= 4.5)
                assertTrue("$name: button", Contrast.ratio(palette.ink, key) >= 4.5)
                assertTrue("$name: Select", Contrast.ratio(palette.onSelect, palette.select) >= 4.5)
            }
            // The dial spans the middle of the gradient. The top stop is
            // behind the header, so it is checked for text above, not arrows.
            for (position in listOf(0.3f, 0.5f, 0.7f)) {
                val ground = if (direction == SkinDirection.GRADIENT) Skin.gradientAt(palette, position) else palette.ground
                val dial = Skin.composite(palette.dialFill, ground)
                assertTrue("${preset.id}/$mode/$direction: arrow at $position", Contrast.ratio(palette.dim, dial) >= 3.0)
                assertTrue("${preset.id}/$mode/$direction: ring at $position", Contrast.ratio(Skin.composite(palette.ring, dial), dial) >= 3.0)
            }
            assertTrue("${preset.id}/$mode: accent", Contrast.ratio(palette.select, palette.ground) >= Skin.barFor(mode))
        }
    }

    @Test
    fun fieldHintsReadAcrossTheWholeGradient() {
        for (preset in Skin.presets) for (mode in SkinMode.values()) {
            val palette = Skin.paletteFor(preset, mode, SkinDirection.GRADIENT)
            for (step in 0..100) {
                val background = Skin.gradientAt(palette, step / 100f)
                assertTrue("${preset.id}/$mode: field hint at $step%",
                    Contrast.ratio(palette.ink, background) >= 4.5)
            }
        }
    }

    @Test
    fun defaultPlateAndGradientKeepTheReviewedColors() {
        val plate = Skin.paletteFor(Skin.defaultPreset, SkinMode.DARK, SkinDirection.PLATE)
        assertEquals(0xFF010F2E.toInt(), plate.ground)
        assertEquals(0xFF011A3D.toInt(), plate.panel)
        assertEquals(0xFF3EF2FD.toInt(), plate.ring)
        val gradient = Skin.paletteFor(Skin.defaultPreset, SkinMode.DARK, SkinDirection.GRADIENT)
        assertEquals(listOf(0xFF0B6FA8.toInt(), 0xFF0A4A7C.toInt(), 0xFF04234B.toInt(), plate.ground), gradient.gradientColors)
        assertEquals(listOf(0f, 0.22f, 0.55f, 1f), gradient.gradientPositions)
        assertTrue("gradient keys are translucent", (gradient.keyFill ushr 24) < 255)
        val light = Skin.paletteFor(Skin.defaultPreset, SkinMode.LIGHT, SkinDirection.GRADIENT)
        assertEquals(0xFFCFE9F5.toInt(), light.gradientColors.first())
        assertTrue("light gradient keys are translucent", (light.keyFill ushr 24) < 255)
    }
}
