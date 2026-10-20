package com.aasif.watchdog.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aasif.watchdog.R

// Mercury's DESIGN.md names Inter (variable, 350-480) as the open substitute for its own arcadia faces:
// a text cut at the default optical size and a display cut at the top of the opsz axis, kept light.
private fun inter(weight: Int, opticalSize: Float) = Font(
    resId = R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", opticalSize),
    ),
)

val InterText = FontFamily(
    inter(360, 14f), inter(400, 14f), inter(440, 14f), inter(500, 14f), inter(600, 14f),
)

val InterDisplay = FontFamily(
    inter(480, 32f), inter(500, 32f),
)

private val Light = FontWeight(360)
private val Regular = FontWeight(400)
private val Ui = FontWeight(440)
private val Medium = FontWeight(500)
private val Headline = FontWeight(480)
private const val DisplayTracking = 0.01f

val WatchdogTypography = Typography(
    displayLarge = TextStyle(fontFamily = InterDisplay, fontWeight = Headline, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = DisplayTracking.em),
    displayMedium = TextStyle(fontFamily = InterDisplay, fontWeight = Headline, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = DisplayTracking.em),
    displaySmall = TextStyle(fontFamily = InterDisplay, fontWeight = Headline, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = DisplayTracking.em),
    headlineLarge = TextStyle(fontFamily = InterDisplay, fontWeight = Headline, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = DisplayTracking.em),
    headlineMedium = TextStyle(fontFamily = InterDisplay, fontWeight = Headline, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = DisplayTracking.em),
    headlineSmall = TextStyle(fontFamily = InterDisplay, fontWeight = Headline, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = DisplayTracking.em),
    titleLarge = TextStyle(fontFamily = InterDisplay, fontWeight = Headline, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = DisplayTracking.em),
    titleMedium = TextStyle(fontFamily = InterText, fontWeight = Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = InterText, fontWeight = Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = InterText, fontWeight = Regular, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = InterText, fontWeight = Light, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = InterText, fontWeight = Light, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = InterText, fontWeight = Ui, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = InterText, fontWeight = Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.02.em),
    labelSmall = TextStyle(fontFamily = InterText, fontWeight = Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.02.em),
)

/** Tabular figures, so amounts and dates line up in a column. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
