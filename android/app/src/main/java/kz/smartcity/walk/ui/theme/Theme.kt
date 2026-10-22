package kz.smartcity.walk.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Палитра: бирюза столичного неба, золото Байтерека, зелень степи. Контраст текста ≥ 4,5:1.
private val LightColors = lightColorScheme(
    primary = Color(0xFF0A7389),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6EEF6),
    onPrimaryContainer = Color(0xFF00313C),
    secondary = Color(0xFFF2B33D),
    onSecondary = Color(0xFF2B1D00),
    secondaryContainer = Color(0xFFFFE6AE),
    onSecondaryContainer = Color(0xFF2B1D00),
    tertiary = Color(0xFF23855A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC3EFD5),
    onTertiaryContainer = Color(0xFF00321C),
    background = Color(0xFFEAF2F5),
    onBackground = Color(0xFF111C20),
    surface = Color(0xFFF9FCFD),
    onSurface = Color(0xFF111C20),
    surfaceVariant = Color(0xFFDCE8EC),
    onSurfaceVariant = Color(0xFF3A4B51),
    outline = Color(0xFF6A7D83),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FD3E6),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF0A4C5B),
    onPrimaryContainer = Color(0xFFC6EEF6),
    secondary = Color(0xFFF6C35E),
    onSecondary = Color(0xFF2B1D00),
    secondaryContainer = Color(0xFF5A4300),
    onSecondaryContainer = Color(0xFFFFE6AE),
    tertiary = Color(0xFF7FD8A7),
    onTertiary = Color(0xFF00391F),
    tertiaryContainer = Color(0xFF115234),
    onTertiaryContainer = Color(0xFFC3EFD5),
    background = Color(0xFF0A1316),
    onBackground = Color(0xFFDDE8EB),
    surface = Color(0xFF142026),
    onSurface = Color(0xFFDDE8EB),
    surfaceVariant = Color(0xFF223238),
    onSurfaceVariant = Color(0xFFB8CACF),
    outline = Color(0xFF82969C),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD5),
)

// Сильные скругления для всех компонентов Material.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(20.dp),
    medium = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(36.dp),
    extraLarge = RoundedCornerShape(44.dp),
)

private val AppTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.4).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.2).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Bold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
    )
}

@Composable
fun WalkTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, shapes = AppShapes,
        content = content)
}
