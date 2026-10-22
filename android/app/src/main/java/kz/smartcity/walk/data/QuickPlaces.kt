package kz.smartcity.walk.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** Быстрый маршрут: «Дом», «Брат» — имя, иконка и точка. Хранится только на телефоне. */
@Serializable
data class QuickPlace(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val icon: String = "star",
    val address: String? = null,
    val lat: Double,
    val lon: Double,
)

/**
 * Сохранённые места и недавние запросы. Места видит ИИ (чтобы «поехали к брату» работало), а меняются они
 * только руками человека или с его согласия на предложение ИИ «запомнить».
 */
class QuickPlaces(context: Context) {
    private val prefs = context.getSharedPreferences("walk_places", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<QuickPlace>> = _items.asStateFlow()
    private val _recent = MutableStateFlow(loadRecent())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    private fun load(): List<QuickPlace> = prefs.getString("quick", null)
        ?.let { runCatching { json.decodeFromString(ListSerializer(QuickPlace.serializer()), it) }.getOrNull() } ?: emptyList()

    private fun loadRecent(): List<String> = prefs.getString("recent", null)
        ?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()

    private fun save(list: List<QuickPlace>) {
        _items.value = list
        prefs.edit { putString("quick", json.encodeToString(ListSerializer(QuickPlace.serializer()), list)) }
    }

    /** Новое место или правка существующего (по id); не больше 12 мест. */
    fun upsert(p: QuickPlace) {
        val list = _items.value.toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list += p
        save(list.take(12))
    }

    fun remove(id: String) = save(_items.value.filterNot { it.id == id })

    /** Недавние запросы (до 8, без повторов) — для подсказок на главном экране. */
    fun addRecent(query: String) {
        val q = query.trim().take(80)
        if (q.length < 3) return
        val list = (listOf(q) + _recent.value.filterNot { it.equals(q, ignoreCase = true) }).take(8)
        _recent.value = list
        prefs.edit { putString("recent", json.encodeToString(ListSerializer(String.serializer()), list)) }
    }

    fun clear() {
        prefs.edit { clear() }
        _items.value = emptyList()
        _recent.value = emptyList()
    }

    companion object {
        val ICONS = listOf("home", "work", "family", "friend", "school", "sport", "food", "shop", "star", "heart")
    }
}
