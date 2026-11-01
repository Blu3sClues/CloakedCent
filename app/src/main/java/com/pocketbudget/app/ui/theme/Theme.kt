package com.pocketbudget.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = AquaPrimary,
    onPrimary = AquaOnPrimary,
    primaryContainer = AquaPrimaryContainer,
    onPrimaryContainer = AquaOnPrimaryContainer,
    secondary = AquaSecondary,
    secondaryContainer = AquaSecondaryContainer,
    onSecondaryContainer = AquaOnSecondaryContainer,
    tertiary = AquaTertiary,
    tertiaryContainer = AquaTertiaryContainer,
    onTertiaryContainer = AquaOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightBackground,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    surfaceContainerLowest = LightBackground,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    error = LightError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
)

private val DarkColors = darkColorScheme(
    primary = MidnightPrimary,
    onPrimary = MidnightOnPrimary,
    primaryContainer = MidnightPrimaryContainer,
    onPrimaryContainer = MidnightOnPrimaryContainer,
    secondary = MidnightSecondary,
    onSecondary = MidnightOnSecondary,
    secondaryContainer = MidnightSecondaryContainer,
    onSecondaryContainer = MidnightOnSecondaryContainer,
    tertiary = MidnightTertiary,
    tertiaryContainer = MidnightTertiaryContainer,
    onTertiaryContainer = MidnightOnTertiaryContainer,
    background = MidnightBackground,
    onBackground = MidnightOnBackground,
    surface = MidnightBackground,
    onSurface = MidnightOnBackground,
    surfaceVariant = GreySurfaceVariant,
    onSurfaceVariant = GreyOnSurfaceVariant,
    outline = GreyOutline,
    outlineVariant = GreyOutlineVariant,
    surfaceContainerLowest = GreySurfaceContainerLowest,
    surfaceContainerLow = GreySurfaceContainerLow,
    surfaceContainer = GreySurfaceContainer,
    surfaceContainerHigh = GreySurfaceContainerHigh,
    surfaceContainerHighest = GreySurfaceContainerHighest,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
)

/** Financial meaning colors that Material's scheme does not cover. */
@Immutable
data class LedgerColors(val income: Color, val expense: Color, val scheduled: Color)

private val LightLedgerColors = LedgerColors(income = IncomeLight, expense = LightError, scheduled = AquaTertiary)
private val DarkLedgerColors = LedgerColors(income = IncomeDark, expense = DarkError, scheduled = MidnightTertiary)

private val LocalLedgerColors = staticCompositionLocalOf { LightLedgerColors }

private val PocketBudgetShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

object PocketBudgetTheme {
    val ledgerColors: LedgerColors
        @Composable get() = LocalLedgerColors.current
}

/**
 * Pocket Budget brand theme. Dynamic (wallpaper) color is intentionally not
 * used so the aqua / midnight identity is consistent on every device.
 */
@Composable
fun PocketBudgetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalLedgerColors provides if (darkTheme) DarkLedgerColors else LightLedgerColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = PocketBudgetTypography,
            shapes = PocketBudgetShapes,
            content = content,
        )
    }
}
