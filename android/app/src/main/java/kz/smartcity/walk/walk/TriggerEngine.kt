package kz.smartcity.walk.walk

import kz.smartcity.walk.data.api.WeatherDto
import java.time.Instant
import java.time.ZonedDateTime

/** Вход движка триггеров — всё, что нужно для решения, без Android. */
data class TriggerInput(
    val now: ZonedDateTime,
    val walkStartMs: Long,
    val weather: WeatherDto?,
    val feelsLikeHistory: List<Pair<Long, Double>>,
    val batteryPct: Int?,
    val foodStopDuringWalk: Boolean,
    val firedThisWalk: Set<String>,
    val notificationsToday: Int,
    val enabled: (String) -> Boolean,
    val lastFiredMs: (String) -> Long,
    val pausedUntilMs: (String) -> Long,
)

/**
 * Триггеры уведомлений (раздел 10). Общие ограничения: не больше 3 уведомлений в день,
 * тихие часы 22:00–08:00, каждый триггер отключается в профиле, «Не сейчас» удваивает паузу.
 */
object TriggerEngine {
    const val MAX_PER_DAY = 3
    private const val MIN = 60_000L

    fun quietHours(now: ZonedDateTime) = now.hour >= 22 || now.hour < 8

    /** Общие ограничения — их же проверяет навигация для триггера «Цель закроется». */
    fun globalAllowed(now: ZonedDateTime, notificationsToday: Int) = !quietHours(now) && notificationsToday < MAX_PER_DAY

    fun evaluate(i: TriggerInput): String? {
        if (!globalAllowed(i.now, i.notificationsToday)) return null
        val nowMs = i.now.toInstant().toEpochMilli()
        val walkMin = (nowMs - i.walkStartMs) / MIN
        fun allowed(t: String, cooldownMin: Long = 0): Boolean =
            i.enabled(t) && nowMs >= i.pausedUntilMs(t) && nowMs - i.lastFiredMs(t) >= cooldownMin * MIN
        fun firedToday(t: String): Boolean {
            val last = i.lastFiredMs(t)
            return last > 0 && Instant.ofEpochMilli(last).atZone(i.now.zone).toLocalDate() == i.now.toLocalDate()
        }
        val w = i.weather

        // Скоро осадки: ≥ 0,3 мм за 15 мин с началом через 15–45 мин и подтверждение второго источника.
        if (w?.rainSoon == true && allowed("rain", 90)) return "rain"

        // Холод или ветер: ощущаемая температура упала на 6 °C за 2 ч или ниже −15 °C; ветер ≥ 12 м/с.
        val feels = w?.feelsLikeC ?: w?.tempC
        val twoHoursAgo = i.feelsLikeHistory.filter { nowMs - it.first <= 120 * MIN }.minByOrNull { it.first }?.second
        val drop = if (feels != null && twoHoursAgo != null) twoHoursAgo - feels else 0.0
        if (((feels != null && feels < -15) || (w?.windMs ?: 0.0) >= 12.0 || drop >= 6.0) && allowed("cold", 120)) return "cold"

        // Низкий заряд: 1 раз за прогулку.
        if ((i.batteryPct ?: 100) <= 20 && "low_battery" !in i.firedThisWalk && allowed("low_battery")) return "low_battery"

        // Время еды: 12–14 или 18–20, прогулка ≥ 30 мин без остановки на еду; 1 раз за окно.
        val window = when (i.now.hour) { 12, 13 -> 12; 18, 19 -> 18; else -> null }
        if (window != null && walkMin >= 30 && !i.foodStopDuringWalk && allowed("meal")) {
            val last = i.lastFiredMs("meal")
            val lastAt = if (last > 0) Instant.ofEpochMilli(last).atZone(i.now.zone) else null
            val sameWindow = lastAt != null && lastAt.toLocalDate() == i.now.toLocalDate() && lastAt.hour in window until window + 2
            if (!sameWindow) return "meal"
        }

        // Скоро закат: 20–40 мин до заката, 1 раз в день (рядом ли смотровая — проверит сервер).
        val toSunset = w?.minutesToSunset
        if (toSunset != null && toSunset in 20..40 && !firedToday("sunset") && allowed("sunset")) return "sunset"

        // Долгая прогулка: ≥ 90 мин, 1 раз за прогулку.
        if (walkMin >= 90 && "long_walk" !in i.firedThisWalk && allowed("long_walk")) return "long_walk"
        return null
    }
}
