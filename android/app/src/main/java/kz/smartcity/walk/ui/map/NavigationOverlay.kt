package kz.smartcity.walk.ui.map

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kz.smartcity.walk.data.api.PlaceCard
import kz.smartcity.walk.nav.NavState
import kz.smartcity.walk.nav.NavigationEngine
import kz.smartcity.walk.nav.NavigationService
import kz.smartcity.walk.ui.components.formatDistance
import kz.smartcity.walk.ui.components.transportIcon
import kz.smartcity.walk.ui.fx.BubbleButton
import kz.smartcity.walk.ui.fx.BubbleCard
import kz.smartcity.walk.ui.fx.BubbleGhostButton
import kz.smartcity.walk.ui.fx.BubbleIconButton
import kz.smartcity.walk.ui.fx.BubbleShapes
import kz.smartcity.walk.ui.fx.Depth
import kz.smartcity.walk.ui.fx.Fills
import kz.smartcity.walk.ui.fx.bubble

private fun maneuverIcon(s: NavState): ImageVector = when {
    s.arrived -> Icons.Filled.Flag
    s.turnDirection?.contains("left") == true || s.maneuverIcon?.contains("left") == true -> Icons.Filled.TurnLeft
    s.turnDirection?.contains("right") == true || s.maneuverIcon?.contains("right") == true -> Icons.Filled.TurnRight
    s.maneuverIcon?.contains("uturn") == true -> Icons.Filled.UTurnLeft
    s.maneuverIcon?.contains("finish") == true -> Icons.Filled.Flag
    else -> Icons.Filled.ArrowUpward
}

/** Навигация: пузырь манёвра сверху (ближний слой), время прибытия и остаток пути внизу. */
@Suppress("DEPRECATION")
@Composable
fun NavigationOverlay(s: NavState, vm: MapViewModel, bottomSpace: Dp, onOpen2gis: (Target) -> Unit) {
    val context = LocalContext.current
    // Тактильные подсказки манёвров даёт сервис навигации: они нужны и при выключенном экране.
    Box(Modifier.fillMaxSize()) {
        BubbleCard(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
            fill = Fills.primary(), shape = BubbleShapes.XL, depth = Depth.MODAL,
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).bubble(CircleShape, Fills.glass(0.25f), Depth.FLOOR, highlight = 0.3f, rim = 0.6f),
                        contentAlignment = Alignment.Center) {
                        AnimatedContent(targetState = maneuverIcon(s), transitionSpec = {
                            (scaleIn(spring(0.5f), initialScale = 0.6f) + fadeIn()) togetherWith fadeOut()
                        }, label = "maneuver") { icon ->
                            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        s.distanceToManeuverM?.takeIf { !s.arrived }?.let {
                            Text(NavigationEngine.roundDistance(it.toDouble()), color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                        }
                        Text(s.maneuverText ?: "Двигайтесь по маршруту", color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (s.rerouting) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp), color = MaterialTheme.colorScheme.secondary)
                    Text("Перестраиваем маршрут…", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodySmall)
                } else if (s.offRoute) {
                    Text("Вы сошли с маршрута", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodySmall)
                }
                if (s.simulated) Text("Симуляция движения", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        BubbleCard(
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = bottomSpace + 6.dp).fillMaxWidth(),
            shape = BubbleShapes.XL, depth = Depth.SHEET,
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.arrived) {
                    Text("Вы на месте", style = MaterialTheme.typography.headlineMedium)
                    Text(s.dest.name, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BubbleButton(onClick = {
                            NavigationService.stop(context)
                            vm.openPlace(s.dest.poiId, PlaceCard(poiId = s.dest.poiId, name = s.dest.name, lat = s.dest.lat, lon = s.dest.lon,
                                category = s.dest.category))
                        }, modifier = Modifier.weight(1f)) { Text("Карточка места") }
                        BubbleGhostButton(onClick = { NavigationService.stop(context) }, modifier = Modifier.weight(1f)) { Text("Готово") }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).bubble(CircleShape, Fills.tint(MaterialTheme.colorScheme.primary), Depth.FLOOR),
                            contentAlignment = Alignment.Center) {
                            Icon(transportIcon(s.transport), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).semantics { contentDescription = "Осталось ${s.etaMin} минут, ${s.remainingM} метров" }) {
                            Text("${s.etaMin} мин", style = MaterialTheme.typography.headlineSmall)
                            Text("${formatDistance(s.remainingM)} · ${s.dest.name}", style = MaterialTheme.typography.bodySmall)
                        }
                        BubbleIconButton(onClick = { NavigationService.toggleSound(context) },
                            icon = if (s.soundOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                            contentDescription = if (s.soundOn) "Выключить звук" else "Включить звук", size = 52.dp,
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BubbleButton(onClick = { vm.stopNavigation() }, modifier = Modifier.weight(1f)) { Text("Завершить") }
                        BubbleGhostButton(onClick = { onOpen2gis(Target(s.dest.poiId, s.dest.name, s.dest.lat, s.dest.lon)) },
                            modifier = Modifier.weight(1f)) { Text("В 2ГИС") }
                    }
                }
            }
        }
    }
}
