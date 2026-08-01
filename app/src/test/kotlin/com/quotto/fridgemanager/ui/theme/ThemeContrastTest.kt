package com.quotto.fridgemanager.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test
    fun `明暗テーマの主要な文字色はWCAG AAのコントラスト比を満たす`() {
        assertReadable("light", LightColors)
        assertReadable("dark", DarkColors)
    }

    private fun assertReadable(name: String, colors: ColorScheme) {
        mapOf(
            "primary" to Contrast(colors.onPrimary, colors.primary, 4.5),
            "background" to Contrast(colors.onBackground, colors.background, 4.5),
            "surface" to Contrast(colors.onSurface, colors.surface, 4.5),
            "surfaceVariant" to Contrast(colors.onSurfaceVariant, colors.surfaceVariant, 4.5),
            "secondary" to Contrast(colors.onSecondary, colors.secondary, 4.5),
            "secondaryContainer" to Contrast(colors.onSecondaryContainer, colors.secondaryContainer, 4.5),
            "error" to Contrast(colors.onError, colors.error, 4.5),
            "errorContainer" to Contrast(colors.onErrorContainer, colors.errorContainer, 4.5),
            // UI部品の境界線はWCAG 1.4.11の非テキスト基準（3:1）で検証する。
            "outline" to Contrast(colors.outline, colors.surface, 3.0),
        ).forEach { (role, expected) ->
            val ratio = contrastRatio(expected.foreground, expected.background)
            assertTrue("$name/$role contrast was $ratio", ratio >= expected.minimum)
        }
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val lighter = maxOf(foreground.luminance(), background.luminance()).toDouble()
        val darker = minOf(foreground.luminance(), background.luminance()).toDouble()
        return (lighter + 0.05) / (darker + 0.05)
    }

    private data class Contrast(
        val foreground: Color,
        val background: Color,
        val minimum: Double,
    )
}
