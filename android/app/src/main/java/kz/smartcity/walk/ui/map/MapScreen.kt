package kz.smartcity.walk.ui.map

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Navigation
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
import androidx.compose.ui.graphics.graphicsLayer
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
import kz.smartcity.walk.ui.components.quickIcon
import kz.smartcity.walk.ui.fx.BubbleChip
import kz.smartcity.walk.ui.fx.LocalHaptics


private enum class CamMode { FREE, FOLLOW, COMPASS }

private val ROUTE_BLUE = androidx.compose.ui.graphics.Color(0xFF1A66E0)
private val WALK_GREY = androidx.compose.ui.graphics.Color(0xFF4F5B70)

/** Центр Астаны — пока нет геолокации. */
private val CITY = LatLng(51.128, 71.430)

/**
 * Карта — дальний слой на весь экран; поиск сверху и панель вкладок снизу лежат над ней с системным
 * размытием. Результаты, место и маршрут — стандартная шторка Material 3 (BottomSheetScaffold):
 * её жесты и вложенная прокрутка работают вместе, ничего не дёргается.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun MapScreen(vm: MapViewModel, hazeState: HazeState, modifier: Modifier = Modifier, bottomSpace: Dp = 0.dp,
              active: Boolean = true, wide: Boolean = false) {
    val s by vm.state.collectAsStateWithLifecycle()
    val quick by vm.quickPlaces.collectAsStateWithLifecycle()
    val navPlace by vm.navPlace.collectAsStateWithLifecycle()
    val nav by NavigationBus.state.collectAsStateWithLifecycle()
    LaunchedEffect(nav?.dest?.poiId) { nav?.dest?.let { vm.loadNavPlace(it) } }
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHaptics.current
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val camera = rememberMapCamera(vm.state.value.myLocation ?: CITY)
    // Тайлы карты светлые и в тёмной теме, поэтому линии маршрута — постоянные контрастные цвета, не из темы.
    val primary = ROUTE_BLUE
    val walkColor = WALK_GREY
    // Навигация: камера следует за человеком, пока он сам не потрогает карту; вернуть — кнопкой.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(nav != null) { follow = true }
    // Кнопка «моё место», как в Google Картах: свободно → следовать за точкой → следовать по компасу.
    var camMode by remember { mutableStateOf(CamMode.FREE) }
    val heading by rememberHeading(active)
    LaunchedEffect(active, nav == null) { if (active && nav == null) vm.trackLocation() }
    // Карта заранее на диск: Астана целиком на обзорных масштабах (один раз) и подробно — вокруг человека.
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(s.myLocation != null) { preloadTiles(appContext, s.myLocation ?: CITY) }

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
        // Плавное слежение: позиция приходит раз в секунду, камера едет к ней ~0,9 с без рывков и не меняет масштаб,
        // выбранный человеком. Первый кадр навигации — крупный план (16).
        var navZoomed by remember { mutableStateOf(false) }
        LaunchedEffect(nav == null) { if (nav == null) navZoomed = false }
        LaunchedEffect(nav?.position, follow) {
            val p = nav?.position ?: return@LaunchedEffect
            if (!follow) return@LaunchedEffect
            val z = if (navZoomed) camera.zoom else 16.0
            navZoomed = true
            camera.animateTo(p, z, tween(900, easing = LinearOutSlowInEasing))
        }

        // Следование и компас вне навигатора: камера едет за точкой; в режиме компаса карта поворачивается за телефоном.
        LaunchedEffect(s.myLocation, camMode) {
            val p = s.myLocation ?: return@LaunchedEffect
            if (nav == null && camMode != CamMode.FREE) camera.animateTo(p, camera.zoom, tween(800, easing = LinearOutSlowInEasing))
        }
        LaunchedEffect(heading, camMode) {
            val h = heading ?: return@LaunchedEffect
            if (camMode == CamMode.COMPASS) camera.setBearing(-h)
        }

        val baseLayers = remember(s.answer, s.candidates, s.route, s.selectedOptionId, s.sheet, s.routeTarget, s.place, nav, s.myLocation) {
            val current = nav
            if (current != null) vm.navLayers(current, primary, walkColor)
            else vm.layers(s, primary, walkColor).copy(user = s.myLocation)
        }
        val layers = baseLayers.copy(heading = heading)

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
                onUserGesture = { if (nav != null) follow = false; camMode = CamMode.FREE },
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
                AnimatedVisibility(s.remember != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    s.remember?.let { RememberCard(it, onSave = { haptics?.tap(); vm.acceptRemember() }, onDismiss = vm::dismissRemember) }
                }
            }
        }
        val locate: @Composable (Modifier) -> Unit = { mod ->
            Column(mod, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val on = camMode != CamMode.FREE
                SmallFloatingActionButton(
                    onClick = {
                        haptics?.tap()
                        when (camMode) {
                            CamMode.FREE -> { camMode = CamMode.FOLLOW; scope.launch { camera.animateTo(vm.refreshLocation(), maxOf(camera.zoom, 16.0)) } }
                            CamMode.FOLLOW -> camMode = CamMode.COMPASS
                            CamMode.COMPASS -> { camMode = CamMode.FOLLOW; scope.launch { camera.resetBearing() } }
                        }
                    },
                    containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics {
                        contentDescription = when (camMode) {
                            CamMode.FREE -> "Моё местоположение"; CamMode.FOLLOW -> "Следовать по компасу"; CamMode.COMPASS -> "Север вверх"
                        }
                    },
                ) {
                    Icon(when (camMode) {
                        CamMode.FREE -> Icons.Filled.LocationSearching; CamMode.FOLLOW -> Icons.Filled.MyLocation
                        CamMode.COMPASS -> Icons.Filled.Explore
                    }, contentDescription = null)
                }
                // Компас: виден, пока карта повёрнута; стрелка показывает север, нажатие — север вверх.
                AnimatedVisibility(camera.bearing != 0f && camMode != CamMode.COMPASS, enter = fadeIn() + androidx.compose.animation.scaleIn(),
                    exit = fadeOut() + androidx.compose.animation.scaleOut()) {
                    SmallFloatingActionButton(
                        onClick = { haptics?.tap(); scope.launch { camera.resetBearing() } },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { contentDescription = "Север вверх" },
                    ) {
                        Icon(Icons.Filled.Navigation, contentDescription = null,
                            modifier = Modifier.graphicsLayer { rotationZ = -camera.bearing })
                    }
                }
            }
        }

        val navOverlay: @Composable () -> Boolean = {
            val current = nav
            if (current != null) NavigationOverlay(current, vm, bottomSpace = bottomSpace, wide = wide, follow = follow,
                compass = camMode == CamMode.COMPASS,
                nextStop = s.planLeg?.let { vm.nextPlanLeg() },
                place = navPlace?.takeIf { it.poiId == current.dest.poiId },
                onNextStop = { haptics?.tap(); follow = true; s.planLeg?.let { vm.startPlanLeg(it + 1) } },
                onCompass = {
                    haptics?.tap()
                    if (camMode == CamMode.COMPASS) { camMode = CamMode.FREE; scope.launch { camera.resetBearing() } }
                    else { camMode = CamMode.COMPASS; follow = true }
                },
                onRecenter = { haptics?.tap(); follow = true },
                onOverview = {
                    follow = false
                    scope.launch {
                        val pts = current.geometry + listOfNotNull(current.position)
                        camera.fitBounds(pts, with(density) { 200.dp.toPx() }, with(density) { 260.dp.toPx() }, sidePx, sidePx)
                    }
                },
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
            // Быстрые маршруты на карте: слева от кнопки поиска, пока шторка закрыта.
            AnimatedVisibility(quick.isNotEmpty() && !sheetOpen && !s.composing, enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = bottomSpace + 16.dp, end = 88.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(quick, key = { it.id }) { p ->
                        BubbleChip(p.name, onClick = { haptics?.tap(); vm.routeToQuick(p) }, leading = quickIcon(p.icon))
                    }
                }
            }
            SearchComposer(s, vm, hazeState, quick = quick,
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

/** ИИ предлагает запомнить место — сохраняется только по кнопке. */
@Composable
private fun RememberCard(r: kz.smartcity.walk.data.api.RememberOffer, onSave: () -> Unit, onDismiss: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shadowElevation = 4.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(quickIcon(r.icon), contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Запомнить «${r.name}»?", style = MaterialTheme.typography.titleMedium)
                    r.address?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
            Text("Появится кнопка быстрого маршрута, и ИИ будет понимать «поехали к ${r.name.lowercase()}».",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Запомнить") }
                androidx.compose.material3.OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Не надо") }
            }
        }
    }
}
