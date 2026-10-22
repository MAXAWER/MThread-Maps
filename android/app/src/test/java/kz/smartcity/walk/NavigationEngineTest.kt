package kz.smartcity.walk

import kz.smartcity.walk.data.Geo
import kz.smartcity.walk.data.LatLng
import kz.smartcity.walk.nav.EngineManeuver
import kz.smartcity.walk.nav.NavigationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEngineTest {
    private val start = LatLng(51.1280, 71.4300)
    private val corner = Geo.fromXY(start, 0.0, 400.0)   // 400 м на север
    private val end = Geo.fromXY(start, 300.0, 400.0)    // затем 300 м на восток

    private fun engine(transport: String = "walking") = NavigationEngine(
        geometry = listOf(start, corner, end),
        maneuvers = listOf(
            EngineManeuver("Начните движение", "start", null, start),
            EngineManeuver("Поверните направо", "turn_right", "right", corner),
            EngineManeuver("Вы на месте", "finish", null, end),
        ),
        transport = transport, speedMps = 1.25,
    )

    @Test
    fun nextManeuverAndDistance() {
        val u = engine().update(Geo.fromXY(start, 0.0, 100.0), 0)
        assertEquals("Поверните направо", u.nextManeuver?.text)
        assertEquals(300.0, u.distanceToManeuverM!!, 2.0)
        assertEquals(600.0, u.remainingM, 2.0)
        assertFalse(u.offRoute)
    }

    @Test
    fun walkingPromptsAt50And15Meters() {
        val e = engine()
        assertEquals(null, e.update(Geo.fromXY(start, 0.0, 300.0), 0).prompt)
        val p50 = e.update(Geo.fromXY(start, 0.0, 355.0), 1_000).prompt
        assertTrue(p50!!.startsWith("Через"))
        assertEquals(null, e.update(Geo.fromXY(start, 0.0, 360.0), 2_000).prompt) // не повторяем
        assertEquals("Поверните направо", e.update(Geo.fromXY(start, 0.0, 390.0), 3_000).prompt)
    }

    @Test
    fun rerouteAfter8SecondsOffRouteNotMoreThanEvery20() {
        val e = engine()
        e.update(Geo.fromXY(start, 0.0, 50.0), 0) // на маршруте
        val off = Geo.fromXY(start, 40.0, 100.0)   // 40 м в сторону (> 30 м пешком)
        assertFalse(e.update(off, 1_000).needReroute)
        assertTrue(e.update(off, 5_000).offRoute)
        assertTrue(e.update(off, 9_100).needReroute)
        assertFalse(e.update(off, 18_000).needReroute)   // не чаще раза в 20 с
        assertFalse(e.update(off, 25_000).needReroute)
        assertTrue(e.update(off, 30_000).needReroute)
    }

    @Test
    fun drivingThresholdIs50Meters() {
        val e = engine("driving")
        e.update(Geo.fromXY(start, 0.0, 50.0), 0)
        val off = Geo.fromXY(start, 40.0, 100.0)
        e.update(off, 1_000)
        assertFalse(e.update(off, 12_000).needReroute)
    }

    @Test
    fun arrivalWithin25Meters() {
        val u = engine().update(Geo.fromXY(start, 280.0, 400.0), 0)
        assertTrue(u.arrived)
        assertEquals("Вы на месте", u.prompt)
        assertNotNull(u)
    }

    @Test
    fun noRerouteBeforeJoiningRoute() {
        // Маршрут сервера начинается в округлённой точке: пока пользователь не вышел на линию, перестраивать нельзя.
        val e = engine()
        val far = Geo.fromXY(start, -80.0, -20.0)
        e.update(far, 0)
        assertFalse(e.update(far, 30_000).needReroute)
    }
}
