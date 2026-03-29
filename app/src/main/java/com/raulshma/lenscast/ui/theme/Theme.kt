package com.raulshma.lenscast.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = LensBlueLight,
    onPrimary = LensInk,
    primaryContainer = LensBlueDark,
    onPrimaryContainer = LensMist,
    secondary = LensMist,
    onSecondary = LensInk,
    secondaryContainer = LensInkSoft,
    onSecondaryContainer = LensMist,
    tertiary = LensGreen,
    onTertiary = LensInk,
    tertiaryContainer = Color(0xFF143227),
    onTertiaryContainer = Color(0xFFBEECC8),
    error = LensRed,
    onError = LensWhite,
    background = Color(0xFF08131F),
    onBackground = LensMist,
    surface = Color(0xFF0D1826),
    onSurface = LensMist,
    surfaceVariant = Color(0xFF1B2A3C),
    onSurfaceVariant = Color(0xFFB2C2D5),
    surfaceContainer = Color(0xFF122033),
    surfaceContainerHigh = Color(0xFF18283C),
    outline = Color(0xFF5B7088),
)

private val LightColorScheme = lightColorScheme(
    primary = LensBlueDark,
    onPrimary = LensWhite,
    primaryContainer = LensBlueLight,
    onPrimaryContainer = LensInk,
    secondary = LensInkSoft,
    onSecondary = LensWhite,
    secondaryContainer = LensMist,
    onSecondaryContainer = LensInk,
    tertiary = LensGreen,
    onTertiary = LensWhite,
    tertiaryContainer = Color(0xFFD8F2DF),
    onTertiaryContainer = Color(0xFF12331F),
    error = LensRed,
    onError = LensWhite,
    background = LensCloud,
    onBackground = LensInk,
    surface = LensWhite,
    onSurface = LensInk,
    surfaceVariant = Color(0xFFDCE5EF),
    onSurfaceVariant = LensFog,
    surfaceContainer = Color(0xFFF0F4FA),
    surfaceContainerHigh = Color(0xFFE7EDF5),
    outline = Color(0xFF91A3B6),
)

@Composable
fun LensCastTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
