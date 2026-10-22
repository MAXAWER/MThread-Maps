package kz.smartcity.walk.walk

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kz.smartcity.walk.data.Geo
import kz.smartcity.walk.data.LatLng
import kz.smartcity.walk.data.Settings
import kz.smartcity.walk.data.api.ApiException
import kz.smartcity.walk.data.api.NotifyRequest
import kz.smartcity.walk.data.api.ProfileEvent
import kz.smartcity.walk.data.api.WeatherDto
import kz.smartcity.walk.data.iso
import kz.smartcity.walk.graph
import kz.smartcity.walk.notify.Notifier
import kz.smartcity.walk.work.Workers

data class WalkStatus(
    val state: WalkDetector.State = WalkDetector.State.IDLE,
    val startedMs: Long = 0,
    val distanceM: Int = 0,
    val transport: String = "walking",
    val weather: WeatherDto? = null,
    val lastTrigger: String? = null,
)

/**
 * Сервис прогулки: стартует сам по переходу активности, без кнопок. В состоянии «идёт прогулка» —
 * геолокация раз в 45 с, погода раз в 10 минут, проверка триггеров раз в минуту.
 */
class WalkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val detector = WalkDetector()
    private var locationJob: Job? = null
    private var tickJob: Job? = null
    private var weather: WeatherDto? = null
    private var weatherAtMs = 0L
    private val feelsLike = mutableListOf<Pair<Long, Double>>()
    private val firedThisWalk = mutableSetOf<String>()
    private var lastPos: LatLng? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundSafe("Определяем прогулку…")) return START_NOT_STICKY
        val g = graph
        val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (!g.location.hasForeground() || (!inForeground && !g.location.hasBackground() && g.settings.mockLocation == null)) {
            // Без «Разрешить всегда» сервис, созданный из фона, геолокацию не получит.
            stopSelf()
            return START_NOT_STICKY
        }
        val now = System.currentTimeMillis()
        when (intent?.action) {
            ACTION_ACTIVITY -> {
                val type = Activity.entries[intent.getIntExtra(EXTRA_TYPE, Activity.OTHER.ordinal)]
                handle(detector.onActivity(type, intent.getBooleanExtra(EXTRA_ENTER, false), now))
            }
            ACTION_FORCE -> {
                detector.onActivity(Activity.WALKING, true, now)
                detector.forceWalking(now)
                handle(WalkDetector.Transition.Confirmed)
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (detector.state == WalkDetector.State.IDLE) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureLoops()
        publish()
        return START_NOT_STICKY
    }

    private fun startForegroundSafe(text: String): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, Notifier.ID_WALK, Notifier.walkNotification(this, text), type)
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground failed: ${e.javaClass.simpleName}")
        stopSelf()
        false
    }

    private fun ensureLoops() {
        if (locationJob?.isActive != true) {
            locationJob = scope.launch {
                graph.location.updates(45_000L).collect { loc ->
                    val pos = LatLng(loc.latitude, loc.longitude)
                    lastPos = pos
                    handle(detector.onLocation(pos, System.currentTimeMillis()))
                    publish()
                }
            }
        }
        if (tickJob?.isActive != true) {
            tickJob = scope.launch {
                while (isActive) {
                    delay(60_000L)
                    handle(detector.tick(System.currentTimeMillis()))
                    if (detector.state == WalkDetector.State.WALKING) {
                        refreshWeather()
                        checkTriggers()
                    }
                    publish()
                }
            }
        }
    }

    private fun handle(t: WalkDetector.Transition?) {
        when (t) {
            null -> Unit
            WalkDetector.Transition.Started -> updateNotification("Определяем прогулку…")
            WalkDetector.Transition.Confirmed -> {
                firedThisWalk.clear()
                updateNotification("Идёт прогулка — подсказки включены")
                Workers.refreshDeckNow(this) // колода обновляется в начале прогулки
                scope.launch { refreshWeather(force = true) }
            }
            WalkDetector.Transition.FalseStart -> stopSelf()
            is WalkDetector.Transition.Finished -> {
                finishWalk(t.summary)
                stopSelf()
            }
        }
    }

    /** Итог прогулки (длительность, расстояние, район) уходит в портрет. */
    private fun finishWalk(s: WalkDetector.Summary) {
        val g = graph
        val area = s.lastPos?.let { Geo.geohash(it.lat, it.lon, 6) }
        val event = ProfileEvent(
            action = "walk_finished",
            localTime = g.settings.now().iso(),
            walk = buildJsonObject {
                put("duration_min", JsonPrimitive(s.durationMin))
                put("distance_m", JsonPrimitive(s.distanceM.toInt()))
                if (area != null) put("district_geohash6", JsonPrimitive(area))
                put("transport", JsonPrimitive(detector.transport))
            },
        )
        (application as kz.smartcity.walk.App).scope.launch {
            g.profile.enqueueEvent(event, System.currentTimeMillis())
            Workers.syncProfile(this@WalkService)
        }
    }

    private suspend fun refreshWeather(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - weatherAtMs < 10 * 60_000L) return
        val pos = (lastPos ?: graph.location.current())?.rounded() ?: return
        try {
            weather = graph.api.weather(pos.lat, pos.lon)
            weatherAtMs = now
            (weather?.feelsLikeC ?: weather?.tempC)?.let { feelsLike += now to it }
            feelsLike.removeAll { now - it.first > 3 * 3_600_000L }
        } catch (e: ApiException) {
            Log.w(TAG, "weather failed: ${e.code}")
        }
    }

    private fun battery(): Int? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    private suspend fun checkTriggers() {
        val g = graph
        val s = g.settings
        val now = s.now()
        val input = TriggerInput(
            now = now, walkStartMs = detector.startMs, weather = weather, feelsLikeHistory = feelsLike.toList(),
            batteryPct = battery(), foodStopDuringWalk = g.history.foodVisitSince(detector.startMs),
            firedThisWalk = firedThisWalk, notificationsToday = s.notificationsToday(now.toLocalDate()),
            enabled = s::triggerEnabled, lastFiredMs = s::lastTriggerMs, pausedUntilMs = s::triggerPausedUntil,
        )
        val trigger = TriggerEngine.evaluate(input) ?: return
        val pos = (lastPos ?: g.location.current())?.rounded() ?: return
        try {
            val resp = g.api.notifyCandidates(NotifyRequest(
                lat = pos.lat, lon = pos.lon, localTime = now.iso(), transport = detector.transport, trigger = trigger,
                profileSummary = g.profile.summary(System.currentTimeMillis()), profileAffinity = g.profile.affinity(),
            ))
            when {
                resp.items.isNotEmpty() -> Notifier.showSuggestion(this, resp)
                resp.suggestPlan -> Notifier.showPlanOffer(this, weather?.precipStartsInMin)
                else -> return
            }
            s.markTrigger(trigger, System.currentTimeMillis(), now.toLocalDate())
            firedThisWalk += trigger
            status.value = status.value.copy(lastTrigger = trigger)
        } catch (e: ApiException) {
            Log.w(TAG, "notify candidates failed: ${e.code}")
        }
    }

    private fun updateNotification(text: String) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            try {
                NotificationManagerCompat.from(this).notify(Notifier.ID_WALK, Notifier.walkNotification(this, text))
            } catch (e: SecurityException) {
                Log.w(TAG, "notify denied")
            }
        }
    }

    private fun publish() {
        status.value = status.value.copy(state = detector.state, startedMs = detector.startMs,
            distanceM = detector.distanceM.toInt(), transport = detector.transport, weather = weather)
    }

    override fun onDestroy() {
        running = false
        status.value = WalkStatus()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WalkService"
        const val ACTION_ACTIVITY = "kz.smartcity.walk.ACTIVITY"
        const val ACTION_FORCE = "kz.smartcity.walk.FORCE_WALK"
        const val ACTION_STOP = "kz.smartcity.walk.STOP_WALK"
        const val EXTRA_TYPE = "type"
        const val EXTRA_ENTER = "enter"

        @Volatile
        var running = false
            private set
        val status = MutableStateFlow(WalkStatus())
        val statusFlow: StateFlow<WalkStatus> get() = status

        fun onActivity(ctx: Context, type: Activity, enter: Boolean) {
            if (!(type.moving && enter) && !running) return
            start(ctx, Intent(ctx, WalkService::class.java).setAction(ACTION_ACTIVITY)
                .putExtra(EXTRA_TYPE, type.ordinal).putExtra(EXTRA_ENTER, enter))
        }

        fun forceWalk(ctx: Context) = start(ctx, Intent(ctx, WalkService::class.java).setAction(ACTION_FORCE))

        fun stop(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, WalkService::class.java).setAction(ACTION_STOP))
        }

        private fun start(ctx: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(ctx, intent)
            } catch (e: Exception) {
                Log.w(TAG, "cannot start walk service: ${e.javaClass.simpleName}")
            }
        }

        fun triggerLabel(t: String?) = t?.let { Settings.TRIGGER_LABELS[it] }
    }
}
