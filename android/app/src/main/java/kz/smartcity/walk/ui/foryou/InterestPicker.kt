package kz.smartcity.walk.ui.foryou

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kz.smartcity.walk.data.Categories
import kz.smartcity.walk.ui.components.categoryIcon
import kz.smartcity.walk.ui.components.groupColor
import kz.smartcity.walk.ui.fx.LocalHaptics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Пузырёк на полотне: категория и его место в плитке (с неровным сдвигом, чтобы не было сетки). */
private class Seed(val id: String, val name: String, val group: String, val x: Float, val y: Float, val phase: Float, val wobble: Float)

/**
 * Полотно интересов: бесконечное во все стороны (плитка категорий повторяется), тянется и бросается с инерцией.
 * Пузырьки у центра экрана крупнее, к краям мельче — по неровной кривой; каждый слегка «дышит» со своей фазой,
 * поэтому ничего не стоит по сетке. Тап выбирает или снимает выбор; нужно не меньше [minCount].
 */
@Composable
fun InterestPicker(initial: Set<String>, minCount: Int, bottomSpace: Dp, onDone: (Set<String>) -> Unit, onClose: () -> Unit) {
    val haptics = LocalHaptics.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val cs = MaterialTheme.colorScheme
    BackHandler(onBack = onClose)

    val base = with(density) { 112.dp.toPx() }
    val ids = remember { Categories.all.values.filter { it.id !in Categories.notForDeck } }
    // Плитка: шестиугольная укладка 7 колонок со случайным сдвигом каждого пузырька (детерминированно).
    val cols = 7
    val stepX = base * 1.02f
    val stepY = base * 0.9f
    val rows = (ids.size + cols - 1) / cols
    val tileW = cols * stepX
    val tileH = rows * stepY
    val seeds = remember(base) {
        val rnd = Random(17)
        ids.mapIndexed { i, c ->
            val r = i / cols
            val col = i % cols
            Seed(c.id, c.nameRu, c.group,
                x = col * stepX + (if (r % 2 == 1) stepX / 2 else 0f) + (rnd.nextFloat() - 0.5f) * base * 0.14f,
                y = r * stepY + (rnd.nextFloat() - 0.5f) * base * 0.12f,
                phase = rnd.nextFloat() * 2f * PI.toFloat(), wobble = 0.6f + rnd.nextFloat() * 0.8f)
        }
    }
    val painters = ids.associate { it.id to rememberVectorPainter(categoryIcon(it.id)) }
    val selected = remember { mutableStateMapOf<String, Long>().apply { initial.forEach { put(it, 0L) } } }
    val pan = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - start) / 1_000_000_000f }
    }
    val labels = remember(cs.onSurface) {
        val style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = 15.sp)
        seeds.associate { s ->
            s.id to measurer.measure(s.name, style, overflow = TextOverflow.Ellipsis, maxLines = 3,
                constraints = Constraints(maxWidth = (base * 0.78f).toInt()))
        }
    }

    /** Экранная геометрия пузырька: с повтором плитки, «рыбьим глазом» и дыханием. */
    fun layout(s: Seed, w: Float, h: Float, tx: Float, ty: Float, onEach: (Float, Float, Float) -> Unit) {
        val cx = w / 2
        val cy = h / 2
        // Ближайшие повторы плитки вокруг видимой области.
        val ox = s.x + pan.value.x - cx
        val oy = s.y + pan.value.y - cy
        val kx0 = floor((-w / 2 - base - ox) / tileW).toInt()
        val ky0 = floor((-h / 2 - base - oy) / tileH).toInt()
        for (kx in kx0..kx0 + (w / tileW).toInt() + 2) for (ky in ky0..ky0 + (h / tileH).toInt() + 2) {
            val px = ox + kx * tileW + sin(time * 0.7f * s.wobble + s.phase) * base * 0.035f
            val py = oy + ky * tileH + cos(time * 0.55f * s.wobble + s.phase) * base * 0.035f
            val radius = hypot(w, h) * 0.42f
            val dist = hypot(px, py)
            val x = dist / radius
            if (x > FISHEYE_MAX) continue
            // Радиальный «рыбий глаз»: расстояние от центра — интеграл размера пузырька, поэтому крупные
            // в середине раздвигаются, мелкие у краёв сжимаются, и пузырьки не наползают друг на друга.
            val f = if (dist > 1f) fisheyeIntegral(x) * radius * SPREAD / dist else bubbleScale(0f) * SPREAD
            onEach(cx + px * f + tx, cy + py * f + ty, bubbleScale(x))
        }
    }

    Box(Modifier.fillMaxSize().background(cs.surface)) {
        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = "Полотно интересов: выбрано ${selected.size}" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        scope.launch { pan.stop() }
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var moved = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            val delta = ch.positionChange()
                            moved += delta.getDistance()
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            if (moved > viewConfiguration.touchSlop) {
                                ch.consume()
                                scope.launch { pan.snapTo(pan.value + delta) }
                            }
                        }
                        if (moved <= viewConfiguration.touchSlop) {
                            // Тап: ближайший пузырёк под пальцем.
                            var best: Seed? = null
                            var bestD = Float.MAX_VALUE
                            seeds.forEach { s ->
                                layout(s, size.width.toFloat(), size.height.toFloat(), 0f, 0f) { x, y, sc ->
                                    val dd = hypot(x - down.position.x, y - down.position.y)
                                    if (dd < base / 2 * sc && dd < bestD) { bestD = dd; best = s }
                                }
                            }
                            best?.let { s ->
                                if (selected.containsKey(s.id)) selected.remove(s.id) else selected[s.id] = System.nanoTime()
                                haptics?.tick(if (selected.containsKey(s.id)) 0.7f else 0.4f)
                            }
                        } else {
                            val v = tracker.calculateVelocity()
                            scope.launch { pan.animateDecay(Offset(v.x, v.y), exponentialDecay(frictionMultiplier = 1.1f)) }
                        }
                    }
                },
        ) {
            val now = System.nanoTime()
            seeds.forEach { s ->
                val selAt = selected[s.id]
                // Выбор «вспухает» пружинисто: 0 → 1 с перелётом за ~0,4 с.
                val sel = when {
                    selAt == null -> 0f
                    selAt == 0L -> 1f
                    else -> {
                        val t = ((now - selAt) / 1e9f / 0.42f).coerceIn(0f, 1f)
                        1f + sin(t * PI.toFloat()) * 0.18f * (1f - t) - (1f - t).pow(3)
                    }
                }.coerceAtLeast(0f)
                val gc = groupColor(s.group)
                layout(s, size.width, size.height, 0f, 0f) { x, y, sc ->
                    val r = base / 2 * sc * (1f + 0.08f * sel)
                    val fill = if (selAt != null) lerp(lerp(cs.surfaceContainerHigh, gc, 0.25f), cs.primary, sel.coerceAtMost(1f))
                    else lerp(cs.surfaceContainerHigh, gc, 0.16f)
                    drawCircle(Brush.radialGradient(listOf(fill, lerp(fill, Color.Black, 0.08f)), center = Offset(x - r * 0.3f, y - r * 0.35f),
                        radius = r * 1.6f), r, Offset(x, y))
                    drawCircle(lerp(gc, cs.primary, sel.coerceAtMost(1f)).copy(alpha = 0.55f), r, Offset(x, y), style = Stroke(1.5.dp.toPx()))
                    if (sc < 0.42f) return@layout
                    val textColor = if (selAt != null && sel > 0.5f) cs.onPrimary else cs.onSurface
                    val iconColor = if (selAt != null && sel > 0.5f) cs.onPrimary else gc
                    val label = labels.getValue(s.id)
                    val icon = base * 0.22f
                    val blockH = icon + 4.dp.toPx() + label.size.height
                    translate(x, y) {
                        scale(sc, sc, pivot = Offset.Zero) {
                            translate(-icon / 2, -blockH / 2) {
                                with(painters.getValue(s.id)) { draw(Size(icon, icon), colorFilter = ColorFilter.tint(iconColor)) }
                            }
                            drawText(label, color = textColor,
                                topLeft = Offset(-label.size.width / 2f, -blockH / 2 + icon + 4.dp.toPx()))
                        }
                    }
                }
            }
        }

        // Шапка и кнопка «Готово» — поверх полотна.
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(0.7f to cs.surface, 1f to cs.surface.copy(alpha = 0f)))
            .statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Что вам интересно?", style = MaterialTheme.typography.headlineSmall)
                    Text("Тяните полотно в любую сторону и выбирайте пузырьки", style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Закрыть") }
            }
        }
        val count = selected.size
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(0f to cs.surface.copy(alpha = 0f), 0.15f to cs.surface))
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = bottomSpace + 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (count < minCount) "Выбрано $count из $minCount" else "Выбрано $count — можно ещё",
                style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Spacer(Modifier.padding(top = 8.dp))
            Button(onClick = { haptics?.threshold(); onDone(selected.keys.toSet()) }, enabled = count >= minCount,
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp), modifier = Modifier.widthIn(min = 220.dp)) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(if (count >= minCount) "Построить подборку" else "Нужно ещё ${minCount - count}",
                    style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private const val FISHEYE_MAX = 1.6f
private const val SPREAD = 1.14f

/** Неравномерный рост к центру: крупнее всего в середине, быстро мельчает к краям. */
private fun bubbleScale(x: Float): Float = 0.34f + 0.74f * (1f - x.coerceIn(0f, 1.2f) / 1.2f).pow(1.6f)

private val FISHEYE_TABLE: FloatArray = run {
    val n = 128
    val out = FloatArray(n + 1)
    val dx = FISHEYE_MAX / n
    for (i in 1..n) out[i] = out[i - 1] + (bubbleScale((i - 0.5f) * dx)) * dx
    out
}

/** ∫₀ˣ bubbleScale — по таблице с линейной интерполяцией. */
private fun fisheyeIntegral(x: Float): Float {
    val n = FISHEYE_TABLE.size - 1
    val p = (x / FISHEYE_MAX * n).coerceIn(0f, n.toFloat())
    val i = p.toInt().coerceAtMost(n - 1)
    return FISHEYE_TABLE[i] + (FISHEYE_TABLE[i + 1] - FISHEYE_TABLE[i]) * (p - i)
}
