package kz.smartcity.walk.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kz.smartcity.walk.data.api.DeckFiltersDto

/**
 * Фильтры «Для вас». Группы и «только новое» применяет телефон (выбор категорий, история посещений),
 * остальное — сервер по данным 2ГИС. Хранятся между запусками.
 */
@Serializable
data class DeckFilters(
    val groups: Set<String> = emptySet(),   // food, culture, walks, kids, shopping, fun, sport, night
    val openNow: Boolean = false,
    val maxEtaMin: Int? = null,              // 10 / 20 / 40 минут в пути
    val place: String = "any",               // any / indoor / outdoor
    val kids: Boolean = false,
    val budget: String = "any",              // any / cheap / mid
    val minRating: Double? = null,           // 4.0 / 4.5
    val newOnly: Boolean = false,            // только где ещё не был
) {
    val activeCount: Int
        get() = listOf(groups.isNotEmpty(), openNow, maxEtaMin != null, place != "any", kids, budget != "any",
            minRating != null, newOnly).count { it }

    fun toDto() = DeckFiltersDto(openNow = openNow, maxEtaMin = maxEtaMin, place = place, kids = kids, budget = budget,
        minRating = minRating)
}

class DeckFiltersStore(context: Context) {
    private val prefs = context.getSharedPreferences("walk_deck_filters", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _value = MutableStateFlow(
        prefs.getString("filters", null)?.let { runCatching { json.decodeFromString(DeckFilters.serializer(), it) }.getOrNull() }
            ?: DeckFilters())
    val value: StateFlow<DeckFilters> = _value.asStateFlow()

    fun set(f: DeckFilters) {
        _value.value = f
        prefs.edit { putString("filters", json.encodeToString(DeckFilters.serializer(), f)) }
    }
}
