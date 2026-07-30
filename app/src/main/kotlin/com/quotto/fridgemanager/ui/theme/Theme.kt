package com.quotto.fridgemanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val LightColors = lightColorScheme(
    primary = Color(0xFF256B31),
    onPrimary = Color.White,
    secondary = Color(0xFF52634F),
    background = Color(0xFFF8FBF4),
    surface = Color(0xFFF8FBF4),
    error = Color(0xFFBA1A1A),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF8ED994),
    onPrimary = Color(0xFF00390C),
    secondary = Color(0xFFB9CCB4),
    background = Color(0xFF10140F),
    surface = Color(0xFF10140F),
    error = Color(0xFFFFB4AB),
)

@Composable
fun FridgeManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
