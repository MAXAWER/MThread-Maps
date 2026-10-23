package kz.smartcity.walk.ui.foryou

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.SegmentedButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
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

/**
 * «Для вас» открывается не сразу: после нескольких десятков поисков или после ручного выбора от 10 интересов
 * на полотне пузырьков. Выбор задаёт стартовые рекомендации, дальше их меняют поиски и свайпы карточек.
 */
@Composable
fun ForYouScreen(vm: ForYouViewModel, modifier: Modifier = Modifier, bottomSpace: Dp = 0.dp) {
    val gate by vm.gate.collectAsStateWithLifecycle()
    var picker by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.reloadGate() }
    Box(modifier.fillMaxSize()) {
        if (gate.unlocked) DeckScreen(vm, bottomSpace = bottomSpace, onTune = { picker = true })
        else LockedScreen(gate, bottomSpace = bottomSpace, onPick = { picker = true })
        AnimatedVisibility(picker, enter = fadeIn(tween(220)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 300f), initialScale = 0.9f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.94f)) {
            InterestPicker(
                initial = gate.interests,
                minCount = if (gate.searches >= ForYouViewModel.UNLOCK_SEARCHES) 1 else ForYouViewModel.MIN_INTERESTS,
                bottomSpace = bottomSpace,
                onDone = { vm.saveInterests(it); picker = false },
                onClose = { picker = false },
            )
        }
    }
}

/** Вкладка ещё закрыта: сколько поисков осталось и кнопка выбора интересов. */
@Composable
private fun LockedScreen(gate: ForYouGate, bottomSpace: Dp, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f)).statusBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = bottomSpace),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Для вас", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(132.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Подборка ещё учится", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Она откроется после ${ForYouViewModel.UNLOCK_SEARCHES} поисков — так ИИ поймёт, что вам нравится. " +
            "Или выберите от ${ForYouViewModel.MIN_INTERESTS} интересов сами, и рекомендации появятся сразу.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        Spacer(Modifier.height(20.dp))
        val p by animateFloatAsState(gate.searches / ForYouViewModel.UNLOCK_SEARCHES.toFloat(), spring(stiffness = 120f), label = "gate")
        LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().height(8.dp))
        Spacer(Modifier.height(8.dp))
        Text("Поисков: ${gate.searches} из ${ForYouViewModel.UNLOCK_SEARCHES}", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Button(onClick = onPick, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp)) {
            Icon(Icons.Filled.Tune, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Выбрать интересы", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.weight(1.3f))
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeckScreen(vm: ForYouViewModel, modifier: Modifier = Modifier, bottomSpace: Dp = 0.dp, onTune: () -> Unit) {
    val cards by vm.cards.collectAsStateWithLifecycle()
    val liked by vm.liked.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val last by vm.lastSwipe.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val progress = remember { mutableFloatStateOf(0f) } // насколько верхняя карта ушла к порогу: 0…1
    val top = cards.firstOrNull()
    val motion = remember(top?.card?.poiId) { DeckMotion() }
    var tab by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    var showFilters by rememberSaveable { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomSpace)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Для вас", style = MaterialTheme.typography.headlineLarge)
                    // Что учтено: погода сейчас, интересы и недавние поиски.
                    Text(weather?.let { "Сейчас $it · под погоду, ваши интересы и поиски" }
                        ?: "По вашим интересам, погоде и недавним поискам",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                BubbleIconButton(onClick = onTune, icon = Icons.Filled.Tune, contentDescription = "Интересы",
                    size = 48.dp, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                BubbleIconButton(onClick = { vm.refresh(force = true) }, icon = Icons.Filled.Refresh, contentDescription = "Обновить подборку",
                    size = 48.dp, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Подборка", "Понравившиеся · ${liked.size}").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = tab == i, onClick = { haptics?.tick(0.5f); tab = i },
                        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label, maxLines = 1) }
                }
            }
            if (busy) androidx.compose.material3.LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)) }
            if (tab == 1) {
                LikedList(liked, vm, bottomSpace = 0.dp, modifier = Modifier.weight(1f).fillMaxWidth())
                return@Column
            }
            QuickFilters(filters, onChange = { haptics?.tick(0.4f); vm.setFilters(it) }, onAll = { showFilters = true })
            Spacer(Modifier.height(8.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
                if (cards.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(if (busy) "Собираем подборку…" else "Подборка пуста", style = MaterialTheme.typography.titleLarge)
                        Text(if (filters.activeCount > 0) "Попробуйте ослабить фильтры" else "Карточки подбираются в фоне",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BubbleGhostButton(onClick = { vm.refresh(force = true) }, enabled = !busy) { Text("Собрать сейчас") }
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
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly,
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
                        icon = Icons.Filled.Close, contentDescription = "Не то", size = 64.dp, tint = MaterialTheme.colorScheme.error,
                        glow = MaterialTheme.colorScheme.error,
                    )
                    BubbleIconButton(onClick = { vm.openDetails(top) }, icon = Icons.Filled.Info, contentDescription = "Карточка места",
                        size = 52.dp, tint = MaterialTheme.colorScheme.primary)
                    BubbleIconButton(
                        onClick = {
                            scope.launch {
                                motion.x.animateTo(1800f, spring(dampingRatio = 0.9f, stiffness = 140f))
                                progress.floatValue = 0f
                                vm.swipe(top, true)
                            }
                        },
                        onPress = { haptics?.like() },
                        icon = Icons.Filled.Favorite, contentDescription = "Хочу", size = 64.dp, fill = Fills.accent(),
                        tint = MaterialTheme.colorScheme.onSecondary, glow = MaterialTheme.colorScheme.secondary, depth = Depth.SHEET,
                    )
                }
            }
        }
        SwipeBar(last, liked.size, onUndo = { haptics?.tap(); vm.undo() }, onOpenLiked = { vm.dismissSwipe(); tab = 1 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = bottomSpace + 8.dp))
    }
    if (showFilters) FiltersSheet(filters, onApply = { vm.setFilters(it); showFilters = false }, onDismiss = { showFilters = false })
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
        // Обложка: фото места (сайт, Википедия, OpenStreetMap), иначе карта вокруг него с меткой.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            CardCover(item, Modifier.fillMaxSize())
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
            c.why?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            c.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
