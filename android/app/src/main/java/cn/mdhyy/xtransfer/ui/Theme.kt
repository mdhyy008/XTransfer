package cn.mdhyy.xtransfer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import cn.mdhyy.xtransfer.R

data class XtColors(
    val success: Color,
    val errorOnPrimary: Color
)

val LocalXtColors = staticCompositionLocalOf {
    XtColors(Color(0xFF2E7D32), Color(0xFFFFDAD6))
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private data class SurfaceTone(
    val surface: Color,
    val containerLowest: Color,
    val containerLow: Color,
    val container: Color,
    val containerHigh: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color
)

private data class SeedTone(
    val lightPrimary: Color,
    val lightOnPrimary: Color,
    val lightContainer: Color,
    val lightOnContainer: Color,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val darkContainer: Color,
    val darkOnContainer: Color,
    val lightSurface: SurfaceTone,
    val darkSurface: SurfaceTone
)

private val seeds = mapOf(
    "purple" to SeedTone(
        Color(0xFF6750A4), Color(0xFFFFFFFF), Color(0xFFEADDFF), Color(0xFF21005D),
        Color(0xFFD0BCFF), Color(0xFF381E72), Color(0xFF4F378B), Color(0xFFEADDFF),
        SurfaceTone(
            Color(0xFFFEF7FF), Color(0xFFFFFFFF), Color(0xFFF7F2FA), Color(0xFFF3EDF7),
            Color(0xFFECE6F0), Color(0xFF1D1B20), Color(0xFF49454F), Color(0xFF79747E),
            Color(0xFFCAC4D0), Color(0xFFE8DEF8), Color(0xFF1D192B)
        ),
        SurfaceTone(
            Color(0xFF141218), Color(0xFF0F0D13), Color(0xFF1D1B20), Color(0xFF211F26),
            Color(0xFF2B2930), Color(0xFFE6E0E9), Color(0xFFCAC4D0), Color(0xFF938F99),
            Color(0xFF49454F), Color(0xFF4A4458), Color(0xFFE8DEF8)
        )
    ),
    "blue" to SeedTone(
        Color(0xFF0061A4), Color(0xFFFFFFFF), Color(0xFFD1E4FF), Color(0xFF001D36),
        Color(0xFF9ECAFF), Color(0xFF003258), Color(0xFF00497D), Color(0xFFD1E4FF),
        SurfaceTone(
            Color(0xFFF8F9FF), Color(0xFFFFFFFF), Color(0xFFF2F3FA), Color(0xFFECEEF4),
            Color(0xFFE6E8EF), Color(0xFF191C20), Color(0xFF44474E), Color(0xFF74777F),
            Color(0xFFC4C6CF), Color(0xFFD7E3F7), Color(0xFF111C2B)
        ),
        SurfaceTone(
            Color(0xFF111418), Color(0xFF0C0E11), Color(0xFF191C20), Color(0xFF1D2024),
            Color(0xFF272A2E), Color(0xFFE2E2E6), Color(0xFFC4C6CF), Color(0xFF8E9099),
            Color(0xFF44474E), Color(0xFF3E4759), Color(0xFFD6E3F7)
        )
    ),
    "green" to SeedTone(
        Color(0xFF386A20), Color(0xFFFFFFFF), Color(0xFFB8F397), Color(0xFF042100),
        Color(0xFF9DD67B), Color(0xFF0B3900), Color(0xFF1F5100), Color(0xFFB8F397),
        SurfaceTone(
            Color(0xFFF8FAF3), Color(0xFFFFFFFF), Color(0xFFF2F5ED), Color(0xFFECEFE7),
            Color(0xFFE6E9E1), Color(0xFF1A1C18), Color(0xFF43483E), Color(0xFF74796D),
            Color(0xFFC3C8BC), Color(0xFFD7E8C9), Color(0xFF131F0D)
        ),
        SurfaceTone(
            Color(0xFF11140F), Color(0xFF0C0F0A), Color(0xFF191D16), Color(0xFF1D211A),
            Color(0xFF282B24), Color(0xFFE2E3DC), Color(0xFFC3C8BC), Color(0xFF8D9287),
            Color(0xFF43483E), Color(0xFF3A4B32), Color(0xFFD7E8C9)
        )
    ),
    "orange" to SeedTone(
        Color(0xFF8B5000), Color(0xFFFFFFFF), Color(0xFFFFDCBE), Color(0xFF2D1600),
        Color(0xFFFFB870), Color(0xFF4A2800), Color(0xFF693C00), Color(0xFFFFDCBE),
        SurfaceTone(
            Color(0xFFFFF8F3), Color(0xFFFFFFFF), Color(0xFFFBF2EA), Color(0xFFF5EEE6),
            Color(0xFFEFE7DF), Color(0xFF221A12), Color(0xFF51443A), Color(0xFF837469),
            Color(0xFFD5C3B6), Color(0xFFF7DEC9), Color(0xFF2B1707)
        ),
        SurfaceTone(
            Color(0xFF18120C), Color(0xFF120C07), Color(0xFF201A13), Color(0xFF241E17),
            Color(0xFF2F2821), Color(0xFFEDE0D4), Color(0xFFD5C3B6), Color(0xFF9E8D80),
            Color(0xFF51443A), Color(0xFF574337), Color(0xFFF7DEC9)
        )
    )
)

val seedNames = listOf(
    "purple" to "紫",
    "blue" to "蓝",
    "green" to "绿",
    "orange" to "橙"
)

fun seedColor(seed: String, dark: Boolean): Color {
    val tone = seeds[seed] ?: seeds.getValue("purple")
    return if (dark) tone.darkPrimary else tone.lightPrimary
}

private fun scheme(seed: String, dark: Boolean): ColorScheme {
    val tone = seeds[seed] ?: seeds.getValue("purple")
    val surface = if (dark) tone.darkSurface else tone.lightSurface
    return if (dark) {
        darkColorScheme(
            primary = tone.darkPrimary,
            onPrimary = tone.darkOnPrimary,
            primaryContainer = tone.darkContainer,
            onPrimaryContainer = tone.darkOnContainer,
            secondaryContainer = surface.secondaryContainer,
            onSecondaryContainer = surface.onSecondaryContainer,
            surface = surface.surface,
            surfaceContainerLowest = surface.containerLowest,
            surfaceContainerLow = surface.containerLow,
            surfaceContainer = surface.container,
            surfaceContainerHigh = surface.containerHigh,
            background = surface.surface,
            onBackground = surface.onSurface,
            onSurface = surface.onSurface,
            onSurfaceVariant = surface.onSurfaceVariant,
            outline = surface.outline,
            outlineVariant = surface.outlineVariant,
            error = Color(0xFFF2B8B5),
            errorContainer = Color(0xFF8C1D18),
            onErrorContainer = Color(0xFFF9DEDC)
        )
    } else {
        lightColorScheme(
            primary = tone.lightPrimary,
            onPrimary = tone.lightOnPrimary,
            primaryContainer = tone.lightContainer,
            onPrimaryContainer = tone.lightOnContainer,
            secondaryContainer = surface.secondaryContainer,
            onSecondaryContainer = surface.onSecondaryContainer,
            surface = surface.surface,
            surfaceContainerLowest = surface.containerLowest,
            surfaceContainerLow = surface.containerLow,
            surfaceContainer = surface.container,
            surfaceContainerHigh = surface.containerHigh,
            background = surface.surface,
            onBackground = surface.onSurface,
            onSurface = surface.onSurface,
            onSurfaceVariant = surface.onSurfaceVariant,
            outline = surface.outline,
            outlineVariant = surface.outlineVariant,
            error = Color(0xFFB3261E),
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B)
        )
    }
}

// MiSans 可变字体：同一份资源按字重映射轴值，W400 抬到 450，正文在小字号下不发虚。
@OptIn(ExperimentalTextApi::class)
private val MiSans = FontFamily(
    Font(R.font.misans_variable, weight = FontWeight.W300, variationSettings = FontVariation.Settings(FontVariation.weight(300))),
    Font(R.font.misans_variable, weight = FontWeight.W400, variationSettings = FontVariation.Settings(FontVariation.weight(450))),
    Font(R.font.misans_variable, weight = FontWeight.W500, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.misans_variable, weight = FontWeight.W600, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.misans_variable, weight = FontWeight.W700, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

private val MiSansTypography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = MiSans),
        displayMedium = base.displayMedium.copy(fontFamily = MiSans),
        displaySmall = base.displaySmall.copy(fontFamily = MiSans),
        headlineLarge = base.headlineLarge.copy(fontFamily = MiSans),
        headlineMedium = base.headlineMedium.copy(fontFamily = MiSans),
        headlineSmall = base.headlineSmall.copy(fontFamily = MiSans),
        titleLarge = base.titleLarge.copy(fontFamily = MiSans),
        titleMedium = base.titleMedium.copy(fontFamily = MiSans),
        titleSmall = base.titleSmall.copy(fontFamily = MiSans),
        bodyLarge = base.bodyLarge.copy(fontFamily = MiSans),
        bodyMedium = base.bodyMedium.copy(fontFamily = MiSans),
        bodySmall = base.bodySmall.copy(fontFamily = MiSans),
        labelLarge = base.labelLarge.copy(fontFamily = MiSans),
        labelMedium = base.labelMedium.copy(fontFamily = MiSans),
        labelSmall = base.labelSmall.copy(fontFamily = MiSans)
    )
}

@Composable
fun XTransferTheme(mode: String, seed: String, misans: Boolean = true, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val colors = scheme(seed, dark)
    val xt = XtColors(
        success = if (dark) Color(0xFF81C784) else Color(0xFF2E7D32),
        errorOnPrimary = if (dark) Color(0xFF8C1D18) else Color(0xFFFFDAD6)
    )
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = if (misans) MiSansTypography else Typography()
    ) {
        CompositionLocalProvider(
            LocalContentColor provides colors.onBackground,
            LocalXtColors provides xt,
            content = content
        )
    }
}
