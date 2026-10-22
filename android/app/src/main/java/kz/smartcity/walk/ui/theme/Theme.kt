package kz.smartcity.walk.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Палитра Material 3 от фирменного синего #1F6BFF: полный набор ролей, включая контейнеры поверхностей.
 * Текст всегда берёт «on»-цвет своей поверхности — контраст ≥ 4,5:1 в обеих темах.
 * Жёлтый (secondary) — акцент «Хочу» и номеров рекомендаций, зелёный (tertiary) — «открыто», подтверждения.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5FD0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDAE2FF),
    onPrimaryContainer = Color(0xFF001946),
    inversePrimary = Color(0xFFB1C5FF),
    secondary = Color(0xFFFFC400),
    onSecondary = Color(0xFF261A00),
    secondaryContainer = Color(0xFFFFEDB3),
    onSecondaryContainer = Color(0xFF3D2E00),
    tertiary = Color(0xFF00875F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB4F1D5),
    onTertiaryContainer = Color(0xFF002116),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceTint = Color(0xFF1F5FD0),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    surfaceBright = Color(0xFFF9F9FF),
    surfaceDim = Color(0xFFD9D9E0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB1C5FF),
    onPrimary = Color(0xFF002C70),
    primaryContainer = Color(0xFF1B43A0),
    onPrimaryContainer = Color(0xFFDAE2FF),
    inversePrimary = Color(0xFF1F5FD0),
    secondary = Color(0xFFFFD34D),
    onSecondary = Color(0xFF261A00),
    secondaryContainer = Color(0xFF5C4600),
    onSecondaryContainer = Color(0xFFFFE08A),
    tertiary = Color(0xFF5DDBA8),
    onTertiary = Color(0xFF00382A),
    tertiaryContainer = Color(0xFF005139),
    onTertiaryContainer = Color(0xFFB4F1D5),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceTint = Color(0xFFB1C5FF),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2F3036),
    surfaceBright = Color(0xFF37393E),
    surfaceDim = Color(0xFF111318),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
)

// Крупные скругления Material 3 Expressive.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val AppTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** [dynamicColor] — цвета из обоев (Material You, Android 12+); иначе фирменная палитра. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WalkTheme(dark: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    // Material 3 Expressive: выразительные пружины движения во всех компонентах (кнопки, шторки, индикаторы).
    MaterialExpressiveTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes,
        motionScheme = MotionScheme.expressive(), content = content)
}
