package kz.smartcity.walk.walk

import kz.smartcity.walk.data.Geo
import kz.smartcity.walk.data.LatLng

/**
 * Детектор прогулки: 3 состояния, 4 перехода (раздел 10).
 * Нет прогулки → возможная (переход активности WALKING/RUNNING/ON_BICYCLE);
 * возможная → идёт (3 мин движения и 150 м смещения); возможная → нет (ложный старт: 5 мин без смещения);
 * идёт → нет (10 мин без движения или поездка на транспорте).
 */
class WalkDetector(
    private val confirmMs: Long = 3 * 60_000L,
    private val confirmDistanceM: Double = 150.0,
    private val falseStartMs: Long = 5 * 60_000L,
    private val stopAfterIdleMs: Long = 10 * 60_000L,
    private val vehicleStopMs: Long = 3 * 60_000L,
    private val moveStepM: Double = 50.0,
) {
    enum class State { IDLE, CANDIDATE, WALKING }

    data class Summary(val startMs: Long, val endMs: Long, val distanceM: Double, val lastPos: LatLng?) {
        val durationMin: Int get() = ((endMs - startMs) / 60_000L).toInt()
    }

    sealed interface Transition {
        data object Started : Transition
        data object Confirmed : Transition
        data object FalseStart : Transition
        data class Finished(val summary: Summary) : Transition
    }

    var state = State.IDLE
        private set
    var transport = "walking"
        private set
    var startMs = 0L
        private set
    var distanceM = 0.0
        private set

    private var startPos: LatLng? = null
    private var lastPos: LatLng? = null
    private var anchor: LatLng? = null
    private var lastMoveMs = 0L
    private var vehicleSinceMs: Long? = null

    fun onActivity(type: Activity, enter: Boolean, nowMs: Long): Transition? {
        when {
            type.moving && enter -> {
                transport = if (type == Activity.BICYCLE) "bicycle" else "walking"
                vehicleSinceMs = null
                if (state == State.IDLE) {
                    state = State.CANDIDATE
                    startMs = nowMs
                    lastMoveMs = nowMs
                    distanceM = 0.0
                    startPos = null
                    lastPos = null
                    anchor = null
                    return Transition.Started
                }
            }
            type == Activity.VEHICLE && enter -> vehicleSinceMs = nowMs
            type == Activity.VEHICLE && !enter -> vehicleSinceMs = null
        }
        return tick(nowMs)
    }

    fun onLocation(pos: LatLng, nowMs: Long): Transition? {
        if (state == State.IDLE) return null
        if (startPos == null) startPos = pos
        if (anchor == null) anchor = pos
        lastPos?.let { prev ->
            val step = Geo.distance(prev, pos)
            if (step >= 10.0) distanceM += step // мелкий дрейф GPS не считаем
        }
        lastPos = pos
        anchor?.let { a ->
            if (Geo.distance(a, pos) >= moveStepM) {
                anchor = pos
                lastMoveMs = nowMs
            }
        }
        return tick(nowMs)
    }

    /** Без внешних событий — раз в минуту. */
    fun tick(nowMs: Long): Transition? {
        val displacement = startPos?.let { s -> lastPos?.let { Geo.distance(s, it) } } ?: 0.0
        when (state) {
            State.CANDIDATE -> {
                if (nowMs - startMs >= confirmMs && displacement >= confirmDistanceM) {
                    state = State.WALKING
                    return Transition.Confirmed
                }
                val noMove = nowMs - lastMoveMs >= falseStartMs
                if ((nowMs - startMs >= falseStartMs && noMove) || nowMs - startMs >= 2 * falseStartMs) {
                    reset()
                    return Transition.FalseStart
                }
            }
            State.WALKING -> {
                val idle = nowMs - lastMoveMs >= stopAfterIdleMs
                val inVehicle = vehicleSinceMs?.let { nowMs - it >= vehicleStopMs } ?: false
                if (idle || inVehicle) {
                    val summary = Summary(startMs, nowMs, distanceM, lastPos)
                    reset()
                    return Transition.Finished(summary)
                }
            }
            State.IDLE -> Unit
        }
        return null
    }

    /** Отладка: сразу «идёт прогулка», без подтверждения. */
    fun forceWalking(nowMs: Long) {
        state = State.WALKING
        startMs = nowMs
        lastMoveMs = nowMs
    }

    private fun reset() {
        state = State.IDLE
        startPos = null
        lastPos = null
        anchor = null
        vehicleSinceMs = null
    }
}

/** Типы Activity Recognition, которые нужны детектору. */
enum class Activity(val moving: Boolean) {
    WALKING(true), RUNNING(true), BICYCLE(true), VEHICLE(false), STILL(false), OTHER(false);

    companion object {
        fun fromDetected(type: Int): Activity = when (type) {
            com.google.android.gms.location.DetectedActivity.WALKING -> WALKING
            com.google.android.gms.location.DetectedActivity.RUNNING -> RUNNING
            com.google.android.gms.location.DetectedActivity.ON_BICYCLE -> BICYCLE
            com.google.android.gms.location.DetectedActivity.IN_VEHICLE -> VEHICLE
            com.google.android.gms.location.DetectedActivity.STILL -> STILL
            else -> OTHER
        }
    }
}
