package kz.smartcity.walk.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Контракт сервера (раздел 12). Имена полей — snake_case через JsonNamingStrategy.SnakeCase.

@Serializable
data class Scored(val id: String, val p: Double)

@Serializable
data class JevFilters(
    val intent: String = "find_place",
    val categoryPrimary: Scored? = null,
    val categorySecondary: Scored? = null,
    val purchaseItem: Scored? = null,
    val transport: String? = null,
    val maxTravelMin: Int? = null,
    val timeBudgetMin: Int? = null,
    val open: String? = null,
    val company: String? = null,
    val kidsAge: String? = null,
    val price: String? = null,
    val priceMaxTenge: Int? = null,
    val ambience: String? = null,
    val minRating: Double? = null,
    val novelty: String? = null,
    val hard: List<String> = emptyList(),
    val soft: List<String> = emptyList(),
    val eventOffsetMin: Int? = null,
    val groupSize: Int? = null,
    val needsReturn: Boolean = false,
    val rawTextSearch: Boolean = false,
    val source: String = "jev",
)

@Serializable
data class Chip(val key: String, val label: String, val kind: String = "info", val removable: Boolean = true)

@Serializable
data class FiltersEvent(val filters: JevFilters, val chips: List<Chip> = emptyList(), val transport: String = "walking")

@Serializable
data class Candidate(
    val poiId: String,
    val slot: String = "",
    val name: String,
    val category: String? = null,
    val categoryName: String? = null,
    val lat: Double,
    val lon: Double,
    val etaMin: Int? = null,
    val distanceM: Int? = null,
    val transport: String = "walking",
    val openState: String = "unknown",
    val openUntil: String? = null,
    val rating: Double? = null,
    val reviews: Int? = null,
    val address: String? = null,
    val role: String = "destination",
)

@Serializable
data class CandidatesEvent(val items: List<Candidate> = emptyList(), val dataSource: String = "2gis")

@Serializable
data class AnswerItem(
    val poiId: String,
    val name: String,
    val category: String? = null,
    val categoryName: String? = null,
    val lat: Double,
    val lon: Double,
    val address: String? = null,
    val etaMin: Int? = null,
    val transport: String = "walking",
    val etaSource: String = "matrix",
    val openState: String = "unknown",
    val openUntil: String? = null,
    val rating: Double? = null,
    val reviews: Int? = null,
    val why: String? = null,
    val visitMin: Int? = null,
    val action: String? = null,
    val source: String = "poi_data",
    val confirmed: List<String> = emptyList(),
    val unconfirmed: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    /** Факты из интернета (позиция меню и цена, акция) со ссылкой на источник. */
    val evidence: List<Evidence> = emptyList(),
)

@Serializable
data class Evidence(val fact: String, val source: String? = null)

/** ИИ предлагает запомнить место; сохраняется только после согласия. */
@Serializable
data class RememberOffer(val name: String, val icon: String = "star", val address: String? = null, val lat: Double, val lon: Double)

@Serializable
data class SavedPlaceIn(val name: String, val address: String? = null, val lat: Double, val lon: Double)

@Serializable
data class PlanLeg(
    val to: String,
    val name: String,
    val purpose: String,
    val transport: String,
    val etaMin: Int,
    val lat: Double,
    val lon: Double,
    val openState: String = "unknown",
    val openUntil: String? = null,
    val arriveAt: String? = null,
    val leaveAt: String? = null,
    val visitMin: Int? = null,
    val distanceM: Int? = null,
    /** Выбранный ИИ вариант дороги до этой точки (автобус, остановки, линия) и подпись к нему. */
    val route: RouteOption? = null,
    val routeLabel: String? = null,
)

@Serializable
data class Plan(
    val legs: List<PlanLeg>,
    val totalMin: Int,
    val rainExposureMin: Int,
    val notes: List<String> = emptyList(),
    val explanation: String? = null,
    val geometry: List<List<Double>> = emptyList(),
    val alternatives: List<JsonObject> = emptyList(),
)

@Serializable
data class Answer(
    val mode: String,
    val items: List<AnswerItem> = emptyList(),
    val idea: String? = null,
    val relaxed: List<String> = emptyList(),
    val plan: Plan? = null,
    val note: String? = null,
    val dataSource: String = "2gis",
    val summary: String? = null,
    val understood: List<String> = emptyList(),
    /** Маршрут к первому месту, если агента просили «как добраться». */
    val route: RouteResponse? = null,
    val routeOptionId: String? = null,
    val remember: RememberOffer? = null,
    /** Шаги запроса, которые выполнить нельзя, с причиной. */
    val skipped: List<String> = emptyList(),
)

/** Прошлый ответ на экране — новый запрос может его уточнять («добавь аптеку»). */
@Serializable
data class PreviousItem(val poiId: String, val name: String, val lat: Double, val lon: Double, val purpose: String? = null,
                        val transport: String? = null)

@Serializable
data class PreviousAnswer(val query: String, val summary: String? = null, val trip: Boolean = false,
                          val items: List<PreviousItem> = emptyList())

@Serializable
data class ProgressEvent(val text: String = "")

@Serializable
data class ErrorEvent(val code: String = "error", val message: String = "Ошибка")

@Serializable
data class DoneEvent(val requestId: String = "", val timings: Map<String, Int> = emptyMap(), val weather: String? = null)

@Serializable
data class SearchRequest(
    val query: String,
    val lat: Double,
    val lon: Double,
    val localTime: String,
    val transportHint: String? = null,
    val walkActive: Boolean = false,
    val profileSummary: String = "",
    val profileAffinity: Map<String, Double> = emptyMap(),
    val lang: String? = null,
    val filters: JevFilters? = null,
    val savedPlaces: List<SavedPlaceIn> = emptyList(),
    val previous: PreviousAnswer? = null,
)

@Serializable
data class SuggestItem(
    val id: String? = null,
    val title: String,
    val subtitle: String? = null,
    val type: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

@Serializable
data class SuggestResponse(val items: List<SuggestItem> = emptyList(), val dataSource: String = "2gis")

@Serializable
data class PlaceCard(
    val poiId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String? = null,
    val rubrics: List<String> = emptyList(),
    val category: String? = null,
    val categoryName: String? = null,
    val rating: Double? = null,
    val reviews: Int? = null,
    val attributes: List<String> = emptyList(),
    val branchCount: Int? = null,
    val phone: String? = null,
    val website: String? = null,
    val avgCheck: Int? = null,
    val openState: String = "unknown",
    val openUntil: String? = null,
    val opensAt: String? = null,
    val dataSource: String = "2gis",
)

@Serializable
data class LatLonDto(val lat: Double, val lon: Double)

@Serializable
data class RouteRequest(
    @SerialName("from") val origin: LatLonDto,
    val to: LatLonDto,
    val via: List<LatLonDto> = emptyList(),
    val transport: String = "walking",
)

@Serializable
data class Maneuver(
    val text: String,
    val icon: String? = null,
    val type: String? = null,
    val turnDirection: String? = null,
    val lat: Double,
    val lon: Double,
    val distanceM: Int = 0,
    val durationS: Int = 0,
)

@Serializable
data class RouteResponse(
    val transport: String,
    val totalDistanceM: Int,
    val totalDurationS: Int,
    val geometry: List<List<Double>>,
    val maneuvers: List<Maneuver>,
    val source: String,
    val deeplink: String? = null,
    val note: String? = null,
    val options: List<RouteOption> = emptyList(),
) {
    /** Маршрут по выбранному варианту: его линию, манёвры и этапы (автобусы, остановки) берёт навигатор. */
    fun withOption(id: String?): RouteResponse {
        val o = options.firstOrNull { it.id == id } ?: options.firstOrNull() ?: return this
        return copy(totalDistanceM = o.totalDistanceM, totalDurationS = o.totalDurationS, geometry = o.geometry,
            maneuvers = o.maneuvers, options = listOf(o))
    }

    /** Вариант, по которому идёт навигация (после [withOption] он единственный). */
    val chosen: RouteOption? get() = options.firstOrNull()
}

@Serializable
data class RouteStop(val lat: Double, val lon: Double, val name: String? = null)

/** Этап варианта: walk — пешком, bus/trolleybus/tram/… — поездка с номерами маршрутов и остановками. */
@Serializable
data class RouteLeg(
    val kind: String,
    val names: List<String> = emptyList(),
    val color: String? = null,
    val fromName: String? = null,
    val toName: String? = null,
    val durationS: Int = 0,
    val waitS: Int = 0,
    val distanceM: Int = 0,
    val geometry: List<List<Double>> = emptyList(),
    val stops: List<RouteStop> = emptyList(),
)

@Serializable
data class RouteOption(
    val id: String,
    val label: String? = null,
    val totalDistanceM: Int,
    val totalDurationS: Int,
    val geometry: List<List<Double>> = emptyList(),
    val maneuvers: List<Maneuver> = emptyList(),
    val legs: List<RouteLeg> = emptyList(),
    val transfers: Int? = null,
    val walkText: String? = null,
    val departure: String? = null,
    val departureInMin: Int? = null,
    val nextDepartures: List<String> = emptyList(),
    val intervalMin: Int? = null,
)

@Serializable
data class DeckRequest(
    val lat: Double,
    val lon: Double,
    val localTime: String,
    val transport: String,
    val categories: List<String>,
    val extraAreas: List<LatLonDto> = emptyList(),
    val profileSummary: String = "",
    val lang: String = "ru",
    val filters: DeckFiltersDto = DeckFiltersDto(),
    val recentQueries: List<String> = emptyList(),
)

/** Фильтры колоды для сервера (что можно отобрать по данным 2ГИС). */
@Serializable
data class DeckFiltersDto(
    val openNow: Boolean = false,
    val maxEtaMin: Int? = null,
    val place: String = "any",
    val kids: Boolean = false,
    val budget: String = "any",
    val minRating: Double? = null,
)

@Serializable
data class DeckCard(
    val slot: String = "",
    val poiId: String,
    val name: String,
    val category: String,
    val categoryName: String,
    val group: String,
    val lat: Double,
    val lon: Double,
    val etaMin: Int? = null,
    val transport: String = "walking",
    val openState: String = "unknown",
    val openUntil: String? = null,
    val rating: Double? = null,
    val reviews: Int? = null,
    val q: Double = 0.8,
    val k: Double = 0.7,
    val indoor: Boolean = true,
    val contextChip: String? = null,
    val why: String? = null,
    val address: String? = null,
    /** Фото места (сайт заведения, Википедия, OpenStreetMap) и подпись источника. */
    val photoUrl: String? = null,
    val photoSource: String? = null,
    val avgCheck: Int? = null,
    val kids: Boolean = false,
    /** Карточка подобрана по недавнему поиску. */
    val fromSearch: String? = null,
)

@Serializable
data class DeckWeather(val brief: String? = null, val precipNext3h: Boolean? = null, val tempC: Double? = null, val isDark: Boolean? = null)

@Serializable
data class SunDto(val sunrise: String? = null, val sunset: String? = null)

@Serializable
data class DeckResponse(
    val generatedAt: String = "",
    val weather: DeckWeather = DeckWeather(),
    val sun: SunDto = SunDto(),
    val isWeekend: Boolean = false,
    val isHoliday: Boolean = false,
    val cards: List<DeckCard> = emptyList(),
    val dataSource: String = "2gis",
)

@Serializable
data class ProfileEvent(
    val query: String = "",
    val filters: JsonObject = JsonObject(emptyMap()),
    val shown: List<JsonObject> = emptyList(),
    val action: String,
    val selectedCategory: String? = null,
    val localTime: String? = null,
    val weather: String? = null,
    val walk: JsonObject? = null,
)

@Serializable
data class ProfileUpdateRequest(val event: ProfileEvent, val summary: String = "", val lang: String = "ru")

@Serializable
data class ProfileOp(val op: String, val key: String, val value: String, val explicit: Boolean = false, val evidence: String = "")

@Serializable
data class ProfileOpsResponse(val ops: List<ProfileOp> = emptyList(), val note: String? = null)

@Serializable
data class HourlyDto(
    val t: String? = null,
    val tempC: Double? = null,
    val feelsLikeC: Double? = null,
    val pop: Double? = null,
    val precipMm: Double? = null,
    val windMs: Double? = null,
)

@Serializable
data class WeatherDto(
    val tempC: Double? = null,
    val feelsLikeC: Double? = null,
    val windMs: Double? = null,
    val precipNow: Boolean = false,
    val precipStartsInMin: Int? = null,
    val precipIntensityMmh: Double? = null,
    val precision: String? = null,
    val confirmed: Boolean = false,
    val rainSoon: Boolean = false,
    val precipNext3h: Boolean = false,
    val hourly: List<HourlyDto> = emptyList(),
    val source: String = "unavailable",
    val sunrise: String? = null,
    val sunset: String? = null,
    val minutesToSunset: Int? = null,
    val isDark: Boolean = false,
)

@Serializable
data class NotifyRequest(
    val lat: Double,
    val lon: Double,
    val localTime: String,
    val transport: String,
    val trigger: String,
    val profileSummary: String = "",
    val profileAffinity: Map<String, Double> = emptyMap(),
    val excludeIds: List<String> = emptyList(),
    val targetCategory: String? = null,
    val targetClosesAt: String? = null,
    val lang: String = "ru",
)

@Serializable
data class NotifyResponse(
    val trigger: String,
    val items: List<AnswerItem> = emptyList(),
    val text: String? = null,
    val suggestPlan: Boolean = false,
    val dataSource: String = "2gis",
)

@Serializable
data class ApiErrorBody(val code: String = "error", val message: String = "Ошибка сервера")
