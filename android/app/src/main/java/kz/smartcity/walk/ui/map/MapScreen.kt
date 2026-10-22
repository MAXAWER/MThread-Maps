package kz.smartcity.walk.ui.map

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch
import kz.smartcity.walk.data.LatLng
import kz.smartcity.walk.nav.NavigationBus
import kz.smartcity.walk.ui.fx.BubbleChip
import kz.smartcity.walk.ui.fx.LocalHaptics


/** Центр Астаны — пока нет геолокации. */
private val CITY = LatLng(51.128, 71.430)

/**
 * Карта — дальний слой на весь экран; поиск сверху и панель вкладок снизу лежат над ней с системным
 * размытием. Результаты, место и маршрут — стандартная шторка Material 3 (BottomSheetScaffold):
 * её жесты и вложенная прокрутка работают вместе, ничего не дёргается.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun MapScreen(vm: MapViewModel, hazeState: HazeState, modifier: Modifier = Modifier, bottomSpace: Dp = 0.dp,
              active: Boolean = true, wide: Boolean = false) {
    val s by vm.state.collectAsStateWithLifecycle()
    val nav by NavigationBus.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHaptics.current
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val camera = rememberMapCamera(vm.state.value.myLocation ?: CITY)
    val primary = MaterialTheme.colorScheme.primary
    val walkColor = MaterialTheme.colorScheme.onSurfaceVariant

    val sheetOpen = s.sheet != SheetMode.NONE && nav == null && active
    val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.Hidden, skipHiddenState = false)
    val scaffold = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    LaunchedEffect(sheetOpen, s.sheet) {
        if (sheetOpen) {
            if (sheetState.currentValue == SheetValue.Hidden) sheetState.partialExpand()
        } else if (sheetState.currentValue != SheetValue.Hidden) {
            sheetState.hide()
        }
    }
    // Шторку смахнули вниз до конца — закрываем её содержимое.
    LaunchedEffect(sheetState) {
        var wasVisible = false
        snapshotFlow { sheetState.currentValue }.collect { v ->
            if (v == SheetValue.Hidden && wasVisible && vm.state.value.sheet != SheetMode.NONE) vm.closeSheet()
            wasVisible = v != SheetValue.Hidden
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val peek = (maxHeight * 0.46f).coerceAtLeast(260.dp)
        val panelWidth = (maxWidth * 0.42f).coerceIn(340.dp, 440.dp)
        val topArea = with(density) { (WindowInsets.statusBars.getTop(this) + (if (wide) 24.dp else 132.dp).roundToPx()).toFloat() }
        val sidePx = with(density) { 40.dp.toPx() }
        // Отступы видимой области: на широком экране слева боковая панель, снизу панель вкладок.
        val leftPx = with(density) { if (wide && sheetOpen) (panelWidth + 36.dp).toPx() else sidePx }
        val sheetBottomPx = with(density) { (if (wide) bottomSpace + 24.dp else peek).toPx() }

        // ---------- камера: только при смене содержимого, не от движений шторки ----------
        val markers = remember(s.answer, s.candidates, s.routeTarget, s.place, s.sheet) { vm.markers(s) }
        LaunchedEffect(markers.map { it.id }, s.sheet) {
            if (nav != null || markers.isEmpty() || s.sheet == SheetMode.ROUTE) return@LaunchedEffect
            val bottom = if (sheetOpen) sheetBottomPx else with(density) { (bottomSpace + 120.dp).toPx() }
            camera.fitBounds(markers.map { LatLng(it.lat, it.lon) } + listOfNotNull(s.myLocation), topArea, bottom, leftPx, sidePx)
        }
        LaunchedEffect(s.route, s.selectedOptionId, s.sheet) {
            val route = s.route ?: return@LaunchedEffect
            if (s.sheet != SheetMode.ROUTE || nav != null) return@LaunchedEffect
            val opt = route.options.firstOrNull { it.id == s.selectedOptionId }
            val geom = (opt?.geometry ?: route.geometry).mapNotNull { if (it.size >= 2) LatLng(it[1], it[0]) else null }
            camera.fitBounds(geom, topArea, sheetBottomPx, leftPx, sidePx)
        }
        LaunchedEffect(s.myLocation != null) {
            val me = s.myLocation ?: return@LaunchedEffect
            if (markers.isEmpty() && nav == null) camera.animateTo(me, 15.0)
        }
        LaunchedEffect(nav?.position) { nav?.position?.let { camera.animateTo(it, 17.0) } }

        val layers = remember(s.answer, s.candidates, s.route, s.selectedOptionId, s.sheet, s.routeTarget, s.place, nav?.geometry, s.myLocation, nav?.position) {
            val base = vm.layers(s, primary, walkColor)
            val navLine = nav?.geometry?.takeIf { it.size > 1 }?.let { listOf(MapLine(it, primary)) }
            base.copy(lines = navLine ?: base.lines, user = nav?.position ?: s.myLocation)
        }

        val sheetContent: @Composable () -> Unit = {
            AnimatedContent(
                targetState = s.sheet,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "sheet",
            ) { mode ->
                when (mode) {
                    SheetMode.RESULTS -> ResultsSheet(s, vm)
                    SheetMode.PLAN -> PlanSheet(s, vm, onOpen2gis = { t -> vm.openIn2gis(context, t, s.transport) })
                    SheetMode.PLACE -> PlaceSheet(s, vm, onCall = { vm.call(context, it) },
                        onOpen2gis = { t -> vm.openIn2gis(context, t, s.routeTransport) })
                    SheetMode.ROUTE -> RouteSheet(s, vm, onOpen2gis = { t -> vm.openIn2gis(context, t, s.routeTransport) })
                    SheetMode.NONE -> Spacer(Modifier.height(1.dp))
                }
            }
        }
        val map: @Composable () -> Unit = {
            TileMap(
                camera = camera,
                layers = layers,
                onMarkerClick = { id -> haptics?.tap(); vm.onMarkerClick(id) },
                onLongPress = { p -> if (nav == null) { haptics?.threshold(); vm.onLongPress(p) } },
                modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                dark = dark,
            )
        }
        // Верх: «ИИ работает» с мыслями, как понял запрос ИИ, сообщения.
        val topBlock: @Composable (Modifier) -> Unit = { mod ->
            Column(mod, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedVisibility(s.searching, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    ThinkingCapsule(s, hazeState, Modifier.padding(horizontal = 12.dp))
                }
                AnimatedVisibility(s.chips.isNotEmpty() && s.sheet != SheetMode.NONE,
                    enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    UnderstoodRow(s, onRemove = vm::removeChip)
                }
                AnimatedVisibility(s.usingCityCenter, enter = fadeIn(), exit = fadeOut()) {
                    Text("Нет геолокации: ищем от центра Астаны", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp).clip(CircleShape)
                            .hazeEffect(hazeState, HazeMaterials.thin(MaterialTheme.colorScheme.surfaceContainerHigh))
                            .padding(horizontal = 14.dp, vertical = 8.dp))
                }
                s.error?.takeIf { s.sheet == SheetMode.NONE }?.let { ErrorBanner(it, onDismiss = vm::dismissError) }
            }
        }
        val locate: @Composable (Modifier) -> Unit = { mod ->
            SmallFloatingActionButton(
                onClick = { haptics?.tap(); scope.launch { camera.animateTo(vm.refreshLocation(), 16.0) } },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = mod.semantics { contentDescription = "Моё местоположение" },
            ) { Icon(Icons.Filled.MyLocation, contentDescription = null) }
        }

        val navOverlay: @Composable () -> Boolean = {
            val current = nav
            if (current != null) NavigationOverlay(current, vm, bottomSpace = bottomSpace,
                onOpen2gis = { t -> vm.openIn2gis(context, t, current.transport) })
            current != null
        }

        if (wide) {
            // Широкий экран (внутренний экран Z Fold, планшет): слева колонка с ходом поиска и панелью, справа карта.
            Box(Modifier.fillMaxSize()) {
                map()
                if (!active || navOverlay()) return@Box
                Column(Modifier.statusBarsPadding().padding(top = 8.dp, start = 4.dp, bottom = bottomSpace).width(panelWidth + 16.dp).fillMaxHeight()) {
                    topBlock(Modifier.fillMaxWidth())
                    AnimatedVisibility(sheetOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Surface(
                            Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp).fillMaxWidth().fillMaxHeight(),
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shadowElevation = 6.dp,
                        ) { Column(Modifier.padding(top = 12.dp)) { sheetContent() } }
                    }
                }
                locate(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 16.dp))
            }
        } else BottomSheetScaffold(
            scaffoldState = scaffold,
            sheetPeekHeight = if (sheetOpen) peek else 0.dp,
            sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            sheetContentColor = MaterialTheme.colorScheme.onSurface,
            sheetShadowElevation = 6.dp,
            sheetDragHandle = { BottomSheetDefaults.DragHandle() },
            sheetSwipeEnabled = sheetOpen,
            containerColor = MaterialTheme.colorScheme.surface,
            sheetContent = { sheetContent() },
        ) {
            Box(Modifier.fillMaxSize()) {
                map()
                if (!active || navOverlay()) return@Box
                topBlock(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(top = 8.dp, end = 56.dp))
                locate(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 12.dp))
            }
        }

        // Кнопка поиска: на узком экране «едет» над шторкой, на широком — над панелью вкладок.
        if (active && nav == null) {
            val heightPx = constraints.maxHeight.toFloat()
            // Положение шторки читается внутри SearchComposer: при перетаскивании перерисовывается только кнопка.
            val sheetTop = { if (!wide && sheetOpen) runCatching { sheetState.requireOffset() }.getOrNull() else null }
            SearchComposer(s, vm, hazeState,
                collapsedBottom = { sheetTop()?.let { with(density) { (heightPx - it).toDp() } + 12.dp } ?: (bottomSpace + 8.dp) },
                collapsedVisible = { sheetTop()?.let { it > heightPx * 0.3f } ?: true })
        }
    }
}

@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(Modifier.padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Закрыть") }
        }
    }
}
