package com.miniichat.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun normalTextRemainsReadableOnLightAndDarkSurfaces() {
        for (scheme in listOf(LightColors, DarkColors)) {
            listOf(
                scheme.onBackground to scheme.background,
                scheme.onSurface to scheme.surface,
                scheme.onSurfaceVariant to scheme.surfaceVariant,
                scheme.onPrimary to scheme.primary,
                scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.primary to scheme.surface
            ).forEach { (foreground, background) ->
                val contrast = contrast(foreground, background)
                assertTrue("Text contrast $contrast must reach 4.5:1", contrast >= 4.5f)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)
}
