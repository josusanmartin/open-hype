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
import kotlinx.coroutines.guava.future
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

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
) : BitmapLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = scope.future {
        BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: error("Could not decode bitmap from ${data.size}-byte payload")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future {
        val request = Request.Builder().url(uri.toString()).build()
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes()
                ?: error("Empty body fetching artwork $uri")
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("Could not decode artwork $uri")
        }
    }
}
