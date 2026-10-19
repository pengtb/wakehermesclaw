package com.openclaw.assistant.ui.theme

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.openclaw.assistant.R
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val OpenClawColorScheme = darkColorScheme(
    primary = OpenClawOrange,
    secondary = OpenClawPopYellow,
    tertiary = OpenClawOrange,
    background = OpenClawDarkGrey,
    surface = OpenClawSurfaceGrey,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = OpenClawTextPrimary,
    onSurface = OpenClawTextPrimary,
    error = OpenClawError
)

/**
 * 浅色版：同样是 OpenClaw 的橙/黄，只是底色翻成浅的。
 * 系统是浅色时用它，深色时仍用上面那套（和以前完全一样）。
 */
private val OpenClawLightColorScheme = lightColorScheme(
    primary = OpenClawOrange,
    secondary = OpenClawPopYellow,
    tertiary = OpenClawOrange,
    background = Color(0xFFF6F6F7),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color(0xFF1B1B1F),
    onTertiary = Color.White,
    onBackground = Color(0xFF1B1B1F),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFEBEBEE),
    onSurfaceVariant = Color(0xFF5B5B62),
    error = OpenClawError
)

// Manrope TTF not bundled — use system sans-serif to avoid FontNotFoundException
val Manrope = FontFamily.SansSerif

private val AppTypography = Typography(
    headlineLarge = Typography().headlineLarge.copy(fontFamily = Manrope),
    headlineMedium = Typography().headlineMedium.copy(fontFamily = Manrope),
    headlineSmall = Typography().headlineSmall.copy(fontFamily = Manrope),
    titleLarge = Typography().titleLarge.copy(fontFamily = Manrope),
    titleMedium = Typography().titleMedium.copy(fontFamily = Manrope),
    titleSmall = Typography().titleSmall.copy(fontFamily = Manrope),
    bodyLarge = Typography().bodyLarge.copy(fontFamily = Manrope),
    bodyMedium = Typography().bodyMedium.copy(fontFamily = Manrope),
    bodySmall = Typography().bodySmall.copy(fontFamily = Manrope),
    labelLarge = Typography().labelLarge.copy(fontFamily = Manrope),
    labelMedium = Typography().labelMedium.copy(fontFamily = Manrope),
    labelSmall = Typography().labelSmall.copy(fontFamily = Manrope)
)

@Composable
fun OpenClawAssistantTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // 跟随系统深浅色：深色用原来的品牌深色方案，浅色用同色系的浅色方案
    val colorScheme = if (darkTheme) OpenClawColorScheme else OpenClawLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val context = view.context
            if (context is Activity) {
                val window = context.window
                window.statusBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
