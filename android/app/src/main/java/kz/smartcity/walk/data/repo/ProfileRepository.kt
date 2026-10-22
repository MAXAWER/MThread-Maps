package kz.smartcity.walk.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kz.smartcity.walk.data.Categories
import kz.smartcity.walk.data.api.ProfileEvent
import kz.smartcity.walk.data.api.ProfileOp
import kz.smartcity.walk.data.db.AppDatabase
import kz.smartcity.walk.data.db.CategoryCounter
import kz.smartcity.walk.data.db.PendingEvent
import kz.smartcity.walk.data.db.ProfileFact
import kotlin.random.Random

/** Портрет живёт только на устройстве: модель возвращает операции, устройство применяет их по правилам. */
class ProfileRepository(private val db: AppDatabase, private val json: Json) {
    private val dao = db.profile()

    val facts: Flow<List<ProfileFact>> = dao.factsFlow()
    val counters: Flow<List<CategoryCounter>> = dao.countersFlow()

    suspend fun applyOps(ops: List<ProfileOp>, nowMs: Long) {
        for (op in ops) {
            if (op.key.startsWith("category.")) {
                val cat = op.key.removePrefix("category.")
                when (op.op) {
                    "add", "reinforce" -> bumpCounter(cat, alpha = 1.0, nowMs = nowMs)
                    "weaken" -> bumpCounter(cat, beta = 1.0, nowMs = nowMs)
                    "remove" -> dao.upsertCounter(CategoryCounter(cat, 1.0, 1.0, nowMs))
                }
                continue
            }
            val existing = dao.fact(op.key, op.value)
            when (op.op) {
                "add", "reinforce" -> if (existing == null) {
                    dao.upsertFact(ProfileFact(key = op.key, value = op.value, confidence = ProfileMath.added(op.explicit),
                        confirmations = 1, lastConfirmedMs = nowMs, explicit = op.explicit))
                } else {
                    val c = ProfileMath.decayed(existing.confidence, existing.lastConfirmedMs, nowMs)
                    dao.upsertFact(existing.copy(confidence = ProfileMath.reinforced(c), confirmations = existing.confirmations + 1,
                        lastConfirmedMs = nowMs, explicit = existing.explicit || op.explicit))
                }
                "weaken" -> if (existing != null) {
                    val c = ProfileMath.decayed(existing.confidence, existing.lastConfirmedMs, nowMs)
                    dao.upsertFact(existing.copy(confidence = ProfileMath.weakened(c), lastConfirmedMs = nowMs))
                }
                "remove" -> dao.deleteFact(op.key, op.value)
            }
        }
        prune(nowMs)
    }

    /** Факт с c < 0,1 удаляется сам; не больше 200 фактов. */
    private suspend fun prune(nowMs: Long) {
        val all = dao.facts().map { it to ProfileMath.decayed(it.confidence, it.lastConfirmedMs, nowMs) }
        all.filter { it.second < ProfileMath.MIN_CONFIDENCE }.forEach { dao.deleteFact(it.first.id) }
        val alive = all.filter { it.second >= ProfileMath.MIN_CONFIDENCE }.sortedByDescending { it.second }
        alive.drop(ProfileMath.MAX_FACTS).forEach { dao.deleteFact(it.first.id) }
    }

    private suspend fun bumpCounter(category: String, alpha: Double = 0.0, beta: Double = 0.0, nowMs: Long) {
        val c = dao.counter(category) ?: CategoryCounter(category, 1.0, 1.0, nowMs)
        dao.upsertCounter(c.copy(alpha = c.alpha + alpha, beta = c.beta + beta, updatedMs = nowMs))
    }

    /** Свайпы и посещения меняют счётчики напрямую, без модели: «хочу» α+1, «не то» β+1, маршрут α+2. */
    suspend fun onSwipe(category: String, like: Boolean, nowMs: Long) =
        if (like) bumpCounter(category, alpha = 1.0, nowMs = nowMs) else bumpCounter(category, beta = 1.0, nowMs = nowMs)

    suspend fun onRouteCompleted(category: String?, nowMs: Long) {
        if (category != null) bumpCounter(category, alpha = 2.0, nowMs = nowMs)
    }

    /**
     * Ручной выбор интересов — сильный стартовый сигнал (α+3), снятый выбор его убирает. Дальше веса
     * сдвигают поиски и свайпы, так что рекомендации меняются вместе с поведением.
     */
    suspend fun seedInterests(added: Set<String>, removed: Set<String>, nowMs: Long) {
        added.forEach { bumpCounter(it, alpha = SEED, nowMs = nowMs) }
        removed.forEach { cat ->
            dao.counter(cat)?.let { dao.upsertCounter(it.copy(alpha = (it.alpha - SEED).coerceAtLeast(1.0), updatedMs = nowMs)) }
        }
    }

    suspend fun affinity(): Map<String, Double> =
        dao.counters().associate { it.category to ProfileMath.affinity(it.alpha, it.beta) }

    /** 3 категории сэмплированием Томпсона + 1 случайная, чтобы пробовать новое (раздел 9). */
    suspend fun deckCategories(rnd: Random = Random.Default): List<String> {
        val counters = dao.counters().associateBy { it.category }
        val pool = Categories.all.keys.filter { it !in Categories.notForDeck }
        val ranked = pool.sortedByDescending { id ->
            val c = counters[id]
            ProfileMath.sampleBeta(c?.alpha ?: 1.0, c?.beta ?: 1.0, rnd)
        }
        val top = ranked.take(3)
        val explore = (pool - top.toSet()).randomOrNull(rnd)
        return top + listOfNotNull(explore)
    }

    suspend fun thetaSamples(categories: Collection<String>, rnd: Random = Random.Default): Map<String, Double> {
        val counters = dao.counters().associateBy { it.category }
        return categories.associateWith { id ->
            val c = counters[id]
            ProfileMath.sampleBeta(c?.alpha ?: 1.0, c?.beta ?: 1.0, rnd)
        }
    }

    /** Сводка для промптов: 15 самых уверенных фактов и сильные интересы, до ~300 токенов. */
    suspend fun summary(nowMs: Long): String {
        val parts = mutableListOf<String>()
        val counters = dao.counters().filter { it.alpha + it.beta >= 3.0 }
        val likes = counters.filter { ProfileMath.affinity(it.alpha, it.beta) >= 0.6 }
            .sortedByDescending { ProfileMath.affinity(it.alpha, it.beta) }.take(5)
        val dislikes = counters.filter { ProfileMath.affinity(it.alpha, it.beta) <= 0.35 }.take(3)
        if (likes.isNotEmpty()) parts += "любит: " + likes.joinToString(", ") { Categories.plural(it.category) }
        if (dislikes.isNotEmpty()) parts += "не интересны: " + dislikes.joinToString(", ") { Categories.plural(it.category) }
        dao.facts()
            .map { it to ProfileMath.decayed(it.confidence, it.lastConfirmedMs, nowMs) }
            .filter { it.second >= ProfileMath.MIN_CONFIDENCE }
            .sortedByDescending { it.second }
            .take(ProfileMath.SUMMARY_FACTS)
            .forEach { (f, _) -> parts += "${KEY_LABELS[f.key] ?: f.key}: ${f.value}" }
        return parts.joinToString("; ").take(1200)
    }

    suspend fun updateFactValue(fact: ProfileFact, value: String) {
        dao.deleteFact(fact.id)
        dao.upsertFact(fact.copy(id = 0, value = value.take(120), explicit = true))
    }

    suspend fun deleteFact(id: Long) = dao.deleteFact(id)

    suspend fun deleteAll() {
        dao.deleteAllFacts()
        dao.deleteAllCounters()
    }

    suspend fun enqueueEvent(event: ProfileEvent, nowMs: Long) {
        db.events().insert(PendingEvent(payload = json.encodeToString(ProfileEvent.serializer(), event), createdMs = nowMs))
    }

    companion object {
        private const val SEED = 3.0
        val KEY_LABELS = mapOf(
            "cuisine" to "кухня", "ambience" to "атмосфера", "price_level" to "цены", "time_pattern" to "привычное время",
            "transport_default" to "транспорт", "company" to "компания", "walk_tolerance" to "прогулка",
            "dislikes" to "не любит", "explicit" to "о себе",
        )
    }
}
