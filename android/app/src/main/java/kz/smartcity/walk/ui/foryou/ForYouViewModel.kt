package kz.smartcity.walk.ui.foryou

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kz.smartcity.walk.AppGraph
import kz.smartcity.walk.AppIntent
import kz.smartcity.walk.data.LatLng
import kz.smartcity.walk.data.api.AnswerItem
import kz.smartcity.walk.data.api.ApiException
import kz.smartcity.walk.data.api.PlaceCard
import kz.smartcity.walk.data.db.SavedPlace
import kz.smartcity.walk.data.repo.DeckItem

/** Открыта ли вкладка: число поисков и выбранные вручную интересы. */
data class ForYouGate(val searches: Int, val interests: Set<String>) {
    val unlocked get() = searches >= ForYouViewModel.UNLOCK_SEARCHES || interests.size >= ForYouViewModel.MIN_INTERESTS
}

class ForYouViewModel(private val g: AppGraph) : ViewModel() {
    val gate = MutableStateFlow(ForYouGate(g.settings.searchCount, g.settings.interests))
    val cards: StateFlow<List<DeckItem>> = g.deck.cards.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val saved: StateFlow<List<SavedPlace>> = g.deck.saved.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val lastLiked = MutableStateFlow<DeckItem?>(null)

    init {
        if (gate.value.unlocked && g.deck.isStale(System.currentTimeMillis())) refresh(force = false)
    }

    fun reloadGate() {
        gate.value = ForYouGate(g.settings.searchCount, g.settings.interests)
    }

    /** Выбор на полотне пузырьков: задаёт стартовые веса категорий и сразу пересобирает колоду. */
    fun saveInterests(chosen: Set<String>) {
        val before = g.settings.interests
        g.settings.interests = chosen
        reloadGate()
        viewModelScope.launch {
            g.profile.seedInterests(added = chosen - before, removed = before - chosen, nowMs = System.currentTimeMillis())
            if (gate.value.unlocked) refresh(force = true)
        }
    }

    fun refresh(force: Boolean) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            message.value = null
            try {
                val at = g.location.current() ?: LatLng(51.128, 71.430)
                when (g.deck.refresh(at, force)) {
                    null -> message.value = "На демо-ключе 2ГИС колода обновляется не чаще 3 раз в сутки"
                    0 -> message.value = "Рядом ничего не нашлось — попробуйте позже"
                    else -> Unit
                }
            } catch (e: ApiException) {
                message.value = e.message
            } finally {
                busy.value = false
            }
        }
    }

    fun swipe(item: DeckItem, like: Boolean) {
        viewModelScope.launch { g.deck.swipe(item, like) }
        if (like) lastLiked.value = item
    }

    fun clearLiked() {
        lastLiked.value = null
    }

    fun openDetails(item: DeckItem) {
        val c = item.card
        g.intents.tryEmit(AppIntent.OpenPlace(PlaceCard(poiId = c.poiId, name = c.name, lat = c.lat, lon = c.lon, address = c.address,
            category = c.category, categoryName = c.categoryName, rating = c.rating, reviews = c.reviews, openState = c.openState,
            openUntil = c.openUntil, dataSource = item.dataSource)))
    }

    fun routeTo(item: DeckItem) {
        val c = item.card
        lastLiked.value = null
        g.intents.tryEmit(AppIntent.RouteTo(AnswerItem(poiId = c.poiId, name = c.name, category = c.category, lat = c.lat, lon = c.lon,
            transport = c.transport, openState = c.openState, openUntil = c.openUntil), startNavigation = false))
    }

    fun routeToSaved(p: SavedPlace) {
        g.intents.tryEmit(AppIntent.RouteTo(AnswerItem(poiId = p.poiId, name = p.name, category = p.category, lat = p.lat, lon = p.lon,
            transport = g.settings.defaultTransport), startNavigation = false))
    }

    companion object {
        /** «Несколько десятков поисковых запросов». */
        const val UNLOCK_SEARCHES = 30
        const val MIN_INTERESTS = 10
    }

    class Factory(private val g: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ForYouViewModel(g) as T
    }
}
