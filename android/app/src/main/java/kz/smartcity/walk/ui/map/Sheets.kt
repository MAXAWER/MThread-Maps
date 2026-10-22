package kz.smartcity.walk.ui.map

import androidx.compose.foundation.clickable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kz.smartcity.walk.BuildConfig
import kz.smartcity.walk.data.api.AnswerItem
import kz.smartcity.walk.data.api.Chip
import kz.smartcity.walk.data.api.RouteLeg
import kz.smartcity.walk.data.api.RouteOption
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
import kz.smartcity.walk.ui.fx.BubbleChip
import kz.smartcity.walk.ui.fx.BubbleGhostButton
import kz.smartcity.walk.ui.fx.Fills
import kz.smartcity.walk.ui.fx.LocalHaptics

/** Тело шторки: ленивый список — его прокрутка вложена в жест шторки Material 3. */
@Composable
private fun SheetList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxWidth().navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun SheetHeader(title: String, onClose: () -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
        }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Закрыть") }
    }
}

/** Как понял запрос ИИ (агент) или какие фильтры учёл Jev (запасной режим). */
@Composable
fun UnderstoodRow(s: MapUiState, onRemove: (Chip) -> Unit) {
    val agent = s.filters?.source == "agent"
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
        item {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(if (agent) " ИИ понял:" else " Учтено:", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
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

@Composable
fun ResultsSheet(s: MapUiState, vm: MapViewModel) {
    val answer = s.answer
    SheetList {
        item {
            SheetHeader(s.itemsTitle ?: if (answer == null && s.searching) "ИИ подбирает…" else "Рекомендации",
                onClose = vm::closeSheet, subtitle = s.lastQuery.takeIf { it.isNotBlank() && s.itemsTitle == null })
        }
        if (s.searching && answer == null) item { AgentProgress(s.progress) }
        when {
            answer == null && s.searching -> items(2) { Box(Modifier.padding(horizontal = 16.dp)) { SkeletonCard() } }
            answer == null && s.error != null -> item {
                Text(s.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp))
            }
            answer != null && answer.mode == "empty" -> item { EmptyAnswer(answer.note, answer.relaxed) }
            answer != null -> {
                answer.summary?.let { sum -> item { SummaryCard(sum) } }
                itemsIndexed(answer.items, key = { _, it -> it.poiId }) { i, item ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        RecommendationCard(i + 1, item, answer.dataSource, onClick = { vm.openItem(item) },
                            onRoute = { vm.routeTo(Target(item.poiId, item.name, item.lat, item.lon, item.category, item.openUntil), item.transport) })
                    }
                }
                answer.idea?.let { idea -> item { IdeaCard(idea) } }
                answer.note?.let { note -> item { NoteText(note) } }
                if (answer.relaxed.isNotEmpty()) item { NoteText("Ослабили условия: " + answer.relaxed.joinToString("; ")) }
                if (s.hiddenVisited > 0) item { NoteText("Скрыто мест, где вы уже были: ${s.hiddenVisited}") }
            }
        }
    }
}

/** Шаги агента по мере работы: выполненные — с галочкой, текущий — с выразительным индикатором Material 3. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AgentProgress(steps: List<String>) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.LoadingIndicator(Modifier.size(36.dp))
                Text("  Нейросеть Claude думает и проверяет варианты", style = MaterialTheme.typography.titleSmall)
            }
            androidx.compose.material3.LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            steps.forEachIndexed { i, step ->
                val last = i == steps.lastIndex
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (last) androidx.compose.material3.CircularWavyProgressIndicator(Modifier.size(16.dp))
                    else Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  $step", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text("  $text", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NoteText(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp))

@Composable
private fun EmptyAnswer(note: String?, relaxed: List<String>) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(note ?: "Ничего не нашлось.", style = MaterialTheme.typography.titleMedium)
            if (relaxed.isNotEmpty()) {
                Text("Что мы уже ослабили:", style = MaterialTheme.typography.labelLarge)
                relaxed.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
            Text("Попробуйте уточнить запрос: район, время или вид транспорта.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Карточка рекомендации: всё, что требует раздел 11. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecommendationCard(n: Int, item: AnswerItem, dataSource: String, onClick: () -> Unit, onRoute: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Рекомендация $n: ${item.name}" },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BubbleBadge("$n", size = 30.dp)
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
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(transportIcon(item.transport), contentDescription = transportLabel(item.transport), modifier = Modifier.size(18.dp))
                            Text(" ${item.etaMin} мин", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                OpenStateText(item.openState, item.openUntil)
            }
            RatingText(item.rating, item.reviews)
            item.why?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            // Проверено в интернете: позиции меню с ценами и прочие факты — с источником.
            if (item.evidence.isNotEmpty()) EvidenceBlock(item.evidence)
            item.visitMin?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" ~$it мин на месте", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            item.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    Surface(shape = CircleShape,
        color = if (confirmed) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (confirmed) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun IdeaCard(text: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Filled.Lightbulb, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SourceBadge("model_idea", "2gis")
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** План: этапы, время, время под дождём, пометки, объяснение. */
@Composable
fun PlanSheet(s: MapUiState, vm: MapViewModel, onOpen2gis: (Target) -> Unit) {
    val plan = s.answer?.plan ?: return
    SheetList {
        item {
            val finish = plan.legs.lastOrNull()?.leaveAt?.let { " · закончите к $it" } ?: ""
            SheetHeader("Маршрут по ${plan.legs.size} точкам", onClose = vm::closeSheet, subtitle = "Всего около ${plan.totalMin} мин$finish")
        }
        s.answer?.summary?.let { sm -> item { SummaryCard(sm) } }
        itemsIndexed(plan.legs) { i, leg ->
            val last = i == plan.legs.lastIndex
            Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BubbleBadge("${i + 1}", size = 32.dp, fill = if (last) Fills.accent() else Fills.tertiary(),
                        color = if (last) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onTertiary)
                    if (!last) Box(Modifier.width(3.dp).height(46.dp).background(MaterialTheme.colorScheme.outlineVariant))
                }
                Spacer(Modifier.width(14.dp))
                Card(onClick = { vm.onMarkerClick(leg.to) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(leg.purpose.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            leg.arriveAt?.let { a ->
                                Text(a + (leg.leaveAt?.let { "–$it" } ?: ""), style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(leg.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(transportIcon(leg.transport), contentDescription = transportLabel(leg.transport), modifier = Modifier.size(16.dp))
                            Text("${leg.etaMin} мин в пути" + (leg.distanceM?.let { " · ${formatDistance(it)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall)
                            OpenStateText(leg.openState, leg.openUntil)
                        }
                        leg.visitMin?.let {
                            Text("На месте ~$it мин", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (plan.rainExposureMin > 0) item {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WaterDrop, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(" Под осадками ~${plan.rainExposureMin} мин", color = MaterialTheme.colorScheme.error)
            }
        }
        plan.explanation?.let { e -> item { Text(e, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp)) } }
        items(plan.notes) { NoteText("• $it") }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
    SheetList {
        item { SheetHeader(p.name, onClose = vm::closeSheet, subtitle = p.categoryName ?: p.rubrics.firstOrNull()) }
        if (s.placeLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(p.category, 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        p.address?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        OpenStateText(p.openState, p.openUntil)
                        p.opensAt?.takeIf { p.openState == "closed" }?.let {
                            Text("Откроется: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                RatingText(p.rating, p.reviews)
                p.avgCheck?.let { Text("Средний чек от $it ₸", style = MaterialTheme.typography.bodyMedium) }
                p.branchCount?.takeIf { it > 1 }?.let { Text("Сеть: $it филиалов", style = MaterialTheme.typography.bodyMedium) }
                if (p.attributes.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.attributes.take(16).forEach { AttrPill(it, confirmed = true) }
                    }
                }
                p.phone?.let { Text("Телефон: $it", style = MaterialTheme.typography.bodyMedium) }
                p.website?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                SourceBadge("poi_data", p.dataSource)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BubbleButton(onClick = { vm.routeToPlace(p) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" Проложить маршрут")
                    }
                    if (p.phone != null) {
                        FilledTonalIconButton(onClick = { onCall(p.phone) }, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Filled.Call, contentDescription = "Позвонить")
                        }
                    }
                    FilledTonalIconButton(onClick = { onOpen2gis(Target(p.poiId, p.name, p.lat, p.lon, p.category, p.openUntil)) },
                        modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.Map, contentDescription = "Открыть в 2ГИС") }
                }
            }
        }
    }
}

/**
 * Маршрут: транспорт, все варианты поездки (агент предлагает лучший, выбрать можно любой) и запуск навигатора.
 * Для автобусов — номера в цветах маршрутов, пешие участки, пересадки, остановки и отправление по расписанию.
 */
@Composable
fun RouteSheet(s: MapUiState, vm: MapViewModel, onOpen2gis: (Target) -> Unit) {
    val target = s.routeTarget ?: return
    val haptics = LocalHaptics.current
    val r = s.route
    SheetList {
        item { SheetHeader(target.name, onClose = vm::closeSheet, subtitle = "Маршрут · ${transportLabel(s.routeTransport)}") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(TRANSPORTS) { t ->
                    BubbleChip(transportLabel(t), onClick = { if (t != s.routeTransport) vm.switchTransport(t) },
                        selected = s.routeTransport == t, leading = transportIcon(t))
                }
            }
        }
        when {
            s.routeLoading -> item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                    SkeletonCard()
                }
            }
            r != null -> {
                r.note?.let { n -> item { NoteText(n) } }
                if (r.source == "demo") item { NoteText("Демо-маршрут: без ключа 2ГИС линия условная") }
                val options = r.options
                if (options.isEmpty()) item {
                    Text("${r.totalDurationS / 60} мин · ${formatDistance(r.totalDistanceM)}", style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 20.dp))
                } else {
                    item {
                        Text(if (options.size > 1) "Варианты: ${options.size} — выберите любой" else "Вариант поездки",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    items(options, key = { it.id }) { o ->
                        OptionCard(o, selected = o.id == (s.selectedOptionId ?: options.first().id), transport = s.routeTransport,
                            onSelect = { haptics?.tick(0.5f); vm.selectOption(o.id) })
                    }
                }
            }
            s.error != null -> item { Text(s.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
        if (BuildConfig.DEBUG) item {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Симуляция движения (отладка)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = s.simulateNavigation, onCheckedChange = { haptics?.tick(); vm.setSimulateNavigation(it) })
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BubbleButton(onClick = vm::startNavigation, enabled = r != null && !s.routeLoading, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Поехали")
                }
                BubbleGhostButton(onClick = { onOpen2gis(target) }, modifier = Modifier.weight(1f)) { Text("В 2ГИС") }
            }
        }
    }
}

/** Карточка варианта: время, подпись, этапы цепочкой; у выбранного — остановки и расписание. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionCard(o: RouteOption, selected: Boolean, transport: String, onSelect: () -> Unit) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).animateContentSize()
            .semantics { this.selected = selected },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${(o.totalDurationS + 30) / 60} мин", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                o.label?.let { Label(it, highlight = it == "Быстрее всего") }
                Spacer(Modifier.weight(1f))
                Text(formatDistance(o.totalDistanceM), style = MaterialTheme.typography.bodyMedium)
            }
            if (o.legs.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    o.legs.forEachIndexed { i, leg ->
                        if (i > 0) Text("›", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 2.dp))
                        LegChip(leg)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(transportIcon(transport), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" ${transportLabel(transport)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            departureText(o)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
            val meta = listOfNotNull(o.walkText, o.transfers?.let { if (it == 0) "без пересадок" else "пересадок: $it" })
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            AnimatedVisibility(selected && o.legs.any { it.kind != "walk" }, enter = fadeIn() + expandVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    o.legs.filter { it.kind != "walk" }.forEach { leg ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.DirectionsBus, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(" ${leg.names.joinToString(", ")}: «${leg.fromName ?: "—"}» → «${leg.toName ?: "—"}», " +
                                "${(leg.stops.size - 1).coerceAtLeast(0)} ост., ${(leg.durationS + 30) / 60} мин",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun departureText(o: RouteOption): String? = when {
    o.departure != null -> buildString {
        append("Отправление в ${o.departure}")
        o.departureInMin?.let { append(if (it <= 0) " — сейчас" else " — через $it мин") }
        if (o.nextDepartures.isNotEmpty()) append(", далее ${o.nextDepartures.joinToString(", ")}")
    }
    o.intervalMin != null -> "Ходит каждые ~${o.intervalMin} мин"
    else -> null
}

@Composable
private fun Label(text: String, highlight: Boolean) {
    Surface(shape = CircleShape,
        color = if (highlight) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (highlight) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

/** Этап цепочкой: «🚶 6 мин» или номер маршрута на его фирменном цвете. */
@Composable
private fun LegChip(leg: RouteLeg) {
    val minutes = (leg.durationS + 30) / 60
    if (leg.kind == "walk") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = "пешком", modifier = Modifier.size(18.dp))
            Text("$minutes", style = MaterialTheme.typography.labelLarge)
        }
        return
    }
    val bg = leg.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: MaterialTheme.colorScheme.primary
    val fg = if (bg.luminance() > 0.5f) Color(0xFF101828) else Color.White
    Surface(shape = RoundedCornerShape(10.dp), color = bg, contentColor = fg) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.DirectionsBus, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(" ${leg.names.joinToString(", ")}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/** Факты из интернета: «Чизбургер — 590 ₸» и ссылка-источник (домен). */
@Composable
private fun EvidenceBlock(evidence: List<kz.smartcity.walk.data.api.Evidence>) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Проверено в интернете", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            evidence.forEach { e ->
                Text("• ${e.fact}", style = MaterialTheme.typography.bodyMedium)
                e.source?.let { src ->
                    val host = runCatching { java.net.URI(src).host?.removePrefix("www.") }.getOrNull() ?: src
                    Text("источник: $host", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 10.dp).then(Modifier.clickable { runCatching { uri.openUri(src) } }))
                }
            }
        }
    }
}
