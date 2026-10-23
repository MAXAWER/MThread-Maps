package kz.smartcity.walk.ui.foryou

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Roofing
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Park
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kz.smartcity.walk.data.Categories
import kz.smartcity.walk.data.DeckFilters
import kz.smartcity.walk.data.LatLng
import kz.smartcity.walk.data.repo.DeckItem
import kz.smartcity.walk.ui.components.CategoryBadge
import kz.smartcity.walk.ui.components.OpenStateText
import kz.smartcity.walk.ui.components.RatingText
import kz.smartcity.walk.ui.components.RemoteImage
import kz.smartcity.walk.ui.components.groupColor
import kz.smartcity.walk.ui.map.PlacePreviewMap

/** Обложка карточки: фото места, иначе фрагмент карты вокруг него с меткой; подпись источника фото. */
@Composable
internal fun CardCover(item: DeckItem, modifier: Modifier = Modifier, height: Dp? = null) {
    val c = item.card
    val color = groupColor(c.group)
    val mapCover: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            PlacePreviewMap(LatLng(c.lat, c.lon), pin = color, modifier = Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) { CategoryBadge(c.category, 44.dp) }
        }
    }
    Box(modifier.then(if (height != null) Modifier.height(height) else Modifier)) {
        if (c.photoUrl != null) RemoteImage(c.photoUrl, Modifier.fillMaxSize(), placeholder = mapCover) else mapCover()
        // Затемнение снизу — подписи читаются поверх любого фото.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f))))
        c.photoSource?.let {
            Text("фото: $it", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
    }
}

private data class Quick(val label: String, val icon: ImageVector, val on: (DeckFilters) -> Boolean, val toggle: (DeckFilters) -> DeckFilters)

private val QUICK = listOf(
    Quick("Открыто сейчас", Icons.Filled.Schedule, { it.openNow }, { it.copy(openNow = !it.openNow) }),
    Quick("До 15 минут", Icons.AutoMirrored.Filled.DirectionsWalk, { it.maxEtaMin == 15 }, { it.copy(maxEtaMin = if (it.maxEtaMin == 15) null else 15) }),
    Quick("Под крышей", Icons.Filled.Roofing, { it.place == "indoor" }, { it.copy(place = if (it.place == "indoor") "any" else "indoor") }),
    Quick("На улице", Icons.Filled.Park, { it.place == "outdoor" }, { it.copy(place = if (it.place == "outdoor") "any" else "outdoor") }),
    Quick("Недорого", Icons.Filled.Savings, { it.budget == "cheap" }, { it.copy(budget = if (it.budget == "cheap") "any" else "cheap") }),
    Quick("Рейтинг 4.5+", Icons.Filled.Star, { it.minRating == 4.5 }, { it.copy(minRating = if (it.minRating == 4.5) null else 4.5) }),
    Quick("С детьми", Icons.Filled.ChildCare, { it.kids }, { it.copy(kids = !it.kids) }),
    Quick("Где не был", Icons.Filled.NewReleases, { it.newOnly }, { it.copy(newOnly = !it.newOnly) }),
)

/** Ряд быстрых фильтров над колодой; первая кнопка открывает все фильтры. */
@Composable
internal fun QuickFilters(f: DeckFilters, onChange: (DeckFilters) -> Unit, onAll: () -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        item {
            FilterChip(selected = f.activeCount > 0, onClick = onAll,
                label = { Text(if (f.activeCount > 0) "Фильтры · ${f.activeCount}" else "Все фильтры") },
                leadingIcon = { Icon(Icons.Filled.FilterList, null, Modifier.size(18.dp)) })
        }
        f.groups.forEach { g ->
            item(key = "g$g") {
                FilterChip(selected = true, onClick = { onChange(f.copy(groups = f.groups - g)) },
                    label = { Text(Categories.groupNames[g] ?: g) })
            }
        }
        items(QUICK, key = { it.label }) { q ->
            FilterChip(selected = q.on(f), onClick = { onChange(q.toggle(f)) }, label = { Text(q.label) },
                leadingIcon = { Icon(q.icon, null, Modifier.size(18.dp)) })
        }
    }
}

/** Все фильтры: группы мест, время в пути, крыша, бюджет, рейтинг, дети, только новое. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FiltersSheet(initial: DeckFilters, onApply: (DeckFilters) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Фильтры подборки", style = MaterialTheme.typography.headlineSmall)
            Section("Что показывать") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Categories.groupNames.filterKeys { it != "services" }.forEach { (id, name) ->
                        FilterChip(selected = id in f.groups, onClick = {
                            f = f.copy(groups = if (id in f.groups) f.groups - id else f.groups + id)
                        }, label = { Text(name) }, colors = FilterChipDefaults.filterChipColors())
                    }
                }
                Text(if (f.groups.isEmpty()) "Ничего не выбрано — всё, что подходит вам" else "Только выбранное",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section("Сколько добираться") {
                Choice(listOf(null to "Не важно", 10 to "10 мин", 20 to "20 мин", 40 to "40 мин"), f.maxEtaMin) { f = f.copy(maxEtaMin = it) }
            }
            Section("Где") {
                Choice(listOf("any" to "Не важно", "indoor" to "Под крышей", "outdoor" to "На улице"), f.place) { f = f.copy(place = it) }
            }
            Section("Бюджет (средний чек)") {
                Choice(listOf("any" to "Любой", "cheap" to "До 3000 ₸", "mid" to "До 7000 ₸"), f.budget) { f = f.copy(budget = it) }
            }
            Section("Рейтинг") {
                Choice(listOf(null to "Любой", 4.0 to "4.0+", 4.5 to "4.5+"), f.minRating) { f = f.copy(minRating = it) }
            }
            Toggle("Открыто сейчас и к приходу", f.openNow) { f = f.copy(openNow = it) }
            Toggle("Подходит детям", f.kids) { f = f.copy(kids = it) }
            Toggle("Только где ещё не был", f.newOnly) { f = f.copy(newOnly = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { f = DeckFilters() }, modifier = Modifier.weight(1f)) { Text("Сбросить") }
                Button(onClick = { onApply(f) }, modifier = Modifier.weight(1f)) { Text("Показать") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choice(options: List<Pair<T, String>>, value: T, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> FilterChip(selected = v == value, onClick = { onPick(v) }, label = { Text(label) }) }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** Подсказка после свайпа: что изменилось и «Отменить». */
@Composable
internal fun SwipeBar(last: LastSwipe?, likedCount: Int, onUndo: () -> Unit, onOpenLiked: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(last != null, modifier = modifier, enter = slideInVertically(spring(dampingRatio = 0.7f)) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()) {
        val s = last ?: return@AnimatedVisibility
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (s.like) Icons.Filled.Favorite else Icons.Filled.Info, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (s.like) "В понравившихся · $likedCount" else "Похожее буду показывать реже",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(s.item.card.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (s.like) TextButton(onClick = onOpenLiked) { Text("Открыть", color = MaterialTheme.colorScheme.inversePrimary) }
                TextButton(onClick = onUndo) { Text("Отменить", color = MaterialTheme.colorScheme.inversePrimary) }
            }
        }
    }
}

/**
 * «Понравившиеся»: карточки с фото, рейтингом и «почему»; маршрут, карточка места, «Был здесь», убрать.
 * Можно отметить несколько мест — ИИ составит маршрут по ним с порядком и дорогой между точками.
 */
@Composable
internal fun LikedList(items: List<DeckItem>, vm: ForYouViewModel, bottomSpace: Dp, modifier: Modifier = Modifier) {
    val picked = remember { mutableStateListOf<String>() }
    if (items.isEmpty()) {
        Column(modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Favorite, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
            Text("Пока пусто", style = MaterialTheme.typography.titleLarge)
            Text("Смахните карточку вправо — место появится здесь", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = bottomSpace + 80.dp)) {
            item {
                Text("Отметьте несколько мест — ИИ составит маршрут по ним", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(items, key = { it.card.poiId }) { item ->
                LikedCard(item, picked = item.card.poiId in picked,
                    onPick = { if (item.card.poiId in picked) picked -= item.card.poiId else picked += item.card.poiId },
                    onRoute = { vm.routeTo(item) }, onDetails = { vm.openDetails(item) },
                    onVisited = { vm.markVisited(item) }, onRemove = { picked -= item.card.poiId; vm.unsave(item) })
            }
        }
        AnimatedVisibility(picked.isNotEmpty(), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomSpace + 8.dp),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            Button(onClick = {
                vm.tripFrom(items.filter { it.card.poiId in picked })
                picked.clear()
            }, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
                Icon(Icons.Filled.Route, null)
                Spacer(Modifier.width(8.dp))
                Text(if (picked.size == 1) "Маршрут к месту" else "Маршрут по ${picked.size} местам", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun LikedCard(item: DeckItem, picked: Boolean, onPick: () -> Unit, onRoute: () -> Unit, onDetails: () -> Unit,
                      onVisited: () -> Unit, onRemove: () -> Unit) {
    val c = item.card
    Card(onClick = onDetails, shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = if (picked) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Box {
            CardCover(item, Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)), height = 140.dp)
            Checkbox(checked = picked, onCheckedChange = { onPick() }, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
            if (item.status == "visited") Surface(Modifier.align(Alignment.TopEnd).padding(10.dp), shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(" Был здесь", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.categoryName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                c.etaMin?.let { Text("$it мин", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                OpenStateText(c.openState, c.openUntil)
            }
            RatingText(c.rating, c.reviews)
            c.why?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onRoute) {
                    Icon(Icons.Filled.Directions, null, Modifier.size(18.dp)); Text(" Маршрут")
                }
                if (item.status != "visited") OutlinedButton(onClick = onVisited) { Text("Был здесь") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Убрать из понравившихся") }
            }
        }
    }
}
