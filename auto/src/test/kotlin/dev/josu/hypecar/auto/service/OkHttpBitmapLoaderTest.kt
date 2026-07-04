package dev.josu.hypecar.auto.service

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OkHttpBitmapLoaderTest {
    @Test
    fun `decodeBitmap downsamples large artwork before returning it`() {
        val source = Bitmap.createBitmap(1_200, 900, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { output ->
            source.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        val loader = OkHttpBitmapLoader(OkHttpClient())

        val decoded = loader.decodeBitmap(bytes).get(2, TimeUnit.SECONDS)

        assertThat(max(decoded.width, decoded.height)).isAtMost(512)
    }
}
