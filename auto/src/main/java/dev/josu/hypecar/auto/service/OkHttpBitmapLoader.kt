package dev.josu.hypecar.auto.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * BitmapLoader that fetches album art via the app's already-trusted OkHttpClient.
 *
 * Media3's bundled SimpleBitmapLoader uses the platform `URL.openConnection()`,
 * which falls over with "Chain validation failed" on stripped trust stores
 * (notably the AAOS_API_35 emulator). Routing image fetches through the same
 * OkHttp instance the app uses for the JSON API also picks up our DoH-fallback
 * `ResilientDns` and OkHttp's response cache for free.
 */
@UnstableApi
class OkHttpBitmapLoader @Inject constructor(
    private val client: OkHttpClient,
) : BitmapLoader,
    Closeable {
    private companion object {
        const val MaxBitmapDimensionPx = 512

        /** ~16 covers at 512px ARGB — enough for a browse screen of rows. */
        const val BitmapCacheBytes = 16 * 1024 * 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Media3's CacheBitmapLoader memoizes only the single most recent request,
    // so without our own cache every re-entered browse section re-downloads
    // all of its row artwork.
    private val bitmapCache = object : android.util.LruCache<String, Bitmap>(BitmapCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = scope.future {
        decodeSampledBitmap(data, "bitmap from ${data.size}-byte payload")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future {
        val cacheKey = uri.toString()
        bitmapCache.get(cacheKey)?.let { return@future it }
        check(uri.scheme == "https" || uri.scheme == "http") {
            "Unsupported artwork scheme '${uri.scheme}' for $uri — only http(s) can be fetched"
        }
        val request = Request.Builder().url(cacheKey).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} fetching artwork $uri" }
            val bytes = response.body?.bytes()
                ?: error("Empty body fetching artwork $uri")
            decodeSampledBitmap(bytes, "artwork $uri").also { bitmapCache.put(cacheKey, it) }
        }
    }

    override fun close() {
        scope.cancel()
        bitmapCache.evictAll()
    }

    private fun decodeSampledBitmap(data: ByteArray, description: String): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        val decoded = BitmapFactory.decodeByteArray(
            data,
            0,
            data.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: error("Could not decode $description")
        return decoded.scaleDownToMaxDimension()
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height
        while (sampledWidth / 2 >= MaxBitmapDimensionPx && sampledHeight / 2 >= MaxBitmapDimensionPx) {
            sampleSize *= 2
            sampledWidth /= 2
            sampledHeight /= 2
        }
        return sampleSize
    }

    private fun Bitmap.scaleDownToMaxDimension(): Bitmap {
        val maxSide = max(width, height)
        if (maxSide <= MaxBitmapDimensionPx) return this
        val scale = MaxBitmapDimensionPx.toFloat() / maxSide.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            this,
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        recycle()
        return scaled
    }
}
