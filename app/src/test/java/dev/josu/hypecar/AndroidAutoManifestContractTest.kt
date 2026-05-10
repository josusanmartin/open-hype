package dev.josu.hypecar

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class AndroidAutoManifestContractTest {
    private val manifestText: String =
        File("src/main/AndroidManifest.xml").readText()
    private val automotiveDescriptorText: String =
        File("src/main/res/xml/automotive_app_desc.xml").readText()

    @Test
    fun `manifest advertises a discoverable Android Auto media app`() {
        assertThat(manifestText).contains("com.google.android.gms.car.application")
        assertThat(manifestText).contains("androidx.car.app.TintableAttributionIcon")
        assertThat(manifestText).contains("android:appCategory=\"audio\"")
        assertThat(manifestText).contains("android:label=\"@string/app_name\"")
        assertThat(manifestText).contains("android:icon=\"@mipmap/ic_launcher\"")
        assertThat(automotiveDescriptorText).contains("""<uses name="media" />""")
        assertThat(manifestText).contains("android.media.browse.MediaBrowserService")
        assertThat(manifestText).contains("androidx.media3.session.MediaLibraryService")
        assertThat(manifestText).contains("android:exported=\"true\"")
    }
}
