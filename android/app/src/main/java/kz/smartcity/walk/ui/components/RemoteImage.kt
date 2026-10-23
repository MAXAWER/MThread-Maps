package kz.smartcity.walk.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Картинки из сети для карточек: загрузка на фоне, уменьшение до ~1000 px, кэш в памяти на 40 штук. */
private object Images {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true).build()
    private val cache = LruCache<String, ImageBitmap>(40)
    private val failed = HashSet<String>()

    fun cached(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext it }
        if (url in failed) return@withContext null
        val bytes = runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Progulka/0.4 (Android)").build()).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        }.getOrNull()
        val bmp = bytes?.let {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1000) sample *= 2
            BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
        if (bmp != null) cache.put(url, bmp) else failed += url
        bmp
    }
}

/** Фото по ссылке; пока грузится или если не загрузилось — [placeholder]. Появляется плавно. */
@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, placeholder: @Composable () -> Unit) {
    var image by remember(url) { mutableStateOf(url?.let { Images.cached(it) }) }
    LaunchedEffect(url) { if (url != null && image == null) image = Images.load(url) }
    Box(modifier) {
        AnimatedContent(targetState = image, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
            label = "photo") { img ->
            if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize()) { placeholder() }
        }
    }
}
