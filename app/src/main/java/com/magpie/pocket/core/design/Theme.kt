package com.magpie.pocket.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import com.magpie.pocket.core.model.DisplayPreferences
import com.magpie.pocket.core.model.ThemeMode

val LocalMagpieColorScheme = compositionLocalOf { LightScheme }
val LocalDisplayPreferences = compositionLocalOf { DisplayPreferences() }

object MagpieTheme {
    val colors: MagpieColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalMagpieColorScheme.current

    val preferences: DisplayPreferences
        @Composable
        @ReadOnlyComposable
        get() = LocalDisplayPreferences.current
}

@Composable
fun MagpiePocketTheme(
    displayPreferences: DisplayPreferences = DisplayPreferences(),
    content: @Composable () -> Unit
) {
    val isDark = when (displayPreferences.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val customScheme = if (isDark) DarkScheme else LightScheme
    val materialScheme = if (isDark) {
        darkColorScheme(
            primary = customScheme.accent,
            onPrimary = customScheme.background,
            background = customScheme.background,
            surface = customScheme.surface,
            onSurface = customScheme.textPrimary
        )
    } else {
        lightColorScheme(
            primary = customScheme.accent,
            onPrimary = customScheme.surface,
            background = customScheme.background,
            surface = customScheme.surface,
            onSurface = customScheme.textPrimary
        )
    }

    CompositionLocalProvider(
        LocalMagpieColorScheme provides customScheme,
        LocalDisplayPreferences provides displayPreferences
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = MagpieTypographySet,
            content = content
        )
    }
}
