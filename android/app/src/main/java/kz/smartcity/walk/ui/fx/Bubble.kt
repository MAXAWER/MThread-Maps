package kz.smartcity.walk.ui.fx

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Слои отдалённости от экрана (уровни высоты Material 3): чем ближе слой, тем больше тень. */
enum class Depth(val elevation: Dp) { FLOOR(0.dp), LOW(1.dp), FLOAT(3.dp), SHEET(6.dp), MODAL(8.dp) }

object BubbleShapes {
    val XS = RoundedCornerShape(16.dp)
    val S = RoundedCornerShape(22.dp)
    val M = RoundedCornerShape(30.dp)
    val L = RoundedCornerShape(36.dp)
    val XL = RoundedCornerShape(44.dp)
    val Pill = RoundedCornerShape(percent = 50)
}

@Composable
fun isLightTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f

/** Заливки пузырей — плоские насыщенные цвета, без градиентов. */
object Fills {
    @Composable
    fun primary(): Brush = SolidColor(MaterialTheme.colorScheme.primary)

    @Composable
    fun accent(): Brush = SolidColor(MaterialTheme.colorScheme.secondary)

    @Composable
    fun tertiary(): Brush = SolidColor(MaterialTheme.colorScheme.tertiary)

    /** Карточки и шторки — контейнер поверхности Material 3: отличается от фона в обеих темах. */
    @Composable
    fun surface(): Brush = SolidColor(MaterialTheme.colorScheme.surfaceContainerLow)

    /** Поверхность над картой: высокий контейнер, при alpha < 1 — полупрозрачный. */
    @Composable
    fun glass(alpha: Float = 0.92f): Brush = SolidColor(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha))

    /** Светлая плашка цвета: фон для пилюль и значков. */
    @Composable
    fun tint(color: Color): Brush = SolidColor(color.copy(alpha = if (isLightTheme()) 0.14f else 0.24f))
}

/**
 * Пузырьковая поверхность: сильное скругление, плоская заливка и тень по уровню глубины —
 * чем ближе слой к пользователю, тем больше и мягче тень.
 */
fun Modifier.bubble(
    shape: Shape,
    fill: Brush,
    depth: Depth = Depth.FLOAT,
    glow: Color = Color(0xFF101828),
): Modifier = this
    .then(if (depth.elevation > 0.dp) Modifier.shadow(depth.elevation, shape, clip = false,
        ambientColor = glow.copy(alpha = 0.20f), spotColor = glow.copy(alpha = 0.30f)) else Modifier)
    .clip(shape)
    .background(fill)

/**
 * Физика нажатия: пружинное «сжатие» пузыря и наклон к точке касания (3D), на отпускании —
 * упругий отскок с перелётом.
 */
@Composable
fun Modifier.pressPhysics(interaction: MutableInteractionSource, tiltDeg: Float = 0f, squash: Float = 0.93f): Modifier {
    val scale = remember { Animatable(1f) }
    val rx = remember { Animatable(0f) }
    val ry = remember { Animatable(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> {
                    launch { scale.animateTo(squash, spring(dampingRatio = 0.6f, stiffness = 900f)) }
                    if (tiltDeg > 0f && size.width > 0 && size.height > 0) {
                        val nx = (i.pressPosition.x / size.width - 0.5f) * 2f
                        val ny = (i.pressPosition.y / size.height - 0.5f) * 2f
                        launch { rx.animateTo(-ny * tiltDeg, spring(0.7f, 600f)) }
                        launch { ry.animateTo(nx * tiltDeg, spring(0.7f, 600f)) }
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    launch { scale.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 280f)) }
                    launch { rx.animateTo(0f, spring(0.38f, 220f)) }
                    launch { ry.animateTo(0f, spring(0.38f, 220f)) }
                }
            }
        }
    }
    return this
        .onSizeChanged { size = it }
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            rotationX = rx.value
            rotationY = ry.value
            cameraDistance = 14f * density
        }
}

@Composable
private fun glowFor(color: Color) = if (isLightTheme()) color else Color.Black

/** Кнопка-пузырь в форме таблетки. */
@Composable
fun BubbleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Brush = Fills.primary(),
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    glow: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    depth: Depth = Depth.FLOAT,
    content: @Composable RowScope.() -> Unit,
) {
    val haptics = LocalHaptics.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .pressPhysics(interaction)
            .alpha(if (enabled) 1f else 0.45f)
            .bubble(BubbleShapes.Pill, fill, depth, glow = glowFor(glow))
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
                haptics?.tap()
                onClick()
            }
            .heightIn(min = 52.dp)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)) { content() }
        }
    }
}

/** Вторичная кнопка — тональная (Material 3 Filled tonal): контейнер primaryContainer. */
@Composable
fun BubbleGhostButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    BubbleButton(onClick, modifier, fill = SolidColor(MaterialTheme.colorScheme.primaryContainer),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer, glow = MaterialTheme.colorScheme.primary,
        enabled = enabled, depth = Depth.FLOOR, content = content)

/** Круглая кнопка-пузырь с иконкой. */
@Composable
fun BubbleIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    fill: Brush = Fills.surface(),
    tint: Color = MaterialTheme.colorScheme.onSurface,
    glow: Color = MaterialTheme.colorScheme.primary,
    depth: Depth = Depth.FLOAT,
    onPress: (() -> Unit)? = null,
) {
    val haptics = LocalHaptics.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .pressPhysics(interaction, squash = 0.88f)
            .bubble(CircleShape, fill, depth, glow = glowFor(glow))
            .clickable(interaction, indication = null, role = Role.Button) {
                onPress?.invoke() ?: haptics?.tap()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

/** Чип-пузырь: выбранный — залит цветом, обычный — белый; есть «крестик» для снятия. */
@Composable
fun BubbleChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leading: ImageVector? = null,
    trailing: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = null,
) {
    val haptics = LocalHaptics.current
    val interaction = remember { MutableInteractionSource() }
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .pressPhysics(interaction, squash = 0.9f)
            .bubble(BubbleShapes.Pill,
                if (selected) SolidColor(accent) else Fills.surface(),
                Depth.FLOOR, glow = glowFor(accent))
            .then(if (selected) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, BubbleShapes.Pill))
            .clickable(interaction, indication = null, role = Role.Button) {
                haptics?.tick(0.45f)
                onClick()
            }
            .heightIn(min = 40.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Icon(trailing, contentDescription = contentDescription, tint = fg.copy(alpha = 0.75f), modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Карточка: поверхность Material 3 со своим цветом текста ([contentColor]) — текст без явного цвета
 * берёт «on»-цвет карточки, а не чёрный по умолчанию, поэтому читается и в тёмной теме.
 */
@Composable
fun BubbleCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    fill: Brush = Fills.surface(),
    shape: Shape = BubbleShapes.L,
    depth: Depth = Depth.LOW,
    glow: Color = MaterialTheme.colorScheme.primary,
    tiltDeg: Float = 2f,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val haptics = LocalHaptics.current
    val interaction = remember { MutableInteractionSource() }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier = modifier
                .then(if (onClick != null) Modifier.pressPhysics(interaction, tiltDeg = tiltDeg, squash = 0.98f) else Modifier)
                .bubble(shape, fill, depth, glow = glowFor(glow))
                .then(if (onClick != null) Modifier.clickable(interaction, indication = null) { haptics?.tap(); onClick() } else Modifier),
            content = content,
        )
    }
}

/** Появление элемента списка: короткое проявление со сдвигом снизу, без перелёта — стабильно и плавно. */
@Composable
fun PopIn(index: Int, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) {
        delay(index * 40L)
        state.targetState = true
    }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(220)) + slideInVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) { it / 6 },
    ) { content() }
}

/** Круглый значок-пузырь с номером или иконкой. */
@Composable
fun BubbleBadge(text: String, modifier: Modifier = Modifier, fill: Brush = Fills.accent(), size: Dp = 30.dp,
                color: Color = MaterialTheme.colorScheme.onSecondary) {
    Box(modifier.size(size).bubble(CircleShape, fill, Depth.LOW, glow = MaterialTheme.colorScheme.secondary),
        contentAlignment = Alignment.Center) {
        Text(text, color = color, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge)
    }
}
