package com.hermes.agent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val HermesDarkColorScheme = darkColorScheme(
    primary = HermesViolet,
    onPrimary = Color.White,
    primaryContainer = HermesVioletContainer,
    onPrimaryContainer = OnSurface,
    secondary = HermesTeal,
    onSecondary = Color.Black,
    secondaryContainer = HermesTealDim,
    onSecondaryContainer = OnSurface,
    tertiary = HermesTeal,
    background = Surface900,
    onBackground = OnSurface,
    surface = Surface800,
    onSurface = OnSurface,
    surfaceVariant = Surface700,
    onSurfaceVariant = OnSurfaceMedium,
    outline = Surface500,
    outlineVariant = Surface600,
    error = ErrorRed,
    onError = Color.White
)

private val HermesLightColorScheme = lightColorScheme(
    primary = HermesVioletDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE7FF),
    onPrimaryContainer = Color(0xFF1A0A3D),
    secondary = HermesTealDim,
    background = Color(0xFFF5F3FF),
    onBackground = Color(0xFF0A0A1A),
    surface = Color.White,
    onSurface = Color(0xFF0A0A1A),
    surfaceVariant = Color(0xFFEEEBFF),
    outline = Color(0xFFB8B0D8),
    error = ErrorRed
)

@Composable
fun HermesAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> HermesDarkColorScheme
        else -> HermesLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HermesTypography,
        content = content
    )
}
