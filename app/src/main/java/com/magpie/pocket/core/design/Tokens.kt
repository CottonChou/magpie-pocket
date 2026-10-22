package com.magpie.pocket.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object MagpieColors {
    // Approved Magpie Pocket palette (design-tokens.json v1.0).
    val LightBackground = Color(0xFFF6F7FB)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceSubtle = Color(0xFFF0F1F6)
    val LightTextPrimary = Color(0xFF202330)
    val LightTextSecondary = Color(0xFF6D7283)
    val LightTextTertiary = Color(0xFF9297A6)
    val LightDivider = Color(0xFFE8EAF1)
    val LightAccent = Color(0xFF5E59D9)
    val LightAccentSurface = Color(0xFFEEEDFC)
    val LightSuccess = Color(0xFF237A61)
    val LightSuccessSurface = Color(0xFFE5F2EC)
    val LightWarning = Color(0xFF986116)
    val LightWarningSurface = Color(0xFFFFF1DB)
    val LightError = Color(0xFFBD4852)
    val LightErrorSurface = Color(0xFFFBE9EB)
    val LightGlassTint = Color(0xFFFFFFFF)
    const val LightGlassAlpha = 0.74f

    val DarkBackground = Color(0xFF101217)
    val DarkSurface = Color(0xFF1A1E26)
    val DarkSurfaceSubtle = Color(0xFF222733)
    val DarkTextPrimary = Color(0xFFEEF0F6)
    val DarkTextSecondary = Color(0xFFA5ACBC)
    val DarkTextTertiary = Color(0xFF777F91)
    val DarkDivider = Color(0xFF2D3340)
    val DarkAccent = Color(0xFFB0AAFF)
    val DarkAccentSurface = Color(0xFF2C2947)
    val DarkSuccess = Color(0xFF7AD7B5)
    val DarkSuccessSurface = Color(0xFF20372E)
    val DarkWarning = Color(0xFFF3BF71)
    val DarkWarningSurface = Color(0xFF392D1D)
    val DarkError = Color(0xFFFF969F)
    val DarkErrorSurface = Color(0xFF3C252C)
    val DarkGlassTint = Color(0xFF272C3A)
    const val DarkGlassAlpha = 0.82f
}

data class MagpieColorScheme(
    val background: Color,
    val surface: Color,
    val surfaceSubtle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val accent: Color,
    val accentSurface: Color,
    val success: Color,
    val successSurface: Color,
    val warning: Color,
    val warningSurface: Color,
    val error: Color,
    val errorSurface: Color,
    val glassTint: Color,
    val glassAlpha: Float,
    val isDark: Boolean
)

val LightScheme = MagpieColorScheme(
    background = MagpieColors.LightBackground,
    surface = MagpieColors.LightSurface,
    surfaceSubtle = MagpieColors.LightSurfaceSubtle,
    textPrimary = MagpieColors.LightTextPrimary,
    textSecondary = MagpieColors.LightTextSecondary,
    textTertiary = MagpieColors.LightTextTertiary,
    divider = MagpieColors.LightDivider,
    accent = MagpieColors.LightAccent,
    accentSurface = MagpieColors.LightAccentSurface,
    success = MagpieColors.LightSuccess,
    successSurface = MagpieColors.LightSuccessSurface,
    warning = MagpieColors.LightWarning,
    warningSurface = MagpieColors.LightWarningSurface,
    error = MagpieColors.LightError,
    errorSurface = MagpieColors.LightErrorSurface,
    glassTint = MagpieColors.LightGlassTint,
    glassAlpha = MagpieColors.LightGlassAlpha,
    isDark = false
)

val DarkScheme = MagpieColorScheme(
    background = MagpieColors.DarkBackground,
    surface = MagpieColors.DarkSurface,
    surfaceSubtle = MagpieColors.DarkSurfaceSubtle,
    textPrimary = MagpieColors.DarkTextPrimary,
    textSecondary = MagpieColors.DarkTextSecondary,
    textTertiary = MagpieColors.DarkTextTertiary,
    divider = MagpieColors.DarkDivider,
    accent = MagpieColors.DarkAccent,
    accentSurface = MagpieColors.DarkAccentSurface,
    success = MagpieColors.DarkSuccess,
    successSurface = MagpieColors.DarkSuccessSurface,
    warning = MagpieColors.DarkWarning,
    warningSurface = MagpieColors.DarkWarningSurface,
    error = MagpieColors.DarkError,
    errorSurface = MagpieColors.DarkErrorSurface,
    glassTint = MagpieColors.DarkGlassTint,
    glassAlpha = MagpieColors.DarkGlassAlpha,
    isDark = true
)

object MagpieDimensions {
    val Spacing2: Dp = 2.dp
    val Spacing4: Dp = 4.dp
    val Spacing6: Dp = 6.dp
    val Spacing8: Dp = 8.dp
    val Spacing12: Dp = 12.dp
    val Spacing16: Dp = 16.dp
    val Spacing20: Dp = 20.dp
    val Spacing24: Dp = 24.dp
    val Spacing32: Dp = 32.dp

    val PagePadding: Dp = 20.dp
    val CompactPagePadding: Dp = 16.dp
    val TouchTargetMinimum: Dp = 48.dp

    val NavigationHeight: Dp = 68.dp
    val NavigationHorizontalMargin: Dp = 16.dp
    val NavigationBottomGapAboveInset: Dp = 8.dp
    val ContentExtraBottomPadding: Dp = 16.dp
    val ChromeHeight: Dp = 64.dp

    val RadiusCard: Dp = 24.dp
    val RadiusPill: Dp = 32.dp
    val RadiusItem: Dp = 14.dp
    val RadiusSheetTop: Dp = 28.dp

    // Backward compatibility aliases
    val RadiusDataPanel: Dp = 24.dp
    val RadiusSelector: Dp = 20.dp
    val RadiusSearch: Dp = 20.dp
    val RadiusBottomNavigation: Dp = RadiusPill
}

object MagpieTypography {
    val PageTitle: TextUnit = 28.sp
    val LargeTitle: TextUnit = 28.sp
    val SectionTitle: TextUnit = 17.sp
    val HeroMetric: TextUnit = 36.sp
    val MetricHero: TextUnit = 36.sp
    val InlineMetric: TextUnit = 24.sp
    val MetricInline: TextUnit = 24.sp
    val Body: TextUnit = 14.sp
    val Subhead: TextUnit = 13.sp
    val Label: TextUnit = 12.sp
    val Navigation: TextUnit = 12.sp
    val Caption: TextUnit = 11.sp

    // Explicit styles carry line heights so tiny metadata never inherits bodyLarge's
    // 20sp line box. Native sp scaling still applies to every value below.
    val PageTitleStyle = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold
    )
    val SectionTitleStyle = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold
    )
    val BodyStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val SubheadStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val LabelStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val CaptionStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val NavigationStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val HeroMetricStyle = TextStyle(
        fontSize = 36.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.SemiBold
    )
    val InlineMetricStyle = TextStyle(
        fontSize = 24.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold
    )
    val SheetTitleStyle = TextStyle(
        fontSize = 21.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold
    )
}

val MagpieTypographySet = Typography(
    displayLarge = MagpieTypography.HeroMetricStyle,
    headlineLarge = MagpieTypography.PageTitleStyle,
    headlineMedium = MagpieTypography.InlineMetricStyle,
    headlineSmall = MagpieTypography.SectionTitleStyle,
    titleLarge = MagpieTypography.PageTitleStyle,
    titleMedium = MagpieTypography.SectionTitleStyle,
    titleSmall = MagpieTypography.SubheadStyle.copy(fontWeight = FontWeight.Medium),
    bodyLarge = MagpieTypography.BodyStyle,
    bodyMedium = MagpieTypography.SubheadStyle,
    bodySmall = MagpieTypography.LabelStyle,
    labelLarge = MagpieTypography.BodyStyle.copy(fontWeight = FontWeight.Medium),
    labelMedium = MagpieTypography.LabelStyle.copy(fontWeight = FontWeight.Medium),
    labelSmall = MagpieTypography.LabelStyle
)
