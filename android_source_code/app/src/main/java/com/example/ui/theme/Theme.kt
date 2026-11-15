package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val ColorOnAmber = MeshBackground

private val MeshDarkScheme = darkColorScheme(
    primary = MeshAmber,
    onPrimary = ColorOnAmber,
    primaryContainer = MeshAmber.copy(alpha = 0.16f),
    onPrimaryContainer = MeshAmber,
    secondary = MeshGreen,
    onSecondary = MeshBackground,
    tertiary = MeshBlue,
    background = MeshBackground,
    onBackground = MeshText,
    surface = MeshSurface,
    onSurface = MeshText,
    surfaceVariant = MeshCard,
    onSurfaceVariant = MeshSecondary,
    outline = MeshSecondary.copy(alpha = 0.35f),
    error = MeshRed,
    onError = MeshText
)

private val OledScheme = MeshDarkScheme.copy(
    background = OledBackground,
    surface = OledSurface,
    surfaceVariant = OledCard
)

private val MeshTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp
    )
)

@Composable
fun MyApplicationTheme(
    isOledTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = if (isOledTheme) {
                OledBackground.toArgb()
            } else {
                MeshBackground.toArgb()
            }
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(
        colorScheme = if (isOledTheme) OledScheme else MeshDarkScheme,
        typography = MeshTypography,
        content = content
    )
}
