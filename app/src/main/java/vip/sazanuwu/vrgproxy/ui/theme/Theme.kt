package vip.sazanuwu.vrgproxy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF3DDC84)
private val AccentDark = Color(0xFF1FA363)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF06210F),
    secondary = Color(0xFF7FD1FF),
    background = Color(0xFF101214),
    surface = Color(0xFF181B1F),
    surfaceVariant = Color(0xFF23282E),
    error = Color(0xFFFF6B6B)
)

private val LightColors = lightColorScheme(
    primary = AccentDark,
    onPrimary = Color.White,
    secondary = Color(0xFF1B6FA8),
    background = Color(0xFFF7F9FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE8EDF1),
    error = Color(0xFFB3261E)
)

@Composable
fun VrgTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
