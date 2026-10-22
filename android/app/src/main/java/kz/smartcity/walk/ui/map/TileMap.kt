package kz.smartcity.walk.ui.map

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kz.smartcity.walk.data.LatLng
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Маркер: kind = rec (рекомендация 1–3), cand (кандидат), waypoint, dest, place. */
data class MapMarker(val id: String, val lat: Double, val lon: Double, val kind: String, val label: String? = null)

/** Линия маршрута: этап пешком — пунктир, поездка — цвет маршрута; невыбранные варианты — бледные. */
data class MapLine(val points: List<LatLng>, val color: Color, val width: Dp = 6.dp, val dashed: Boolean = false,
                   val alpha: Float = 1f, val casing: Boolean = true)

/** Остановка: major — посадка и выход (с подписью), остальные — точки по пути. */
data class MapStop(val lat: Double, val lon: Double, val color: Color, val major: Boolean, val name: String? = null)

data class MapLayers(
    val markers: List<MapMarker> = emptyList(),
    val lines: List<MapLine> = emptyList(),
    val stops: List<MapStop> = emptyList(),
    val user: LatLng? = null,
)

private const val TILE_DP = 256f
private const val MIN_ZOOM = 3.0
private const val MAX_ZOOM = 19.0

/**
 * Камера карты: центр и масштаб. Анимации и жесты идут через один MutatorMutex — касание пальцем
 * сразу останавливает перелёт или инерцию, ничего не дёргается.
 */
@Stable
class MapCamera(center: LatLng, zoom: Double) {
    var center by mutableStateOf(center)
        private set
    var zoom by mutableDoubleStateOf(zoom)
        private set
    internal var size = IntSize.Zero
    internal var tilePx = TILE_DP
    private val mutex = MutatorMutex()

    // ---------- проекция Web Mercator в пикселях экрана ----------
    internal fun world(lat: Double, lon: Double, z: Double = zoom): Offset {
        val scale = tilePx * 2.0.pow(z)
        val s = sin(lat * PI / 180).coerceIn(-0.9999, 0.9999)
        val x = (lon + 180) / 360 * scale
        val y = (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * scale
        return Offset(x.toFloat(), y.toFloat())
    }

    private fun fromWorld(p: Offset, z: Double = zoom): LatLng {
        val scale = tilePx * 2.0.pow(z)
        val lon = p.x / scale * 360 - 180
        val n = PI - 2 * PI * p.y / scale
        val lat = 180 / PI * atan(0.5 * (exp(n) - exp(-n)))
        return LatLng(lat, lon)
    }

    fun toScreen(lat: Double, lon: Double): Offset {
        val c = world(center.lat, center.lon)
        val p = world(lat, lon)
        return Offset(p.x - c.x + size.width / 2f, p.y - c.y + size.height / 2f)
    }

    fun toLatLng(screen: Offset): LatLng {
        val c = world(center.lat, center.lon)
        return fromWorld(Offset(c.x + screen.x - size.width / 2f, c.y + screen.y - size.height / 2f))
    }

    internal fun panBy(delta: Offset) {
        val c = world(center.lat, center.lon)
        center = fromWorld(c - delta)
    }

    internal fun zoomAround(focus: Offset, factor: Float) {
        val anchor = toLatLng(focus)
        zoom = (zoom + log2(factor.toDouble())).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val p = world(anchor.lat, anchor.lon)
        center = fromWorld(Offset(p.x - (focus.x - size.width / 2f), p.y - (focus.y - size.height / 2f)))
    }

    /** Остановить перелёт или инерцию: касание пальцем всегда главнее анимации. */
    internal suspend fun stop() = mutex.mutate(MutatePriority.UserInput) {}

    internal suspend fun fling(velocity: Offset) = mutex.mutate(MutatePriority.UserInput) {
        var last = Offset.Zero
        Animatable(Offset.Zero, Offset.VectorConverter).animateDecay(velocity, exponentialDecay(frictionMultiplier = 1.6f)) {
            panBy(value - last)
            last = value
        }
    }

    /** Плавный перелёт: координаты и масштаб интерполируются одной пружиной без перелёта за цель. */
    suspend fun animateTo(target: LatLng, targetZoom: Double = zoom,
                          spec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow)) =
        mutex.mutate(MutatePriority.Default) {
            val from = center
            val fromZoom = zoom
            val toZoom = targetZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
            Animatable(0f).animateTo(1f, spec) {
                val t = value.toDouble()
                center = LatLng(from.lat + (target.lat - from.lat) * t, from.lon + (target.lon - from.lon) * t)
                zoom = fromZoom + (toZoom - fromZoom) * t
            }
        }

    /**
     * Показать точки целиком с отступами (в пикселях): сверху — поиск, снизу — шторка или панель вкладок,
     * слева — боковая панель на широком экране.
     */
    suspend fun fitBounds(points: List<LatLng>, top: Float, bottom: Float, left: Float, right: Float, maxZoom: Double = 17.0) {
        if (points.isEmpty() || size == IntSize.Zero) return
        if (points.size == 1) {
            val p = points.first()
            val z = max(zoom, 15.0).coerceAtMost(maxZoom)
            animateTo(shiftedCenter(p, z, top, bottom, left, right), z)
            return
        }
        val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }; val maxLon = points.maxOf { it.lon }
        val w = size.width - left - right
        val h = size.height - top - bottom
        var z = maxZoom
        while (z > MIN_ZOOM) {
            val a = world(maxLat, minLon, z)
            val b = world(minLat, maxLon, z)
            if (b.x - a.x <= w && b.y - a.y <= h) break
            z -= 0.1
        }
        animateTo(shiftedCenter(LatLng((minLat + maxLat) / 2, (minLon + maxLon) / 2), z, top, bottom, left, right), z)
    }

    /** Центр камеры, при котором точка оказывается посередине видимой области между панелями. */
    private fun shiftedCenter(p: LatLng, z: Double, top: Float, bottom: Float, left: Float, right: Float): LatLng {
        val w = world(p.lat, p.lon, z)
        return fromWorld(Offset(w.x + (right - left) / 2f, w.y + (bottom - top) / 2f), z)
    }
}

/** Тайлы 2ГИС (HD, 512 px на тайл): LRU-кэш в памяти и не больше 6 загрузок одновременно. */
private object TileStore {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private val cache = LruCache<String, ImageBitmap>(96)
    private val loading = HashSet<String>()
    private val failed = HashMap<String, Long>()
    private val gate = Semaphore(6)
    val version = mutableIntStateOf(0)

    fun get(z: Int, x: Int, y: Int): ImageBitmap? = cache.get("$z/$x/$y")

    fun request(scope: CoroutineScope, z: Int, x: Int, y: Int) {
        val key = "$z/$x/$y"
        synchronized(loading) {
            if (cache.get(key) != null || key in loading) return
            if ((failed[key] ?: 0L) > System.currentTimeMillis()) return
            loading += key
        }
        scope.launch {
            val img = gate.withPermit {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val url = "https://tile${(x + y) % 4}.maps.2gis.com/tiles?x=$x&y=$y&z=$z&v=1&ts=online_hd"
                        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                            val bytes = r.body?.bytes() ?: return@use null
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }
                    }.getOrNull()
                }
            }
            synchronized(loading) {
                loading -= key
                if (img != null) cache.put(key, img) else failed[key] = System.currentTimeMillis() + 15_000
            }
            if (img != null) version.intValue++
        }
    }
}

@Composable
fun rememberMapCamera(center: LatLng, zoom: Double = 14.0): MapCamera = remember { MapCamera(center, zoom) }

/**
 * Карта на Compose: тайлы 2ГИС, маршруты, остановки, маркеры и точка пользователя на одном Canvas.
 * Жесты: панорама с инерцией и трением, щипок вокруг пальцев, двойной тап — ближе, долгое нажатие — точка.
 */
@Composable
fun TileMap(
    camera: MapCamera,
    layers: MapLayers,
    onMarkerClick: (String) -> Unit,
    onLongPress: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
) {
    val density = LocalDensity.current
    camera.tilePx = TILE_DP * density.density
    val scope = rememberCoroutineScope()
    val text = rememberTextMeasurer()
    val colors = remember(dark) { MapColors(dark) }

    Canvas(
        modifier
            .onSizeChanged { camera.size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        hitMarker(camera, layers.markers, pos, density.density)?.let(onMarkerClick)
                    },
                    onDoubleTap = { pos -> scope.launch { camera.animateTo(camera.toLatLng(pos), camera.zoom + 1) } },
                    onLongPress = { pos -> onLongPress(camera.toLatLng(pos)) },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    scope.launch { camera.stop() }
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var moving = false
                    var travel = 0f
                    var lastPointers = 1
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        val pan = event.calculatePan()
                        val zoomChange = event.calculateZoom()
                        if (!moving) {
                            travel += pan.getDistance()
                            if (travel > viewConfiguration.touchSlop || zoomChange != 1f) moving = true
                        }
                        if (moving) {
                            camera.panBy(pan)
                            if (zoomChange != 1f) camera.zoomAround(event.calculateCentroid(), zoomChange)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        if (pressed != lastPointers) tracker.resetTracking()
                        lastPointers = pressed
                        event.changes.firstOrNull { it.pressed }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                    } while (true)
                    if (moving && lastPointers == 1) {
                        val v = tracker.calculateVelocity()
                        if (v.x * v.x + v.y * v.y > 250f * 250f) scope.launch { camera.fling(Offset(v.x, v.y)) }
                    }
                }
            },
    ) {
        TileStore.version.intValue // чтение подписывает отрисовку на загрузку новых тайлов
        drawRect(colors.background)
        drawTiles(camera, scope)
        layers.lines.forEach { drawLine(camera, it, density.density) }
        layers.stops.sortedBy { it.major }.forEach { drawStop(camera, it, text, colors, density.density) }
        layers.markers.sortedBy { it.kind == "rec" || it.kind == "dest" }.forEach { drawMarker(camera, it, text, density.density) }
        layers.user?.let { drawUser(camera, it, density.density) }
    }
}

private class MapColors(dark: Boolean) {
    val background = if (dark) Color(0xFF1E2128) else Color(0xFFF1F3F8)
    val label = Color(0xFF101828)
    val labelBg = Color.White
}

private fun DrawScope.drawTiles(camera: MapCamera, scope: CoroutineScope) {
    val z = camera.zoom.roundToInt().coerceIn(3, 18)
    val k = 2.0.pow(camera.zoom - z).toFloat()
    val tile = camera.tilePx
    val c = camera.world(camera.center.lat, camera.center.lon, z.toDouble())
    val w = size.width; val h = size.height
    val x0 = floor((c.x - w / 2 / k) / tile).toInt(); val x1 = floor((c.x + w / 2 / k) / tile).toInt()
    val y0 = floor((c.y - h / 2 / k) / tile).toInt(); val y1 = floor((c.y + h / 2 / k) / tile).toInt()
    val n = 1 shl z
    for (tx in x0..x1) for (ty in y0..y1) {
        if (ty < 0 || ty >= n) continue
        val wx = ((tx % n) + n) % n
        val dx = (tx * tile - c.x) * k + w / 2
        val dy = (ty * tile - c.y) * k + h / 2
        val dst = IntSize((tile * k).roundToInt() + 1, (tile * k).roundToInt() + 1)
        val img = TileStore.get(z, wx, ty)
        if (img != null) {
            drawImage(img, dstOffset = IntOffset(dx.roundToInt(), dy.roundToInt()), dstSize = dst)
            continue
        }
        TileStore.request(scope, z, wx, ty)
        // Пока тайл грузится — растягиваем «родителя» уровнем выше, чтобы не было пустых квадратов.
        val parent = TileStore.get(z - 1, wx / 2, ty / 2) ?: continue
        val half = parent.width / 2
        drawImage(parent, srcOffset = IntOffset((wx % 2) * half, (ty % 2) * half), srcSize = IntSize(half, half),
            dstOffset = IntOffset(dx.roundToInt(), dy.roundToInt()), dstSize = dst)
    }
}

private fun DrawScope.drawLine(camera: MapCamera, line: MapLine, d: Float) {
    if (line.points.size < 2) return
    val path = Path()
    line.points.forEachIndexed { i, p ->
        val s = camera.toScreen(p.lat, p.lon)
        if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
    }
    val width = line.width.value * d
    if (line.casing && !line.dashed) {
        drawPath(path, Color.White.copy(alpha = line.alpha), style = Stroke(width + 4 * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    drawPath(path, line.color.copy(alpha = line.alpha), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round,
        pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(2 * d, 9 * d)) else null))
}

private fun DrawScope.drawStop(camera: MapCamera, stop: MapStop, text: TextMeasurer, colors: MapColors, d: Float) {
    val s = camera.toScreen(stop.lat, stop.lon)
    if (s.x < -50 || s.y < -50 || s.x > size.width + 50 || s.y > size.height + 50) return
    val r = (if (stop.major) 7f else 4f) * d
    drawCircle(Color.White, r + 2.5f * d, s)
    drawCircle(stop.color, r, s)
    if (stop.major) drawCircle(Color.White, r * 0.4f, s)
    val name = stop.name ?: return
    if (!stop.major) return
    val layout = text.measure(name, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.label), maxLines = 1)
    val pad = 6 * d
    val tl = Offset(s.x + r + 6 * d, s.y - layout.size.height / 2f - pad / 2)
    drawRoundRect(Color.Black.copy(alpha = 0.12f), topLeft = tl + Offset(0f, 1.5f * d),
        size = Size(layout.size.width + 2 * pad, layout.size.height + pad),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20 * d))
    drawRoundRect(colors.labelBg, topLeft = tl, size = Size(layout.size.width + 2 * pad, layout.size.height + pad),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20 * d))
    drawText(layout, topLeft = tl + Offset(pad, pad / 2))
}

private val MARKER_COLORS = mapOf(
    "rec" to Color(0xFFFF6A00), "cand" to Color(0xFF1F5FD0), "waypoint" to Color(0xFF8B5CF6),
    "dest" to Color(0xFFE5304F), "saved" to Color(0xFF00875F), "place" to Color(0xFFE5304F),
)

private fun DrawScope.drawMarker(camera: MapCamera, m: MapMarker, text: TextMeasurer, d: Float) {
    val s = camera.toScreen(m.lat, m.lon)
    if (s.x < -60 || s.y < -80 || s.x > size.width + 60 || s.y > size.height + 60) return
    val big = m.kind == "rec" || m.kind == "dest" || m.kind == "place"
    val r = (if (big) 16f else 10f) * d
    val cy = s.y - r - 7 * d
    val color = MARKER_COLORS[m.kind] ?: MARKER_COLORS.getValue("cand")
    drawCircle(Color.Black.copy(alpha = 0.22f), r + 4 * d, Offset(s.x, cy + 2.5f * d))
    val tail = Path().apply { moveTo(s.x, s.y); lineTo(s.x - 6 * d, cy + r - 2 * d); lineTo(s.x + 6 * d, cy + r - 2 * d); close() }
    drawPath(tail, Color.White)
    drawCircle(Color.White, r + 3 * d, Offset(s.x, cy))
    drawCircle(color, r, Offset(s.x, cy))
    val label = m.label ?: return
    val layout = text.measure(label, TextStyle(fontSize = if (big) 15.sp else 10.sp, fontWeight = FontWeight.ExtraBold, color = Color.White))
    drawText(layout, topLeft = Offset(s.x - layout.size.width / 2f, cy - layout.size.height / 2f))
}

private fun DrawScope.drawUser(camera: MapCamera, p: LatLng, d: Float) {
    val s = camera.toScreen(p.lat, p.lon)
    drawCircle(Color(0x331F5FD0), 24 * d, s)
    drawCircle(Color.Black.copy(alpha = 0.18f), 12.5f * d, s + Offset(0f, 1.5f * d))
    drawCircle(Color.White, 12 * d, s)
    drawCircle(Color(0xFF1F5FD0), 8.5f * d, s)
}

/** Попадание пальцем в маркер: круг маркера стоит над точкой, поэтому цель — чуть выше неё. */
private fun hitMarker(camera: MapCamera, markers: List<MapMarker>, pos: Offset, d: Float): String? =
    markers.map { it to camera.toScreen(it.lat, it.lon) }
        .map { (m, s) -> m to (Offset(s.x, s.y - 22 * d) - pos).getDistance() }
        .filter { it.second < 30 * d }
        .minByOrNull { it.second }?.first?.id
