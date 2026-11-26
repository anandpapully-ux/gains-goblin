package com.healthvectors.fitness.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthvectors.fitness.domain.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5E), onPrimary = Color.White,
    primaryContainer = Color(0xFF9EF2E0), onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635E), secondaryContainer = Color(0xFFCCE8E1), onSecondaryContainer = Color(0xFF05201B),
    tertiary = Color(0xFFB4570B), tertiaryContainer = Color(0xFFFFDCC4), onTertiaryContainer = Color(0xFF301400),
    background = Color(0xFFF5FAF8), surface = Color(0xFFF5FAF8),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEFF5F2), surfaceContainer = Color(0xFFE9EFEC),
    surfaceContainerHigh = Color(0xFFE3EAE7), surfaceContainerHighest = Color(0xFFDDE4E1)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF82D6C4), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046), onPrimaryContainer = Color(0xFF9EF2E0),
    secondary = Color(0xFFB1CCC5), secondaryContainer = Color(0xFF334B46), onSecondaryContainer = Color(0xFFCCE8E1),
    tertiary = Color(0xFFFFB77C), tertiaryContainer = Color(0xFF713700), onTertiaryContainer = Color(0xFFFFDCC4),
    background = Color(0xFF0E1513), surface = Color(0xFF0E1513),
    surfaceContainerLowest = Color(0xFF090F0E), surfaceContainerLow = Color(0xFF171D1B), surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF252B2A), surfaceContainerHighest = Color(0xFF303634)
)

/** Default Material type with firmer weights on titles and big numbers, so headings read as headings. */
private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)
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
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
