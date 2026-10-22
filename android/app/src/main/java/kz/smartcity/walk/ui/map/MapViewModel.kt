package kz.smartcity.walk.ui.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kz.smartcity.walk.AppGraph
import kz.smartcity.walk.AppIntent
import kz.smartcity.walk.data.LatLng
import kz.smartcity.walk.data.api.Answer
import kz.smartcity.walk.data.api.AnswerItem
import kz.smartcity.walk.data.api.ApiException
import kz.smartcity.walk.data.api.Candidate
import kz.smartcity.walk.data.api.Chip
import kz.smartcity.walk.data.api.JevFilters
import kz.smartcity.walk.data.api.LatLonDto
import kz.smartcity.walk.data.api.PlaceCard
import kz.smartcity.walk.data.api.ProfileEvent
import kz.smartcity.walk.data.api.RouteRequest
import kz.smartcity.walk.data.api.RouteResponse
import kz.smartcity.walk.data.api.SearchEvent
import kz.smartcity.walk.data.api.SearchRequest
import kz.smartcity.walk.data.api.SuggestItem
import kz.smartcity.walk.data.iso
import kz.smartcity.walk.map.MapMarker
import kz.smartcity.walk.nav.NavDestination
import kz.smartcity.walk.nav.NavigationService
import kz.smartcity.walk.walk.WalkDetector
import kz.smartcity.walk.walk.WalkService
import kz.smartcity.walk.work.Workers

enum class SheetMode { NONE, RESULTS, PLAN, PLACE, ROUTE }

data class Target(
    val poiId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val category: String? = null,
    val openUntil: String? = null,
)

data class MapUiState(
    val query: String = "",
    val suggestions: List<SuggestItem> = emptyList(),
    val searching: Boolean = false,
    val chips: List<Chip> = emptyList(),
    val filters: JevFilters? = null,
    val transport: String = "walking",
    val candidates: List<Candidate> = emptyList(),
    val answer: Answer? = null,
    val error: String? = null,
    val sheet: SheetMode = SheetMode.NONE,
    val place: PlaceCard? = null,
    val placeLoading: Boolean = false,
    val route: RouteResponse? = null,
    val routeTarget: Target? = null,
    val routeLoading: Boolean = false,
    val routeTransport: String = "walking",
    val myLocation: LatLng? = null,
    val usingCityCenter: Boolean = false,
    val dataSource: String = "2gis",
    val lastQuery: String = "",
    val itemsTitle: String? = null,
    val hiddenVisited: Int = 0,
    val simulateNavigation: Boolean = false,
)

/** Центр Астаны — если геолокации нет. */
private val CITY_CENTER = LatLng(51.128, 71.430)

class MapViewModel(private val g: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(MapUiState(transport = g.settings.defaultTransport, routeTransport = g.settings.defaultTransport))
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var suggestJob: Job? = null
    private var routeJob: Job? = null
    private var pendingNavigation = false

    /** Итог поиска для портрета: выбранное место, маршрут, закрытие без выбора или переформулировка. */
    private data class SearchCtx(val query: String, val filters: JevFilters?, var shown: List<AnswerItem>,
                                 var selected: AnswerItem? = null, var weather: String? = null, var closed: Boolean = false)
    private var ctx: SearchCtx? = null

    init {
        viewModelScope.launch { refreshLocation() }
        viewModelScope.launch {
            g.intents.collect { intent ->
                g.intents.resetReplayCache()
                handleIntent(intent)
            }
        }
    }

    suspend fun refreshLocation(): LatLng {
        val loc = g.location.current()
        _state.update { it.copy(myLocation = loc ?: it.myLocation, usingCityCenter = loc == null && it.myLocation == null) }
        return loc ?: _state.value.myLocation ?: CITY_CENTER
    }

    fun setSimulateNavigation(on: Boolean) = _state.update { it.copy(simulateNavigation = on) }

    fun onQueryChange(text: String) {
        _state.update { it.copy(query = text) }
        suggestJob?.cancel()
        if (text.trim().length < 2) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(300)
            val at = (_state.value.myLocation ?: CITY_CENTER).rounded()
            try {
                val r = g.api.suggest(text.trim(), at.lat, at.lon)
                _state.update { it.copy(suggestions = r.items.take(6)) }
            } catch (_: ApiException) {
                _state.update { it.copy(suggestions = emptyList()) }
            }
        }
    }

    fun clearSuggestions() = _state.update { it.copy(suggestions = emptyList()) }

    /** Выбор подсказки открывает карточку места; отправка текста запускает ИИ-поиск. */
    fun pickSuggestion(s: SuggestItem) {
        clearSuggestions()
        if (s.type == "rubric" || s.lat == null || s.lon == null) {
            search(s.title)
            return
        }
        _state.update { it.copy(query = s.title) }
        val id = s.id
        if (id != null && s.type == "branch") openPlace(id, preview = PlaceCard(poiId = id, name = s.title, lat = s.lat, lon = s.lon, address = s.subtitle))
        else routeTo(Target(id ?: "point", s.title, s.lat, s.lon))
    }

    fun search(text: String, filters: JevFilters? = null) {
        val query = text.trim()
        if (query.isEmpty()) return
        finishSearch(if (filters == null) "reformulated" else null)
        searchJob?.cancel()
        suggestJob?.cancel()
        _state.update {
            it.copy(query = query, lastQuery = query, suggestions = emptyList(), searching = true, chips = emptyList(),
                filters = filters, candidates = emptyList(), answer = null, error = null, sheet = SheetMode.RESULTS,
                place = null, route = null, routeTarget = null, itemsTitle = null, hiddenVisited = 0)
        }
        val newCtx = SearchCtx(query, filters, emptyList())
        ctx = newCtx
        searchJob = viewModelScope.launch {
            val at = refreshLocation().rounded()
            val nowMs = System.currentTimeMillis()
            val walk = WalkService.statusFlow.value
            val req = SearchRequest(
                query = query, lat = at.lat, lon = at.lon, localTime = g.settings.now().iso(),
                transportHint = if (walk.state == WalkDetector.State.WALKING) walk.transport else g.settings.defaultTransport,
                walkActive = walk.state == WalkDetector.State.WALKING,
                profileSummary = g.profile.summary(nowMs), profileAffinity = g.profile.affinity(), filters = filters,
            )
            g.api.search(req).collect { ev ->
                when (ev) {
                    is SearchEvent.Filters -> _state.update {
                        it.copy(chips = ev.data.chips, filters = ev.data.filters, transport = ev.data.transport,
                            routeTransport = ev.data.transport)
                    }
                    is SearchEvent.Candidates -> _state.update { it.copy(candidates = ev.data.items, dataSource = ev.data.dataSource) }
                    is SearchEvent.AnswerEv -> onAnswer(ev.data, newCtx)
                    is SearchEvent.Error -> _state.update { it.copy(error = ev.data.message, searching = false) }
                    is SearchEvent.Done -> {
                        newCtx.weather = ev.data.weather
                        _state.update { it.copy(searching = false) }
                    }
                }
            }
            _state.update { it.copy(searching = false) }
        }
    }

    private suspend fun onAnswer(answer: Answer, c: SearchCtx) {
        var a = answer
        var hidden = 0
        // «Где не был»: история посещений не покидает телефон, поэтому фильтруем здесь.
        if (_state.value.filters?.novelty == "not_visited") {
            val visited = g.history.visitedIds(90, System.currentTimeMillis())
            val kept = a.items.filter { it.poiId !in visited }
            hidden = a.items.size - kept.size
            if (kept.isNotEmpty()) a = a.copy(items = kept)
        }
        c.shown = a.items
        val sheet = when (a.mode) {
            "plan" -> SheetMode.PLAN
            else -> SheetMode.RESULTS
        }
        _state.update { it.copy(answer = a, dataSource = a.dataSource, sheet = sheet, hiddenVisited = hidden) }
        if (a.mode == "place") a.items.firstOrNull()?.let { openItem(it) }
    }

    /** Убрать фильтр: новый поиск с отредактированными фильтрами, Jev повторно не вызывается. */
    fun removeChip(chip: Chip) {
        val f = _state.value.filters ?: return
        val edited = when {
            chip.key.startsWith("hard:") -> f.copy(hard = f.hard - chip.key.removePrefix("hard:"))
            chip.key.startsWith("soft:") -> f.copy(soft = f.soft - chip.key.removePrefix("soft:"))
            chip.key == "category_primary" -> f.copy(categoryPrimary = null, rawTextSearch = true)
            chip.key == "category_secondary" -> f.copy(categorySecondary = null)
            chip.key == "purchase_item" -> f.copy(purchaseItem = null)
            chip.key == "transport" -> f.copy(transport = null)
            chip.key == "max_travel_min" -> f.copy(maxTravelMin = null)
            chip.key == "time_budget_min" -> f.copy(timeBudgetMin = null, needsReturn = false)
            chip.key == "open" -> f.copy(open = null)
            chip.key == "company" -> f.copy(company = null, kidsAge = null)
            chip.key == "group_size" -> f.copy(groupSize = null)
            chip.key == "price" -> f.copy(price = null, priceMaxTenge = null)
            chip.key == "ambience" -> f.copy(ambience = null)
            chip.key == "min_rating" -> f.copy(minRating = null)
            chip.key == "novelty" -> f.copy(novelty = null)
            else -> return
        }
        search(_state.value.lastQuery, edited)
    }

    fun openItem(item: AnswerItem) {
        ctx?.let { if (!it.closed) it.selected = item }
        openPlace(item.poiId, preview = PlaceCard(poiId = item.poiId, name = item.name, lat = item.lat, lon = item.lon,
            address = item.address, category = item.category, categoryName = item.categoryName, rating = item.rating,
            reviews = item.reviews, openState = item.openState, openUntil = item.openUntil, dataSource = _state.value.dataSource))
    }

    fun openPlace(poiId: String, preview: PlaceCard? = null) {
        _state.update { it.copy(sheet = SheetMode.PLACE, place = preview ?: it.place, placeLoading = true) }
        if (poiId.startsWith("geo:") || poiId == "point") {
            _state.update { it.copy(placeLoading = false) }
            return
        }
        viewModelScope.launch {
            try {
                val card = g.api.place(poiId)
                _state.update { it.copy(place = card, placeLoading = false) }
            } catch (_: ApiException) {
                _state.update { it.copy(placeLoading = false) }
            }
        }
    }

    fun onMarkerClick(id: String) {
        val s = _state.value
        s.answer?.items?.firstOrNull { it.poiId == id }?.let { openItem(it); return }
        s.candidates.firstOrNull { it.poiId == id }?.let { c ->
            openPlace(c.poiId, PlaceCard(poiId = c.poiId, name = c.name, lat = c.lat, lon = c.lon, address = c.address,
                category = c.category, categoryName = c.categoryName, rating = c.rating, reviews = c.reviews,
                openState = c.openState, openUntil = c.openUntil, dataSource = s.dataSource))
            return
        }
        s.answer?.plan?.legs?.firstOrNull { it.to == id }?.let { leg ->
            openPlace(leg.to, PlaceCard(poiId = leg.to, name = leg.name, lat = leg.lat, lon = leg.lon, openState = leg.openState,
                openUntil = leg.openUntil, dataSource = s.dataSource))
        }
    }

    fun onLongPress(p: LatLng) = routeTo(Target("point", "Точка на карте", p.lat, p.lon))

    fun routeToPlace(card: PlaceCard) = routeTo(Target(card.poiId, card.name, card.lat, card.lon, card.category, card.openUntil))

    fun routeTo(target: Target, transport: String = _state.value.routeTransport, via: List<LatLng> = emptyList()) {
        routeJob?.cancel()
        _state.update { it.copy(sheet = SheetMode.ROUTE, routeTarget = target, routeLoading = true, route = null,
            routeTransport = transport, error = null) }
        routeJob = viewModelScope.launch {
            val from = refreshLocation().rounded()
            try {
                val route = g.api.route(RouteRequest(LatLonDto(from.lat, from.lon), LatLonDto(target.lat, target.lon),
                    via.map { LatLonDto(it.lat, it.lon) }, transport))
                _state.update { it.copy(route = route, routeLoading = false, routeTransport = route.transport) }
                if (pendingNavigation) {
                    pendingNavigation = false
                    startNavigation()
                }
            } catch (e: ApiException) {
                _state.update { it.copy(routeLoading = false, error = e.message) }
            }
        }
    }

    fun switchTransport(t: String) {
        val target = _state.value.routeTarget ?: return
        routeTo(target, t)
    }

    /** План: маршрут через все этапы (промежуточные точки и цель). */
    fun startPlan() {
        val plan = _state.value.answer?.plan ?: return
        val last = plan.legs.lastOrNull() ?: return
        finishSearch("route_started")
        val via = plan.legs.dropLast(1).map { LatLng(it.lat, it.lon) }
        pendingNavigation = true
        routeTo(Target(last.to, last.name, last.lat, last.lon, openUntil = last.openUntil), plan.legs.first().transport, via)
    }

    fun startNavigation() {
        val s = _state.value
        val route = s.route ?: return
        val target = s.routeTarget ?: return
        finishSearch("route_started")
        viewModelScope.launch {
            val precise = g.location.current()
            NavigationService.start(g.app, route, NavDestination(target.poiId, target.name, target.category, target.lat, target.lon,
                target.openUntil), precise, s.simulateNavigation)
            _state.update { it.copy(sheet = SheetMode.NONE) }
        }
    }

    fun stopNavigation() = NavigationService.stop(g.app)

    fun closeSheet() {
        val s = _state.value
        when (s.sheet) {
            SheetMode.PLACE, SheetMode.ROUTE -> if (s.answer != null) {
                _state.update { it.copy(sheet = if (s.answer.mode == "plan") SheetMode.PLAN else SheetMode.RESULTS, route = null, routeTarget = null) }
                return
            }
            SheetMode.RESULTS, SheetMode.PLAN -> finishSearch(null)
            SheetMode.NONE -> Unit
        }
        searchJob?.cancel()
        _state.update {
            it.copy(sheet = SheetMode.NONE, answer = null, candidates = emptyList(), chips = emptyList(), place = null,
                route = null, routeTarget = null, searching = false, error = null, itemsTitle = null)
        }
    }

    private fun finishSearch(action: String?) {
        val c = ctx ?: return
        if (c.closed || c.shown.isEmpty()) return
        c.closed = true
        val chosen = c.selected
        val finalAction = when {
            action == "route_started" -> "route_started"
            chosen != null -> "selected"
            action != null -> action
            else -> "closed_no_choice"
        }
        val f = _state.value.filters ?: c.filters
        val event = ProfileEvent(
            query = c.query,
            filters = buildJsonObject {
                f?.let {
                    put("intent", JsonPrimitive(it.intent))
                    it.categoryPrimary?.let { cp -> put("category", JsonPrimitive(cp.id)) }
                    it.transport?.let { t -> put("transport", JsonPrimitive(t)) }
                    it.company?.let { co -> put("company", JsonPrimitive(co)) }
                    it.price?.let { p -> put("price", JsonPrimitive(p)) }
                    it.ambience?.let { a -> put("ambience", JsonPrimitive(a)) }
                }
            },
            shown = c.shown.map { item ->
                JsonObject(mapOf("id" to JsonPrimitive(item.poiId), "category" to JsonPrimitive(item.category ?: ""),
                    "eta_min" to JsonPrimitive(item.etaMin ?: 0)))
            },
            action = finalAction,
            selectedCategory = chosen?.category,
            localTime = g.settings.now().iso(),
            weather = c.weather,
        )
        viewModelScope.launch {
            g.profile.enqueueEvent(event, System.currentTimeMillis())
            Workers.syncProfile(g.app)
        }
    }

    private fun handleIntent(intent: AppIntent) {
        when (intent) {
            is AppIntent.Search -> search(intent.query)
            is AppIntent.RouteTo -> {
                val i = intent.item
                pendingNavigation = intent.startNavigation
                routeTo(Target(i.poiId, i.name, i.lat, i.lon, i.category, i.openUntil), i.transport)
            }
            is AppIntent.ShowItems -> _state.update {
                it.copy(answer = Answer(mode = "simple", items = intent.items, dataSource = it.dataSource),
                    sheet = SheetMode.RESULTS, itemsTitle = intent.title, chips = emptyList())
            }
            is AppIntent.OpenPlace -> openPlace(intent.card.poiId, intent.card)
        }
    }

    /** Маркеры: номера 1–3 для рекомендаций, этапы плана, остальные кандидаты — иконками. */
    fun markers(s: MapUiState): List<MapMarker> {
        val out = mutableListOf<MapMarker>()
        val plan = s.answer?.plan
        if (plan != null) {
            plan.legs.forEachIndexed { i, leg ->
                out += MapMarker(leg.to, leg.lat, leg.lon, if (i == plan.legs.lastIndex) "dest" else "waypoint", "${i + 1}")
            }
        } else {
            val recIds = s.answer?.items?.map { it.poiId }.orEmpty()
            s.answer?.items?.forEachIndexed { i, it -> out += MapMarker(it.poiId, it.lat, it.lon, "rec", "${i + 1}") }
            s.candidates.filter { it.poiId !in recIds }.forEach { out += MapMarker(it.poiId, it.lat, it.lon, "cand") }
        }
        s.routeTarget?.let { t -> if (out.none { it.id == t.poiId }) out += MapMarker(t.poiId, t.lat, t.lon, "dest") }
        if (s.sheet == SheetMode.PLACE) s.place?.let { p -> if (out.none { it.id == p.poiId }) out += MapMarker(p.poiId, p.lat, p.lon, "place") }
        return out
    }

    /** Запасной путь: маршрут в приложении 2ГИС (подтверждено значение car; пешком и транспорт — проверить). */
    fun openIn2gis(context: Context, target: Target, transport: String) {
        val from = _state.value.myLocation
        val type = when (transport) {
            "driving", "taxi" -> "car"
            "public_transport" -> "bus"
            "bicycle", "scooter" -> "bicycle"
            else -> "pedestrian"
        }
        val link = if (from != null) "dgis://2gis.ru/routeSearch/rsType/$type/from/${from.lon},${from.lat}/to/${target.lon},${target.lat}"
        else "dgis://2gis.ru/geo/${target.lon},${target.lat}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${target.lat},${target.lon}?q=${target.lat},${target.lon}(${Uri.encode(target.name)})"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(geo)
            } catch (_: ActivityNotFoundException) {
                _state.update { it.copy(error = "Нет приложения карт для открытия маршрута") }
            }
        }
    }

    fun call(context: Context, phone: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            _state.update { it.copy(error = "Не удалось открыть звонилку") }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    class Factory(private val g: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MapViewModel(g) as T
    }
}
