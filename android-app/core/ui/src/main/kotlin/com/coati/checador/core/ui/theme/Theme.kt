package com.coati.checador.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = CoatiNavy,
    onPrimary = CoatiBackground,
    primaryContainer = CoatiNavyLight,
    onPrimaryContainer = CoatiBackground,
    secondary = CoatiBlue,
    onSecondary = CoatiBackground,
    secondaryContainer = CoatiBlueLight,
    onSecondaryContainer = CoatiNavyDark,
    tertiary = CoatiTeal,
    onTertiary = CoatiBackground,
    tertiaryContainer = CoatiTealLight,
    onTertiaryContainer = CoatiNavyDark,
    background = CoatiBackground,
    onBackground = CoatiNavy,
    surface = CoatiSurface,
    onSurface = CoatiOnSurface,
    surfaceVariant = CoatiSurfaceVariant,
    onSurfaceVariant = CoatiOnSurfaceVariant,
    error = CoatiError,
    onError = CoatiSurface
)

private val DarkColorScheme = darkColorScheme(
    primary = CoatiBlue,
    onPrimary = CoatiNavyDark,
    primaryContainer = CoatiNavy,
    onPrimaryContainer = CoatiBackground,
    secondary = CoatiTeal,
    onSecondary = CoatiNavyDark,
    secondaryContainer = CoatiTealDark,
    onSecondaryContainer = CoatiBackground,
    tertiary = CoatiBlueLight,
    onTertiary = CoatiNavyDark,
    background = CoatiNavyDark,
    onBackground = CoatiBackground,
    surface = CoatiNavy,
    onSurface = CoatiBackground,
    surfaceVariant = CoatiNavyLight,
    onSurfaceVariant = CoatiSurfaceVariant,
    error = CoatiError,
    onError = CoatiSurface
)

@Composable
fun CoatiCheckTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
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
        typography = CoatiTypography,
        content = content
    )
}
