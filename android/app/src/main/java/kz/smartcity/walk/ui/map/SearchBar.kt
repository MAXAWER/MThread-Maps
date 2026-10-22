package kz.smartcity.walk.ui.map

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.delay
import kz.smartcity.walk.data.QuickPlace
import kz.smartcity.walk.ui.components.hintIcon
import kz.smartcity.walk.ui.components.quickIcon
import kz.smartcity.walk.ui.fx.BubbleChip
import kz.smartcity.walk.ui.fx.LocalHaptics


private val FAB = 56.dp
private val MAX_BAR = 600.dp

/** Пружина морфинга: мягкая, без отскока по краям — строка не «дрожит» при открытии клавиатуры. */
private val Morph = spring<Float>(dampingRatio = 0.86f, stiffness = 420f)

/**
 * Поиск: кружок с лупой, который по нажатию плавно «расплывается» в строку ввода ровно над клавиатурой.
 * Строка многострочная (с переносом) и ограничена по ширине; после отправки очищается и сворачивается обратно.
 *
 * [collapsedBottom] — где стоит кружок (над шторкой или панелью вкладок), [collapsedVisible] — виден ли он
 * (шторка раскрыта во весь экран — прячем).
 */
@OptIn(ExperimentalHazeMaterialsApi::class, ExperimentalLayoutApi::class)
@Composable
fun SearchComposer(s: MapUiState, vm: MapViewModel, hazeState: HazeState, collapsedBottom: () -> Dp, collapsedVisible: () -> Boolean,
                   quick: List<QuickPlace>, modifier: Modifier = Modifier) {
    val haptics = LocalHaptics.current
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    val p by animateFloatAsState(if (s.composing) 1f else 0f, Morph, label = "morph")
    val shown by animateFloatAsState(if (s.composing || collapsedVisible()) 1f else 0f, tween(180), label = "fab")
    // Низ строки = верх клавиатуры (или системной навигации), с зазором; insets ime анимируются вместе с клавиатурой.
    val imeBottom = with(density) { maxOf(WindowInsets.ime.getBottom(this), WindowInsets.navigationBars.getBottom(this)).toDp() }
    val submit = {
        val q = s.query
        if (q.isNotBlank()) { haptics?.tap(); focus.clearFocus(); keyboard?.hide(); vm.submit(q) }
    }

    BackHandler(enabled = s.composing) { focus.clearFocus(); vm.closeComposer() }
    LaunchedEffect(s.composing) {
        if (s.composing) { delay(16); runCatching { requester.requestFocus() }; keyboard?.show() } else focus.clearFocus()
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Затемнение карты под строкой ввода; тап по нему — свернуть.
        if (p > 0.01f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = p }.background(Color.Black.copy(alpha = 0.32f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { focus.clearFocus(); vm.closeComposer() })
        }
        val fullWidth = (maxWidth - 24.dp).coerceAtMost(MAX_BAR)
        val width = lerp(FAB, fullWidth, p)
        val endPad = lerp(16.dp, (maxWidth - fullWidth) / 2, p)
        val bottom = lerp(collapsedBottom(), imeBottom + 10.dp, p)

        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = endPad, bottom = bottom).width(width)
                .graphicsLayer { alpha = shown; scaleX = 0.6f + 0.4f * shown; scaleY = 0.6f + 0.4f * shown },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Над строкой: подсказки 2ГИС по вводу или быстрые запросы — выезжают снизу вместе со строкой.
            AnimatedVisibility(s.composing && p > 0.6f, enter = fadeIn(tween(160)) + expandVertically(expandFrom = Alignment.Bottom),
                exit = fadeOut(tween(100)) + shrinkVertically(shrinkTowards = Alignment.Bottom)) {
                if (s.suggestions.isNotEmpty()) {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            s.suggestions.forEach { sug ->
                                Row(Modifier.fillMaxWidth().clickable { focus.clearFocus(); vm.pickSuggestion(sug) }
                                    .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (sug.type == "rubric") Icons.Filled.Category else Icons.Filled.Place, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(sug.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        sug.subtitle?.takeIf { it.isNotBlank() }?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (s.query.isBlank()) {
                    // Быстрые маршруты — первыми, затем подсказки под этого человека (время, интересы, недавнее).
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        quick.forEach { p -> BubbleChip(p.name, onClick = { vm.routeToQuick(p) }, leading = quickIcon(p.icon)) }
                        s.hints.forEach { h -> BubbleChip(if (h.text.length > 40) h.text.take(39).trimEnd() + "…" else h.text, onClick = { vm.search(h.text) }, leading = hintIcon(h.icon)) }
                    }
                }
            }
            // Сама строка: кружок → капсула. Углы 28 dp: при высоте 56 это круг, при росте — скруглённая карточка.
            Box(
                Modifier.fillMaxWidth().heightIn(min = FAB).clip(RoundedCornerShape(28.dp))
                    .hazeEffect(hazeState, HazeMaterials.thick(
                        if (p > 0.5f) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer))
                    .then(if (s.composing) Modifier else Modifier.clickable { haptics?.tap(); vm.openComposer() })
                    .semantics { contentDescription = if (s.composing) "Строка запроса" else "Поиск" },
            ) {
                // Поле создаётся сразу при открытии (фокус и клавиатура — без ожидания анимации), проявляется по ходу морфинга.
                if (!s.composing && p < 0.5f) {
                    Icon(if (s.searching) Icons.Filled.AutoAwesome else Icons.Filled.Search, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.align(Alignment.Center).size(26.dp).alpha(1f - p * 2f))
                } else {
                    Row(Modifier.fillMaxWidth().alpha(((p - 0.4f) / 0.6f).coerceIn(0f, 1f)).padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                            if (s.query.isEmpty()) {
                                Text("Спросите ИИ: куда пойти, как добраться…", style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            BasicTextField(
                                value = s.query,
                                // Enter в многострочном поле — отправка, а не новая строка (перенос — только автоматический).
                                onValueChange = { v -> if ('\n' in v) { vm.onQueryChange(v.replace("\n", " ").trim()); submit() } else vm.onQueryChange(v) },
                                maxLines = 5,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { submit() }),
                                modifier = Modifier.fillMaxWidth().focusRequester(requester),
                            )
                        }
                        FilledIconButton(onClick = { submit() }, enabled = s.query.isNotBlank(),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                            modifier = Modifier.padding(start = 6.dp).size(44.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Искать")
                        }
                    }
                }
            }
        }
    }
}

/** Мысли агента для бегущей строки: его шаги и найденные места — по одной, без выдумок. */
private fun thoughtsOf(s: MapUiState): List<String> =
    s.progress + s.candidates.take(6).map { c -> "Смотрю «${c.name}»" + (c.distanceM?.let { " — ${it} м" } ?: "") }

/**
 * Капсула «ИИ работает»: запрос, бегущая градиентная обводка и строка мыслей под ним. Каждая новая мысль
 * выезжает с инерцией из-под строки (пружина), старая уезжает вверх. Если мыслей накопилось много,
 * они сменяются быстрее — видно, что нейросеть делает много и быстро.
 */
@OptIn(ExperimentalHazeMaterialsApi::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ThinkingCapsule(s: MapUiState, hazeState: HazeState, modifier: Modifier = Modifier) {
    val lines by rememberUpdatedState(thoughtsOf(s))
    var shown by remember { mutableStateOf("Читаю запрос…") }
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.lastQuery, s.searching) {
        if (!s.searching) return@LaunchedEffect
        var i = 0
        shown = "Читаю запрос…"; step = 0
        while (true) {
            val l = lines
            if (i < l.size) {
                shown = l[i]; i++; step = i
                delay(if (l.size - i > 2) 420 else 900)
            } else delay(120)
        }
    }
    val flow = rememberInfiniteTransition(label = "flow")
    val shift by flow.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "shift")
    val spin by flow.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "spin")
    val pulse by flow.animateFloat(0.55f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    val c1 = MaterialTheme.colorScheme.primary
    val c2 = MaterialTheme.colorScheme.tertiary
    val c3 = MaterialTheme.colorScheme.secondary
    Box(
        modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp))
            .hazeEffect(hazeState, HazeMaterials.regular(MaterialTheme.colorScheme.surfaceContainerHigh))
            .drawWithContent {
                drawContent()
                val w = size.width
                val brush = Brush.linearGradient(listOf(c1, c2, c3, c1), start = Offset(w * shift * 2 - w, 0f),
                    end = Offset(w * shift * 2, size.height), tileMode = TileMode.Mirror)
                val sw = 2.dp.toPx()
                drawRoundRect(brush, topLeft = Offset(sw / 2, sw / 2), size = size.copy(size.width - sw, size.height - sw),
                    cornerRadius = CornerRadius(26.dp.toPx() - sw / 2), style = Stroke(sw))
            }
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.LoadingIndicator(Modifier.size(28.dp), color = c1)
                Spacer(Modifier.width(10.dp))
                Text(s.lastQuery, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (step > 0) Text("$step", style = MaterialTheme.typography.labelMedium, color = c1,
                    modifier = Modifier.padding(start = 8.dp))
            }
            // Строка мыслей: новая выезжает снизу с пружиной, старая уходит вверх; лишнее обрезано рамкой строки.
            AnimatedContent(
                targetState = shown,
                transitionSpec = {
                    (slideInVertically(spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) { it } +
                        fadeIn(tween(220))) togetherWith
                        (slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { -it } + fadeOut(tween(140))) using
                        SizeTransform(clip = true)
                },
                label = "thought",
                modifier = Modifier.padding(start = 30.dp, top = 2.dp).clip(RoundedCornerShape(4.dp)),
            ) { text ->
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
