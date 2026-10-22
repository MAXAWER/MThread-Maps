package kz.smartcity.walk.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kz.smartcity.walk.data.QuickPlace
import kz.smartcity.walk.data.QuickPlaces
import kz.smartcity.walk.data.api.SuggestItem
import kz.smartcity.walk.ui.components.QUICK_ICON_LABELS
import kz.smartcity.walk.ui.components.quickIcon

/** Список быстрых маршрутов в профиле: нажатие — правка, корзина — удалить, «Добавить» — новый. */
@Composable
fun ColumnScope.QuickPlacesList(items: List<QuickPlace>, onEdit: (QuickPlace?) -> Unit, onDelete: (QuickPlace) -> Unit) {
    Text("Кнопки на карте и в поиске: одно нажатие — маршрут. ИИ тоже понимает «поехали к брату».",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    items.forEach { p ->
        Row(Modifier.fillMaxWidth().clickable { onEdit(p) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(quickIcon(p.icon), null) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                p.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            IconButton(onClick = { onDelete(p) }) { Icon(Icons.Filled.Delete, contentDescription = "Удалить «${p.name}»") }
        }
    }
    FilledTonalButton(onClick = { onEdit(null) }) {
        Icon(Icons.Filled.Add, null)
        Spacer(Modifier.width(8.dp))
        Text("Добавить быстрый маршрут")
    }
}

/**
 * Редактор быстрого маршрута: название, иконка и адрес (поиск 2ГИС или «моё место»). Сохранить можно,
 * когда есть название и точка.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickPlaceDialog(
    initial: QuickPlace?,
    suggest: suspend (String) -> List<SuggestItem>,
    here: suspend () -> Pair<Double, Double>?,
    onSave: (QuickPlace) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var icon by remember { mutableStateOf(initial?.icon ?: "home") }
    var query by remember { mutableStateOf(initial?.address ?: "") }
    var point by remember { mutableStateOf(initial?.let { Triple(it.address, it.lat, it.lon) }) }
    var results by remember { mutableStateOf<List<SuggestItem>>(emptyList()) }
    var locating by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.trim().length < 3 || query == point?.first) { results = emptyList(); return@LaunchedEffect }
        delay(300)
        results = runCatching { suggest(query.trim()) }.getOrDefault(emptyList()).filter { it.lat != null && it.lon != null }.take(5)
    }
    LaunchedEffect(locating) {
        if (!locating) return@LaunchedEffect
        here()?.let { (lat, lon) -> point = Triple("Моё местоположение", lat, lon); query = "Моё местоположение" }
        locating = false
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(20.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (initial == null) "Новый быстрый маршрут" else "Быстрый маршрут", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(name, { name = it.take(30) }, label = { Text("Название") }, singleLine = true,
                    placeholder = { Text("Дом, Брат, Спортзал…") }, modifier = Modifier.fillMaxWidth())
                Text("Иконка", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickPlaces.ICONS.forEach { key ->
                        val on = key == icon
                        Surface(onClick = { icon = key }, shape = CircleShape,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp).semantics { selected = on; contentDescription = QUICK_ICON_LABELS[key] ?: key }) {
                            Box(contentAlignment = Alignment.Center) { Icon(quickIcon(key), null) }
                        }
                    }
                }
                OutlinedTextField(query, { query = it; if (it != point?.first) point = null }, label = { Text("Адрес или место") },
                    singleLine = true, placeholder = { Text("Кенесары 40, ЖК Хайвил…") }, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { locating = true }) { Icon(Icons.Filled.MyLocation, contentDescription = "Моё местоположение") }
                    })
                results.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable {
                        val label = listOfNotNull(r.title, r.subtitle?.takeIf { it.isNotBlank() && it != r.title }).joinToString(", ")
                        point = Triple(label, r.lat!!, r.lon!!); query = label; results = emptyList()
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(r.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            r.subtitle?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (point == null && query.isNotBlank() && results.isEmpty()) {
                    Text("Выберите адрес из подсказок", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    val p = point
                    Button(enabled = name.isNotBlank() && p != null, onClick = {
                        if (p != null) onSave(QuickPlace(id = initial?.id ?: java.util.UUID.randomUUID().toString(), name = name.trim(),
                            icon = icon, address = p.first, lat = p.second, lon = p.third))
                    }) { Text("Сохранить") }
                }
            }
        }
    }
}
