package com.peri.hardwareinformation.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A fixed palette, deliberately not dynamic colour.
 *
 * Dynamic colour derives the scheme from the user's wallpaper, which means an information-dense
 * utility looks different on every device and can land on a low-contrast tint that makes a wall of
 * label/value rows hard to read. For an app whose whole promise is predictability, the colours have
 * to be predictable too. Neutral greys carry the structure; the single accent is reserved for
 * interactive text and the current selection.
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF1B6AC9),
    onPrimary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE8F8),
    onSecondaryContainer = Color(0xFF11314F),
    background = Color(0xFFFBFBFC),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFBFBFC),
    onSurface = Color(0xFF191C1E),
    onSurfaceVariant = Color(0xFF5B6167),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F5F7),
    surfaceContainer = Color(0xFFECEEF1),
    surfaceContainerHigh = Color(0xFFE5E7EB),
    surfaceContainerHighest = Color(0xFFDDE0E4),
    outline = Color(0xFF8A9097),
    outlineVariant = Color(0xFFDBDEE2),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8FB8F6),
    onPrimary = Color(0xFF0A2544),
    secondaryContainer = Color(0xFF23344A),
    onSecondaryContainer = Color(0xFFD5E3FA),
    background = Color(0xFF101315),
    onBackground = Color(0xFFE1E4E7),
    surface = Color(0xFF101315),
    onSurface = Color(0xFFE1E4E7),
    onSurfaceVariant = Color(0xFF9AA1A8),
    surfaceContainerLowest = Color(0xFF0B0D0F),
    surfaceContainerLow = Color(0xFF181B1D),
    surfaceContainer = Color(0xFF1C1F22),
    surfaceContainerHigh = Color(0xFF262A2D),
    surfaceContainerHighest = Color(0xFF313539),
    outline = Color(0xFF8A9097),
    outlineVariant = Color(0xFF383C40),
)

/**
 * Two font weights only — regular for everything, bold for headings.
 *
 * Material 3's defaults reach for Medium (500) in the title and label styles, which puts three
 * weights on screen at once and blurs the line between a section heading and the rows under it.
 * Collapsing to regular/bold makes the hierarchy legible at a glance and keeps the weights easy to
 * apply consistently. Body line heights are raised to 1.5 for comfortable reading.
 */
private val Default = Typography()

private val AppTypography = Default.copy(
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmall = Default.titleSmall.copy(fontWeight = FontWeight.Bold),
    bodyLarge = Default.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = Default.bodyMedium.copy(lineHeight = 21.sp),
    bodySmall = Default.bodySmall.copy(lineHeight = 18.sp),
    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.Normal),
    labelMedium = Default.labelMedium.copy(fontWeight = FontWeight.Normal),
    labelSmall = Default.labelSmall.copy(fontWeight = FontWeight.Normal),
)

@Composable
fun HardwareInformationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) = MaterialTheme(
    colorScheme = if (darkTheme) DarkScheme else LightScheme,
    typography = AppTypography,
    content = content,
)
