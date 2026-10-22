package kz.smartcity.walk.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kz.smartcity.walk.AppGraph
import kz.smartcity.walk.ui.foryou.ForYouScreen
import kz.smartcity.walk.ui.foryou.ForYouViewModel
import kz.smartcity.walk.ui.fx.BubbleShapes
import kz.smartcity.walk.ui.fx.Depth
import kz.smartcity.walk.ui.fx.Fills
import kz.smartcity.walk.ui.fx.LocalHaptics
import kz.smartcity.walk.ui.fx.bubble
import kz.smartcity.walk.ui.fx.pressPhysics
import kz.smartcity.walk.ui.map.MapScreen
import kz.smartcity.walk.ui.map.MapViewModel
import kz.smartcity.walk.ui.onboarding.OnboardingScreen
import kz.smartcity.walk.ui.profile.ProfileScreen
import kz.smartcity.walk.ui.profile.ProfileViewModel
import kz.smartcity.walk.work.Workers
import kotlin.math.abs
import kotlin.math.roundToInt

private val NAV_BAR_HEIGHT = 68.dp
private val NAV_BAR_MARGIN = 10.dp

/** Место, которое экраны оставляют под плавающей панелью вкладок: системная навигация, панель и зазор над ней. */
@Composable
private fun navBarSpace(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + NAV_BAR_HEIGHT + NAV_BAR_MARGIN * 2 + 4.dp

/**
 * Три вкладки: «Карта», «Для вас», «Профиль». Карта — дальний слой на весь экран (под плавающей
 * панелью тоже), остальное лежит поверх неё ближе к пользователю.
 */
@Composable
fun AppRoot(graph: AppGraph) {
    CompositionLocalProvider(LocalHaptics provides graph.haptics) {
        var onboarded by remember { mutableStateOf(graph.settings.onboardingDone) }
        if (!onboarded) {
            OnboardingScreen(graph) { onboarded = true }
            return@CompositionLocalProvider
        }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val mapVm: MapViewModel = viewModel(factory = MapViewModel.Factory(graph))
        LaunchedEffect(Unit) {
            // Колода обновляется при открытии приложения, если ей больше 2 часов.
            if (graph.deck.isStale(System.currentTimeMillis())) Workers.refreshDeckNow(graph.app)
            graph.intents.collect { tab = 0 }
        }
        val bottomSpace = navBarSpace()
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            MapScreen(mapVm, bottomSpace = bottomSpace, active = tab == 0)
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    // Новый экран всплывает из глубины к пользователю, прежний подаётся назад и тает.
                    (fadeIn(tween(220)) + scaleIn(spring(dampingRatio = 0.72f, stiffness = 380f), initialScale = 0.92f)) togetherWith
                        (fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.96f))
                },
                label = "tab",
            ) { t ->
                when (t) {
                    1 -> ForYouScreen(viewModel(factory = ForYouViewModel.Factory(graph)), bottomSpace = bottomSpace)
                    2 -> ProfileScreen(viewModel(factory = ProfileViewModel.Factory(graph)), bottomSpace = bottomSpace)
                    else -> Box(Modifier.fillMaxSize()) // карта: поверх неё ничего
                }
            }
            BubbleNavBar(tab, onTab = { tab = it }, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

private data class NavItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/** Плавающая панель-пузырь: индикатор перетекает к выбранной вкладке и растягивается от скорости. */
@Composable
private fun BubbleNavBar(tab: Int, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf(NavItem("Карта", Icons.Filled.Map), NavItem("Для вас", Icons.Filled.Style), NavItem("Профиль", Icons.Filled.Person))
    val haptics = LocalHaptics.current
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = NAV_BAR_MARGIN)
            .fillMaxWidth()
            .height(NAV_BAR_HEIGHT)
            .bubble(BubbleShapes.Pill, Fills.surface(), Depth.MODAL),
    ) {
        val itemWidthPx = with(density) { (maxWidth / items.size).toPx() }
        val x = remember { Animatable(tab * itemWidthPx) }
        LaunchedEffect(tab, itemWidthPx) { x.animateTo(tab * itemWidthPx, spring(dampingRatio = 0.62f, stiffness = 380f)) }
        val stretch = 1f + (abs(x.velocity) / 9000f).coerceAtMost(0.35f)
        Box(
            Modifier
                .offset { IntOffset(x.value.roundToInt(), 0) }
                .width(maxWidth / items.size)
                .fillMaxHeight()
                .padding(6.dp)
                .graphicsLayer { scaleX = stretch; scaleY = 1f / stretch.coerceAtMost(1.15f) }
                .bubble(BubbleShapes.Pill, Fills.primary(), Depth.LOW, glow = MaterialTheme.colorScheme.primary),
        )
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, item ->
                val selected = tab == i
                val interaction = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressPhysics(interaction, squash = 0.88f)
                        .clickable(interaction, indication = null, role = Role.Tab) {
                            if (i != tab) haptics?.tick(0.55f)
                            onTab(i)
                        }
                        .semantics { this.selected = selected; contentDescription = item.label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    val color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(item.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                    Text(item.label, color = color, style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}
