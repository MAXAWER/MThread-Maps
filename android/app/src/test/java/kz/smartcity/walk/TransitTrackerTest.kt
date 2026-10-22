package kz.smartcity.walk

import kz.smartcity.walk.data.Geo
import kz.smartcity.walk.data.LatLng
import kz.smartcity.walk.nav.TransitLeg
import kz.smartcity.walk.nav.TransitTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitTrackerTest {
    private val start = LatLng(51.1280, 71.4300)
    private fun p(x: Double, y: Double) = Geo.fromXY(start, x, y)

    // 200 м пешком на север до остановки, 2 км автобусом на север (5 остановок), 150 м пешком на восток.
    private val stop1 = p(0.0, 200.0)
    private val stop2 = p(0.0, 2200.0)
    private val legs = listOf(
        TransitLeg("walk", emptyList(), null, null, "A", 160, 0, 200, listOf(start, stop1), emptyList(), emptyList()),
        TransitLeg("bus", listOf("47"), "#1F5FD0", "A", "F", 600, 120, 2000, listOf(stop1, stop2),
            (0..4).map { p(0.0, 200.0 + it * 500.0) }, listOf("A", "B", "C", "D", "F")),
        TransitLeg("walk", emptyList(), null, "F", null, 120, 0, 150, listOf(stop2, p(150.0, 2200.0)), emptyList(), emptyList()),
    )

    @Test
    fun walkToStopThenRideCountsStopsThenWalk() {
        val t = TransitTracker(legs)
        val w = t.update(p(0.0, 50.0))
        assertEquals(0, w.legIndex)
        assertTrue(w.toLegEndM in 140..160)

        val ride = t.update(p(0.0, 900.0)) // в автобусе, между B и C
        assertEquals(1, ride.legIndex)
        assertEquals(3, ride.stopsLeft) // C, D, F
        assertEquals("C", ride.nextStopName)

        val last = t.update(p(60.0, 2200.0))
        assertEquals(2, last.legIndex)
    }

    @Test
    fun legIndexNeverGoesBack() {
        val t = TransitTracker(legs)
        t.update(p(0.0, 900.0))
        assertEquals(1, t.update(p(0.0, 120.0)).legIndex) // GPS-скачок назад не возвращает к ходьбе
    }
}
