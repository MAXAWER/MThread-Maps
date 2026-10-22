package kz.smartcity.walk.ui.map

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kz.smartcity.walk.BuildConfig
import kz.smartcity.walk.data.LatLng
import kz.smartcity.walk.map.MapEvent
import kz.smartcity.walk.map.WebViewMapProvider
import kz.smartcity.walk.nav.NavigationBus
import kz.smartcity.walk.ui.fx.BubbleChip
import kz.smartcity.walk.ui.fx.BubbleIconButton
import kz.smartcity.walk.ui.fx.BubbleShapes
import kz.smartcity.walk.ui.fx.Depth
import kz.smartcity.walk.ui.fx.Fills
import kz.smartcity.walk.ui.fx.LocalHaptics
import kz.smartcity.walk.ui.fx.SheetPhysics
import kz.smartcity.walk.ui.fx.bubble

@Suppress("DEPRECATION")
private val HINTS = listOf(
    "Кофе рядом" to Icons.Filled.LocalCafe,
    "Прогулка на час" to Icons.Filled.DirectionsWalk,
    "С ребёнком" to Icons.Filled.ChildCare,
    "Поесть недорого" to Icons.Filled.Restaurant,
)

/** Размытие карты по уровням глубины: шторка наполовину, шторка вверху, карта под другой вкладкой. */
private val BLUR_RADII = floatArrayOf(0f, 6f, 12f, 20f)

/** [active] = false — открыта другая вкладка: карта уходит на дальний слой под матовое стекло. */
@Composable
fun MapScreen(vm: MapViewModel, modifier: Modifier = Modifier, bottomSpace: Dp = 0.dp, active: Boolean = true) {
    val s by vm.state.collectAsStateWithLifecycle()
    val nav by NavigationBus.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHaptics.current
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val map = remember {
        WebViewMapProvider(context, BuildConfig.MAPGL_KEY, BuildConfig.MAPGL_STYLE_ID, BuildConfig.MAP_BASE_URL, dark,
            vm.state.value.myLocation ?: LatLng(51.128, 71.430)).also { it.load() }
    }
    DisposableEffect(Unit) { onDispose { map.destroy() } }
    val sheet = remember { SheetPhysics(scope, haptics) }

    var rootHeightPx by remember { mutableFloatStateOf(0f) }
    var panelTopPx by remember { mutableFloatStateOf(0f) }
    var centeredOnUser by remember { mutableStateOf(false) }
    fun bottomPaddingCss(): Int = if (rootHeightPx <= 0f || panelTopPx <= 0f) 360
    else ((rootHeightPx - panelTopPx) / density.density).toInt() + 24
    val topPaddingCss = 190

    // Якоря шторки: свернута (заголовок), наполовину, почти во весь экран.
    val sheetOpen = s.sheet != SheetMode.NONE
    LaunchedEffect(rootHeightPx, s.sheet) {
        if (rootHeightPx <= 0f || !sheetOpen) return@LaunchedEffect
        val reserve = with(density) { (bottomSpace + 84.dp + 150.dp).toPx() }
        val max = (rootHeightPx - reserve).coerceAtLeast(with(density) { 360.dp.toPx() })
        val peek = with(density) { 132.dp.toPx() }
        // Средний якорь вмещает заголовок и первую рекомендацию целиком, вместе с кнопкой «Маршрут».
        sheet.setAnchors(listOf(peek, max * 0.64f, max), initialIndex = 1)
        if (sheet.settled && sheet.height.value < max * 0.55f) sheet.animateTo(1)
    }

    LaunchedEffect(map) {
        map.events.collect { e ->
            when (e) {
                is MapEvent.MarkerClick -> {
                    haptics?.tap()
                    vm.onMarkerClick(e.id)
                }
                is MapEvent.LongPress -> if (NavigationBus.state.value == null) {
                    haptics?.threshold()
                    vm.onLongPress(e.point)
                }
                is MapEvent.Ready -> vm.state.value.myLocation?.let { map.setCenter(it, 14.0); centeredOnUser = true }
                else -> Unit
            }
        }
    }
    val markers = remember(s.answer, s.candidates, s.routeTarget, s.place, s.sheet) { vm.markers(s) }
    LaunchedEffect(markers, panelTopPx) {
        map.setMarkers(markers)
        if (markers.isNotEmpty() && nav == null) {
            map.fitBounds(markers.map { LatLng(it.lat, it.lon) } + listOfNotNull(s.myLocation), topPaddingCss, bottomPaddingCss())
        }
    }
    LaunchedEffect(s.answer) { if (s.answer != null && s.answer?.mode != "empty") haptics?.success() else if (s.answer != null) haptics?.warning() }
    val planGeometry = s.answer?.plan?.geometry
    LaunchedEffect(s.route, planGeometry, nav?.geometry, s.sheet) {
        val line = nav?.geometry
            ?: s.route?.geometry?.map { LatLng(it[1], it[0]) }
            ?: planGeometry?.takeIf { s.sheet == SheetMode.PLAN }?.map { LatLng(it[1], it[0]) }
        if (line != null && line.size > 1) {
            map.drawRoute(line)
            if (nav == null) map.fitBounds(line, topPaddingCss, bottomPaddingCss())
        } else {
            map.clear("route")
        }
    }
    LaunchedEffect(s.myLocation, nav?.position) {
        map.setUserLocation(nav?.position ?: s.myLocation)
        val p = nav?.position
        if (p != null) map.setCenter(p)
        else if (!centeredOnUser && s.myLocation != null && markers.isEmpty()) {
            map.setCenter(s.myLocation!!, 15.0)
            centeredOnUser = true
        }
    }

    // Глубина: чем выше шторка, тем дальше уходит карта — параллакс, масштаб, затемнение, размытие.
    // На другой вкладке карта уходит на самый дальний слой.
    val away by animateFloatAsState(if (active) 0f else 1f, spring(dampingRatio = 0.9f, stiffness = 260f), label = "away")
    val sheetDepth = if (sheetOpen && nav == null) sheet.progress else 0f
    val depth = maxOf(sheetDepth, away)
    val blurLevel = when { away > 0.9f -> 3; depth > 0.8f -> 2; depth > 0.55f -> 1; else -> 0 }
    LaunchedEffect(blurLevel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val r = BLUR_RADII[blurLevel]
            map.view.setRenderEffect(if (r == 0f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        }
    }
    LaunchedEffect(active, sheetOpen, nav != null) { map.setScaleVisible(active && !sheetOpen && nav == null) }

    Box(modifier.fillMaxSize().onGloballyPositioned { rootHeightPx = it.size.height.toFloat() }) {
        AndroidView(
            factory = { map.view },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationY = -depth * 48.dp.toPx()
                scaleX = 1f - depth * 0.035f
                scaleY = 1f - depth * 0.035f
            },
        )
        if (sheetDepth > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f * sheetDepth)))
        if (!active) {
            // Дальний слой не отвечает на касания: они достаются экрану поверх него, а не карте.
            Box(Modifier.fillMaxSize().pointerInput(Unit) {})
            return@Box
        }
        val current = nav
        if (current != null) {
            NavigationOverlay(current, vm, bottomSpace = bottomSpace,
                onOpen2gis = { t -> vm.openIn2gis(context, t, current.transport) })
            return@Box
        }
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(visible = s.usingCityCenter, enter = scaleIn(spring(0.55f)) + fadeIn(), exit = fadeOut()) {
                Text("Нет геолокации: ищем от центра Астаны", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp)
                        .bubble(BubbleShapes.Pill, Fills.glass(), Depth.LOW, glow = MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 14.dp, vertical = 8.dp))
            }
            AnimatedVisibility(
                visible = s.chips.isNotEmpty() && sheetOpen,
                enter = slideInVertically(spring(0.6f, Spring.StiffnessMediumLow)) { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
            ) { JevChipsRow(s, onRemove = vm::removeChip) }
            if (s.error != null && !sheetOpen) ErrorBanner(s.error!!, onDismiss = vm::dismissError)
        }
        // Поля по бокам — у каждого элемента, а не у колонки: ряд подсказок прокручивается от края до края экрана.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()
                .padding(top = 8.dp, bottom = bottomSpace)
                .onGloballyPositioned { panelTopPx = it.positionInRoot().y },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Кнопка «моё место» — часть слоя карты: уходит, когда шторка поднимается к фильтрам.
            val locVisibility = ((0.85f - sheetDepth) / 0.25f).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(54.dp)) {
                if (locVisibility > 0.01f) BubbleIconButton(
                    onClick = { scope.launch { val p = vm.refreshLocation(); map.setCenter(p, 15.0) } },
                    icon = Icons.Filled.MyLocation, contentDescription = "Моё местоположение",
                    modifier = Modifier.align(Alignment.CenterEnd).graphicsLayer {
                        alpha = locVisibility
                        scaleX = 0.7f + 0.3f * locVisibility
                        scaleY = 0.7f + 0.3f * locVisibility
                    },
                    size = 54.dp, fill = Fills.glass(0.95f), tint = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(
                visible = sheetOpen,
                enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
                exit = slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it } + fadeOut(),
            ) {
                PhysicsSheet(sheet, Modifier.padding(horizontal = 12.dp)) {
                    when (s.sheet) {
                        SheetMode.RESULTS -> ResultsSheet(s, vm)
                        SheetMode.PLAN -> PlanSheet(s, vm, onOpen2gis = { t -> vm.openIn2gis(context, t, s.transport) })
                        SheetMode.PLACE -> PlaceSheet(s, vm, onCall = { vm.call(context, it) },
                            onOpen2gis = { t -> vm.openIn2gis(context, t, s.routeTransport) })
                        SheetMode.ROUTE -> RouteSheet(s, vm, onOpen2gis = { t -> vm.openIn2gis(context, t, s.routeTransport) })
                        SheetMode.NONE -> Unit
                    }
                }
            }
            AnimatedVisibility(visible = !sheetOpen, enter = scaleIn(spring(0.5f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    flingBehavior = kz.smartcity.walk.ui.fx.rememberFrictionFling(1.2f)) {
                    items(HINTS) { (hint, icon) ->
                        BubbleChip(hint, onClick = { vm.search(hint) }, leading = icon)
                    }
                }
            }
            SearchBubble(s, vm, Modifier.padding(horizontal = 12.dp))
        }
    }
}

/** Шторка с физикой: ручка тянется пальцем, содержимое — вложенная прокрутка с трением. */
@Composable
private fun PhysicsSheet(sheet: SheetPhysics, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val heightDp = with(density) { sheet.height.value.toDp() }
    Column(
        modifier
            .fillMaxWidth()
            .height(heightDp.coerceAtLeast(96.dp))
            .bubble(BubbleShapes.XL, Fills.surface(), Depth.SHEET, glow = MaterialTheme.colorScheme.primary, highlight = 0.2f),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> sheet.dragBy(-delta) },
                    onDragStopped = { velocity -> scope.launch { sheet.settle(-velocity) } },
                )
                .semantics { contentDescription = "Потяните, чтобы раскрыть или свернуть" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 44.dp, height = 5.dp).bubble(BubbleShapes.Pill,
                SolidColor(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)), Depth.FLOOR, highlight = 0f, rim = 0f))
        }
        Box(Modifier.weight(1f).nestedScroll(sheet.nestedScroll)) { content() }
    }
}

@Composable
private fun SearchBubble(s: MapUiState, vm: MapViewModel, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    Column(modifier) {
        AnimatedVisibility(visible = s.suggestions.isNotEmpty(), enter = scaleIn(spring(0.6f), initialScale = 0.9f) + fadeIn(),
            exit = fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)
                .bubble(BubbleShapes.L, Fills.glass(0.97f), Depth.FLOAT, glow = MaterialTheme.colorScheme.primary)
                .padding(vertical = 6.dp)) {
                s.suggestions.forEach { sug ->
                    Row(
                        Modifier.fillMaxWidth().clickable { focus.clearFocus(); vm.pickSuggestion(sug) }.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (sug.type == "rubric") Icons.Filled.Category else Icons.Filled.Place, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(sug.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            sug.subtitle?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(60.dp)
                .bubble(BubbleShapes.Pill, Fills.glass(0.97f), Depth.MODAL, glow = MaterialTheme.colorScheme.primary)
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).bubble(BubbleShapes.Pill, Fills.primary(), Depth.LOW, glow = MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (s.query.isEmpty()) {
                    Text("Куда пойдём? Например: тихое кафе с розеткой", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = s.query,
                    onValueChange = vm::onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search(s.query) }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Строка запроса" },
                )
            }
            if (s.query.isNotEmpty()) {
                BubbleIconButton(onClick = { vm.onQueryChange(""); vm.clearSuggestions() }, icon = Icons.Filled.Close,
                    contentDescription = "Очистить", size = 44.dp, depth = Depth.FLOOR)
            }
        }
    }
}

@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .bubble(BubbleShapes.L, Fills.tint(MaterialTheme.colorScheme.error), Depth.FLOAT, glow = MaterialTheme.colorScheme.error)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f))
            .padding(start = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
        BubbleIconButton(onClick = onDismiss, icon = Icons.Filled.Close, contentDescription = "Закрыть", size = 44.dp, depth = Depth.FLOOR,
            fill = SolidColor(Color.Transparent))
    }
}
