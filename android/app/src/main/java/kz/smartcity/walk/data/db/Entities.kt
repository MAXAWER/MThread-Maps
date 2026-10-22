package kz.smartcity.walk.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Факт портрета: значение, уверенность c, число подтверждений, дата, источник (раздел 8). */
@Entity(tableName = "profile_fact", indices = [Index(value = ["key", "value"], unique = true)])
data class ProfileFact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val confidence: Double,
    val confirmations: Int,
    val lastConfirmedMs: Long,
    val explicit: Boolean,
)

/** Интерес к категории — счётчики Beta(α, β). Старт α = β = 1. */
@Entity(tableName = "category_counter")
data class CategoryCounter(
    @PrimaryKey val category: String,
    val alpha: Double,
    val beta: Double,
    val updatedMs: Long,
)

/** История посещений и частые районы: только на устройстве, 90 дней. */
@Entity(tableName = "visit", indices = [Index("timeMs")])
data class Visit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val placeId: String,
    val category: String?,
    val geohash6: String,
    val timeMs: Long,
)

/** Итог поиска для обновления портрета: уходит на сервер фоновой задачей при наличии сети. */
@Entity(tableName = "pending_event")
data class PendingEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val payload: String,
    val createdMs: Long,
)

/** Карточка колоды «Для вас»: JSON карточки сервера + место в колоде. */
@Entity(tableName = "deck_card")
data class DeckCardEntity(
    @PrimaryKey val poiId: String,
    val json: String,
    val rank: Int,
    val status: String,
    val createdMs: Long,
)

/** «Хочу»: сохранённое место. */
@Entity(tableName = "saved_place")
data class SavedPlace(
    @PrimaryKey val poiId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val category: String?,
    val savedMs: Long,
)
