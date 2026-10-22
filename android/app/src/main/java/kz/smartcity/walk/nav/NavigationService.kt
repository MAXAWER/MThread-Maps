package kz.smartcity.walk.nav

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kz.smartcity.walk.data.Geo
import kz.smartcity.walk.data.LatLng
import kz.smartcity.walk.data.api.ApiException
import kz.smartcity.walk.data.api.LatLonDto
import kz.smartcity.walk.data.api.NotifyRequest
import kz.smartcity.walk.data.api.ProfileEvent
import kz.smartcity.walk.data.api.RouteRequest
import kz.smartcity.walk.data.api.RouteResponse
import kz.smartcity.walk.data.iso
import kz.smartcity.walk.graph
import kz.smartcity.walk.notify.Notifier
import kz.smartcity.walk.walk.TriggerEngine
import kz.smartcity.walk.work.Workers
import java.time.LocalTime
import java.util.Locale

@Serializable
data class NavDestination(
    val poiId: String,
    val name: String,
    val category: String? = null,
    val lat: Double,
    val lon: Double,
    val openUntil: String? = null,
)

data class NavState(
    val dest: NavDestination,
    val transport: String,
    val geometry: List<LatLng>,
    val position: LatLng? = null,
    val maneuverText: String? = null,
    val maneuverIcon: String? = null,
    val turnDirection: String? = null,
    val distanceToManeuverM: Int? = null,
    val remainingM: Int = 0,
    val etaMin: Int = 0,
    val offRoute: Boolean = false,
    val rerouting: Boolean = false,
    val arrived: Boolean = false,
    val soundOn: Boolean = true,
    val simulated: Boolean = false,
    val note: String? = null,
    /** Общественный транспорт: этапы поездки, текущий этап и остановки до выхода. */
    val legs: List<TransitLeg> = emptyList(),
    val legIndex: Int = 0,
    val toLegEndM: Int? = null,
    val stopsLeft: Int? = null,
    val nextStopName: String? = null,
    /** Ближайшее отправление на момент построения ([startedAtMs]) или интервал движения. */
    val departure: String? = null,
    val departureInMin: Int? = null,
    val nextDepartures: List<String> = emptyList(),
    val intervalMin: Int? = null,
    val startedAtMs: Long = 0,
) {
    val transit get() = legs.any { !it.isWalk }
    val currentLeg get() = legs.getOrNull(legIndex)
}

object NavigationBus {
    val state = MutableStateFlow<NavState?>(null)
}

/** Сервис переднего плана: геолокация раз в секунду, привязка к маршруту, голос, перестроение. */
class NavigationService : Service(), TextToSpeech.OnInitListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: NavigationEngine? = null
    private var tracker: TransitTracker? = null
    private var lastLeg = -1
    private var lastStopsLeft: Int? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var locationJob: Job? = null
    private var dest: NavDestination? = null
    private var transport = "walking"
    private var closingChecked = false
    private var simulate = false
    private var deviationDone = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS &&
            (tts?.setLanguage(Locale.forLanguageTag("ru-RU")) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= 0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                NavigationBus.state.value = null
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SOUND -> {
                val s = graph.settings
                s.soundOn = !s.soundOn
                NavigationBus.state.value = NavigationBus.state.value?.copy(soundOn = s.soundOn)
                return START_NOT_STICKY
            }
        }
        val json = graph.json
        val route = intent?.getStringExtra(EXTRA_ROUTE)?.let { json.decodeFromString(RouteResponse.serializer(), it) }
        val d = intent?.getStringExtra(EXTRA_DEST)?.let { json.decodeFromString(NavDestination.serializer(), it) }
        val origin = intent?.getStringExtra(EXTRA_ORIGIN)?.let { json.decodeFromString(LatLonDto.serializer(), it) }
        if (route == null || d == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, Notifier.ID_NAV, Notifier.navNotification(this, "Маршрут до «${d.name}»", "Строим подсказки…"), type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        dest = d
        transport = route.transport
        simulate = intent.getBooleanExtra(EXTRA_SIMULATE, false)
        closingChecked = false
        deviationDone = false
        setRoute(route, origin?.let { LatLng(it.lat, it.lon) })
        startLocations(route)
        return START_NOT_STICKY
    }

    /** Маршрут сервера начинается в округлённой точке; отрезок от точной позиции достраивается здесь, на устройстве. */
    private fun setRoute(input: RouteResponse, precise: LatLng?) {
        val route = if (input.options.size > 1) input.withOption(null) else input
        var geometry = route.geometry.map { LatLng(it[1], it[0]) }
        if (precise != null && geometry.isNotEmpty() && Geo.distance(precise, geometry.first()) > 10.0) {
            geometry = listOf(precise) + geometry
        }
        val maneuvers = route.maneuvers.map { EngineManeuver(it.text, it.icon, it.turnDirection, LatLng(it.lat, it.lon)) }
        val speed = if (route.totalDurationS > 0 && route.totalDistanceM > 0) route.totalDistanceM.toDouble() / route.totalDurationS
        else NavigationEngine.defaultSpeedMps(route.transport)
        engine = NavigationEngine(geometry, maneuvers, route.transport, speed)
        val opt = route.chosen
        val legs = opt?.legs.orEmpty().map { l ->
            TransitLeg(kind = l.kind, names = l.names, color = l.color, fromName = l.fromName, toName = l.toName,
                durationS = l.durationS, waitS = l.waitS, distanceM = l.distanceM,
                line = l.geometry.mapNotNull { if (it.size >= 2) LatLng(it[1], it[0]) else null },
                stops = l.stops.map { LatLng(it.lat, it.lon) }, stopNames = l.stops.map { it.name })
        }
        tracker = legs.takeIf { it.any { l -> !l.isWalk } }?.let { TransitTracker(it) }
        lastLeg = -1
        lastStopsLeft = null
        val d = dest ?: return
        NavigationBus.state.value = NavState(dest = d, transport = route.transport, geometry = geometry,
            remainingM = route.totalDistanceM, etaMin = route.totalDurationS / 60, soundOn = graph.settings.soundOn,
            simulated = simulate, note = route.note, legs = legs, departure = opt?.departure,
            departureInMin = opt?.departureInMin, nextDepartures = opt?.nextDepartures.orEmpty(), intervalMin = opt?.intervalMin,
            startedAtMs = System.currentTimeMillis())
    }

    /** Голос для поездки с пересадками: смена этапа и предупреждение перед выходом. */
    private fun transitPrompt(state: NavState, p: TransitProgress): String? {
        val leg = state.legs.getOrNull(p.legIndex) ?: return null
        var prompt: String? = null
        if (p.legIndex != lastLeg) {
            prompt = when {
                !leg.isWalk -> "Садитесь на ${legTitle(leg)}. Выход на остановке ${leg.toName ?: "по маршруту"}"
                p.legIndex == state.legs.lastIndex -> "Выходите. Дальше пешком до места"
                else -> state.legs.getOrNull(p.legIndex + 1)?.let { "Идите к остановке ${it.fromName ?: ""}" }
            }
            lastLeg = p.legIndex
        } else if (!leg.isWalk && p.stopsLeft == 1 && lastStopsLeft != 1) {
            prompt = "Следующая остановка ваша: ${leg.toName ?: ""}"
        }
        lastStopsLeft = p.stopsLeft
        return prompt
    }

    private fun startLocations(route: RouteResponse) {
        locationJob?.cancel()
        val positions: Flow<LatLng> = if (simulate) {
            RouteSimulator.positions(route.geometry.map { LatLng(it[1], it[0]) }, NavigationEngine.defaultSpeedMps(route.transport),
                deviate = !deviationDone).also { deviationDone = true }
        } else {
            flow { graph.location.updates(1_000L, Priority.PRIORITY_HIGH_ACCURACY).collect { emit(LatLng(it.latitude, it.longitude)) } }
        }
        locationJob = scope.launch { positions.collect { onPosition(it) } }
    }

    private fun onPosition(pos: LatLng) {
        val e = engine ?: return
        val d = dest ?: return
        val u = e.update(pos, System.currentTimeMillis())
        val state = NavigationBus.state.value ?: return
        val tp = tracker?.update(pos)
        // На транспорте пошаговые повороты не нужны: говорим о посадке, пересадке и выходе.
        val say = if (tp != null) (if (u.arrived) u.prompt else transitPrompt(state, tp)) else u.prompt
        if (say != null && state.soundOn && ttsReady) {
            tts?.speak(say, TextToSpeech.QUEUE_FLUSH, null, "nav")
        }
        // Тактильные подсказки: чувствуются в кармане и без звука.
        when {
            u.arrived && !state.arrived -> graph.haptics.arrival()
            u.prompt != null && !u.arrived -> graph.haptics.maneuver()
            u.offRoute && !state.offRoute -> graph.haptics.warning()
        }
        NavigationBus.state.value = state.copy(
            position = pos,
            maneuverText = if (u.arrived) "Вы на месте" else u.nextManeuver?.text ?: "Двигайтесь по маршруту",
            maneuverIcon = if (u.arrived) "finish" else u.nextManeuver?.icon,
            turnDirection = if (u.arrived) null else u.nextManeuver?.turnDirection,
            distanceToManeuverM = u.distanceToManeuverM?.toInt(), remainingM = u.remainingM.toInt(), etaMin = u.etaMin,
            offRoute = u.offRoute, arrived = u.arrived,
            legIndex = tp?.legIndex ?: 0, toLegEndM = tp?.toLegEndM, stopsLeft = tp?.stopsLeft, nextStopName = tp?.nextStopName,
        )
        updateNotification(state.dest.name, if (u.arrived) "Вы на месте" else "${u.nextManeuver?.text ?: "По маршруту"} · ${u.etaMin} мин")
        if (u.needReroute) reroute(pos)
        if (u.arrived) onArrived(d, pos)
        if (!closingChecked && !u.arrived) checkClosing(d, u.etaMin)
    }

    private fun reroute(pos: LatLng) {
        val d = dest ?: return
        NavigationBus.state.value = NavigationBus.state.value?.copy(rerouting = true)
        scope.launch {
            try {
                val from = pos.rounded()
                val route = graph.api.route(RouteRequest(LatLonDto(from.lat, from.lon), LatLonDto(d.lat, d.lon), transport = transport))
                setRoute(route, pos)
                if (simulate) startLocations(route)
            } catch (e: ApiException) {
                Log.w(TAG, "reroute failed: ${e.code}")
            } finally {
                NavigationBus.state.value = NavigationBus.state.value?.copy(rerouting = false)
            }
        }
    }

    private fun onArrived(d: NavDestination, pos: LatLng) {
        locationJob?.cancel()
        val g = graph
        val nowMs = System.currentTimeMillis()
        (application as kz.smartcity.walk.App).scope.launch {
            g.history.recordVisit(d.poiId, d.category, pos, nowMs)
            g.profile.onRouteCompleted(d.category, nowMs)
            g.profile.enqueueEvent(ProfileEvent(action = "route_completed", selectedCategory = d.category,
                localTime = g.settings.now().iso(),
                filters = JsonObject(mapOf("transport" to JsonPrimitive(transport)))), nowMs)
            Workers.syncProfile(this@NavigationService)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /** «Цель закроется»: прибытие позже, чем за 10 минут до закрытия — 1 раз за маршрут. */
    private fun checkClosing(d: NavDestination, etaMin: Int) {
        val until = d.openUntil?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return
        closingChecked = true
        val g = graph
        val now = g.settings.now()
        var closesAt = now.with(until)
        if (closesAt.isBefore(now)) closesAt = closesAt.plusDays(1)
        val arrival = now.plusMinutes(etaMin.toLong())
        if (arrival.plusMinutes(10).isBefore(closesAt)) return
        if (!g.settings.triggerEnabled("closing") || !TriggerEngine.globalAllowed(now, g.settings.notificationsToday(now.toLocalDate()))) return
        val pos = NavigationBus.state.value?.position?.rounded() ?: return
        scope.launch {
            try {
                val resp = g.api.notifyCandidates(NotifyRequest(lat = pos.lat, lon = pos.lon, localTime = now.iso(),
                    transport = transport, trigger = "closing", targetCategory = d.category, targetClosesAt = d.openUntil,
                    excludeIds = listOf(d.poiId), profileAffinity = g.profile.affinity()))
                if (resp.items.isNotEmpty()) {
                    Notifier.showSuggestion(this@NavigationService, resp)
                    g.settings.markTrigger("closing", System.currentTimeMillis(), now.toLocalDate())
                }
            } catch (e: ApiException) {
                Log.w(TAG, "closing check failed: ${e.code}")
            }
        }
    }

    private fun updateNotification(title: String, text: String) {
        try {
            NotificationManagerCompat.from(this).notify(Notifier.ID_NAV, Notifier.navNotification(this, title, text))
        } catch (e: SecurityException) {
            Log.w(TAG, "notify denied")
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NavigationService"
        const val ACTION_START = "kz.smartcity.walk.NAV_START"
        const val ACTION_STOP = "kz.smartcity.walk.NAV_STOP"
        const val ACTION_SOUND = "kz.smartcity.walk.NAV_SOUND"
        const val EXTRA_ROUTE = "route"
        const val EXTRA_DEST = "dest"
        const val EXTRA_ORIGIN = "origin"
        const val EXTRA_SIMULATE = "simulate"

        fun start(ctx: Context, route: RouteResponse, dest: NavDestination, precise: LatLng?, simulate: Boolean) {
            val json = ctx.graph.json
            val intent = Intent(ctx, NavigationService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_ROUTE, json.encodeToString(RouteResponse.serializer(), route))
                .putExtra(EXTRA_DEST, json.encodeToString(NavDestination.serializer(), dest))
                .putExtra(EXTRA_SIMULATE, simulate)
            precise?.let { intent.putExtra(EXTRA_ORIGIN, json.encodeToString(LatLonDto.serializer(), LatLonDto(it.lat, it.lon))) }
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun stop(ctx: Context) {
            NavigationBus.state.value = null
            ctx.startService(Intent(ctx, NavigationService::class.java).setAction(ACTION_STOP))
        }

        fun toggleSound(ctx: Context) {
            ctx.startService(Intent(ctx, NavigationService::class.java).setAction(ACTION_SOUND))
        }
    }
}

/** Отладка (сценарий 14): движение вдоль маршрута, а с 30-й по 45-ю секунду — уход на 40 м в сторону. */
object RouteSimulator {
    fun positions(route: List<LatLng>, speedMps: Double, deviate: Boolean): Flow<LatLng> = flow {
        if (route.size < 2) return@flow
        val origin = route.first()
        val xy = route.map { Geo.toXY(origin, it) }
        var t = 0
        var seg = 0
        var segPos = 0.0
        while (seg < xy.size - 1) {
            val a = xy[seg]
            val b = xy[seg + 1]
            val len = kotlin.math.hypot(b[0] - a[0], b[1] - a[1])
            if (segPos >= len) {
                segPos -= len
                seg++
                continue
            }
            val f = if (len == 0.0) 0.0 else segPos / len
            var x = a[0] + f * (b[0] - a[0])
            var y = a[1] + f * (b[1] - a[1])
            if (deviate && t in 30..45 && len > 0) { // перпендикулярный сдвиг на 40 м
                x += -(b[1] - a[1]) / len * 40.0
                y += (b[0] - a[0]) / len * 40.0
            }
            emit(Geo.fromXY(origin, x, y))
            delay(1_000L)
            t++
            segPos += speedMps * 3 // ускорено в 3 раза, чтобы демо не тянулось
        }
        emit(route.last())
    }
}

/** «автобус 47» / «троллейбус 3, 12» — для голоса и подписей. */
fun legTitle(leg: TransitLeg): String {
    val kind = when (leg.kind) {
        "bus" -> "автобус"; "trolleybus" -> "троллейбус"; "tram" -> "трамвай"; "shuttle_bus" -> "маршрутку"
        "metro" -> "метро"; "light_rail" -> "LRT"; else -> "транспорт"
    }
    return if (leg.names.isEmpty()) kind else "$kind ${leg.names.joinToString(", ")}"
}
