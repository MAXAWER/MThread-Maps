package kz.smartcity.walk.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: String, override val message: String) : Exception(message)

/** События потока SSE поиска (раздел 12). */
sealed interface SearchEvent {
    data class Filters(val data: FiltersEvent) : SearchEvent
    data class Candidates(val data: CandidatesEvent) : SearchEvent
    data class AnswerEv(val data: Answer) : SearchEvent
    data class Error(val data: ErrorEvent) : SearchEvent
    data class Done(val data: DoneEvent) : SearchEvent
}

/** Клиент своего сервера. Приложение обращается только к нему (кроме тайлов MapGL). */
class ApiClient(private val baseUrl: () -> String, private val installId: () -> String) {

    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun request(path: String) = Request.Builder()
        .url(baseUrl().trimEnd('/') + path)
        .header("X-Install-Id", installId())

    fun search(req: SearchRequest): Flow<SearchEvent> = callbackFlow {
        val body = json.encodeToString(SearchRequest.serializer(), req).toRequestBody(jsonType)
        val call = http.newCall(request("/v1/search").post(body).header("Accept", "text/event-stream").build())
        val job = launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val err = errorOf(resp)
                        trySend(SearchEvent.Error(ErrorEvent(err.code, err.message)))
                        return@use
                    }
                    val source = resp.body?.source() ?: return@use
                    var event: String? = null
                    val data = StringBuilder()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.startsWith("event:") -> event = line.substring(6).trim()
                            line.startsWith("data:") -> data.append(line.substring(5).trim())
                            line.isEmpty() -> {
                                val name = event
                                if (name != null) parse(name, data.toString())?.let { trySend(it) }
                                event = null
                                data.setLength(0)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (!call.isCanceled()) trySend(SearchEvent.Error(ErrorEvent("network", "Нет связи с сервером")))
            }
            close()
        }
        awaitClose {
            call.cancel()
            job.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun parse(event: String, data: String): SearchEvent? = runCatching {
        when (event) {
            "filters" -> SearchEvent.Filters(json.decodeFromString(FiltersEvent.serializer(), data))
            "candidates" -> SearchEvent.Candidates(json.decodeFromString(CandidatesEvent.serializer(), data))
            "answer" -> SearchEvent.AnswerEv(json.decodeFromString(Answer.serializer(), data))
            "error" -> SearchEvent.Error(json.decodeFromString(ErrorEvent.serializer(), data))
            "done" -> SearchEvent.Done(json.decodeFromString(DoneEvent.serializer(), data))
            else -> null
        }
    }.getOrNull()

    private fun errorOf(resp: Response): ApiException {
        val text = resp.body?.string().orEmpty()
        val err = runCatching { json.decodeFromString(ApiErrorBody.serializer(), text) }.getOrNull()
        return ApiException(err?.code ?: "http_${resp.code}", err?.message ?: "Сервер ответил ошибкой ${resp.code}")
    }

    private suspend fun <T> execute(req: Request, de: DeserializationStrategy<T>): T = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw errorOf(resp)
                json.decodeFromString(de, resp.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw ApiException("network", "Нет связи с сервером")
        }
    }

    private suspend fun <B, T> post(path: String, body: B, ser: kotlinx.serialization.SerializationStrategy<B>,
                                    de: DeserializationStrategy<T>): T {
        val payload = json.encodeToString(ser, body).toRequestBody(jsonType)
        return execute(request(path).post(payload).build(), de)
    }

    private suspend fun <T> get(path: String, params: Map<String, String>, de: DeserializationStrategy<T>): T {
        val url = (baseUrl().trimEnd('/') + path).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return execute(Request.Builder().url(url).header("X-Install-Id", installId()).build(), de)
    }

    suspend fun suggest(q: String, lat: Double, lon: Double): SuggestResponse =
        get("/v1/suggest", mapOf("q" to q, "lat" to lat.toString(), "lon" to lon.toString()), SuggestResponse.serializer())

    suspend fun place(id: String): PlaceCard = get("/v1/places/" + java.net.URLEncoder.encode(id, "UTF-8"), emptyMap(), PlaceCard.serializer())

    suspend fun route(req: RouteRequest): RouteResponse = post("/v1/route", req, RouteRequest.serializer(), RouteResponse.serializer())

    suspend fun deck(req: DeckRequest): DeckResponse = post("/v1/deck", req, DeckRequest.serializer(), DeckResponse.serializer())

    suspend fun profileUpdate(req: ProfileUpdateRequest): ProfileOpsResponse =
        post("/v1/profile/update", req, ProfileUpdateRequest.serializer(), ProfileOpsResponse.serializer())

    suspend fun weather(lat: Double, lon: Double): WeatherDto =
        get("/v1/weather", mapOf("lat" to lat.toString(), "lon" to lon.toString()), WeatherDto.serializer())

    suspend fun notifyCandidates(req: NotifyRequest): NotifyResponse =
        post("/v1/notify/candidates", req, NotifyRequest.serializer(), NotifyResponse.serializer())
}
