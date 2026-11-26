package com.radba.remoto.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Paleta "Liquid Glass" noturna usada em toda a interface. */
object GlassColors {
    val bg: Color = Color(0xFF05060A)
    val glass: Color = Color(0xFF131722)
    val glassHi: Color = Color(0xFF1E2433)
    val edge: Color = Color(0xFF262C3D)
    val edgeHi: Color = Color(0xFF4A5470)
    val text: Color = Color(0xFFEEF1F8)
    val sub: Color = Color(0xFF8A93A8)
    val acc: Color = Color(0xFF7DA7FF)
    val acc2: Color = Color(0xFFA592FF)
    val ok: Color = Color(0xFF5FE3A6)
    val warn: Color = Color(0xFFFFB86B)
    val bad: Color = Color(0xFFFF6B7A)
}

private val DarkScheme = darkColorScheme(
    primary = GlassColors.acc,
    onPrimary = GlassColors.bg,
    primaryContainer = GlassColors.glassHi,
    onPrimaryContainer = GlassColors.text,
    secondary = GlassColors.acc2,
    onSecondary = GlassColors.bg,
    secondaryContainer = GlassColors.glassHi,
    onSecondaryContainer = GlassColors.text,
    tertiary = GlassColors.ok,
    onTertiary = GlassColors.bg,
    tertiaryContainer = GlassColors.glassHi,
    onTertiaryContainer = GlassColors.text,
    background = GlassColors.bg,
    onBackground = GlassColors.text,
    surface = GlassColors.glass,
    onSurface = GlassColors.text,
    surfaceVariant = GlassColors.glassHi,
    onSurfaceVariant = GlassColors.sub,
    surfaceTint = GlassColors.acc,
    inverseSurface = GlassColors.text,
    inverseOnSurface = GlassColors.bg,
    error = GlassColors.bad,
    onError = GlassColors.bg,
    errorContainer = GlassColors.glassHi,
    onErrorContainer = GlassColors.text,
    outline = GlassColors.edge,
    outlineVariant = GlassColors.edgeHi,
    scrim = Color.Black,
)

/** Segoe UI não está disponível no Android; SansSerif (Roboto) é o fallback mais próximo. */
private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp),
)

@Composable
fun RadbaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = AppTypography,
        content = content,
    )
}
