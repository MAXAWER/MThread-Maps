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

// Палитра: яркие плоские цвета без градиентов — насыщенный синий, солнечно-жёлтый, сочный зелёный
// на чистом светлом фоне. Контраст текста ≥ 4,5:1.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF00205C),
    secondary = Color(0xFFFFC400),
    onSecondary = Color(0xFF1F1600),
    secondaryContainer = Color(0xFFFFF1BF),
    onSecondaryContainer = Color(0xFF3D2E00),
    tertiary = Color(0xFF00B37E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCCF5E6),
    onTertiaryContainer = Color(0xFF00382A),
    background = Color(0xFFF2F4F8),
    onBackground = Color(0xFF101828),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101828),
    surfaceVariant = Color(0xFFE8ECF4),
    onSurfaceVariant = Color(0xFF475467),
    outline = Color(0xFF98A2B3),
    error = Color(0xFFF04438),
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF7A271A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6E9BFF),
    onPrimary = Color(0xFF00205C),
    primaryContainer = Color(0xFF1B3E8F),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFFFFD34D),
    onSecondary = Color(0xFF1F1600),
    secondaryContainer = Color(0xFF5C4600),
    onSecondaryContainer = Color(0xFFFFF1BF),
    tertiary = Color(0xFF3DDC97),
    onTertiary = Color(0xFF00382A),
    tertiaryContainer = Color(0xFF00573F),
    onTertiaryContainer = Color(0xFFCCF5E6),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF181C24),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF242A36),
    onSurfaceVariant = Color(0xFFB8C0D0),
    outline = Color(0xFF7A8499),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFEE4E2),
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
