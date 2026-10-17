package cattivole.spectra.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Spectra look: a deep blue-black night theme and a cool paper day theme,
 * both carrying the cyan-to-violet spectrum of the app icon. Colours are the
 * brand's own rather than the wallpaper's, so signal colours always read the
 * same against them.
 */
private val Cyan = Color(0xFF33B9E1)
private val Violet = Color(0xFF8B6CFF)

private val Dark = darkColorScheme(
    primary = Color(0xFF5ED3F4),
    onPrimary = Color(0xFF00313F),
    primaryContainer = Color(0xFF0F3A4B),
    onPrimaryContainer = Color(0xFFC2F0FF),
    secondary = Color(0xFF9DB4FF),
    onSecondary = Color(0xFF0D1F52),
    secondaryContainer = Color(0xFF22305A),
    onSecondaryContainer = Color(0xFFDCE3FF),
    tertiary = Color(0xFFB7A2FF),
    onTertiary = Color(0xFF2A1670),
    tertiaryContainer = Color(0xFF35266B),
    onTertiaryContainer = Color(0xFFE8DEFF),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5C0A06),
    errorContainer = Color(0xFF4A1716),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF080C14),
    onBackground = Color(0xFFE7ECF5),
    surface = Color(0xFF080C14),
    onSurface = Color(0xFFE7ECF5),
    surfaceVariant = Color(0xFF1B2333),
    onSurfaceVariant = Color(0xFF94A1B8),
    surfaceTint = Color(0xFF5ED3F4),
    outline = Color(0xFF3A465C),
    outlineVariant = Color(0xFF232C3D),
    inverseSurface = Color(0xFFE7ECF5),
    inverseOnSurface = Color(0xFF151B27),
    inversePrimary = Color(0xFF00677F),
    surfaceBright = Color(0xFF252E40),
    surfaceDim = Color(0xFF080C14),
    surfaceContainerLowest = Color(0xFF05080E),
    surfaceContainerLow = Color(0xFF0E141F),
    surfaceContainer = Color(0xFF121A27),
    surfaceContainerHigh = Color(0xFF192232),
    surfaceContainerHighest = Color(0xFF212B3D),
    scrim = Color.Black,
)

private val Light = lightColorScheme(
    primary = Color(0xFF00789A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F2FC),
    onPrimaryContainer = Color(0xFF002A36),
    secondary = Color(0xFF3F5BC4),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE4FF),
    onSecondaryContainer = Color(0xFF0D1F52),
    tertiary = Color(0xFF6A4FE0),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8E0FF),
    onTertiaryContainer = Color(0xFF22105E),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFFE3E0),
    onErrorContainer = Color(0xFF5C0A06),
    background = Color(0xFFF2F5FA),
    onBackground = Color(0xFF111723),
    surface = Color(0xFFF2F5FA),
    onSurface = Color(0xFF111723),
    surfaceVariant = Color(0xFFE4E9F2),
    onSurfaceVariant = Color(0xFF5A6578),
    surfaceTint = Color(0xFF00789A),
    outline = Color(0xFFB9C2D2),
    outlineVariant = Color(0xFFE1E6EF),
    inverseSurface = Color(0xFF1C2330),
    inverseOnSurface = Color(0xFFEEF2F8),
    inversePrimary = Color(0xFF5ED3F4),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDCE2EC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F9FC),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEDF1F7),
    surfaceContainerHighest = Color(0xFFE5EAF2),
    scrim = Color.Black,
)

/** Colours outside Material's roles: signal levels, warnings, and the brand gradient. */
@Immutable
data class SpectraColors(
    val excellent: Color,
    val good: Color,
    val fair: Color,
    val weak: Color,
    val poor: Color,
    val warning: Color,
    val danger: Color,
    val success: Color,
    val info: Color,
    val revealed: Color,
    val random: Color,
    val spectrum: Brush,
    val isDark: Boolean,
)

private val DarkExtras = SpectraColors(
    excellent = Color(0xFF34D399),
    good = Color(0xFF2DD4BF),
    fair = Color(0xFFFACC15),
    weak = Color(0xFFFB923C),
    poor = Color(0xFFF87171),
    warning = Color(0xFFFBBF24),
    danger = Color(0xFFF87171),
    success = Color(0xFF34D399),
    info = Color(0xFF60A5FA),
    revealed = Color(0xFFC084FC),
    random = Color(0xFFA78BFA),
    spectrum = Brush.linearGradient(listOf(Cyan, Color(0xFF5B8CFF), Violet)),
    isDark = true,
)

private val LightExtras = SpectraColors(
    excellent = Color(0xFF16A34A),
    good = Color(0xFF0D9488),
    fair = Color(0xFFCA8A04),
    weak = Color(0xFFEA580C),
    poor = Color(0xFFDC2626),
    warning = Color(0xFFD97706),
    danger = Color(0xFFDC2626),
    success = Color(0xFF16A34A),
    info = Color(0xFF2563EB),
    revealed = Color(0xFF9333EA),
    random = Color(0xFF7C3AED),
    spectrum = Brush.linearGradient(listOf(Color(0xFF0FA3CF), Color(0xFF4F7BFF), Color(0xFF7C5CFF))),
    isDark = false,
)

val LocalSpectraColors = staticCompositionLocalOf { DarkExtras }

object Spectra {
    val colors: SpectraColors
        @Composable get() = LocalSpectraColors.current
}

private val SpectraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private fun spectraTypography(): Typography {
    val base = Typography()
    return base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
    )
}

/** Digits that line up in columns. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun SpectraTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalSpectraColors provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = spectraTypography(),
            shapes = SpectraShapes,
            content = content,
        )
    }
}
