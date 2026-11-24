package id.exynos850.fmradio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NightDarkColors = darkColorScheme(
    primary = Color(0xFFF59E0B),
    onPrimary = Color(0xFF0B0E14),
    secondary = Color(0xFF10B981),
    background = Color(0xFF0B0E14),
    surface = Color(0xFF131822),
    surfaceVariant = Color(0xFF1C2331),
    onBackground = Color(0xFFE2E8F0),
    onSurface = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF283245)
)

private val DaylightColors = lightColorScheme(
    primary = Color(0xFFD97706),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF059669),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFE2E8F0)
)

@Composable
fun A21sFmTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) NightDarkColors else DaylightColors,
        content = content
    )
}
