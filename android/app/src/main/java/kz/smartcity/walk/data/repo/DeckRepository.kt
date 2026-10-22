package kz.smartcity.walk.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kz.smartcity.walk.data.Categories
import kz.smartcity.walk.data.LatLng
import kz.smartcity.walk.data.Settings
import kz.smartcity.walk.data.api.ApiClient
import kz.smartcity.walk.data.api.DeckCard
import kz.smartcity.walk.data.api.DeckRequest
import kz.smartcity.walk.data.api.LatLonDto
import kz.smartcity.walk.data.db.AppDatabase
import kz.smartcity.walk.data.db.DeckCardEntity
import kz.smartcity.walk.data.db.SavedPlace
import kz.smartcity.walk.data.iso

data class DeckItem(val card: DeckCard, val dataSource: String)

/** Колода «Для вас»: собирается в фоне заранее, ранжируется и прореживается на устройстве (раздел 9). */
class DeckRepository(
    private val db: AppDatabase,
    private val api: ApiClient,
    private val profile: ProfileRepository,
    private val history: HistoryRepository,
    private val settings: Settings,
    private val json: Json,
) {
    val cards: Flow<List<DeckItem>> = db.deck().newCards().map { list ->
        list.mapNotNull { e -> runCatching { json.decodeFromString(StoredCard.serializer(), e.json) }.getOrNull() }
            .map { DeckItem(it.card, it.dataSource) }
    }
    val saved: Flow<List<SavedPlace>> = db.saved().all()

    fun isStale(nowMs: Long, maxAgeMs: Long = 2 * 3_600_000L) = nowMs - settings.lastDeckRefreshMs > maxAgeMs

    /** @return число карточек или null, если обновление пропущено из-за лимита демо-ключа. */
    suspend fun refresh(at: LatLng, force: Boolean = false): Int? {
        val now = settings.now()
        if (!force && settings.deckRefreshesToday(now.toLocalDate()) >= DEMO_KEY_DAILY_LIMIT) return null
        val point = at.rounded()
        val categories = profile.deckCategories()
        val areas = history.frequentAreas(at, now.toInstant().toEpochMilli()).map { LatLonDto(it.lat, it.lon) }
        val resp = api.deck(DeckRequest(
            lat = point.lat, lon = point.lon, localTime = now.iso(), transport = settings.defaultTransport,
            categories = categories, extraAreas = areas, profileSummary = profile.summary(now.toInstant().toEpochMilli()),
        ))
        val nowMs = now.toInstant().toEpochMilli()
        val visited = history.visitedIds(30, nowMs)
        val swiped = db.deck().swipedIds(nowMs - 14 * 86_400_000L).toSet()
        val fresh = resp.cards.filter { it.poiId !in swiped }
        val theta = profile.thetaSamples(fresh.map { it.category }.toSet())
        val scored = fresh.map { c ->
            val n = if (c.poiId in visited) 0.3 else 1.0
            c to ProfileMath.deckScore(theta[c.category] ?: 0.5, c.k, (c.etaMin ?: 15).toDouble(), c.q, n)
        }
        val chosen = ProfileMath.diversify(
            scored.sortedByDescending { it.second }, score = { it.second }, category = { it.first.category },
            group = { Categories.group(it.first.category) ?: it.first.group }, take = DECK_SIZE,
        )
        db.deck().replaceNew(chosen.mapIndexed { i, (card, _) ->
            DeckCardEntity(card.poiId, json.encodeToString(StoredCard.serializer(), StoredCard(card, resp.dataSource)), i, "new", nowMs)
        })
        settings.lastDeckRefreshMs = nowMs
        settings.countDeckRefresh(now.toLocalDate())
        return chosen.size
    }

    suspend fun swipe(item: DeckItem, like: Boolean) {
        val nowMs = settings.now().toInstant().toEpochMilli()
        db.deck().setStatus(item.card.poiId, if (like) "liked" else "disliked")
        profile.onSwipe(item.card.category, like, nowMs)
        if (like) {
            db.saved().insert(SavedPlace(item.card.poiId, item.card.name, item.card.lat, item.card.lon, item.card.category, nowMs))
        }
    }

    suspend fun unsave(poiId: String) = db.saved().delete(poiId)

    companion object {
        const val DECK_SIZE = 20
        const val DEMO_KEY_DAILY_LIMIT = 3
    }
}

@kotlinx.serialization.Serializable
data class StoredCard(val card: DeckCard, val dataSource: String)
