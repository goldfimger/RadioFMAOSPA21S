package id.exynos850.fmradio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OneUiDarkColors = darkColorScheme(
    primary = Color(0xFFFF7A38),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF22C55E),
    background = Color(0xFF000000),
    surface = Color(0xFF17171A),
    surfaceVariant = Color(0xFF222227),
    onBackground = Color(0xFFF4F4F6),
    onSurface = Color(0xFFF4F4F6),
    onSurfaceVariant = Color(0xFF9A9AA6),
    outline = Color(0xFF2A2A32)
)

private val OneUiLightColors = lightColorScheme(
    primary = Color(0xFFE65100),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF16A34A),
    background = Color(0xFFF6F6F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECEEF2),
    onBackground = Color(0xFF121214),
    onSurface = Color(0xFF121214),
    onSurfaceVariant = Color(0xFF64646E),
    outline = Color(0xFFE2E2E8)
)

@Composable
fun A21sFmTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) OneUiDarkColors else OneUiLightColors,
        content = content
    )
}
