package kz.smartcity.walk.nav

import kz.smartcity.walk.data.Geo
import kz.smartcity.walk.data.LatLng
import kotlin.math.max
import kotlin.math.roundToInt

data class EngineManeuver(val text: String, val icon: String?, val turnDirection: String?, val point: LatLng)

data class NavUpdate(
    val alongM: Double,
    val crossTrackM: Double,
    val nextManeuver: EngineManeuver?,
    val distanceToManeuverM: Double?,
    val remainingM: Double,
    val etaMin: Int,
    val offRoute: Boolean,
    val needReroute: Boolean,
    val arrived: Boolean,
    val prompt: String?,
)

/**
 * Логика пошаговой навигации без Android (раздел 7): привязка к линии маршрута, манёвры,
 * подсказки голосом, сход с маршрута и прибытие. Покрыта unit-тестами.
 */
class NavigationEngine(
    geometry: List<LatLng>,
    maneuvers: List<EngineManeuver>,
    val transport: String,
    private val speedMps: Double,
    private val arrivalRadiusM: Double = 25.0,
) {
    private val driving = transport == "driving" || transport == "taxi"
    val offRouteThresholdM = if (driving) 50.0 else 30.0
    private val offRouteHoldMs = 8_000L
    private val rerouteMinIntervalMs = 20_000L
    private val promptDistances = if (driving) listOf(200.0, 50.0) else listOf(50.0, 15.0)

    private val points: List<LatLng> = geometry.ifEmpty { maneuvers.map { it.point } }
    private val origin = points.first()
    private val xy = points.map { Geo.toXY(origin, it) }
    private val cum = DoubleArray(points.size)
    val totalM: Double

    private val maneuverList: List<EngineManeuver>
    private val maneuverAlong: List<Double>

    private var offRouteSinceMs: Long? = null
    private var lastRerouteMs = Long.MIN_VALUE / 4 // первое перестроение не ограничено интервалом
    private var lastAlong = 0.0
    private val prompted = mutableSetOf<Pair<Int, Int>>()
    private var joinedRoute = false

    init {
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + Geo.distance(points[i - 1], points[i])
        }
        totalM = cum.lastOrNull() ?: 0.0
        // Первый манёвр («начните движение») голосом не повторяем; остальные — по положению на линии.
        val list = maneuvers.drop(1)
        maneuverList = list
        maneuverAlong = list.map { project(it.point).first }
    }

    /** Проекция на ломаную: (расстояние вдоль маршрута, расстояние от линии). */
    private fun project(p: LatLng): Pair<Double, Double> {
        val q = Geo.toXY(origin, p)
        var bestAlong = 0.0
        var bestDist = Double.MAX_VALUE
        for (i in 0 until xy.size - 1) {
            val a = xy[i]
            val b = xy[i + 1]
            val dx = b[0] - a[0]
            val dy = b[1] - a[1]
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((q[0] - a[0]) * dx + (q[1] - a[1]) * dy) / len2).coerceIn(0.0, 1.0)
            val px = a[0] + t * dx
            val py = a[1] + t * dy
            val d = kotlin.math.hypot(q[0] - px, q[1] - py)
            val along = cum[i] + t * (cum[i + 1] - cum[i])
            // Небольшой штраф за «прыжок назад» по маршруту — чтобы не цепляться за параллельный участок.
            val penalty = if (along < lastAlong - 50) 15.0 else 0.0
            if (d + penalty < bestDist) {
                bestDist = d + penalty
                bestAlong = along
            }
        }
        if (xy.size == 1) bestDist = Geo.distance(points[0], p)
        return bestAlong to (bestDist.coerceAtLeast(0.0))
    }

    fun update(pos: LatLng, nowMs: Long): NavUpdate {
        val (along, cross) = project(pos)
        lastAlong = max(lastAlong, along)
        if (cross <= offRouteThresholdM) joinedRoute = true

        // Сход с маршрута: дальше порога дольше 8 с → перестроение, не чаще раза в 20 с.
        val off = joinedRoute && cross > offRouteThresholdM
        var reroute = false
        if (off) {
            val since = offRouteSinceMs ?: nowMs.also { offRouteSinceMs = it }
            if (nowMs - since >= offRouteHoldMs && nowMs - lastRerouteMs >= rerouteMinIntervalMs) {
                reroute = true
                lastRerouteMs = nowMs
                offRouteSinceMs = null
            }
        } else {
            offRouteSinceMs = null
        }

        val toEnd = Geo.distance(pos, points.last())
        val arrived = toEnd <= arrivalRadiusM
        val idx = maneuverAlong.indexOfFirst { it > along + 1.0 }
        val next = if (idx >= 0) maneuverList[idx] else null
        val toManeuver = if (idx >= 0) maneuverAlong[idx] - along else null
        var prompt: String? = null
        if (next != null && toManeuver != null && !arrived && !off) {
            for ((pi, threshold) in promptDistances.withIndex()) {
                if (toManeuver <= threshold && (idx to pi) !in prompted) {
                    // Помечаем и более дальние пороги, чтобы не говорить их после ближнего.
                    for (k in 0..pi) prompted += idx to k
                    prompt = if (pi == promptDistances.lastIndex) next.text
                    else "Через ${roundDistance(toManeuver)} ${next.text.replaceFirstChar { it.lowercase() }}"
                }
            }
        }
        if (arrived) prompt = "Вы на месте"
        val remaining = if (arrived) 0.0 else max(0.0, totalM - along) + if (cross > offRouteThresholdM) cross else 0.0
        return NavUpdate(
            alongM = along, crossTrackM = cross, nextManeuver = next, distanceToManeuverM = toManeuver,
            remainingM = remaining, etaMin = (remaining / speedMps / 60.0).roundToInt(), offRoute = off,
            needReroute = reroute && !arrived, arrived = arrived, prompt = prompt,
        )
    }

    companion object {
        fun roundDistance(m: Double): String = when {
            m >= 1000 -> String.format(java.util.Locale.US, "%.1f км", m / 1000).replace('.', ',')
            m >= 100 -> "${(m / 50).roundToInt() * 50} м"
            else -> "${(m / 10).roundToInt() * 10} м"
        }

        fun defaultSpeedMps(transport: String) = when (transport) {
            "bicycle" -> 4.2; "scooter" -> 3.3; "driving", "taxi" -> 7.0; "public_transport" -> 4.2
            else -> 1.25
        }
    }
}
