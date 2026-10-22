package kz.smartcity.walk.ui.fx

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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

/** Слои отдалённости от экрана: чем ближе слой, тем больше и цветнее тень. */
enum class Depth(val elevation: Dp) { FLOOR(0.dp), LOW(6.dp), FLOAT(12.dp), SHEET(26.dp), MODAL(34.dp) }

object BubbleShapes {
    val XS = RoundedCornerShape(16.dp)
    val S = RoundedCornerShape(22.dp)
    val M = RoundedCornerShape(30.dp)
    val L = RoundedCornerShape(36.dp)
    val XL = RoundedCornerShape(44.dp)
    val Pill = RoundedCornerShape(percent = 50)
}

fun Color.lighter(f: Float) = lerp(this, Color.White, f)
fun Color.darker(f: Float) = lerp(this, Color.Black, f)

@Composable
fun isLightTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f

/** Заливки пузырей: диагональный градиент даёт объём, как у надутой оболочки. */
object Fills {
    @Composable
    fun primary(): Brush = MaterialTheme.colorScheme.primary.let {
        Brush.linearGradient(listOf(it.lighter(0.22f), it, it.darker(0.12f)), Offset.Zero, Offset(600f, 600f))
    }

    @Composable
    fun accent(): Brush = MaterialTheme.colorScheme.secondary.let {
        Brush.linearGradient(listOf(it.lighter(0.25f), it, it.darker(0.08f)), Offset.Zero, Offset(600f, 600f))
    }

    @Composable
    fun tertiary(): Brush = MaterialTheme.colorScheme.tertiary.let {
        Brush.linearGradient(listOf(it.lighter(0.2f), it, it.darker(0.1f)), Offset.Zero, Offset(600f, 600f))
    }

    @Composable
    fun surface(): Brush = MaterialTheme.colorScheme.surface.let {
        Brush.verticalGradient(listOf(it.lighter(if (isLightTheme()) 0.5f else 0.06f), it))
    }

    /** Полупрозрачное «стекло» над картой. */
    @Composable
    fun glass(alpha: Float = 0.82f): Brush = MaterialTheme.colorScheme.surface.let {
        Brush.verticalGradient(listOf(it.lighter(0.3f).copy(alpha = alpha), it.copy(alpha = alpha * 0.92f)))
    }

    @Composable
    fun tint(color: Color): Brush =
        Brush.linearGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.10f)), Offset.Zero, Offset(400f, 400f))
}

/**
 * Пузырьковая поверхность: цветная мягкая тень по уровню глубины, градиентная заливка,
 * блик в верхней половине и светлая кромка — объём без резких границ.
 */
fun Modifier.bubble(
    shape: Shape,
    fill: Brush,
    depth: Depth = Depth.FLOAT,
    glow: Color = Color(0xFF0E5E73),
    highlight: Float = 0.26f,
    rim: Float = 0.5f,
): Modifier = this
    .then(if (depth.elevation > 0.dp) Modifier.shadow(depth.elevation, shape, clip = false,
        ambientColor = glow.copy(alpha = 0.30f), spotColor = glow.copy(alpha = 0.42f)) else Modifier)
    .clip(shape)
    .background(fill)
    .drawBehind {
        if (highlight > 0f) {
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = highlight), 1f to Color.Transparent,
                startY = 0f, endY = size.height * 0.55f))
        }
    }
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = rim), Color.White.copy(alpha = 0.03f))), shape)

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

/** Вторичная кнопка: светлый пузырь. */
@Composable
fun BubbleGhostButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    BubbleButton(onClick, modifier, fill = Fills.surface(), contentColor = MaterialTheme.colorScheme.primary,
        glow = MaterialTheme.colorScheme.primary, enabled = enabled, depth = Depth.LOW, content = content)

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

/** Чип-пузырь: выбранный — градиентный, обычный — стеклянный; есть «крестик» для снятия. */
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
                if (selected) Brush.linearGradient(listOf(accent.lighter(0.2f), accent, accent.darker(0.1f))) else Fills.glass(0.9f),
                Depth.LOW, glow = glowFor(accent), highlight = if (selected) 0.3f else 0.18f)
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

/** Карточка-пузырь: наклон к пальцу и пружинный отскок. */
@Composable
fun BubbleCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    fill: Brush = Fills.surface(),
    shape: Shape = BubbleShapes.L,
    depth: Depth = Depth.LOW,
    glow: Color = MaterialTheme.colorScheme.primary,
    tiltDeg: Float = 3.5f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val haptics = LocalHaptics.current
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.pressPhysics(interaction, tiltDeg = tiltDeg, squash = 0.97f) else Modifier)
            .bubble(shape, fill, depth, glow = glowFor(glow))
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null) { haptics?.tap(); onClick() } else Modifier),
        content = content,
    )
}

/** Появление «лопающимся пузырём» с задержкой по порядку. */
@Composable
fun PopIn(index: Int, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) {
        delay(index * 70L)
        state.targetState = true
    }
    AnimatedVisibility(
        visibleState = state,
        enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.82f) + fadeIn(),
    ) { content() }
}

/** Круглый значок-пузырь с номером или иконкой. */
@Composable
fun BubbleBadge(text: String, modifier: Modifier = Modifier, fill: Brush = Fills.accent(), size: Dp = 30.dp,
                color: Color = MaterialTheme.colorScheme.onSecondary) {
    Box(modifier.size(size).bubble(CircleShape, fill, Depth.LOW, glow = MaterialTheme.colorScheme.secondary, highlight = 0.35f),
        contentAlignment = Alignment.Center) {
        Text(text, color = color, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge)
    }
}
