package kz.smartcity.walk.map

import kotlinx.coroutines.flow.SharedFlow
import kz.smartcity.walk.data.LatLng

/** Маркер: kind = rec (рекомендация 1–3), cand (кандидат), waypoint, dest, saved. */
data class MapMarker(
    val id: String,
    val lat: Double,
    val lon: Double,
    val kind: String,
    val label: String? = null,
    val icon: String? = null,
)

sealed interface MapEvent {
    data class Ready(val engine: String) : MapEvent
    data class MarkerClick(val id: String) : MapEvent
    data class LongPress(val point: LatLng) : MapEvent
    data class CameraIdle(val center: LatLng, val zoom: Double) : MapEvent
    data class Error(val message: String) : MapEvent
}

/**
 * Карта подключается через этот интерфейс: переход на нативный SDK 2ГИС позже —
 * замена одной реализации (раздел 7).
 */
interface MapProvider {
    val events: SharedFlow<MapEvent>
    fun setCenter(point: LatLng, zoom: Double? = null)
    fun setUserLocation(point: LatLng?)
    fun setMarkers(markers: List<MapMarker>)
    fun drawRoute(points: List<LatLng>, color: String = "#1F6BFF")
    fun fitBounds(points: List<LatLng>, top: Int = 120, bottom: Int = 360)
    fun clear(what: String = "all")
    /** Линейка масштаба: прячется, когда карту закрывают шторка или навигация. */
    fun setScaleVisible(visible: Boolean)
}
