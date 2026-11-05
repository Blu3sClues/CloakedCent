package com.cloakedcent.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// System sans-serif (no bundled or downloadable fonts: the app is offline).
private val Default = Typography()

internal val CloakedCentTypography = Typography(
    displaySmall = Default.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Default.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Default.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Amounts use tabular figures so digits line up in lists. */
object MoneyTextStyles {
    private val tabular = TextStyle(fontFamily = FontFamily.Default, fontFeatureSettings = "tnum")

    val hero = tabular.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold)
    val row = tabular.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val caption = tabular.copy(fontSize = 13.sp, lineHeight = 18.sp)
}
