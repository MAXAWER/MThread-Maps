package kz.smartcity.walk.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Точная геолокация остаётся на устройстве; на сервер уходит только LatLng.rounded(). */
class LocationProvider(private val context: Context, private val settings: Settings) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    fun hasForeground(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
        granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackground(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    @Volatile private var cached: LatLng? = null
    @Volatile private var cachedAtMs = 0L

    /**
     * Текущая точка: свежий кэш (до 2 мин), иначе запрос не дольше 4 с, иначе последняя известная.
     * Без таймаута getCurrentLocation ждёт фикс до ~30 с, и поиск «висит».
     */
    @SuppressLint("MissingPermission")
    suspend fun current(): LatLng? {
        settings.mockLocation?.let { return it }
        if (!hasForeground()) return null
        cached?.takeIf { System.currentTimeMillis() - cachedAtMs < 120_000L }?.let { return it }
        return try {
            val cts = CancellationTokenSource()
            val fresh = withTimeoutOrNull(4_000L) {
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
            }
            if (fresh == null) cts.cancel()
            val loc = fresh ?: client.lastLocation.await()
            loc?.let { LatLng(it.latitude, it.longitude) }?.also { remember(it) } ?: cached
        } catch (e: Exception) {
            cached
        }
    }

    fun remember(p: LatLng) {
        cached = p
        cachedAtMs = System.currentTimeMillis()
    }

    /** Поток позиций. В отладке с подставной точкой — она же с заданным интервалом. */
    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long, priority: Int = Priority.PRIORITY_BALANCED_POWER_ACCURACY): Flow<Location> {
        val mock = settings.mockLocation
        if (mock != null) return flow {
            while (true) {
                emit(Location("mock").apply { latitude = mock.lat; longitude = mock.lon; accuracy = 10f; time = System.currentTimeMillis() })
                delay(intervalMs)
            }
        }
        return callbackFlow {
            val request = LocationRequest.Builder(priority, intervalMs).setMinUpdateIntervalMillis(intervalMs / 2).build()
            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let {
                        remember(LatLng(it.latitude, it.longitude))
                        trySend(it)
                    }
                }
            }
            if (hasForeground()) client.requestLocationUpdates(request, cb, Looper.getMainLooper())
            awaitClose { client.removeLocationUpdates(cb) }
        }
    }
}
