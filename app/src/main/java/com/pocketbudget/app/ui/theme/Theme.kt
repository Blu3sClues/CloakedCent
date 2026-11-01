package com.pocketbudget.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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

/**
 * Pocket Budget brand theme. Dynamic (wallpaper) color is intentionally not
 * used so the aqua / midnight identity is consistent on every device.
 */
@Composable
fun PocketBudgetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
