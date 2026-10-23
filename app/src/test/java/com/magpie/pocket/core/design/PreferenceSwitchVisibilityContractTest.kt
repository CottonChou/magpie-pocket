package com.magpie.pocket.core.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the preference switches the user has to read at a glance: the thumb must stay
 * visibly separated from its track in both themes and in both states, and ON / OFF must look
 * different. Contrast is measured with WCAG relative luminance on the real palette colors, so a
 * palette refresh that quietly washes a switch out fails here instead of on a device.
 *
 * Only enabled states carry the 3:1 requirement. Disabled states get a lower bar: perceivable
 * separation rather than a numeric ratio, which matches how disabled controls are allowed to look.
 */
class PreferenceSwitchVisibilityContractTest {

    // ---- WCAG math (pure JVM, no Android runtime) ------------------------

    /** sRGB companding per WCAG 2.x: linear segment below 0.04045, otherwise a 2.4 gamma. */
    private fun linearize(component: Float): Float =
        if (component <= 0.04045f) component / 12.92f
        else pow((component + 0.055f) / 1.055f, 2.4f)

    private fun pow(base: Float, exponent: Float): Float =
        Math.pow(base.toDouble(), exponent.toDouble()).toFloat()

    private fun Color.relativeLuminance(): Float =
        0.2126f * linearize(red) + 0.7152f * linearize(green) + 0.0722f * linearize(blue)

    /** Composites a translucent color onto an opaque backdrop, as the rendered switch appears. */
    private fun Color.over(backdrop: Color): Color =
        if (alpha >= 0.999f) {
            this
        } else {
            Color(
                red = red * alpha + backdrop.red * (1f - alpha),
                green = green * alpha + backdrop.green * (1f - alpha),
                blue = blue * alpha + backdrop.blue * (1f - alpha)
            )
        }

    private fun contrast(first: Color, second: Color, backdrop: Color): Float {
        val a = first.over(backdrop).relativeLuminance()
        val b = second.over(backdrop).relativeLuminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }

    @Test
    fun contrastHelperMatchesKnownWcagRatios() {
        val white = Color(0xFFFFFFFF.toInt())
        val black = Color(0xFF000000.toInt())
        val midGray = Color(0xFF808080.toInt())

        assertEquals(21f, contrast(white, black, white), 0.05f)
        // 50% sRGB gray linearizes to ~0.216, so it is ~3.95:1 on white and ~5.3:1 against black.
        assertEquals(3.95f, contrast(midGray, white, white), 0.08f)
        assertEquals(5.32f, contrast(midGray, black, white), 0.10f)
        assertEquals(1f, contrast(midGray, midGray, white), 0.001f)

        // A half-opaque black reads as mid gray once composited; raw channels would say "identical".
        val translucentBlack = Color(red = 0f, green = 0f, blue = 0f, alpha = 0.5f)
        assertEquals(1f, contrast(translucentBlack, translucentBlack, white), 0.001f)
        assertTrue(
            "composited translucency must still register contrast",
            contrast(translucentBlack, white, white) > 4f
        )
        assertTrue(
            "raw-channel comparison would wrongly pass two identical blacks",
            contrast(translucentBlack, black, white) < contrast(black, white, white)
        )
    }

    // ---- enabled pairs: the >= 3:1 requirement ---------------------------

    @Test
    fun lightSwitchStatesKeepThumbReadableOnTrack() {
        assertEnabledContrast(LightScheme, switchPalette(LightScheme))
    }

    @Test
    fun darkSwitchStatesKeepThumbReadableOnTrack() {
        assertEnabledContrast(DarkScheme, switchPalette(DarkScheme))
    }

    private fun assertEnabledContrast(scheme: MagpieColorScheme, palette: SwitchPalette) {
        val theme = if (scheme.isDark) "dark" else "light"
        // Switches sit on cards, so any translucency resolves against the surface, not the page.
        val backdrop = scheme.surface

        assertAtLeast3(
            "$theme checked thumb vs track",
            contrast(palette.checkedThumb, palette.checkedTrack, backdrop),
            palette
        )
        assertAtLeast3(
            "$theme unchecked thumb vs track",
            contrast(palette.uncheckedThumb, palette.uncheckedTrack, backdrop),
            palette
        )
    }

    private fun assertAtLeast3(label: String, ratio: Float, palette: SwitchPalette) {
        assertTrue(
            "$label must reach WCAG 3:1 (was ${fmt(ratio)}); palette=$palette",
            ratio >= 3f
        )
    }

    // ---- the checked thumb must not disappear ----------------------------

    @Test
    fun checkedThumbNeverDisappearsIntoItsTrack() {
        listOf(LightScheme, DarkScheme).forEach { scheme ->
            val palette = switchPalette(scheme)
            val theme = if (scheme.isDark) "dark" else "light"

            assertNotEquals(
                "$theme checked thumb cannot reuse the track color",
                palette.checkedTrack,
                palette.checkedThumb
            )
            val separation = kotlin.math.abs(
                palette.checkedThumb.over(scheme.surface).relativeLuminance() -
                    palette.checkedTrack.over(scheme.surface).relativeLuminance()
            )
            assertTrue(
                "$theme checked thumb needs real luminance separation (was ${fmt(separation)})",
                separation >= 0.2f
            )
        }
    }

    @Test
    fun enabledSwitchRolesAreOpaqueAndThemeSpecific() {
        val light = switchPalette(LightScheme)
        val dark = switchPalette(DarkScheme)

        listOf(light, dark).forEach { palette ->
            listOf(
                palette.checkedTrack,
                palette.checkedThumb,
                palette.uncheckedTrack,
                palette.uncheckedThumb
            ).forEach { color ->
                assertTrue(
                    "Enabled switch roles must be effectively opaque, saw alpha=${color.alpha}",
                    color.alpha >= 0.999f
                )
            }
            assertNotEquals("$palette collapsed onto itself", light, dark)
        }

        assertNotEquals("both themes must not share the checked track", light.checkedTrack, dark.checkedTrack)
        assertNotEquals("both themes must not share the unchecked thumb", light.uncheckedThumb, dark.uncheckedThumb)
    }

    // ---- state legibility ------------------------------------------------

    @Test
    fun onAndOffTracksArePerceivablyDifferent() {
        listOf(LightScheme, DarkScheme).forEach { scheme ->
            val palette = switchPalette(scheme)
            val theme = if (scheme.isDark) "dark" else "light"
            val ratio = contrast(palette.checkedTrack, palette.uncheckedTrack, scheme.surface)
            assertTrue(
                "$theme switch must read differently when toggled (track ratio ${fmt(ratio)})",
                ratio >= 1.5f
            )

            val thumbRatio = contrast(palette.checkedThumb, palette.uncheckedThumb, scheme.surface)
            assertTrue(
                "$theme thumb should also change state character (ratio ${fmt(thumbRatio)})",
                thumbRatio >= 1.2f
            )
        }
    }

    @Test
    fun disabledStatesRemainPerceivablyDifferent() {
        listOf(LightScheme, DarkScheme).forEach { scheme ->
            val palette = switchPalette(scheme)
            val theme = if (scheme.isDark) "dark" else "light"

            assertNotEquals(
                "$theme disabled thumb cannot equal disabled track",
                palette.disabledTrack,
                palette.disabledThumb
            )
            val ratio = contrast(palette.disabledThumb, palette.disabledTrack, scheme.surface)
            assertTrue(
                "$theme disabled switch still needs visible separation (was ${fmt(ratio)})",
                ratio > 1.05f
            )
        }
    }

    // ---- helpers ----------------------------------------------------------

    private fun fmt(value: Float): String = String.format("%.2f", value)
}
