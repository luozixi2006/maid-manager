package com.miniichat.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Shapes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Clean iOS-style flat theme.
 * Grouped neutral surfaces, hairline dividers and one restrained blue accent.
 */
internal val LightColors = lightColorScheme(
    primary = Color(0xFF0066CC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F2FF),
    onPrimaryContainer = Color(0xFF00366F),
    secondary = Color(0xFF0066CC),
    onSecondary = Color.White,
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF111114),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111114),
    surfaceVariant = Color(0xFFF3F2F7),
    onSurfaceVariant = Color(0xFF62626B),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE9E9EF),
    outline = Color(0xFFE3E1EA),
    outlineVariant = Color(0xFFEEEDF2),
    error = Color(0xFFE34864),
    onError = Color.White
)

// Tightened dark scheme: lighter onSurfaceVariant for legibility, slightly bolder
// surface contrast so subtle UI (chips, dividers, hint text) reads cleanly.
internal val DarkColors = darkColorScheme(
    primary = Color(0xFF83BDFF),
    onPrimary = Color(0xFF002B55),
    primaryContainer = Color(0xFF153657),
    onPrimaryContainer = Color(0xFFD6EAFF),
    secondary = Color(0xFF83BDFF),
    onSecondary = Color(0xFF002B55),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F1F7),
    surface = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    onSurface = Color(0xFFF2F1F7),
    surfaceVariant = Color(0xFF24242C),
    onSurfaceVariant = Color(0xFFC4C2D0),
    outline = Color(0xFF3A3A45),
    outlineVariant = Color(0xFF26262E),
    error = Color(0xFFFF8FA0),
    onError = Color(0xFF3D0011)
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp
    ),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun MaidManagerTheme(
    themeMode: String = "system", // system | light | dark
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            val dyn = if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            // Material You's generated dark scheme can produce very low-contrast
            // onSurfaceVariant against its surface, especially under cool-toned
            // wallpapers. Override the most legibility-critical roles to keep
            // chip labels, hints, and divider tints readable while still using
            // the wallpaper-derived hue for primary / containers.
            if (darkTheme) {
                dyn.copy(
                    background = Color(0xFF000000),
                    onBackground = Color(0xFFF2F1F7),
                    surface = Color(0xFF1C1C1E),
                    surfaceContainer = Color(0xFF1C1C1E),
                    surfaceContainerLow = Color(0xFF1C1C1E),
                    surfaceContainerHigh = Color(0xFF2C2C2E),
                    onSurface = Color(0xFFF2F1F7),
                    surfaceVariant = Color(0xFF24242C),
                    onSurfaceVariant = Color(0xFFC4C2D0),
                    outline = Color(0xFF3A3A45),
                    outlineVariant = Color(0xFF26262E)
                )
            } else dyn
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(24.dp)
        )
    ) {
        // MaterialTheme alone does not provide LocalContentColor. A root Surface does.
        Surface(modifier = Modifier.fillMaxSize(), color = colors.background,
            contentColor = colors.onBackground, content = content)
    }
}
