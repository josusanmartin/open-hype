package dev.josu.hypecar.auto.service

import android.graphics.Bitmap
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OkHttpBitmapLoaderTest {
    @Test
    fun `decodeBitmap downsamples large artwork before returning it`() {
        val source = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { output ->
            source.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        val loader = OkHttpBitmapLoader(OkHttpClient())

        val decoded = loader.decodeBitmap(bytes).get(2, TimeUnit.SECONDS)

        assertThat(max(decoded.width, decoded.height)).isAtMost(512)
        loader.close()
    }

    @Test
    fun `decodeBitmap downsamples an extreme aspect ratio`() {
        val loader = OkHttpBitmapLoader(OkHttpClient())

        val decoded = loader.decodeBitmap(bitmapBytes(width = 4_096, height = 16))
            .get(2, TimeUnit.SECONDS)

        assertThat(decoded.width).isAtMost(512)
        assertThat(decoded.height).isAtLeast(1)
        loader.close()
    }

    @Test
    fun `loadBitmap accepts bounded image responses`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "image/png")
                    .setBody(Buffer().write(bitmapBytes(width = 96, height = 64))),
            )
            val loader = OkHttpBitmapLoader(clientRewrittenTo(server))

            val bitmap = loader.loadBitmap(Uri.parse("https://art.example/cover.png"))
                .get(2, TimeUnit.SECONDS)

            assertThat(bitmap.width).isEqualTo(96)
            assertThat(bitmap.height).isEqualTo(64)
            loader.close()
        }
    }

    @Test
    fun `loadBitmap rejects a declared payload over the byte limit`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setBody("x")
                    .setHeader("Content-Type", "image/jpeg")
                    .setHeader("Content-Length", MaxArtworkPayloadBytes + 1L),
            )
            val loader = OkHttpBitmapLoader(clientRewrittenTo(server))

            val thrown = assertThrows(ExecutionException::class.java) {
                loader.loadBitmap(Uri.parse("https://art.example/oversized.jpg"))
                    .get(2, TimeUnit.SECONDS)
            }

            assertThat(thrown.cause).hasMessageThat().contains("too large")
            loader.close()
        }
    }

    @Test
    fun `loadBitmap bounds a chunked payload without a content length`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "image/png")
                    .setChunkedBody(
                        Buffer().write(ByteArray(MaxArtworkPayloadBytes + 1)),
                        /* maxChunkSize = */
                        8 * 1_024,
                    ),
            )
            val loader = OkHttpBitmapLoader(clientRewrittenTo(server))

            val thrown = assertThrows(ExecutionException::class.java) {
                loader.loadBitmap(Uri.parse("https://art.example/chunked.png"))
                    .get(5, TimeUnit.SECONDS)
            }

            assertThat(thrown.cause).hasMessageThat().contains("exceeds")
            loader.close()
        }
    }

    @Test
    fun `loadBitmap rejects a non-image response`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setBody("<html>not artwork</html>"),
            )
            val loader = OkHttpBitmapLoader(clientRewrittenTo(server))

            val thrown = assertThrows(ExecutionException::class.java) {
                loader.loadBitmap(Uri.parse("https://art.example/not-an-image"))
                    .get(2, TimeUnit.SECONDS)
            }

            assertThat(thrown.cause).hasMessageThat().contains("Unexpected content type")
            loader.close()
        }
    }

    @Test
    fun `cancelling the bitmap future cancels the in-flight network call`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeadersDelay(30, TimeUnit.SECONDS)
                    .setHeader("Content-Type", "image/png")
                    .setBody(Buffer().write(bitmapBytes(width = 8, height = 8))),
            )
            val callStarted = CountDownLatch(1)
            val callCancelled = CountDownLatch(1)
            val client = clientRewrittenTo(
                server,
                object : EventListener() {
                    override fun callStart(call: Call) {
                        callStarted.countDown()
                    }

                    override fun canceled(call: Call) {
                        callCancelled.countDown()
                    }
                },
            )
            val loader = OkHttpBitmapLoader(client)
            val future = loader.loadBitmap(Uri.parse("https://art.example/slow.png"))

            // Cancelling before the loader coroutine creates a Call is also
            // valid, but cannot exercise the OkHttp cancellation bridge.
            assertThat(callStarted.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(future.cancel(true)).isTrue()
            assertThat(callCancelled.await(2, TimeUnit.SECONDS)).isTrue()
            loader.close()
        }
    }

    @Test
    fun `loadBitmap rejects non-HTTPS artwork before making a request`() {
        val loader = OkHttpBitmapLoader(OkHttpClient())

        val thrown = assertThrows(ExecutionException::class.java) {
            loader.loadBitmap(Uri.parse("http://art.example/insecure.png"))
                .get(2, TimeUnit.SECONDS)
        }

        assertThat(thrown.cause).hasMessageThat().contains("only HTTPS")
        loader.close()
    }

    private fun bitmapBytes(width: Int, height: Int): ByteArray {
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                check(source.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            source.recycle()
        }
    }

    private fun clientRewrittenTo(
        server: MockWebServer,
        eventListener: EventListener? = null,
    ): OkHttpClient = OkHttpClient.Builder()
        .apply { eventListener?.let(::eventListener) }
        .addInterceptor { chain ->
            val original = chain.request()
            val localUrl = server.url(original.url.encodedPath)
            chain.proceed(original.newBuilder().url(localUrl).build())
                .newBuilder()
                .request(original)
                .build()
        }
        .build()
}
