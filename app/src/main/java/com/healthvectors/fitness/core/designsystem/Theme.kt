package com.healthvectors.fitness.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.healthvectors.fitness.domain.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5E), onPrimary = Color.White,
    primaryContainer = Color(0xFF9EF2E0), onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635E), tertiary = Color(0xFFB4570B),
    background = Color(0xFFF7FAF8), surface = Color(0xFFF7FAF8)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF82D6C4), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046), onPrimaryContainer = Color(0xFF9EF2E0),
    secondary = Color(0xFFB1CCC5), tertiary = Color(0xFFFFB77C),
    background = Color(0xFF0E1513), surface = Color(0xFF0E1513)
)

@Composable
fun FitnessTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
