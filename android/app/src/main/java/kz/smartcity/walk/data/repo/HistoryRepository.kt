package kz.smartcity.walk.data.repo

import kz.smartcity.walk.data.Geo
import kz.smartcity.walk.data.LatLng
import kz.smartcity.walk.data.db.AppDatabase
import kz.smartcity.walk.data.db.Visit

/** История посещений: не покидает телефон. Частые районы — ячейки geohash 6 (≈1,2 × 0,6 км). */
class HistoryRepository(private val db: AppDatabase) {
    private val dao = db.visits()

    suspend fun recordVisit(placeId: String, category: String?, at: LatLng, nowMs: Long) {
        dao.insert(Visit(placeId = placeId, category = category, geohash6 = Geo.geohash(at.lat, at.lon, 6), timeMs = nowMs))
    }

    suspend fun visitedIds(days: Int, nowMs: Long): Set<String> =
        dao.since(nowMs - days * DAY_MS).map { it.placeId }.toSet()

    suspend fun foodVisitSince(sinceMs: Long): Boolean =
        dao.since(sinceMs).any { kz.smartcity.walk.data.Categories.group(it.category) == "food" }

    /** До 3 частых районов (кроме текущего): только центры ячеек, не история. */
    suspend fun frequentAreas(current: LatLng, nowMs: Long): List<LatLng> {
        val here = Geo.geohash(current.lat, current.lon, 6)
        return dao.since(nowMs - 90 * DAY_MS)
            .groupingBy { it.geohash6 }.eachCount()
            .filterKeys { it != here }
            .entries.sortedByDescending { it.value }
            .take(3)
            .map { Geo.geohashCenter(it.key) }
    }

    suspend fun purge(nowMs: Long) = dao.deleteOlderThan(nowMs - 90 * DAY_MS)

    suspend fun deleteAll() = dao.deleteAll()

    companion object {
        const val DAY_MS = 86_400_000L
    }
}
