package com.vertos.promoblocker

import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

enum class AppThemeMode(val label: String) {
    DARK("Dark"), LIGHT("Light"), DARK_OLED("Pure black");

    val isDark: Boolean get() = this != LIGHT

    companion object {
        fun fromStored(value: String?): AppThemeMode = entries.firstOrNull { it.name == value } ?: DARK
    }
}

private val LightPalette = lightColorScheme(
    primary = Color(0xFF176653), onPrimary = Color.White,
    primaryContainer = Color(0xFFD4EDE3), onPrimaryContainer = Color(0xFF103D32),
    secondary = Color(0xFF53665F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5ECE7), onSecondaryContainer = Color(0xFF263F35),
    tertiary = Color(0xFF436278), tertiaryContainer = Color(0xFFDCEAF3), onTertiaryContainer = Color(0xFF254356),
    background = Color(0xFFF5F7F4), onBackground = Color(0xFF192B25),
    surface = Color(0xFFFCFDFB), onSurface = Color(0xFF192B25),
    surfaceVariant = Color(0xFFE7EDE7), onSurfaceVariant = Color(0xFF52635B),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F4EF),
    surfaceContainer = Color(0xFFEAF0E9), surfaceContainerHigh = Color(0xFFE4EBE3),
    surfaceContainerHighest = Color(0xFFDFE7DD),
    outline = Color(0xFF77877E), outlineVariant = Color(0xFFD8E0D7),
    error = Color(0xFFAC3D39), errorContainer = Color(0xFFFFE9E5), onErrorContainer = Color(0xFF762A27),
)
private val DarkPalette = darkColorScheme(
    primary = Color(0xFFF0F0F0), onPrimary = Color(0xFF191919),
    primaryContainer = Color(0xFF2B2B2B), onPrimaryContainer = Color(0xFFF5F5F5),
    secondary = Color(0xFFC6C6C6), onSecondary = Color(0xFF262626),
    secondaryContainer = Color(0xFF343434), onSecondaryContainer = Color(0xFFE7E7E7),
    tertiary = Color(0xFFD2D2D2), tertiaryContainer = Color(0xFF333333), onTertiaryContainer = Color(0xFFF0F0F0),
    background = Color(0xFF0D0D0D), onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF171717), onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF303030), onSurfaceVariant = Color(0xFFBEBEBE),
    surfaceContainerLowest = Color(0xFF090909), surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF222222), surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF343434),
    outline = Color(0xFF8F8F8F), outlineVariant = Color(0xFF404040),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF512D29), onErrorContainer = Color(0xFFFFDAD4),
)

private val AmoledPalette = DarkPalette.copy(
    background = Color.Black,
    surface = Color(0xFF101010),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF090909),
    surfaceContainer = Color(0xFF141414),
    primaryContainer = Color(0xFF202020),
)

@Composable
fun PromoTheme(mode: AppThemeMode, content: @Composable () -> Unit) {
    val dark = mode.isDark
    val view = LocalView.current
    val palette = when (mode) {
        AppThemeMode.DARK -> DarkPalette
        AppThemeMode.LIGHT -> LightPalette
        AppThemeMode.DARK_OLED -> AmoledPalette
    }
    if (!view.isInEditMode) {
        LaunchedEffect(view, dark) {
            (view.context as? androidx.activity.ComponentActivity)?.let { activity ->
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                activity.window.isNavigationBarContrastEnforced = false
            }
        }
    }
    MaterialTheme(
        colorScheme = palette,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
