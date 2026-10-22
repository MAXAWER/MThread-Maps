package kz.smartcity.walk

import kz.smartcity.walk.data.Geo
import kz.smartcity.walk.data.LatLng
import kz.smartcity.walk.data.api.WeatherDto
import kz.smartcity.walk.walk.Activity
import kz.smartcity.walk.walk.TriggerEngine
import kz.smartcity.walk.walk.TriggerInput
import kz.smartcity.walk.walk.WalkDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class WalkDetectorTest {
    private val p0 = LatLng(51.128, 71.430)
    private val min = 60_000L

    @Test
    fun confirmsAfter3MinutesAnd150Meters() {
        val d = WalkDetector()
        assertEquals(WalkDetector.Transition.Started, d.onActivity(Activity.WALKING, true, 0))
        d.onLocation(p0, 0)
        assertNull(d.onLocation(Geo.fromXY(p0, 0.0, 200.0), 2 * min)) // далеко, но меньше 3 минут
        assertEquals(WalkDetector.Transition.Confirmed, d.onLocation(Geo.fromXY(p0, 0.0, 260.0), 3 * min + 1))
        assertEquals(WalkDetector.State.WALKING, d.state)
    }

    @Test
    fun falseStartAfter5MinutesWithoutMovement() {
        val d = WalkDetector()
        d.onActivity(Activity.WALKING, true, 0)
        d.onLocation(p0, 0)
        d.onLocation(Geo.fromXY(p0, 5.0, 5.0), 3 * min)
        assertEquals(WalkDetector.Transition.FalseStart, d.tick(5 * min + 1))
        assertEquals(WalkDetector.State.IDLE, d.state)
    }

    @Test
    fun finishesAfter10MinutesIdleWithSummary() {
        val d = WalkDetector()
        d.onActivity(Activity.BICYCLE, true, 0)
        assertEquals("bicycle", d.transport)
        d.onLocation(p0, 0)
        d.onLocation(Geo.fromXY(p0, 0.0, 300.0), 4 * min)
        assertEquals(WalkDetector.State.WALKING, d.state)
        val t = d.tick(15 * min)
        assertTrue(t is WalkDetector.Transition.Finished)
        val s = (t as WalkDetector.Transition.Finished).summary
        assertEquals(300.0, s.distanceM, 5.0)
        assertEquals(15, s.durationMin)
    }

    @Test
    fun vehicleEndsWalk() {
        val d = WalkDetector()
        d.onActivity(Activity.WALKING, true, 0)
        d.forceWalking(0)
        d.onActivity(Activity.VEHICLE, true, min)
        assertTrue(d.tick(5 * min) is WalkDetector.Transition.Finished)
    }
}

class TriggerEngineTest {
    private val zone = ZoneOffset.ofHours(5)
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 22, h, m, 0, 0, zone)

    private fun input(
        now: ZonedDateTime, weather: WeatherDto? = null, walkMin: Long = 40, battery: Int? = 80, today: Int = 0,
        last: Map<String, Long> = emptyMap(), paused: Map<String, Long> = emptyMap(), fired: Set<String> = emptySet(),
        food: Boolean = false, enabled: Set<String>? = null, feels: List<Pair<Long, Double>> = emptyList(),
    ) = TriggerInput(
        now = now, walkStartMs = now.toInstant().toEpochMilli() - walkMin * 60_000, weather = weather, feelsLikeHistory = feels,
        batteryPct = battery, foodStopDuringWalk = food, firedThisWalk = fired, notificationsToday = today,
        enabled = { enabled?.contains(it) ?: true }, lastFiredMs = { last[it] ?: 0 }, pausedUntilMs = { paused[it] ?: 0 },
    )

    @Test
    fun rainHasPriorityAndCooldown90() {
        val w = WeatherDto(rainSoon = true, precipStartsInMin = 30, confirmed = true)
        val now = at(15)
        assertEquals("rain", TriggerEngine.evaluate(input(now, w)))
        val recent = now.toInstant().toEpochMilli() - 60 * 60_000
        assertTrue(TriggerEngine.evaluate(input(now, w, last = mapOf("rain" to recent))) != "rain")
    }

    @Test
    fun quietHoursAndDailyLimit() {
        val w = WeatherDto(rainSoon = true)
        assertNull(TriggerEngine.evaluate(input(at(22, 30), w)))
        assertNull(TriggerEngine.evaluate(input(at(7, 59), w)))
        assertNull(TriggerEngine.evaluate(input(at(15), w, today = 3)))
    }

    @Test
    fun snoozeAndDisable() {
        val w = WeatherDto(rainSoon = true)
        val now = at(15)
        val until = now.toInstant().toEpochMilli() + 60_000
        assertTrue(TriggerEngine.evaluate(input(now, w, paused = mapOf("rain" to until))) != "rain")
        assertTrue(TriggerEngine.evaluate(input(now, w, enabled = setOf("cold"))) != "rain")
    }

    @Test
    fun coldByDropOrFrostOrWind() {
        val now = at(15)
        val nowMs = now.toInstant().toEpochMilli()
        assertEquals("cold", TriggerEngine.evaluate(input(now, WeatherDto(feelsLikeC = -16.0))))
        assertEquals("cold", TriggerEngine.evaluate(input(now, WeatherDto(feelsLikeC = 2.0, windMs = 13.0))))
        assertEquals("cold", TriggerEngine.evaluate(input(now, WeatherDto(feelsLikeC = 0.0),
            feels = listOf(nowMs - 110 * 60_000 to 7.0))))
    }

    @Test
    fun mealWindowNeedsWalk30MinWithoutFood() {
        assertEquals("meal", TriggerEngine.evaluate(input(at(12, 30), walkMin = 35)))
        assertNull(TriggerEngine.evaluate(input(at(12, 30), walkMin = 20)))
        assertNull(TriggerEngine.evaluate(input(at(12, 30), walkMin = 35, food = true)))
        assertNull(TriggerEngine.evaluate(input(at(15), walkMin = 35)))
    }

    @Test
    fun sunsetLowBatteryLongWalk() {
        assertEquals("sunset", TriggerEngine.evaluate(input(at(16, 30), WeatherDto(minutesToSunset = 30))))
        assertEquals("low_battery", TriggerEngine.evaluate(input(at(16), battery = 15)))
        assertNull(TriggerEngine.evaluate(input(at(16), battery = 15, fired = setOf("low_battery"))))
        assertEquals("long_walk", TriggerEngine.evaluate(input(at(16), walkMin = 95)))
    }
}
