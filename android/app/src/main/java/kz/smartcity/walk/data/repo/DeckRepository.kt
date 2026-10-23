package kz.smartcity.walk.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kz.smartcity.walk.data.Categories
import kz.smartcity.walk.data.DeckFilters
import kz.smartcity.walk.data.DeckFiltersStore
import kz.smartcity.walk.data.LatLng
import kz.smartcity.walk.data.QuickPlaces
import kz.smartcity.walk.data.Settings
import kz.smartcity.walk.data.api.ApiClient
import kz.smartcity.walk.data.api.DeckCard
import kz.smartcity.walk.data.api.DeckRequest
import kz.smartcity.walk.data.api.LatLonDto
import kz.smartcity.walk.data.db.AppDatabase
import kz.smartcity.walk.data.db.DeckCardEntity
import kz.smartcity.walk.data.db.SavedPlace
import kz.smartcity.walk.data.iso

/** Карточка колоды; [status] — new / liked / visited (для «Понравившихся»), [savedMs] — когда сохранена. */
data class DeckItem(val card: DeckCard, val dataSource: String, val status: String = "new", val savedMs: Long = 0)

/**
 * Колода «Для вас»: собирается в фоне заранее, ранжируется и прореживается на устройстве (раздел 9).
 * Учитывает фильтры, недавние поиски, портрет и погоду (сервер), а каждый свайп сразу перестраивает остаток колоды.
 */
class DeckRepository(
    private val db: AppDatabase,
    private val api: ApiClient,
    private val profile: ProfileRepository,
    private val history: HistoryRepository,
    private val settings: Settings,
    private val json: Json,
    private val filters: DeckFiltersStore,
    private val places: QuickPlaces,
) {
    private fun decode(e: DeckCardEntity) =
        runCatching { json.decodeFromString(StoredCard.serializer(), e.json) }.getOrNull()?.let { DeckItem(it.card, it.dataSource, e.status, e.createdMs) }

    val cards: Flow<List<DeckItem>> = db.deck().newCards().map { list -> list.mapNotNull(::decode) }

    /** Понравившиеся целиком: фото, рейтинг, «почему» — для раздела «Понравившиеся». */
    val liked: Flow<List<DeckItem>> = db.deck().likedCards().map { list -> list.mapNotNull(::decode) }
    val saved: Flow<List<SavedPlace>> = db.saved().all()

    /** Погода при последней сборке: «0 °C, ощущается −5 °C…» — в шапке «Для вас». */
    val weather = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun isStale(nowMs: Long, maxAgeMs: Long = 2 * 3_600_000L) = nowMs - settings.lastDeckRefreshMs > maxAgeMs

    /** @return число карточек или null, если обновление пропущено из-за лимита демо-ключа. */
    suspend fun refresh(at: LatLng, force: Boolean = false): Int? {
        val now = settings.now()
        if (!force && settings.deckRefreshesToday(now.toLocalDate()) >= DEMO_KEY_DAILY_LIMIT) return null
        val f = filters.value.value
        val point = at.rounded()
        val categories = profile.deckCategories(f.groups)
        if (categories.isEmpty()) return 0
        val areas = history.frequentAreas(at, now.toInstant().toEpochMilli()).map { LatLonDto(it.lat, it.lon) }
        val resp = api.deck(DeckRequest(
            lat = point.lat, lon = point.lon, localTime = now.iso(), transport = settings.defaultTransport,
            categories = categories, extraAreas = areas, profileSummary = profile.summary(now.toInstant().toEpochMilli()),
            filters = f.toDto(), recentQueries = places.recent.value.take(8),
        ))
        weather.value = resp.weather.brief
        val nowMs = now.toInstant().toEpochMilli()
        val visited = history.visitedIds(if (f.newOnly) 365 else 30, nowMs)
        val swiped = db.deck().swipedIds(nowMs - 14 * 86_400_000L).toSet()
        val fresh = resp.cards.filter { it.poiId !in swiped && !(f.newOnly && it.poiId in visited) }
        val ranked = rank(fresh, visited)
        val chosen = ProfileMath.diversify(
            ranked, score = { it.second }, category = { it.first.category },
            group = { Categories.group(it.first.category) ?: it.first.group }, take = DECK_SIZE,
        )
        db.deck().replaceNew(chosen.mapIndexed { i, (card, _) ->
            DeckCardEntity(card.poiId, json.encodeToString(StoredCard.serializer(), StoredCard(card, resp.dataSource)), i, "new", nowMs)
        })
        settings.lastDeckRefreshMs = nowMs
        settings.countDeckRefresh(now.toLocalDate())
        return chosen.size
    }

    /** Оценка карточек: интерес к категории (θ ~ Beta), контекст K, дорога, качество, новизна и черты места. */
    private suspend fun rank(cards: List<DeckCard>, visited: Set<String>): List<Pair<DeckCard, Double>> {
        val theta = profile.thetaSamples(cards.map { it.category }.toSet())
        val feat = profile.featureAffinity()
        return cards.map { c ->
            val n = if (c.poiId in visited) 0.3 else 1.0
            val base = ProfileMath.deckScore(theta[c.category] ?: 0.5, c.k, (c.etaMin ?: 15).toDouble(), c.q, n)
            c to base * ProfileMath.featureFactor(features(c)) { feat[it] }
        }.sortedByDescending { it.second }
    }

    private fun features(c: DeckCard) = ProfileMath.features(c.indoor, c.avgCheck, c.etaMin, c.rating)

    suspend fun swipe(item: DeckItem, like: Boolean) {
        val nowMs = settings.now().toInstant().toEpochMilli()
        db.deck().setStatus(item.card.poiId, if (like) "liked" else "disliked")
        profile.onSwipe(item.card.category, like, nowMs, features(item.card))
        if (like) {
            db.saved().insert(SavedPlace(item.card.poiId, item.card.name, item.card.lat, item.card.lon, item.card.category, nowMs))
        }
        rerank()
    }

    /** Отменить последний свайп: карточка возвращается наверх колоды, обучение откатывается. */
    suspend fun undo(item: DeckItem, like: Boolean) {
        val nowMs = settings.now().toInstant().toEpochMilli()
        profile.undoSwipe(item.card.category, like, nowMs, features(item.card))
        if (like) db.saved().delete(item.card.poiId)
        db.deck().setStatus(item.card.poiId, "new")
        db.deck().setRank(item.card.poiId, -1)
    }

    /**
     * После свайпа остаток колоды переупорядочивается по обновлённым интересам: «не то» кафе — кафе и похожие
     * черты уходят вниз, «хочу» парк — парки и прогулки поднимаются. Верхняя карта (следующая) не прыгает: только хвост.
     */
    private suspend fun rerank() {
        val rest = db.deck().newList()
        if (rest.size < 3) return
        val head = rest.first()
        val tail = rest.drop(1).mapNotNull { e -> decode(e)?.card }
        val visited = history.visitedIds(30, settings.now().toInstant().toEpochMilli())
        val ordered = rank(tail, visited)
        db.deck().setRank(head.poiId, 0)
        ordered.forEachIndexed { i, (c, _) -> db.deck().setRank(c.poiId, i + 1) }
    }

    /** Отметить «Был здесь» — карточка остаётся в понравившихся, но с отметкой. */
    suspend fun markVisited(poiId: String) = db.deck().setStatus(poiId, "visited")

    /** Убрать из понравившихся. */
    suspend fun unsave(poiId: String) {
        db.saved().delete(poiId)
        db.deck().setStatus(poiId, "removed")
    }

    fun filters() = filters.value

    fun setFilters(f: DeckFilters) = filters.set(f)

    companion object {
        const val DECK_SIZE = 20
        /** Демо-ключ 2ГИС: смена фильтров тоже пересобирает колоду, поэтому лимит выше прежних 3 в сутки. */
        const val DEMO_KEY_DAILY_LIMIT = 12
    }
}

@kotlinx.serialization.Serializable
data class StoredCard(val card: DeckCard, val dataSource: String)
