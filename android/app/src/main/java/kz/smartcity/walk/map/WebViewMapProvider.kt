package kz.smartcity.walk.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kz.smartcity.walk.data.LatLng

/**
 * Карта 2ГИС MapGL JS внутри WebView. Мост Kotlin ↔ JS — WebMessagePort.
 * Команды: setCenter, setUserLocation, setMarkers, drawRoute, fitBounds, clear.
 * События: ready, markerClick, mapLongPress, cameraIdle.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewMapProvider(
    private val context: Context,
    private val baseUrl: String,
    private val dark: Boolean,
    private val initialCenter: LatLng,
) : MapProvider {

    private val _events = MutableSharedFlow<MapEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<MapEvent> = _events

    private var port: WebMessagePort? = null
    private val pending = ArrayDeque<String>()

    val view: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        setBackgroundColor(if (dark) Color.parseColor("#0F1416") else Color.parseColor("#E8EEF0"))
        contentDescription = "Карта"
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) = connect()
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d("MapJS", "${message.messageLevel()}: ${message.message()} (${message.lineNumber()})")
                return true
            }
        }
    }

    fun load() {
        val html = context.assets.open("map.html").bufferedReader().use { it.readText() }
        view.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
    }

    private fun connect() {
        if (port != null) return
        val channel = view.createWebMessageChannel()
        port = channel[0]
        channel[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(p: WebMessagePort, message: WebMessage) {
                message.data?.let { onJs(it) }
            }
        })
        view.postWebMessage(WebMessage("init", arrayOf(channel[1])), Uri.parse(baseUrl))
        send(buildJsonObject {
            put("type", "init")
            put("dark", dark)
            put("zoom", 14.0)
            put("center", point(initialCenter))
        }, front = true)
        while (pending.isNotEmpty()) port?.postMessage(WebMessage(pending.removeFirst()))
    }

    private fun onJs(data: String) {
        val o = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        val type = o["type"]?.jsonPrimitive?.content
        val ev = when (type) {
            "ready" -> MapEvent.Ready(o["engine"]?.jsonPrimitive?.content ?: "raster")
            "markerClick" -> o["id"]?.jsonPrimitive?.content?.let { MapEvent.MarkerClick(it) }
            "mapLongPress" -> MapEvent.LongPress(LatLng(o.num("lat"), o.num("lon")))
            "cameraIdle" -> MapEvent.CameraIdle(LatLng(o.num("lat"), o.num("lon")), o.num("zoom"))
            "error" -> MapEvent.Error(o["message"]?.jsonPrimitive?.content ?: "")
            else -> null
        }
        ev?.let { _events.tryEmit(it) }
    }

    private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.double ?: 0.0

    private fun send(cmd: JsonObject, front: Boolean = false) {
        val text = cmd.toString()
        val p = port
        if (p == null) {
            if (front) pending.addFirst(text) else pending.addLast(text)
        } else {
            p.postMessage(WebMessage(text))
        }
    }

    private fun point(p: LatLng) = buildJsonArray { add(JsonPrimitive(p.lon)); add(JsonPrimitive(p.lat)) }

    override fun setCenter(point: LatLng, zoom: Double?) = send(buildJsonObject {
        put("type", "setCenter")
        put("center", point(point))
        zoom?.let { put("zoom", it) }
    })

    override fun setUserLocation(point: LatLng?) = send(buildJsonObject {
        put("type", "setUserLocation")
        if (point != null) put("point", point(point))
    })

    override fun setMarkers(markers: List<MapMarker>) = send(buildJsonObject {
        put("type", "setMarkers")
        put("markers", JsonArray(markers.map { m ->
            buildJsonObject {
                put("id", m.id)
                put("lat", m.lat)
                put("lon", m.lon)
                put("kind", m.kind)
                m.label?.let { put("label", it) }
                m.icon?.let { put("icon", it) }
            }
        }))
    })

    override fun drawRoute(points: List<LatLng>, color: String) = send(buildJsonObject {
        put("type", "drawRoute")
        put("color", color)
        put("coordinates", JsonArray(points.map { point(it) }))
    })

    override fun fitBounds(points: List<LatLng>, top: Int, bottom: Int) {
        if (points.isEmpty()) return
        send(buildJsonObject {
            put("type", "fitBounds")
            put("points", JsonArray(points.map { point(it) }))
            put("padding", buildJsonObject { put("top", top); put("bottom", bottom); put("left", 48); put("right", 48) })
        })
    }

    override fun clear(what: String) = send(buildJsonObject { put("type", "clear"); put("what", what) })

    override fun setScaleVisible(visible: Boolean) = send(buildJsonObject { put("type", "setScale"); put("visible", visible) })

    fun destroy() {
        port?.close()
        view.destroy()
    }
}
