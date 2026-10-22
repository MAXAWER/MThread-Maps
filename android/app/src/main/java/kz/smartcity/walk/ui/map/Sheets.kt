package kz.smartcity.walk.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kz.smartcity.walk.BuildConfig
import kz.smartcity.walk.data.api.AnswerItem
import kz.smartcity.walk.data.api.Chip
import kz.smartcity.walk.ui.components.CategoryBadge
import kz.smartcity.walk.ui.components.OpenStateText
import kz.smartcity.walk.ui.components.RatingText
import kz.smartcity.walk.ui.components.SkeletonCard
import kz.smartcity.walk.ui.components.SourceBadge
import kz.smartcity.walk.ui.components.TRANSPORTS
import kz.smartcity.walk.ui.components.attrLabel
import kz.smartcity.walk.ui.components.formatDistance
import kz.smartcity.walk.ui.components.transportIcon
import kz.smartcity.walk.ui.components.transportLabel
import kz.smartcity.walk.ui.fx.BubbleBadge
import kz.smartcity.walk.ui.fx.BubbleButton
import kz.smartcity.walk.ui.fx.BubbleCard
import kz.smartcity.walk.ui.fx.BubbleChip
import kz.smartcity.walk.ui.fx.BubbleGhostButton
import kz.smartcity.walk.ui.fx.BubbleIconButton
import kz.smartcity.walk.ui.fx.BubbleShapes
import kz.smartcity.walk.ui.fx.Depth
import kz.smartcity.walk.ui.fx.Fills
import kz.smartcity.walk.ui.fx.LocalHaptics
import kz.smartcity.walk.ui.fx.PopIn
import kz.smartcity.walk.ui.fx.bubble
import kz.smartcity.walk.ui.fx.rememberFrictionFling

@Composable
private fun SheetHeader(title: String, onClose: () -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        BubbleIconButton(onClick = onClose, icon = Icons.Filled.Close, contentDescription = "Закрыть", size = 44.dp, depth = Depth.LOW)
    }
}

/** Прокручиваемое тело шторки: инерция с трением. */
@Composable
private fun SheetBody(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState(), flingBehavior = rememberFrictionFling(1.3f)).padding(bottom = 16.dp),
    ) { content() }
}

/** «Jev учёл: …» — пузыри-чипы появляются по событию filters, пока идёт поиск. */
@Composable
fun JevChipsRow(s: MapUiState, onRemove: (Chip) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .bubble(BubbleShapes.Pill, Fills.glass(0.92f), Depth.FLOAT, glow = MaterialTheme.colorScheme.primary)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (s.filters?.source == "demo" || s.dataSource == "demo") "Фильтры:" else "Jev учёл:",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            flingBehavior = rememberFrictionFling(1.2f)) {
            items(s.chips, key = { it.key }) { chip ->
                BubbleChip(
                    chip.label,
                    onClick = { if (chip.removable) onRemove(chip) },
                    selected = chip.kind == "hard",
                    accent = if (chip.kind == "warning") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trailing = if (chip.removable) Icons.Filled.Close else null,
                    contentDescription = "Убрать фильтр ${chip.label}",
                )
            }
        }
    }
}

@Composable
fun ResultsSheet(s: MapUiState, vm: MapViewModel) {
    val answer = s.answer
    SheetBody {
        SheetHeader(s.itemsTitle ?: if (s.searching && answer == null) "Подбираем места…" else "Рекомендации", onClose = vm::closeSheet,
            subtitle = s.lastQuery.takeIf { it.isNotBlank() && s.itemsTitle == null })
        if (s.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp))
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                answer == null && s.searching -> repeat(2) { SkeletonCard() }
                answer == null && s.error != null -> Text(s.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
                answer != null && answer.mode == "empty" -> EmptyAnswer(answer.note, answer.relaxed)
                answer != null -> {
                    answer.items.forEachIndexed { i, item ->
                        PopIn(i) {
                            RecommendationCard(i + 1, item, answer.dataSource, onClick = { vm.openItem(item) },
                                onRoute = { vm.routeTo(Target(item.poiId, item.name, item.lat, item.lon, item.category, item.openUntil), item.transport) })
                        }
                    }
                    answer.idea?.let { PopIn(answer.items.size) { IdeaCard(it) } }
                    answer.note?.let { NoteText(it) }
                    if (answer.relaxed.isNotEmpty()) NoteText("Ослабили условия: " + answer.relaxed.joinToString("; "))
                    if (s.hiddenVisited > 0) NoteText("Скрыто мест, где вы уже были: ${s.hiddenVisited}")
                }
            }
        }
    }
}

@Composable
private fun NoteText(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))

@Composable
private fun EmptyAnswer(note: String?, relaxed: List<String>) {
    BubbleCard(Modifier.fillMaxWidth(), fill = Fills.tint(MaterialTheme.colorScheme.secondary), depth = Depth.FLOOR) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(note ?: "Ничего не нашлось.", style = MaterialTheme.typography.titleMedium)
            if (relaxed.isNotEmpty()) {
                Text("Что мы уже ослабили:", style = MaterialTheme.typography.labelLarge)
                relaxed.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
            Text("Попробуйте другое время, район или вид транспорта.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Карточка рекомендации: всё, что требует раздел 11, в пузырьковом стиле. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecommendationCard(n: Int, item: AnswerItem, dataSource: String, onClick: () -> Unit, onRoute: () -> Unit) {
    BubbleCard(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Рекомендация $n: ${item.name}" },
        onClick = onClick,
        fill = Fills.surface(),
        depth = Depth.FLOAT,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BubbleBadge("$n", size = 32.dp)
                Spacer(Modifier.width(10.dp))
                CategoryBadge(item.category, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.categoryName ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (item.etaMin != null) {
                    Row(Modifier.bubble(BubbleShapes.Pill, Fills.tint(MaterialTheme.colorScheme.primary), Depth.FLOOR)
                        .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(transportIcon(item.transport), contentDescription = transportLabel(item.transport), modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text(" ${item.etaMin} мин", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
                OpenStateText(item.openState, item.openUntil)
            }
            RatingText(item.rating, item.reviews)
            item.why?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            item.visitMin?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" ~$it мин на месте", style = MaterialTheme.typography.bodySmall)
                }
            }
            item.action?.let {
                Text("→ $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            if (item.confirmed.isNotEmpty() || item.unconfirmed.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.confirmed.forEach { AttrPill("${attrLabel(it)} ✓", confirmed = true) }
                    item.unconfirmed.forEach { AttrPill("${attrLabel(it)} — не подтверждено", confirmed = false) }
                }
            }
            item.notes.forEach { NoteText(it) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceBadge(item.source, dataSource)
                Spacer(Modifier.weight(1f))
                BubbleButton(onClick = onRoute) {
                    Icon(Icons.Filled.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Маршрут")
                }
            }
        }
    }
}

@Composable
private fun AttrPill(text: String, confirmed: Boolean) {
    val color = if (confirmed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(text, style = MaterialTheme.typography.labelMedium, color = color,
        modifier = Modifier.bubble(BubbleShapes.Pill, Fills.tint(color), Depth.FLOOR)
            .padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun IdeaCard(text: String) {
    BubbleCard(Modifier.fillMaxWidth(), fill = Fills.accent(), depth = Depth.LOW, glow = MaterialTheme.colorScheme.secondary) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondary)
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SourceBadge("model_idea", "2gis")
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondary)
            }
        }
    }
}

/** План: шкала этапов из пузырей на градиентной линии, время, время под дождём, пометки, объяснение. */
@Composable
fun PlanSheet(s: MapUiState, vm: MapViewModel, onOpen2gis: (Target) -> Unit) {
    val plan = s.answer?.plan ?: return
    SheetBody {
        SheetHeader("План маршрута", onClose = vm::closeSheet, subtitle = "Всего около ${plan.totalMin} мин")
        Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            plan.legs.forEachIndexed { i, leg ->
                val last = i == plan.legs.lastIndex
                PopIn(i) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            BubbleBadge("${i + 1}", size = 34.dp, fill = if (last) Fills.accent() else Fills.tertiary(),
                                color = if (last) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onTertiary)
                            if (!last) Box(Modifier.width(4.dp).height(46.dp).background(MaterialTheme.colorScheme.tertiary))
                        }
                        Spacer(Modifier.width(14.dp))
                        BubbleCard(Modifier.weight(1f).padding(bottom = 10.dp), onClick = { vm.onMarkerClick(leg.to) }, depth = Depth.LOW) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(leg.purpose.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(leg.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(transportIcon(leg.transport), contentDescription = transportLabel(leg.transport), modifier = Modifier.size(16.dp))
                                    Text("${leg.etaMin} мин", style = MaterialTheme.typography.bodySmall)
                                    OpenStateText(leg.openState, leg.openUntil)
                                }
                            }
                        }
                    }
                }
            }
            if (plan.rainExposureMin > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Icon(Icons.Filled.WaterDrop, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(" Под осадками ~${plan.rainExposureMin} мин", color = MaterialTheme.colorScheme.error)
                }
            }
            plan.explanation?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp)) }
            plan.notes.forEach { NoteText("• $it") }
            Spacer(Modifier.height(8.dp))
            SourceBadge("poi_data", s.dataSource)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BubbleButton(onClick = vm::startPlan, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Начать")
                }
                BubbleGhostButton(onClick = {
                    val l = plan.legs.last()
                    onOpen2gis(Target(l.to, l.name, l.lat, l.lon))
                }, modifier = Modifier.weight(1f)) { Text("В 2ГИС") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceSheet(s: MapUiState, vm: MapViewModel, onCall: (String) -> Unit, onOpen2gis: (Target) -> Unit) {
    val p = s.place ?: return
    SheetBody {
        SheetHeader(p.name, onClose = vm::closeSheet, subtitle = p.categoryName ?: p.rubrics.firstOrNull())
        if (s.placeLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(p.category, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    p.address?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    OpenStateText(p.openState, p.openUntil)
                    p.opensAt?.takeIf { p.openState == "closed" }?.let { Text("Откроется: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
            RatingText(p.rating, p.reviews)
            if (p.reviews != null) NoteText("По отзывам 2ГИС отдаёт только итоговую оценку и число отзывов")
            p.avgCheck?.let { Text("Средний чек от $it ₸", style = MaterialTheme.typography.bodySmall) }
            p.branchCount?.takeIf { it > 1 }?.let { Text("Сеть: $it филиалов", style = MaterialTheme.typography.bodySmall) }
            if (p.attributes.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.attributes.take(16).forEach { AttrPill(it, confirmed = true) }
                }
            }
            p.phone?.let { Text("Телефон: $it", style = MaterialTheme.typography.bodySmall) }
            p.website?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            SourceBadge("poi_data", p.dataSource)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                BubbleButton(onClick = { vm.routeToPlace(p) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Маршрут")
                }
                if (p.phone != null) {
                    BubbleIconButton(onClick = { onCall(p.phone) }, icon = Icons.Filled.Call, contentDescription = "Позвонить",
                        size = 52.dp, tint = MaterialTheme.colorScheme.primary)
                }
                BubbleIconButton(onClick = { onOpen2gis(Target(p.poiId, p.name, p.lat, p.lon, p.category, p.openUntil)) },
                    icon = Icons.Filled.Map, contentDescription = "Открыть в 2ГИС", size = 52.dp, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Маршрут: переключатель транспорта, время и расстояние, запуск пошаговой навигации. */
@Composable
fun RouteSheet(s: MapUiState, vm: MapViewModel, onOpen2gis: (Target) -> Unit) {
    val target = s.routeTarget ?: return
    val haptics = LocalHaptics.current
    SheetBody {
        SheetHeader("Маршрут: ${target.name}", onClose = vm::closeSheet)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            flingBehavior = rememberFrictionFling(1.2f)) {
            items(TRANSPORTS) { t ->
                BubbleChip(transportLabel(t), onClick = { vm.switchTransport(t) }, selected = s.routeTransport == t, leading = transportIcon(t))
            }
        }
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val r = s.route
            when {
                s.routeLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                r != null -> {
                    Text("${r.totalDurationS / 60} мин · ${formatDistance(r.totalDistanceM)}", style = MaterialTheme.typography.headlineSmall)
                    r.note?.let { NoteText(it) }
                    if (r.source == "demo") NoteText("Демо-маршрут: без ключа 2ГИС линия условная")
                    r.maneuvers.drop(1).take(3).forEach { m ->
                        Text("• ${m.text}" + if (m.distanceM > 0) " (${formatDistance(m.distanceM)})" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                s.error != null -> Text(s.error, color = MaterialTheme.colorScheme.error)
            }
            if (BuildConfig.DEBUG) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Симуляция движения (отладка)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = s.simulateNavigation, onCheckedChange = { haptics?.tick(); vm.setSimulateNavigation(it) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BubbleButton(onClick = vm::startNavigation, enabled = r != null && !s.routeLoading, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Начать")
                }
                BubbleGhostButton(onClick = { onOpen2gis(target) }, modifier = Modifier.weight(1f)) { Text("В 2ГИС") }
            }
        }
    }
}
