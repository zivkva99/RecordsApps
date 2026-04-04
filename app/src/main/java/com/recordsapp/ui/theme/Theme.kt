package com.recordsapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = VinylGold,
    onPrimary = VinylBlack,
    primaryContainer = VinylDarkGray,
    onPrimaryContainer = VinylLightGold,
    secondary = VinylGreen,
    background = VinylBlack,
    onBackground = VinylCream,
    surface = VinylDarkGray,
    onSurface = VinylCream,
    error = VinylRed
)

private val LightColorScheme = lightColorScheme(
    primary = VinylGold,
    onPrimary = VinylWhite,
    primaryContainer = VinylLightGold,
    onPrimaryContainer = VinylBlack,
    secondary = VinylGreen,
    background = VinylWhite,
    onBackground = VinylBlack,
    surface = VinylCream,
    onSurface = VinylBlack,
    error = VinylRed
)

@Composable
fun RecordsAppTheme(
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
        typography = Typography,
        content = content
    )
}
