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
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

internal const val MaxArtworkPayloadBytes = 4 * 1024 * 1024

/** Fetches a bounded HTTPS image and cancels the OkHttp call with its coroutine. */
internal suspend fun OkHttpClient.fetchArtworkBytes(uri: Uri): ByteArray {
    check(uri.scheme.equals("https", ignoreCase = true)) {
        "Unsupported artwork scheme '${uri.scheme}' for $uri — only HTTPS can be fetched"
    }
    val request = Request.Builder().url(uri.toString()).build()
    return newCall(request).awaitResponse().use { response ->
        check(response.isSuccessful) { "HTTP ${response.code} fetching artwork $uri" }
        check(response.request.url.isHttps) { "Artwork request was redirected away from HTTPS: ${response.request.url}" }
        val body = response.body ?: error("Empty body fetching artwork $uri")
        val contentType = body.contentType()
        check(contentType?.type.equals("image", ignoreCase = true)) {
            "Unexpected content type '$contentType' fetching artwork $uri"
        }
        val declaredLength = body.contentLength()
        check(declaredLength < 0 || declaredLength <= MaxArtworkPayloadBytes) {
            "Artwork $uri is too large ($declaredLength bytes)"
        }
        val initialCapacity = declaredLength
            .takeIf { it in 1..MaxArtworkPayloadBytes.toLong() }
            ?.toInt()
            ?: DEFAULT_BUFFER_SIZE
        ByteArrayOutputStream(initialCapacity).use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalBytes = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    check(totalBytes <= MaxArtworkPayloadBytes - read) {
                        "Artwork $uri exceeds $MaxArtworkPayloadBytes bytes"
                    }
                    output.write(buffer, 0, read)
                    totalBytes += read
                }
            }
            output.toByteArray()
        }
    }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}

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

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/", ignoreCase = true)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = scope.future {
        decodeSampledBitmap(data, "bitmap from ${data.size}-byte payload")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future {
        val cacheKey = uri.toString()
        bitmapCache.get(cacheKey)?.let { return@future it }
        val bytes = client.fetchArtworkBytes(uri)
        decodeSampledBitmap(bytes, "artwork $uri").also { bitmapCache.put(cacheKey, it) }
    }

    override fun close() {
        scope.cancel()
        bitmapCache.evictAll()
    }

    private fun decodeSampledBitmap(data: ByteArray, description: String): Bitmap {
        check(data.isNotEmpty()) { "Empty $description" }
        check(data.size <= MaxArtworkPayloadBytes) {
            "$description exceeds $MaxArtworkPayloadBytes bytes"
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Could not decode bounds for $description" }
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
        val maxSide = max(width, height)
        while (
            maxSide / sampleSize > MaxBitmapDimensionPx * 2 &&
            sampleSize <= Int.MAX_VALUE / 2
        ) {
            sampleSize *= 2
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
