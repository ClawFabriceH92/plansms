package com.fabrice.plansms.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    secondary = Cyan,
    onSecondary = Color.White,
    tertiary = Amber,
    background = Cream,
    onBackground = TextDark,
    surface = Color.White,
    onSurface = TextDark,
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = TextDark.copy(alpha = 0.75f),
    error = Danger
)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = NightBlue,
    secondary = Cyan,
    tertiary = Amber,
    background = NightBlue,
    onBackground = TextLight,
    surface = Color(0xFF16223C),
    onSurface = TextLight,
    surfaceVariant = Color(0xFF1E2C4A),
    onSurfaceVariant = TextLight.copy(alpha = 0.7f),
    error = Color(0xFFEF5350)
)

@Composable
fun PlanSmsTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PlanSmsTypography,
        shapes = PlanSmsShapes,
        content = content
    )
}
