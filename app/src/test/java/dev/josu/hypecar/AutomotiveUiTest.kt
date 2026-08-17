package dev.josu.hypecar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.ui.isAutomotiveUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AutomotiveUiTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [34])
    fun `ordinary devices are not classified as automotive by a broad device substring`() {
        assertThat(context.isAutomotiveUi()).isFalse()
    }

    @Test
    @Config(qualifiers = "car", sdk = [34])
    fun `car UI mode is classified as automotive`() {
        assertThat(context.isAutomotiveUi()).isTrue()
    }
}
