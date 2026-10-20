package com.aasif.watchdog.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Mercury (VoltAgent/awesome-design-md) tokens: one indigo action colour, a lavender-tinted light world
// and an ink-violet "night" dark world. Status colours are tuned to sit with the violet, not green.

internal val Indigo = Color(0xFF5266EB)
internal val IndigoLift = Color(0xFF8C9BFF)
internal val Canvas = Color(0xFFFBFCFD)
internal val Night = Color(0xFF171721)
internal val NightRaised = Color(0xFF1E1E2A)
internal val Lavender = Color(0xFFEDECFB)
internal val Ink = Color(0xFF28253B)
internal val OnNight = Color(0xFFEDEDF3)

val LightScheme: ColorScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECEEFD),
    onPrimaryContainer = Color(0xFF3A4BD0),
    secondary = Color(0xFF554E7B),
    onSecondary = Color.White,
    secondaryContainer = Lavender,
    onSecondaryContainer = Ink,
    tertiary = Color(0xFF0E6E86),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDF2F6),
    onTertiaryContainer = Color(0xFF0A4F61),
    error = Color(0xFFC0344C),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8EC),
    onErrorContainer = Color(0xFF8C1F33),
    background = Canvas,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Lavender,
    onSurfaceVariant = Color(0xFF554E7B),
    outline = Color(0xFF666579),
    outlineVariant = Color(0xFFE7E6F2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F6FD),
    surfaceContainer = Color(0xFFF1F0FB),
    surfaceContainerHigh = Lavender,
    surfaceContainerHighest = Lavender,
    scrim = Night,
)

val DarkScheme: ColorScheme = darkColorScheme(
    primary = IndigoLift,
    onPrimary = Color(0xFF11132E),
    primaryContainer = Color(0xFF262B4D),
    onPrimaryContainer = Color(0xFFC9D0FF),
    secondary = Color(0xFFC3C3CC),
    onSecondary = Night,
    secondaryContainer = Color(0xFF272735),
    onSecondaryContainer = OnNight,
    tertiary = Color(0xFF67C7DB),
    onTertiary = Color(0xFF06303A),
    tertiaryContainer = Color(0xFF12313A),
    onTertiaryContainer = Color(0xFFB6E8F2),
    error = Color(0xFFFF8FA0),
    onError = Color(0xFF4A0E1A),
    errorContainer = Color(0xFF3F1A22),
    onErrorContainer = Color(0xFFFFD6DC),
    background = Night,
    onBackground = OnNight,
    surface = NightRaised,
    onSurface = OnNight,
    surfaceVariant = Color(0xFF272735),
    onSurfaceVariant = Color(0xFFC3C3CC),
    outline = Color(0xFF9C9BAE),
    outlineVariant = Color(0xFF2E2E40),
    surfaceContainerLowest = Color(0xFF12121A),
    surfaceContainerLow = Color(0xFF1B1B26),
    surfaceContainer = NightRaised,
    surfaceContainerHigh = Color(0xFF272735),
    surfaceContainerHighest = Color(0xFF2E2E40),
    scrim = Color.Black,
)

/** Roles Material 3 has no slot for: status fills, the night hero band, skeleton and snackbar colours. */
@Immutable
class WatchdogColors(
    val brandText: Color,
    val amber: Color,
    val amberSoft: Color,
    val info: Color,
    val infoSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val dim: Color,
    val fieldBorder: Color,
    val hero: Color,
    val heroInk: Color,
    val heroInk2: Color,
    val heroChip: Color,
    val skeleton: Color,
    val skeletonHi: Color,
    val skeletonHero: Color,
    val skeletonHeroHi: Color,
    val snack: Color,
    val snackInk: Color,
    val snackAction: Color,
)

val LightWatchdogColors = WatchdogColors(
    brandText = Color(0xFF3A4BD0),
    amber = Color(0xFF94550A),
    amberSoft = Color(0xFFFBF0DC),
    info = Color(0xFF0E6E86),
    infoSoft = Color(0xFFDDF2F6),
    danger = Color(0xFFC0344C),
    dangerSoft = Color(0xFFFCE8EC),
    dim = Color(0xFF666579),
    fieldBorder = Color(0xFF9291A6),
    hero = Night,
    heroInk = OnNight,
    heroInk2 = Color(0xFFC3C3CC),
    heroChip = Color(0x1AFFFFFF),
    skeleton = Color(0xFFEFEEF7),
    skeletonHi = Color(0xFFF8F8FC),
    skeletonHero = Color(0xFF26263A),
    skeletonHeroHi = Color(0xFF32324A),
    snack = Night,
    snackInk = OnNight,
    snackAction = Color(0xFF9CB4E8),
)

val DarkWatchdogColors = WatchdogColors(
    brandText = IndigoLift,
    amber = Color(0xFFF2B45A),
    amberSoft = Color(0xFF3A2C14),
    info = Color(0xFF67C7DB),
    infoSoft = Color(0xFF12313A),
    danger = Color(0xFFFF8FA0),
    dangerSoft = Color(0xFF3F1A22),
    dim = Color(0xFF9C9BAE),
    fieldBorder = Color(0xFF6B6A7B),
    hero = Color(0xFF232340),
    heroInk = OnNight,
    heroInk2 = Color(0xFFC3C3CC),
    heroChip = Color(0x1AFFFFFF),
    skeleton = Color(0xFF232331),
    skeletonHi = Color(0xFF2C2C3D),
    skeletonHero = Color(0xFF2C2C4A),
    skeletonHeroHi = Color(0xFF38385A),
    snack = Color(0xFFEDEDF3),
    snackInk = Night,
    snackAction = Color(0xFF4A5BD6),
)

internal val LocalWatchdogColors = staticCompositionLocalOf { LightWatchdogColors }

val MaterialTheme.watchdog: WatchdogColors
    @Composable @ReadOnlyComposable get() = LocalWatchdogColors.current
