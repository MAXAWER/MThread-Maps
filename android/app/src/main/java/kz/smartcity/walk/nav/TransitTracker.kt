package kz.smartcity.walk.nav

import kz.smartcity.walk.data.Geo
import kz.smartcity.walk.data.LatLng
import kotlin.math.hypot

/** Этап поездки для навигатора: линия, остановки и подписи. */
data class TransitLeg(
    val kind: String,
    val names: List<String>,
    val color: String?,
    val fromName: String?,
    val toName: String?,
    val durationS: Int,
    val waitS: Int,
    val distanceM: Int,
    val line: List<LatLng>,
    val stops: List<LatLng>,
    val stopNames: List<String?>,
) {
    val isWalk get() = kind == "walk"
}

/** Где человек на маршруте с пересадками: номер этапа, сколько до его конца и сколько остановок осталось. */
data class TransitProgress(val legIndex: Int, val toLegEndM: Int, val stopsLeft: Int?, val nextStopName: String?)

/**
 * Следит за этапами маршрута на общественном транспорте (без Android, покрыт тестами).
 * Этап только растёт: переход на следующий — у конца текущего или когда человек явно уже на линии следующего.
 */
class TransitTracker(val legs: List<TransitLeg>) {
    private var index = 0
    private val origin = legs.firstOrNull { it.line.isNotEmpty() }?.line?.first() ?: LatLng(0.0, 0.0)
    private val xy = legs.map { leg -> leg.line.map { Geo.toXY(origin, it) } }
    private val cum = xy.map { pts ->
        DoubleArray(pts.size).also { c -> for (i in 1 until pts.size) c[i] = c[i - 1] + dist(pts[i - 1], pts[i]) }
    }

    fun update(pos: LatLng): TransitProgress {
        if (legs.isEmpty()) return TransitProgress(0, 0, null, null)
        val q = Geo.toXY(origin, pos)
        // У конца этапа (≤ 35 м) или ближе к линии следующего — переходим дальше.
        while (index < legs.lastIndex) {
            val (along, cross) = project(index, q)
            val left = (cum[index].lastOrNull() ?: 0.0) - along
            val (_, nextCross) = project(index + 1, q)
            if (left <= 35.0 || (nextCross + 15.0 < cross && nextCross < 40.0)) index++ else break
        }
        val (along, _) = project(index, q)
        val total = cum[index].lastOrNull() ?: 0.0
        val leg = legs[index]
        var stopsLeft: Int? = null
        var nextStop: String? = null
        if (!leg.isWalk && leg.stops.isNotEmpty()) {
            val stopAlong = leg.stops.map { project(index, Geo.toXY(origin, it)).first }
            val ahead = stopAlong.indices.filter { stopAlong[it] > along + 20.0 }
            stopsLeft = ahead.size
            nextStop = ahead.firstOrNull()?.let { leg.stopNames.getOrNull(it) }
        }
        return TransitProgress(index, (total - along).coerceAtLeast(0.0).toInt(), stopsLeft, nextStop)
    }

    /** Проекция точки на линию этапа: (расстояние вдоль, расстояние от линии). */
    private fun project(leg: Int, q: DoubleArray): Pair<Double, Double> {
        val pts = xy[leg]
        if (pts.isEmpty()) return 0.0 to Double.MAX_VALUE
        if (pts.size == 1) return 0.0 to dist(pts[0], q)
        var bestAlong = 0.0
        var best = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val dx = b[0] - a[0]
            val dy = b[1] - a[1]
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((q[0] - a[0]) * dx + (q[1] - a[1]) * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(q[0] - (a[0] + t * dx), q[1] - (a[1] + t * dy))
            if (d < best) {
                best = d
                bestAlong = cum[leg][i] + t * (cum[leg][i + 1] - cum[leg][i])
            }
        }
        return bestAlong to best
    }

    private fun dist(a: DoubleArray, b: DoubleArray) = hypot(a[0] - b[0], a[1] - b[1])
}
