package kz.smartcity.walk.data

import android.content.Context
import androidx.core.content.edit
import kz.smartcity.walk.BuildConfig
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID

/** Настройки устройства (SharedPreferences). Портрет и история — в Room. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("walk_settings", Context.MODE_PRIVATE)

    /** Случайный UUID на установку, без персональных данных (заголовок X-Install-Id). */
    val installId: String
        get() = prefs.getString(KEY_INSTALL, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_INSTALL, it) }
        }

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(v) = prefs.edit { putBoolean("onboarding_done", v) }

    var defaultTransport: String
        get() = prefs.getString("default_transport", "walking") ?: "walking"
        set(v) = prefs.edit { putString("default_transport", v) }

    var soundOn: Boolean
        get() = prefs.getBoolean("sound_on", true)
        set(v) = prefs.edit { putBoolean("sound_on", v) }

    /** Тактильный отклик (линейный вибромотор). */
    var hapticsOn: Boolean
        get() = prefs.getBoolean("haptics_on", true)
        set(v) = prefs.edit { putBoolean("haptics_on", v) }

    fun triggerEnabled(trigger: String): Boolean = prefs.getBoolean("trigger_on_$trigger", true)
    fun setTriggerEnabled(trigger: String, on: Boolean) = prefs.edit { putBoolean("trigger_on_$trigger", on) }

    /** Пауза триггера после «Не сейчас»: каждый раз удваивается. */
    fun triggerPausedUntil(trigger: String): Long = prefs.getLong("trigger_pause_until_$trigger", 0)
    fun snoozeTrigger(trigger: String, baseMinutes: Int, nowMs: Long) {
        val current = prefs.getInt("trigger_pause_min_$trigger", baseMinutes)
        val next = (current * 2).coerceAtMost(7 * 24 * 60)
        prefs.edit {
            putInt("trigger_pause_min_$trigger", next)
            putLong("trigger_pause_until_$trigger", nowMs + next * 60_000L)
        }
    }

    fun lastTriggerMs(trigger: String): Long = prefs.getLong("trigger_last_$trigger", 0)
    fun markTrigger(trigger: String, nowMs: Long, today: LocalDate) {
        prefs.edit {
            putLong("trigger_last_$trigger", nowMs)
            val day = prefs.getString("notif_day", "")
            val count = if (day == today.toString()) prefs.getInt("notif_count", 0) else 0
            putString("notif_day", today.toString())
            putInt("notif_count", count + 1)
        }
    }

    fun notificationsToday(today: LocalDate): Int =
        if (prefs.getString("notif_day", "") == today.toString()) prefs.getInt("notif_count", 0) else 0

    var lastDeckRefreshMs: Long
        get() = prefs.getLong("deck_refresh_ms", 0)
        set(v) = prefs.edit { putLong("deck_refresh_ms", v) }

    /** Демо-ключ 2ГИС: обновлять колоду не чаще 3 раз в сутки. */
    fun deckRefreshesToday(today: LocalDate): Int =
        if (prefs.getString("deck_day", "") == today.toString()) prefs.getInt("deck_count", 0) else 0

    fun countDeckRefresh(today: LocalDate) {
        val n = deckRefreshesToday(today)
        prefs.edit { putString("deck_day", today.toString()); putInt("deck_count", n + 1) }
    }

    // ---- отладочное меню (раздел 15): подстановка координат, времени, адреса сервера ----
    // В release-сборке подстановки не читаются: настоящий GPS, текущее время и адрес сервера из сборки.
    var apiBaseUrl: String
        get() = prefs.getString("api_base_url", null)?.takeIf { BuildConfig.DEBUG } ?: BuildConfig.API_BASE_URL
        set(v) = prefs.edit { putString("api_base_url", v.trim().trimEnd('/')) }

    var mockLocation: LatLng?
        get() {
            if (!BuildConfig.DEBUG || !prefs.contains("mock_lat")) return null
            return LatLng(prefs.getFloat("mock_lat", 0f).toDouble(), prefs.getFloat("mock_lon", 0f).toDouble())
        }
        set(v) = prefs.edit {
            if (v == null) { remove("mock_lat"); remove("mock_lon") }
            else { putFloat("mock_lat", v.lat.toFloat()); putFloat("mock_lon", v.lon.toFloat()) }
        }

    var timeOffsetMinutes: Int
        get() = if (BuildConfig.DEBUG) prefs.getInt("time_offset_min", 0) else 0
        set(v) = prefs.edit { putInt("time_offset_min", v) }

    /** Текущее время Астаны (с поправкой из отладочного меню). */
    fun now(): ZonedDateTime = ZonedDateTime.now(ASTANA).plusMinutes(timeOffsetMinutes.toLong())

    /** Удаление данных: всё, кроме идентификатора установки и адреса сервера. */
    fun clearUserData() {
        val keep = setOf(KEY_INSTALL, "api_base_url", "onboarding_done", "default_transport")
        prefs.edit { prefs.all.keys.filter { it !in keep }.forEach { remove(it) } }
    }

    companion object {
        private const val KEY_INSTALL = "install_id"
        val TRIGGERS = listOf("rain", "cold", "long_walk", "meal", "sunset", "low_battery", "closing")
        val TRIGGER_LABELS = mapOf(
            "rain" to "Скоро осадки", "cold" to "Холод или ветер", "long_walk" to "Долгая прогулка",
            "meal" to "Время еды", "sunset" to "Скоро закат", "low_battery" to "Низкий заряд",
            "closing" to "Цель закроется",
        )
        val TRIGGER_COOLDOWN_MIN = mapOf("rain" to 90, "cold" to 120)
    }
}
