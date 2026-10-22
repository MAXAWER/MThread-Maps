package kz.smartcity.walk.ui.foryou

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kz.smartcity.walk.data.repo.DeckItem
import kz.smartcity.walk.ui.components.CategoryBadge
import kz.smartcity.walk.ui.components.OpenStateText
import kz.smartcity.walk.ui.components.RatingText
import kz.smartcity.walk.ui.components.SourceBadge
import kz.smartcity.walk.ui.components.categoryIcon
import kz.smartcity.walk.ui.components.groupColor
import kz.smartcity.walk.ui.components.transportIcon
import kz.smartcity.walk.ui.components.transportLabel
import kz.smartcity.walk.ui.fx.BubbleChip
import kz.smartcity.walk.ui.fx.BubbleGhostButton
import kz.smartcity.walk.ui.fx.BubbleIconButton
import kz.smartcity.walk.ui.fx.BubbleShapes
import kz.smartcity.walk.ui.fx.Depth
import kz.smartcity.walk.ui.fx.Fills
import kz.smartcity.walk.ui.fx.LocalHaptics
import kz.smartcity.walk.ui.fx.bubble
import kz.smartcity.walk.ui.fx.rememberFrictionFling
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/** Движение верхней карты: общее для жестов и кнопок под колодой. */
private class DeckMotion {
    val x = Animatable(0f)
    val y = Animatable(0f)
}

@Composable
fun ForYouScreen(vm: ForYouViewModel, modifier: Modifier = Modifier, bottomSpace: Dp = 0.dp) {
    val cards by vm.cards.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val lastLiked by vm.lastLiked.collectAsStateWithLifecycle()
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val progress = remember { mutableFloatStateOf(0f) } // насколько верхняя карта ушла к порогу: 0…1
    val top = cards.firstOrNull()
    val motion = remember(top?.card?.poiId) { DeckMotion() }

    Column(
        modifier.fillMaxSize()
            // Плотная подложка: сквозь неё едва видна размытая карта на дальнем слое.
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomSpace),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Для вас", style = MaterialTheme.typography.headlineLarge)
                Text("Подборка рядом: по портрету, времени и погоде", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BubbleIconButton(onClick = { vm.refresh(force = false) }, icon = Icons.Filled.Refresh, contentDescription = "Обновить колоду",
                size = 50.dp, tint = MaterialTheme.colorScheme.primary)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(14.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            if (cards.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(if (busy) "Собираем колоду…" else "Колода пуста", style = MaterialTheme.typography.titleLarge)
                    Text("Карточки подбираются в фоне, вкладка открывается сразу.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BubbleGhostButton(onClick = { vm.refresh(force = false) }, enabled = !busy) { Text("Собрать сейчас") }
                }
            } else {
                // Три верхние карты стопкой: дальние — меньше, ниже, размытее и темнее.
                val visible = cards.take(3)
                visible.reversed().forEachIndexed { i, item ->
                    val depth = visible.lastIndex - i
                    key(item.card.poiId) {
                        DeckCard(
                            item = item, depth = depth, motion = if (depth == 0) motion else null, progress = progress,
                            widthPx = widthPx, modifier = Modifier.align(Alignment.TopCenter),
                            onSwiped = { like -> progress.floatValue = 0f; vm.swipe(item, like) },
                            onDetails = { vm.openDetails(item) },
                        )
                    }
                }
            }
        }
        if (top != null) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                BubbleIconButton(
                    onClick = {
                        scope.launch {
                            motion.x.animateTo(-1800f, spring(dampingRatio = 0.9f, stiffness = 140f))
                            progress.floatValue = 0f
                            vm.swipe(top, false)
                        }
                    },
                    onPress = { haptics?.dislike() },
                    icon = Icons.Filled.Close, contentDescription = "Не то", size = 66.dp, tint = MaterialTheme.colorScheme.error,
                    glow = MaterialTheme.colorScheme.error,
                )
                BubbleIconButton(onClick = { vm.openDetails(top) }, icon = Icons.Filled.Info, contentDescription = "Карточка места",
                    size = 54.dp, tint = MaterialTheme.colorScheme.primary)
                BubbleIconButton(
                    onClick = {
                        scope.launch {
                            motion.x.animateTo(1800f, spring(dampingRatio = 0.9f, stiffness = 140f))
                            progress.floatValue = 0f
                            vm.swipe(top, true)
                        }
                    },
                    onPress = { haptics?.like() },
                    icon = Icons.Filled.Favorite, contentDescription = "Хочу", size = 66.dp, fill = Fills.accent(),
                    tint = MaterialTheme.colorScheme.onSecondary, glow = MaterialTheme.colorScheme.secondary, depth = Depth.SHEET,
                )
            }
        }
        AnimatedVisibility(visible = lastLiked != null, enter = slideInVertically(spring(0.6f)) { it } + fadeIn(), exit = fadeOut()) {
            lastLiked?.let { liked ->
                Row(Modifier.fillMaxWidth().bubble(BubbleShapes.Pill, Fills.accent(), Depth.FLOAT, glow = MaterialTheme.colorScheme.secondary)
                    .padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Сохранено: ${liked.card.name}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSecondary, fontWeight = FontWeight.SemiBold)
                    androidx.compose.material3.TextButton(onClick = { haptics?.tap(); vm.routeTo(liked) }) {
                        Text("Маршрут", color = MaterialTheme.colorScheme.onSecondary, fontWeight = FontWeight.Bold)
                    }
                    BubbleIconButton(onClick = vm::clearLiked, icon = Icons.Filled.Close, contentDescription = "Скрыть", size = 44.dp,
                        depth = Depth.FLOOR, fill = Fills.glass(0.0f), tint = MaterialTheme.colorScheme.onSecondary)
                }
            }
        }
        if (saved.isNotEmpty()) {
            Text("Хочу сходить", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp),
                flingBehavior = rememberFrictionFling(1.2f)) {
                items(saved, key = { it.poiId }) { p ->
                    BubbleChip(p.name, onClick = { vm.routeToSaved(p) }, leading = categoryIcon(p.category))
                }
            }
        }
    }
}

@Composable
private fun DeckCard(
    item: DeckItem,
    depth: Int,
    motion: DeckMotion?,
    progress: MutableFloatState,
    widthPx: Float,
    onSwiped: (Boolean) -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = widthPx * 0.35f
    val interactive = motion != null
    if (motion != null) {
        LaunchedEffect(motion) {
            snapshotFlow { motion.x.value }.collect { progress.floatValue = (abs(it) / threshold).coerceIn(0f, 1f) }
        }
    }
    // Глубина: дальние карты «подъезжают» к экрану, пока верхнюю тянут к порогу.
    val d = if (interactive) 0f else (depth - progress.floatValue).coerceAtLeast(0f)
    val gestures = if (motion == null) Modifier else Modifier.pointerInput(item.card.poiId) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            scope.launch { motion.x.stop(); motion.y.stop() }
            var dragging = false
            var travel = 0f
            var past = false
            var total = 0f
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                total += abs(delta.x) + abs(delta.y)
                if (!dragging && total > viewConfiguration.touchSlop) dragging = true
                if (!dragging) continue
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                scope.launch {
                    motion.x.snapTo(motion.x.value + delta.x)
                    motion.y.snapTo(motion.y.value + delta.y)
                }
                // «Фактура»: импульс каждые ~26 px пути, сила — от скорости.
                travel += hypot(delta.x, delta.y)
                if (travel > 26f) {
                    travel = 0f
                    val v = tracker.calculateVelocity()
                    haptics?.texture((hypot(v.x, v.y) / 3500f).coerceIn(0f, 1f))
                }
                val nowPast = abs(motion.x.value + delta.x) > threshold
                if (nowPast != past) {
                    past = nowPast
                    if (nowPast) haptics?.threshold()
                }
            }
            if (!dragging) {
                onDetails()
                return@awaitEachGesture
            }
            val v = tracker.calculateVelocity()
            scope.launch { release(motion, v, threshold, widthPx, haptics, onSwiped, onDetails) }
        }
    }
    val groupTint = groupColor(item.card.group)
    // Карта занимает верхние 92 % места: снизу остаётся полоса, где выглядывают дальние карты стопки.
    Box(
        modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .graphicsLayer {
                val m = motion
                translationX = m?.x?.value ?: 0f
                translationY = (m?.y?.value ?: 0f) + d * 30.dp.toPx()
                rotationZ = if (m != null) 15f * m.x.value / widthPx else 0f // поворот 15° × смещение / ширина
                rotationY = if (m != null) 6f * m.x.value / widthPx else 0f
                cameraDistance = 16f * density.density
                val sc = 1f - d * 0.05f
                scaleX = sc
                scaleY = sc
            }
            .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && d > 0.05f) Modifier.blur((d * 2.5f).dp) else Modifier)
            .then(gestures)
            .semantics {
                contentDescription = "${item.card.name}, ${item.card.categoryName}"
                onClick(label = "Карточка места") { onDetails(); true }
                customActions = listOf(
                    CustomAccessibilityAction("Хочу") { onSwiped(true); true },
                    CustomAccessibilityAction("Не то") { onSwiped(false); true },
                )
            }
            .bubble(BubbleShapes.XL, Fills.surface(), if (interactive) Depth.MODAL else Depth.LOW, glow = groupTint),
    ) {
        DeckCardContent(item, likeHint = (motion?.x?.value ?: 0f) / threshold)
        if (d > 0.02f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.10f * d)))
    }
}

/** Трение улетающей карты: меньше — дальше катится. */
private const val FLY_FRICTION = 0.5f

/** Отпускание: бросок продолжается по инерции с трением; не хватило — пружина возвращает с исходной скоростью. */
private suspend fun release(
    motion: DeckMotion, v: Velocity, threshold: Float, widthPx: Float,
    haptics: kz.smartcity.walk.ui.fx.Haptics?, onSwiped: (Boolean) -> Unit, onDetails: () -> Unit,
) = coroutineScope {
    val projectedX = exponentialDecay<Float>(frictionMultiplier = 1.2f).calculateTargetValue(motion.x.value, v.x)
    when {
        abs(projectedX) > threshold -> {
            val like = projectedX > 0
            if (like) haptics?.like() else haptics?.dislike()
            val dir = sign(projectedX)
            // Улёт по инерции с трением до границы за краем экрана: там анимация и заканчивается, без долгого
            // «хвоста» затухания. Скорость — не меньше той, что уносит карту за границу (путь затухания = v / 4,2f).
            val edge = widthPx * 1.4f
            val minSpeed = (edge - abs(motion.x.value)) * 4.2f * FLY_FRICTION * 1.5f
            motion.x.updateBounds(-edge, edge)
            launch { motion.y.animateDecay(v.y, exponentialDecay(1.2f)) }
            motion.x.animateDecay(dir * maxOf(abs(v.x), minSpeed), exponentialDecay(FLY_FRICTION))
            onSwiped(like)
        }
        motion.y.value < -threshold * 0.6f && v.y < -600f -> {
            haptics?.tap()
            onDetails()
            launch { motion.x.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 260f), initialVelocity = v.x) }
            motion.y.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 260f), initialVelocity = v.y)
        }
        else -> {
            launch { motion.x.animateTo(0f, spring(dampingRatio = 0.48f, stiffness = 260f), initialVelocity = v.x) }
            motion.y.animateTo(0f, spring(dampingRatio = 0.48f, stiffness = 260f), initialVelocity = v.y)
        }
    }
}

@Composable
private fun DeckCardContent(item: DeckItem, likeHint: Float) {
    val c = item.card
    val color = groupColor(c.group)
    Column(Modifier.fillMaxSize()) {
        // Иллюстрация категории: фото мест Places API не подтверждены, поэтому векторные «пузыри».
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(210.dp).align(Alignment.TopStart).padding(0.dp)
                .graphicsLayer { translationX = -60f; translationY = -40f }
                .bubble(BubbleShapes.Pill, Fills.glass(0.18f), Depth.FLOOR))
            Box(Modifier.size(90.dp).align(Alignment.BottomEnd)
                .graphicsLayer { translationX = -30f; translationY = -50f }
                .bubble(BubbleShapes.Pill, Fills.glass(0.14f), Depth.FLOOR))
            CategoryBadge(c.category, 132.dp)
            if (likeHint > 0.3f) Stamp("ХОЧУ", MaterialTheme.colorScheme.tertiary, Modifier.align(Alignment.TopStart), likeHint)
            if (likeHint < -0.3f) Stamp("НЕ ТО", MaterialTheme.colorScheme.error, Modifier.align(Alignment.TopEnd), -likeHint)
            c.contextChip?.let {
                Text(it, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)
                        .bubble(BubbleShapes.Pill, Fills.glass(0.92f), Depth.LOW, glow = color)
                        .padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(c.categoryName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                c.etaMin?.let {
                    Row(Modifier.bubble(BubbleShapes.Pill, Fills.tint(MaterialTheme.colorScheme.primary), Depth.FLOOR)
                        .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(transportIcon(c.transport), contentDescription = transportLabel(c.transport), modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text(" $it мин", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
                OpenStateText(c.openState, c.openUntil)
            }
            RatingText(c.rating, c.reviews)
            c.why?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            SourceBadge("poi_data", item.dataSource)
        }
    }
}

@Composable
private fun Stamp(text: String, color: Color, modifier: Modifier, strength: Float) {
    val a = strength.coerceIn(0f, 1f)
    Text(text, color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge,
        modifier = modifier.padding(18.dp)
            .graphicsLayer { scaleX = 0.8f + 0.25f * a; scaleY = 0.8f + 0.25f * a; alpha = a; rotationZ = if (text == "ХОЧУ") -12f else 12f }
            .bubble(BubbleShapes.Pill, SolidColor(color), Depth.FLOAT, glow = color)
            .padding(horizontal = 16.dp, vertical = 8.dp))
}
