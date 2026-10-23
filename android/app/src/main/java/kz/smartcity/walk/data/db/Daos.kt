package kz.smartcity.walk.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile_fact ORDER BY confidence DESC")
    fun factsFlow(): Flow<List<ProfileFact>>

    @Query("SELECT * FROM profile_fact")
    suspend fun facts(): List<ProfileFact>

    @Query("SELECT * FROM profile_fact WHERE `key` = :key AND value = :value LIMIT 1")
    suspend fun fact(key: String, value: String): ProfileFact?

    @Upsert
    suspend fun upsertFact(fact: ProfileFact)

    @Query("DELETE FROM profile_fact WHERE id = :id")
    suspend fun deleteFact(id: Long)

    @Query("DELETE FROM profile_fact WHERE `key` = :key AND value = :value")
    suspend fun deleteFact(key: String, value: String)

    @Query("DELETE FROM profile_fact")
    suspend fun deleteAllFacts()

    @Query("SELECT * FROM category_counter")
    fun countersFlow(): Flow<List<CategoryCounter>>

    @Query("SELECT * FROM category_counter")
    suspend fun counters(): List<CategoryCounter>

    @Query("SELECT * FROM category_counter WHERE category = :category")
    suspend fun counter(category: String): CategoryCounter?

    @Upsert
    suspend fun upsertCounter(counter: CategoryCounter)

    @Query("DELETE FROM category_counter")
    suspend fun deleteAllCounters()
}

@Dao
interface VisitDao {
    @Insert
    suspend fun insert(visit: Visit)

    @Query("SELECT * FROM visit WHERE timeMs >= :sinceMs ORDER BY timeMs DESC")
    suspend fun since(sinceMs: Long): List<Visit>

    @Query("DELETE FROM visit WHERE timeMs < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)

    @Query("DELETE FROM visit")
    suspend fun deleteAll()
}

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: PendingEvent)

    @Query("SELECT * FROM pending_event ORDER BY id LIMIT 20")
    suspend fun batch(): List<PendingEvent>

    @Query("DELETE FROM pending_event WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM pending_event")
    suspend fun deleteAll()
}

@Dao
interface DeckDao {
    @Query("SELECT * FROM deck_card WHERE status = 'new' ORDER BY rank")
    fun newCards(): Flow<List<DeckCardEntity>>

    @Query("SELECT COUNT(*) FROM deck_card WHERE status = 'new'")
    suspend fun newCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cards: List<DeckCardEntity>)

    @Query("DELETE FROM deck_card WHERE status = 'new'")
    suspend fun deleteNew()

    @Transaction
    suspend fun replaceNew(cards: List<DeckCardEntity>) {
        deleteNew()
        insertAll(cards)
    }

    @Query("UPDATE deck_card SET status = :status WHERE poiId = :poiId")
    suspend fun setStatus(poiId: String, status: String)

    @Query("SELECT * FROM deck_card WHERE status = 'new' ORDER BY rank")
    suspend fun newList(): List<DeckCardEntity>

    @Query("UPDATE deck_card SET rank = :rank WHERE poiId = :poiId")
    suspend fun setRank(poiId: String, rank: Int)

    /** Понравившиеся карточки целиком (с фото, рейтингом, «почему») — для раздела «Понравившиеся». */
    @Query("SELECT * FROM deck_card WHERE status IN ('liked', 'visited') ORDER BY createdMs DESC")
    fun likedCards(): Flow<List<DeckCardEntity>>

    @Query("SELECT * FROM deck_card WHERE poiId = :poiId LIMIT 1")
    suspend fun byId(poiId: String): DeckCardEntity?

    @Query("SELECT poiId FROM deck_card WHERE status != 'new' AND createdMs >= :sinceMs")
    suspend fun swipedIds(sinceMs: Long): List<String>

    @Query("DELETE FROM deck_card")
    suspend fun deleteAll()
}

@Dao
interface SavedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(place: SavedPlace)

    @Query("SELECT * FROM saved_place ORDER BY savedMs DESC")
    fun all(): Flow<List<SavedPlace>>

    @Query("DELETE FROM saved_place WHERE poiId = :poiId")
    suspend fun delete(poiId: String)

    @Query("DELETE FROM saved_place")
    suspend fun deleteAll()
}
