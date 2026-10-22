package kz.smartcity.walk.ui.map

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kz.smartcity.walk.data.api.PlaceCard
import kz.smartcity.walk.nav.NavState
import kz.smartcity.walk.nav.NavigationEngine
import kz.smartcity.walk.nav.NavigationService
import kz.smartcity.walk.nav.TransitLeg
import kz.smartcity.walk.nav.legTitle
import kz.smartcity.walk.ui.components.formatDistance
import kz.smartcity.walk.ui.components.transportIcon
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private fun maneuverIcon(s: NavState): ImageVector = when {
    s.arrived -> Icons.Filled.Flag
    s.turnDirection?.contains("left") == true || s.maneuverIcon?.contains("left") == true -> Icons.Filled.TurnLeft
    s.turnDirection?.contains("right") == true || s.maneuverIcon?.contains("right") == true -> Icons.Filled.TurnRight
    s.maneuverIcon?.contains("uturn") == true -> Icons.Filled.UTurnLeft
    s.maneuverIcon?.contains("finish") == true -> Icons.Filled.Flag
    else -> Icons.Filled.ArrowUpward
}

private fun parseHex(hex: String?): Color? = hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

private fun stopsWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "остановка"
        m10 in 2..4 && m100 !in 12..14 -> "остановки"
        else -> "остановок"
    }
}

/** Ближайшее отправление с поправкой на прошедшее время, иначе интервал движения. Ничего не придумываем. */
private fun departureText(s: NavState, nowMs: Long): String? {
    val elapsed = ((nowMs - s.startedAtMs) / 60_000).toInt()
    s.departureInMin?.let { d ->
        val left = d - elapsed
        if (left >= 0) return "Отправление через $left мин" + (s.departure?.let { " (в $it)" } ?: "")
        s.nextDepartures.firstOrNull()?.let { return "Следующее отправление в $it" }
    }
    return s.intervalMin?.let { "Ходит каждые ~$it мин" }
}

/**
 * Навигация. Сверху — что делать сейчас: для автобуса — к какой остановке идти, на какой номер садиться, сколько
 * остановок до выхода и когда ближайший рейс; для пешком/авто — манёвр. Снизу — время прибытия и все шаги поездки.
 * Камера следует за человеком, пока он не потрогает карту; «Следовать» возвращает слежение.
 */
@Composable
fun NavigationOverlay(
    s: NavState, vm: MapViewModel, bottomSpace: Dp, wide: Boolean, follow: Boolean,
    compass: Boolean, onCompass: () -> Unit,
    nextStop: kz.smartcity.walk.data.api.PlanLeg? = null, onNextStop: () -> Unit = {},
    onRecenter: () -> Unit, onOverview: () -> Unit, onOpen2gis: (Target) -> Unit,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val align = if (wide) Alignment.TopStart else Alignment.TopCenter
    val bottomAlign = if (wide) Alignment.BottomStart else Alignment.BottomCenter
    Box(Modifier.fillMaxSize()) {
        // ---------- сверху: текущий шаг ----------
        Column(
            Modifier.align(align).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 560.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shadowElevation = 6.dp,
            ) {
                Column(Modifier.padding(16.dp)) {
                    if (s.transit && !s.arrived) TransitInstruction(s) else TurnInstruction(s)
                    if (s.rerouting) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                        Text("Перестраиваем маршрут…", style = MaterialTheme.typography.bodySmall)
                    } else if (s.offRoute) {
                        Text("Вы сошли с маршрута", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (s.simulated) Text("Симуляция движения", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp).alpha(0.8f))
                }
            }
            if (s.transit && !s.arrived) LegStrip(s)
        }

        // ---------- снизу: кнопки камеры и карточка поездки ----------
        Column(
            Modifier.align(bottomAlign).navigationBarsPadding().padding(12.dp).widthIn(max = 560.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(!follow, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    ExtendedFloatingActionButton(onClick = onRecenter, icon = { Icon(Icons.Filled.Navigation, null) },
                        text = { Text("Следовать") }, containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary)
                }
                SmallFloatingActionButton(onClick = onCompass,
                    containerColor = if (compass) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (compass) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { contentDescription = if (compass) "Север вверх" else "Карта по направлению взгляда" }) {
                    Icon(Icons.Filled.Explore, null)
                }
                SmallFloatingActionButton(onClick = onOverview, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { contentDescription = "Весь маршрут" }) { Icon(Icons.Filled.ZoomOutMap, null) }
            }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 6.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (s.arrived && nextStop != null) {
                        // Маршрут по точкам: эта точка пройдена — следующий этап одним нажатием.
                        Text("Вы на месте: ${s.dest.name}", style = MaterialTheme.typography.titleMedium)
                        Text("Дальше: ${nextStop.purpose.replaceFirstChar { it.uppercase() }} — ${nextStop.name}",
                            style = MaterialTheme.typography.headlineSmall)
                        Text(listOfNotNull("${nextStop.etaMin} мин в пути", nextStop.routeLabel).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onNextStop, modifier = Modifier.weight(1f)) { Text("Поехали дальше") }
                            FilledTonalButton(onClick = { vm.stopNavigation() }, modifier = Modifier.weight(1f)) { Text("Завершить") }
                        }
                        return@Column
                    }
                    if (s.arrived) {
                        Text("Вы на месте", style = MaterialTheme.typography.headlineSmall)
                        Text(s.dest.name, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = {
                                NavigationService.stop(context)
                                vm.openPlace(s.dest.poiId, PlaceCard(poiId = s.dest.poiId, name = s.dest.name, lat = s.dest.lat,
                                    lon = s.dest.lon, category = s.dest.category))
                            }, modifier = Modifier.weight(1f)) { Text("Карточка места") }
                            FilledTonalButton(onClick = { NavigationService.stop(context) }, modifier = Modifier.weight(1f)) { Text("Готово") }
                        }
                        return@Column
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(48.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(transportIcon(s.transport), null) }
                        }
                        Spacer(Modifier.width(12.dp))
                        // Время Астаны (как у расписаний 2ГИС), а не часовой пояс телефона.
                        val arrival = LocalTime.now(kz.smartcity.walk.data.ASTANA).plusMinutes(s.etaMin.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
                        Column(Modifier.weight(1f).semantics { contentDescription = "Осталось ${s.etaMin} минут, прибытие в $arrival" }) {
                            Text("${s.etaMin} мин", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Прибытие в $arrival · ${formatDistance(s.remainingM)}", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(s.dest.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { NavigationService.toggleSound(context) }) {
                            Icon(if (s.soundOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                                contentDescription = if (s.soundOn) "Выключить звук" else "Включить звук")
                        }
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                contentDescription = if (expanded) "Свернуть шаги" else "Все шаги поездки")
                        }
                    }
                    AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            if (s.legs.isNotEmpty()) s.legs.forEachIndexed { i, leg -> StepRow(s, i, leg) }
                            else s.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp)) }
                                ?: Text("Пошаговые подсказки — сверху и голосом", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = { vm.stopNavigation() }, modifier = Modifier.weight(1f)) { Text("Завершить") }
                        OutlinedButton(onClick = { onOpen2gis(Target(s.dest.poiId, s.dest.name, s.dest.lat, s.dest.lon)) },
                            modifier = Modifier.weight(1f)) { Text("В 2ГИС") }
                    }
                }
            }
        }
    }
}

/** Пешком / авто: манёвр, расстояние до него. */
@Composable
private fun TurnInstruction(s: NavState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(targetState = maneuverIcon(s), transitionSpec = {
                    (scaleIn(spring(0.6f), initialScale = 0.6f) + fadeIn()) togetherWith fadeOut()
                }, label = "maneuver") { icon -> Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp)) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            s.distanceToManeuverM?.takeIf { !s.arrived }?.let {
                Text(NavigationEngine.roundDistance(it.toDouble()), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Text(s.maneuverText ?: "Двигайтесь по маршруту", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Автобус: шаг поездки с плавной сменой при переходе к следующему этапу. */
@Composable
private fun TransitInstruction(s: NavState) {
    AnimatedContent(targetState = s.legIndex, transitionSpec = {
        (slideInVertically(spring(dampingRatio = 0.8f)) { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
    }, label = "leg") { index ->
        val leg = s.legs.getOrNull(index) ?: return@AnimatedContent
        val next = s.legs.getOrNull(index + 1)
        val left = s.toLegEndM ?: leg.distanceM
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leg.isWalk) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(56.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, Modifier.size(30.dp)) }
                    }
                } else RouteBadge(leg, large = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    val title = when {
                        !leg.isWalk -> "${legTitle(leg).replaceFirstChar { it.uppercase() }} до «${leg.toName ?: "выхода"}»"
                        next != null && !next.isWalk -> "Идите к остановке «${next.fromName ?: leg.toName ?: "посадки"}»"
                        else -> "Идите до «${s.dest.name}»"
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    val meta = if (leg.isWalk) "${formatDistance(left)} · ~${(left / 75).coerceAtLeast(1)} мин пешком"
                    else s.stopsLeft?.let { n ->
                        if (n <= 1) "Выходите на следующей" else "Ещё $n ${stopsWord(n)}" + (s.nextStopName?.let { " · следующая «$it»" } ?: "")
                    } ?: "${leg.stops.size.coerceAtLeast(2) - 1} ${stopsWord(leg.stops.size.coerceAtLeast(2) - 1)} · ${leg.durationS / 60} мин"
                    Text(meta, style = MaterialTheme.typography.bodyMedium)
                }
            }
            // Ближайший рейс — пока человек идёт к первой посадке или только что сел.
            val firstRide = s.legs.indexOfFirst { !it.isWalk }
            val beforeRide = index < firstRide || (index == firstRide && (s.stopsLeft ?: 0) >= leg.stops.size - 2)
            if (beforeRide) departureText(s, System.currentTimeMillis())?.let { text ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    val ride = s.legs.getOrNull(firstRide)
                    Text((ride?.let { legTitle(it).replaceFirstChar { c -> c.uppercase() } + ": " } ?: "") + text,
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Номер маршрута в его цвете (как на табличке остановки); текст — светлый или тёмный по яркости фона. */
@Composable
private fun RouteBadge(leg: TransitLeg, large: Boolean = false) {
    val bg = parseHex(leg.color) ?: MaterialTheme.colorScheme.primary
    val fg = if (bg.luminance() > 0.5f) Color(0xFF101828) else Color.White
    Surface(shape = RoundedCornerShape(if (large) 16.dp else 10.dp), color = bg, contentColor = fg) {
        Row(Modifier.padding(horizontal = if (large) 12.dp else 8.dp, vertical = if (large) 10.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.DirectionsBus, null, Modifier.size(if (large) 24.dp else 16.dp))
            Spacer(Modifier.width(4.dp))
            Text(leg.names.joinToString(", ").ifEmpty { "—" },
                style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/** Лента этапов: пешком N мин › 47 › пешком N мин; текущий — крупнее, пройденные — бледные. */
@Composable
private fun LegStrip(s: NavState) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 3.dp) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            s.legs.forEachIndexed { i, leg ->
                if (i > 0) Text(" › ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val a = if (i < s.legIndex) 0.4f else 1f
                Box(Modifier.alpha(a)) {
                    if (leg.isWalk) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, Modifier.size(18.dp),
                            tint = if (i == s.legIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${(leg.durationS / 60).coerceAtLeast(1)}", style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (i == s.legIndex) FontWeight.Bold else FontWeight.Normal)
                    } else RouteBadge(leg)
                }
            }
        }
    }
}

/** Шаг поездки в списке: что делать, сколько времени, через какие остановки. */
@Composable
private fun StepRow(s: NavState, i: Int, leg: TransitLeg) {
    val current = i == s.legIndex
    val next = s.legs.getOrNull(i + 1)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).alpha(if (i < s.legIndex) 0.5f else 1f), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(64.dp), contentAlignment = Alignment.TopStart) {
            if (leg.isWalk) Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null,
                tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            else RouteBadge(leg)
        }
        Column(Modifier.weight(1f)) {
            val title = when {
                !leg.isWalk -> "${legTitle(leg).replaceFirstChar { it.uppercase() }}: «${leg.fromName ?: "посадка"}» → «${leg.toName ?: "выход"}»"
                next != null && !next.isWalk -> "Пешком до остановки «${next.fromName ?: "посадки"}»"
                i == s.legs.lastIndex -> "Пешком до «${s.dest.name}»"
                else -> "Пешком: пересадка"
            }
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
            val meta = buildString {
                append("${(leg.durationS / 60).coerceAtLeast(1)} мин · ${formatDistance(leg.distanceM)}")
                if (!leg.isWalk) {
                    val n = (leg.stops.size - 1).coerceAtLeast(1)
                    append(" · $n ${stopsWord(n)}")
                    if (leg.waitS > 0) append(" · ожидание ~${leg.waitS / 60} мин")
                }
            }
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!leg.isWalk && leg.stopNames.size > 2) {
                Text(leg.stopNames.drop(1).dropLast(1).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
