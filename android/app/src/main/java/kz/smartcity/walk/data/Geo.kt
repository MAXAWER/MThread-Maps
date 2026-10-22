package kz.smartcity.walk.data

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lon: Double) {
    /** Округление до 3 знаков ≈ 110 м: на сервер уходит только такая точка (раздел 13). */
    fun rounded(): LatLng = LatLng(round3(lat), round3(lon))
}

fun round3(v: Double): Double = (v * 1000.0).roundToLong() / 1000.0

/** Астана живёт по UTC+5 (с марта 2024 года). */
val ASTANA: ZoneOffset = ZoneOffset.ofHours(5)
private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

fun ZonedDateTime.iso(): String = format(ISO)

object Geo {
    private const val R = 6_371_000.0
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun distance(a: LatLng, b: LatLng): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * asin(min(1.0, sqrt(h)))
    }

    fun geohash(lat: Double, lon: Double, precision: Int = 6): String {
        var latLo = -90.0; var latHi = 90.0; var lonLo = -180.0; var lonHi = 180.0
        val out = StringBuilder()
        var bit = 0; var ch = 0; var even = true
        while (out.length < precision) {
            if (even) {
                val mid = (lonLo + lonHi) / 2
                if (lon >= mid) { ch = ch or (16 shr bit); lonLo = mid } else lonHi = mid
            } else {
                val mid = (latLo + latHi) / 2
                if (lat >= mid) { ch = ch or (16 shr bit); latLo = mid } else latHi = mid
            }
            even = !even
            if (bit < 4) bit++ else { out.append(BASE32[ch]); bit = 0; ch = 0 }
        }
        return out.toString()
    }

    fun geohashCenter(code: String): LatLng {
        var latLo = -90.0; var latHi = 90.0; var lonLo = -180.0; var lonHi = 180.0
        var even = true
        for (c in code) {
            val idx = BASE32.indexOf(c)
            for (mask in intArrayOf(16, 8, 4, 2, 1)) {
                if (even) {
                    val mid = (lonLo + lonHi) / 2
                    if (idx and mask != 0) lonLo = mid else lonHi = mid
                } else {
                    val mid = (latLo + latHi) / 2
                    if (idx and mask != 0) latLo = mid else latHi = mid
                }
                even = !even
            }
        }
        return LatLng((latLo + latHi) / 2, (lonLo + lonHi) / 2)
    }

    /** Локальная плоская проекция в метрах вокруг origin (для привязки к линии маршрута). */
    fun toXY(origin: LatLng, p: LatLng): DoubleArray {
        val x = Math.toRadians(p.lon - origin.lon) * R * cos(Math.toRadians(origin.lat))
        val y = Math.toRadians(p.lat - origin.lat) * R
        return doubleArrayOf(x, y)
    }

    fun fromXY(origin: LatLng, x: Double, y: Double): LatLng {
        val lat = origin.lat + Math.toDegrees(y / R)
        val lon = origin.lon + Math.toDegrees(x / (R * cos(Math.toRadians(origin.lat))))
        return LatLng(lat, lon)
    }
}
