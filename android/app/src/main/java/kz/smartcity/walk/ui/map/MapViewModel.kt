package kz.smartcity.walk.ui.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import kz.smartcity.walk.nav.NavDestination
import kz.smartcity.walk.nav.NavState
import kz.smartcity.walk.data.Categories
import kz.smartcity.walk.data.QuickPlace
import kz.smartcity.walk.data.QuickPlaces
import kz.smartcity.walk.data.api.RememberOffer
import kz.smartcity.walk.data.api.SavedPlaceIn
import kz.smartcity.walk.data.api.PreviousAnswer
import kz.smartcity.walk.data.api.PreviousItem
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
    /** Шаги агента по порядку: «Ищу: университет», «Проверяю, как добраться…». */
    val progress: List<String> = emptyList(),
    /** Выбранный вариант маршрута: агент предлагает, пользователь может выбрать любой. */
    val selectedOptionId: String? = null,
    /** Открыта строка ввода над клавиатурой (кнопка-кружок «расплылась» в строку). */
    val composing: Boolean = false,
    /** Подсказки запросов под этого человека: время суток, интересы, недавние запросы. */
    val hints: List<Hint> = emptyList(),
    /** ИИ предложил запомнить место — ждём согласия. */
    val remember: RememberOffer? = null,
    /** Навигация по плану: номер текущего этапа (точки), иначе null. */
    val planLeg: Int? = null,
)

/** Подсказка запроса: текст и ключ иконки. */
data class Hint(val text: String, val icon: String)

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

    /** Живая точка на карте, пока открыта вкладка карты: раз в 2 с, экономный режим. */
    suspend fun trackLocation() {
        try {
            g.location.updates(2_000L).collect { loc ->
                _state.update { it.copy(myLocation = LatLng(loc.latitude, loc.longitude), usingCityCenter = false) }
            }
        } catch (_: SecurityException) {
            // нет разрешения — остаётся последняя известная точка
        }
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

    fun openComposer() {
        _state.update { it.copy(composing = true) }
        viewModelScope.launch {
            val h = buildHints()
            _state.update { it.copy(hints = h) }
        }
    }

    /**
     * Подсказки собираются на телефоне из данных о человеке: недавние запросы, сильные интересы (ручной выбор и
     * портрет), время суток и день недели. Без запросов к серверу — появляются мгновенно.
     */
    private suspend fun buildHints(): List<Hint> {
        val now = g.settings.now()
        val out = mutableListOf<Hint>()
        g.places.recent.value.take(2).forEach { out += Hint(it, "history") }
        val h = now.hour
        out += when (h) {
            in 6..10 -> Hint("Кофе и завтрак рядом", "coffee")
            in 11..14 -> Hint("Бизнес-ланч рядом до 3000 ₸", "food")
            in 15..17 -> Hint("Десерт и кофе поблизости", "coffee")
            in 18..21 -> Hint("Где поужинать сегодня", "food")
            else -> Hint("Что открыто сейчас рядом", "night")
        }
        val weekend = now.dayOfWeek.value >= 6
        val affinity = g.profile.affinity()
        val interests = (g.settings.interests.toList() + affinity.entries.sortedByDescending { it.value }.map { it.key })
            .distinct().filter { it in Categories.all && it !in Categories.notForDeck }
            // Ночные места (клубы, бары) — только вечером и ночью.
            .filter { h !in 6..17 || Categories.all[it]?.night != true }
        interests.take(2).forEach { cat ->
            out += Hint("${Categories.plural(cat).replaceFirstChar { it.uppercase() }} рядом", "cat:$cat")
        }
        if (weekend) out += Hint("Куда сходить на выходных", "star")
        if (h in 16..20) out += Hint("Прогулка на закате", "walk")
        if (out.size < 6) out += Hint("Как добраться на автобусе до Байтерека", "bus")
        return out.distinctBy { it.text.lowercase() }.take(7)
    }

    val quickPlaces get() = g.places.items

    /** Быстрый маршрут: сразу маршрут к сохранённому месту привычным транспортом. */
    fun routeToQuick(q: QuickPlace) {
        _state.update { it.copy(composing = false, query = "") }
        routeTo(Target("saved:${q.id}", q.name, q.lat, q.lon), transport = g.settings.defaultTransport)
    }

    fun acceptRemember() {
        val r = _state.value.remember ?: return
        val icon = r.icon.takeIf { it in QuickPlaces.ICONS } ?: "star"
        g.places.upsert(QuickPlace(name = r.name, icon = icon, address = r.address, lat = r.lat, lon = r.lon))
        _state.update { it.copy(remember = null) }
    }

    fun dismissRemember() = _state.update { it.copy(remember = null) }

    /**
     * Отправка из строки ввода. Имя сохранённого места («домой», «к брату») — сразу маршрут; адрес — мгновенно через
     * подсказки 2ГИС без ИИ; всё остальное — поиск ИИ.
     */
    fun submit(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        val low = q.lowercase()
        g.places.items.value.firstOrNull { p -> low == p.name.lowercase() || low.removePrefix("к ").removePrefix("в ") == p.name.lowercase() ||
            (low.startsWith("домой") && p.icon == "home") }?.let { routeToQuick(it); return }
        if (looksLikeAddress(q)) {
            _state.update { it.copy(composing = false, query = "", suggestions = emptyList()) }
            viewModelScope.launch {
                val at = (_state.value.myLocation ?: CITY_CENTER).rounded()
                val top = try { g.api.suggest(q, at.lat, at.lon).items.firstOrNull { it.lat != null && it.lon != null && it.type != "rubric" } }
                    catch (_: ApiException) { null }
                if (top != null) pickSuggestion(top) else search(q)
            }
            return
        }
        search(q)
    }

    /** «Кенесары 40», «ул. Сыганак 10/1», «пр. Мангилик Ел, 55» — улица с номером дома. */
    private fun looksLikeAddress(q: String): Boolean {
        val low = q.lowercase()
        val street = Regex("""\b(ул\.?|улица|пр\.?|просп\.?|проспект|шоссе|бульвар|б-р|мкр\.?|микрорайон|переулок|пер\.?|көшесі|даңғылы|жк)\b""")
        val house = Regex("""\d+[а-яa-z]?(/\d+)?\s*$""")
        return low.split(' ').size <= 6 && (street.containsMatchIn(low) && Regex("""\d""").containsMatchIn(low) ||
            house.containsMatchIn(low) && !Regex("""(до|за|на|час|мин|₸|тг|тенге|руб|человек|лет)\b""").containsMatchIn(low))
    }

    fun closeComposer() = _state.update { it.copy(composing = false, suggestions = emptyList()) }

    /** Выбор подсказки открывает карточку места; отправка текста запускает ИИ-поиск. */
    fun pickSuggestion(s: SuggestItem) {
        clearSuggestions()
        if (s.type == "rubric" || s.lat == null || s.lon == null) {
            search(s.title)
            return
        }
        _state.update { it.copy(query = "", composing = false) }
        val id = s.id
        if (id != null && s.type == "branch") openPlace(id, preview = PlaceCard(poiId = id, name = s.title, lat = s.lat, lon = s.lon, address = s.subtitle))
        else routeTo(Target(id ?: "point", s.title, s.lat, s.lon))
    }

    fun search(text: String, filters: JevFilters? = null) {
        val query = text.trim()
        if (query.isEmpty()) return
        // Ответ ещё на экране — новый запрос может его уточнять: передаём прошлые точки ИИ.
        val previous = _state.value.answer?.takeIf { _state.value.sheet != SheetMode.NONE && filters == null }?.let { a ->
            val stops = a.plan?.legs?.map { PreviousItem(it.to, it.name, it.lat, it.lon, it.purpose, it.transport) }
                ?: a.items.map { PreviousItem(it.poiId, it.name, it.lat, it.lon, transport = it.transport) }
            PreviousAnswer(_state.value.lastQuery, a.summary, trip = a.plan != null, items = stops.take(8))
        }
        finishSearch(if (filters == null) "reformulated" else null)
        searchJob?.cancel()
        suggestJob?.cancel()
        _state.update {
            // Строка ввода очищается и сворачивается сразу после отправки запроса.
            it.copy(query = "", composing = false, lastQuery = query, suggestions = emptyList(), searching = true, chips = emptyList(),
                filters = filters, candidates = emptyList(), answer = null, error = null, sheet = SheetMode.RESULTS,
                place = null, route = null, routeTarget = null, itemsTitle = null, hiddenVisited = 0,
                progress = emptyList(), selectedOptionId = null)
        }
        if (filters == null) g.settings.searchCount += 1
        if (filters == null) g.places.addRecent(query)
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
                // Сохранённые места — чтобы «поехали к брату» понимал и ИИ (координаты с точностью ~10 м).
                previous = previous,
                savedPlaces = g.places.items.value.map { p ->
                    SavedPlaceIn(p.name, p.address, Math.round(p.lat * 1e4) / 1e4, Math.round(p.lon * 1e4) / 1e4)
                },
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
                    is SearchEvent.Progress -> _state.update {
                        if (it.progress.lastOrNull() == ev.text) it else it.copy(progress = it.progress + ev.text)
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
        val offer = a.remember?.takeIf { r -> g.places.items.value.none { it.name.equals(r.name, ignoreCase = true) } }
        _state.update { it.copy(answer = a, dataSource = a.dataSource, sheet = sheet, hiddenVisited = hidden, remember = offer) }
        val first = a.items.firstOrNull()
        val route = a.route
        when {
            // Просили «как добраться»: сразу маршрут с вариантами, выбранный агентом — подсвечен.
            route != null && first != null -> {
                c.selected = first
                _state.update {
                    it.copy(sheet = SheetMode.ROUTE, route = route, routeTransport = route.transport, routeLoading = false,
                        routeTarget = Target(first.poiId, first.name, first.lat, first.lon, first.category, first.openUntil),
                        selectedOptionId = a.routeOptionId ?: route.options.firstOrNull()?.id)
                }
            }
            a.mode == "place" && first != null -> openItem(first)
        }
    }

    fun selectOption(id: String) = _state.update { it.copy(selectedOptionId = id) }

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
            routeTransport = transport, error = null, selectedOptionId = null) }
        routeJob = viewModelScope.launch {
            val from = refreshLocation().rounded()
            try {
                val route = g.api.route(RouteRequest(LatLonDto(from.lat, from.lon), LatLonDto(target.lat, target.lon),
                    via.map { LatLonDto(it.lat, it.lon) }, transport))
                _state.update { it.copy(route = route, routeLoading = false, routeTransport = route.transport,
                    selectedOptionId = route.options.firstOrNull()?.id) }
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
    /**
     * Маршрут по плану ведётся по этапам: до первой точки, на месте — «Дальше» к следующей. Каждый этап — ровно тот
     * вариант дороги, что выбрал ИИ (автобус, пешком, авто); промежуточные точки не теряются.
     */
    fun startPlan() = startPlanLeg(0)

    fun startPlanLeg(index: Int) {
        val plan = _state.value.answer?.plan ?: return
        val leg = plan.legs.getOrNull(index) ?: return
        if (index == 0) finishSearch("route_started")
        _state.update { it.copy(planLeg = index) }
        val option = leg.route
        if (option == null) {
            // ИИ не приложил дорогу (сбой маршрутов) — строим этап заново и стартуем, как только он готов.
            pendingNavigation = true
            routeTo(Target(leg.to, leg.name, leg.lat, leg.lon, openUntil = leg.openUntil), leg.transport)
            return
        }
        val route = RouteResponse(transport = leg.transport, totalDistanceM = option.totalDistanceM,
            totalDurationS = option.totalDurationS, geometry = option.geometry, maneuvers = option.maneuvers,
            source = "plan", options = listOf(option))
        viewModelScope.launch {
            val precise = g.location.current()
            NavigationService.start(g.app, route, NavDestination(leg.to, leg.name, null, leg.lat, leg.lon, leg.openUntil),
                precise, _state.value.simulateNavigation)
            _state.update { it.copy(sheet = SheetMode.NONE) }
        }
    }

    /** Следующая точка плана во время навигации по нему (для кнопки «Дальше»). */
    fun nextPlanLeg(): kz.smartcity.walk.data.api.PlanLeg? {
        val s = _state.value
        val i = s.planLeg ?: return null
        return s.answer?.plan?.legs?.getOrNull(i + 1)
    }

    fun startNavigation() {
        val s = _state.value
        val route = s.route?.withOption(s.selectedOptionId) ?: return
        val target = s.routeTarget ?: return
        finishSearch("route_started")
        viewModelScope.launch {
            val precise = g.location.current()
            NavigationService.start(g.app, route, NavDestination(target.poiId, target.name, target.category, target.lat, target.lon,
                target.openUntil), precise, s.simulateNavigation)
            _state.update { it.copy(sheet = SheetMode.NONE) }
        }
    }

    fun stopNavigation() {
        _state.update { it.copy(planLeg = null) }
        NavigationService.stop(g.app)
    }

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

    /**
     * Слои карты: маркеры, а на экране маршрута — выбранный вариант по этапам (пешком — пунктир, автобус — цветом
     * маршрута, с остановками посадки и выхода) и бледные линии остальных вариантов. План — одна линия.
     */
    fun layers(s: MapUiState, primary: Color, walk: Color): MapLayers {
        val markers = if (s.sheet == SheetMode.ROUTE) markers(s).filter { it.kind == "dest" || it.id == s.routeTarget?.poiId } else markers(s)
        val route = s.route
        if (s.sheet != SheetMode.ROUTE || route == null) {
            val plan = s.answer?.plan?.takeIf { s.sheet == SheetMode.PLAN }
            if (plan != null && plan.legs.any { it.route != null }) {
                val lines = mutableListOf<MapLine>()
                val stops = mutableListOf<MapStop>()
                plan.legs.forEach { leg ->
                    val o = leg.route ?: return@forEach
                    if (o.legs.isEmpty()) lines += MapLine(o.geometry.toLatLng(), primary)
                    else o.legs.forEach { l ->
                        if (l.kind == "walk") lines += MapLine(l.geometry.toLatLng(), walk, width = 5.dp, dashed = true)
                        else {
                            val color = parseColor(l.color) ?: primary
                            lines += MapLine(l.geometry.toLatLng(), color, width = 7.dp)
                            l.stops.forEachIndexed { i, st ->
                                val edge = i == 0 || i == l.stops.lastIndex
                                stops += MapStop(st.lat, st.lon, color, major = edge, name = if (edge) st.name else null)
                            }
                        }
                    }
                }
                return MapLayers(markers = markers, lines = lines, stops = stops)
            }
            val planLine = plan?.geometry?.takeIf { it.size > 1 }
            return MapLayers(markers = markers, lines = listOfNotNull(planLine?.let { MapLine(it.toLatLng(), primary) }))
        }
        if (route.options.isEmpty()) return MapLayers(markers, listOf(MapLine(route.geometry.toLatLng(), primary)))
        val selected = route.options.firstOrNull { it.id == s.selectedOptionId } ?: route.options.first()
        val lines = mutableListOf<MapLine>()
        route.options.filter { it.id != selected.id }.forEach {
            lines += MapLine(it.geometry.toLatLng(), Color(0xFF8A94A6), width = 5.dp, alpha = 0.55f, casing = false)
        }
        val stops = mutableListOf<MapStop>()
        if (selected.legs.isEmpty()) {
            lines += MapLine(selected.geometry.toLatLng(), primary)
        } else {
            selected.legs.forEach { leg ->
                if (leg.kind == "walk") {
                    lines += MapLine(leg.geometry.toLatLng(), walk, width = 5.dp, dashed = true)
                } else {
                    val color = parseColor(leg.color) ?: primary
                    lines += MapLine(leg.geometry.toLatLng(), color, width = 7.dp)
                    leg.stops.forEachIndexed { i, st ->
                        val edge = i == 0 || i == leg.stops.lastIndex
                        stops += MapStop(st.lat, st.lon, color, major = edge, name = if (edge) st.name else null)
                    }
                }
            }
        }
        return MapLayers(markers = markers, lines = lines, stops = stops)
    }

    /**
     * Слои навигации: пройденные этапы бледнеют, текущий — ярче и толще; у поездок — все остановки, посадка и выход
     * подписаны. Без этапов (пешком, авто) — одна линия маршрута.
     */
    fun navLayers(nav: NavState, primary: Color, walk: Color): MapLayers {
        val dest = MapMarker(nav.dest.poiId, nav.dest.lat, nav.dest.lon, "dest")
        if (nav.legs.isEmpty()) {
            return MapLayers(markers = listOf(dest), lines = listOfNotNull(nav.geometry.takeIf { it.size > 1 }?.let { MapLine(it, primary) }),
                user = nav.position)
        }
        val lines = mutableListOf<MapLine>()
        val stops = mutableListOf<MapStop>()
        nav.legs.forEachIndexed { i, leg ->
            val passed = i < nav.legIndex
            val alpha = if (passed) 0.35f else 1f
            if (leg.isWalk) {
                lines += MapLine(leg.line, walk, width = if (i == nav.legIndex) 6.dp else 5.dp, dashed = true, alpha = alpha)
            } else {
                val color = parseColor(leg.color) ?: primary
                lines += MapLine(leg.line, color, width = if (i == nav.legIndex) 9.dp else 7.dp, alpha = alpha)
                if (!passed) leg.stops.forEachIndexed { k, st ->
                    val edge = k == 0 || k == leg.stops.lastIndex
                    stops += MapStop(st.lat, st.lon, color, major = edge, name = if (edge) leg.stopNames.getOrNull(k) else null)
                }
            }
        }
        return MapLayers(markers = listOf(dest), lines = lines, stops = stops, user = nav.position)
    }

    private fun List<List<Double>>.toLatLng() = mapNotNull { p -> if (p.size >= 2) LatLng(p[1], p[0]) else null }

    private fun parseColor(hex: String?): Color? =
        hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

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
