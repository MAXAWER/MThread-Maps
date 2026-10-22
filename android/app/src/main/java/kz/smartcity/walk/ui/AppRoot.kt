package kz.smartcity.walk.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.rememberHazeState
import kz.smartcity.walk.AppGraph
import kz.smartcity.walk.nav.NavigationBus
import kz.smartcity.walk.ui.foryou.ForYouScreen
import kz.smartcity.walk.ui.foryou.ForYouViewModel
import kz.smartcity.walk.ui.fx.LocalHaptics
import kz.smartcity.walk.ui.fx.pressPhysics
import kz.smartcity.walk.ui.map.MapScreen
import kz.smartcity.walk.ui.map.MapViewModel
import kz.smartcity.walk.ui.map.SheetMode
import kz.smartcity.walk.ui.onboarding.OnboardingScreen
import kz.smartcity.walk.ui.profile.ProfileScreen
import kz.smartcity.walk.ui.profile.ProfileViewModel
import kz.smartcity.walk.work.Workers
import kotlin.math.roundToInt

private val NAV_BAR_HEIGHT = 72.dp
private val NAV_BAR_MARGIN = 12.dp

/** Ширина, начиная с которой экран считается широким (внутренний экран Z Fold, планшет): шторка — боковая панель. */
val WIDE_LAYOUT = 600.dp

/** Место, которое экраны оставляют под плавающей панелью вкладок: системная навигация, панель и зазор над ней. */
@Composable
private fun navBarSpace(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + NAV_BAR_HEIGHT + NAV_BAR_MARGIN * 2

/**
 * Три вкладки: «Карта», «Для вас», «Профиль». Карта — дальний слой на весь экран, остальные вкладки и панель
 * лежат над ней. Поверхности над картой размывают её (Haze): поиск — саму карту, панель вкладок — весь экран.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AppRoot(graph: AppGraph) {
    CompositionLocalProvider(LocalHaptics provides graph.haptics) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var onboarded by remember { mutableStateOf(graph.settings.onboardingDone) }
            if (!onboarded) {
                OnboardingScreen(graph) { onboarded = true }
                return@Surface
            }
            var tab by rememberSaveable { mutableIntStateOf(0) }
            val mapVm: MapViewModel = viewModel(factory = MapViewModel.Factory(graph))
            val mapState by mapVm.state.collectAsStateWithLifecycle()
            val nav by NavigationBus.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
                // Колода обновляется при открытии приложения, если ей больше 2 часов.
                if (graph.deck.isStale(System.currentTimeMillis())) Workers.refreshDeckNow(graph.app)
                graph.intents.collect { tab = 0 }
            }
            val mapHaze = rememberHazeState()
            val rootHaze = rememberHazeState()
            val bottomSpace = navBarSpace()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= WIDE_LAYOUT
                // На узком экране панель вкладок уступает место шторке и навигатору; на широком шторка сбоку.
                val showBar = tab != 0 || nav == null && !mapState.composing && (wide || mapState.sheet == SheetMode.NONE)
                Box(Modifier.fillMaxSize().hazeSource(rootHaze)) {
                    MapScreen(mapVm, mapHaze, bottomSpace = bottomSpace, active = tab == 0, wide = wide)
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = { (fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.96f)) togetherWith fadeOut(tween(150)) },
                        label = "tab",
                    ) { t ->
                        when (t) {
                            1 -> ForYouScreen(viewModel(factory = ForYouViewModel.Factory(graph)), bottomSpace = bottomSpace)
                            2 -> ProfileScreen(viewModel(factory = ProfileViewModel.Factory(graph)), bottomSpace = bottomSpace)
                            else -> Box(Modifier.fillMaxSize()) // карта: поверх неё ничего
                        }
                    }
                }
                AnimatedVisibility(
                    showBar,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    FloatingNavBar(tab, onTab = { tab = it }) {
                        Modifier.hazeEffect(rootHaze, HazeMaterials.thick(MaterialTheme.colorScheme.surfaceContainer))
                    }
                }
            }
        }
    }
}

private data class NavItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/** Плавающая панель вкладок Material 3 с размытием фона: тональный индикатор плавно переезжает к выбранной. */
@Composable
private fun FloatingNavBar(tab: Int, onTab: (Int) -> Unit, blur: @Composable () -> Modifier) {
    val items = listOf(NavItem("Карта", Icons.Filled.Map), NavItem("Для вас", Icons.Filled.Style), NavItem("Профиль", Icons.Filled.Person))
    val haptics = LocalHaptics.current
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = NAV_BAR_MARGIN)
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .height(NAV_BAR_HEIGHT)
            .clip(CircleShape)
            .then(blur()),
    ) {
        val itemWidthPx = with(density) { (maxWidth / items.size).toPx() }
        val x = remember { Animatable(tab * itemWidthPx) }
        LaunchedEffect(tab, itemWidthPx) { x.animateTo(tab * itemWidthPx, spring(dampingRatio = 0.8f, stiffness = 500f)) }
        Surface(
            Modifier
                .offset { IntOffset(x.value.roundToInt(), 0) }
                .width(maxWidth / items.size)
                .fillMaxHeight()
                .padding(6.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {}
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, item ->
                val selected = tab == i
                val interaction = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressPhysics(interaction, squash = 0.9f)
                        .clickable(interaction, indication = null, role = Role.Tab) {
                            if (i != tab) haptics?.tick(0.55f)
                            onTab(i)
                        }
                        .semantics { this.selected = selected; contentDescription = item.label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(item.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                    Text(item.label, color = color, style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}
