package dev.josu.hypecar

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class AndroidAutoManifestContractTest {
    private val manifestText: String =
        File("src/main/AndroidManifest.xml").readText()
    private val automotiveDescriptorText: String =
        File("src/main/res/xml/automotive_app_desc.xml").readText()
    private val buildGradleText: String =
        File("build.gradle.kts").readText()

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

    @Test
    fun `media service is wired with the required foreground service type and target class`() {
        // Without foregroundServiceType the service can't be promoted to FG and
        // will crash on Android 14+ once playback starts.
        assertThat(manifestText).contains("android:foregroundServiceType=\"mediaPlayback\"")
        // The service name must point at the real implementation; a typo here
        // produces a runtime "service not found" only when the user opens Auto.
        assertThat(manifestText).contains("dev.josu.hypecar.auto.service.HypeMediaLibraryService")
    }

    @Test
    fun `manifest declares the Assistant voice-search intent filter`() {
        // Required for "Hey Google, play X on Hype Machine" to route into onSearch.
        assertThat(manifestText).contains("android.media.action.MEDIA_PLAY_FROM_SEARCH")
    }

    @Test
    fun `manifest declares the foreground service media playback permission`() {
        // Android 14+ requires the matching runtime permission for the
        // mediaPlayback foreground service type.
        assertThat(manifestText).contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK")
    }

    @Test
    fun `app builds as one unified phone and car apk`() {
        assertThat(buildGradleText).doesNotContain("productFlavors")
        assertThat(buildGradleText).doesNotContain("applicationIdSuffix")
        assertThat(buildGradleText).doesNotContain("versionNameSuffix")
        assertThat(File("src/automotive/AndroidManifest.xml").exists()).isFalse()
    }

    @Test
    fun `unified manifest advertises projected Auto and Automotive media capability`() {
        assertThat(manifestText).contains("com.google.android.gms.car.application")
        assertThat(manifestText).contains("com.android.automotive")
        assertThat(manifestText).contains("android.hardware.type.automotive")
        assertThat(manifestText).contains("android:required=\"false\"")
    }
}
