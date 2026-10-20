package com.aasif.watchdog.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.aasif.watchdog.ui.theme.DarkScheme
import com.aasif.watchdog.ui.theme.DarkWatchdogColors
import com.aasif.watchdog.ui.theme.LightScheme
import com.aasif.watchdog.ui.theme.LightWatchdogColors
import com.aasif.watchdog.ui.theme.LocalWatchdogColors
import com.aasif.watchdog.ui.theme.WatchdogShapes
import com.aasif.watchdog.ui.theme.WatchdogTypography

/** What Profile's Appearance switch chose: follow the phone, or always light or dark. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Whether [mode] means dark right now. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** A fixed brand palette (no dynamic colour), light or dark as [dark] says; by default it follows the phone. */
@Composable
fun WatchdogTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWatchdogColors provides if (dark) DarkWatchdogColors else LightWatchdogColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = WatchdogTypography,
            shapes = WatchdogShapes,
            content = content,
        )
    }
}
