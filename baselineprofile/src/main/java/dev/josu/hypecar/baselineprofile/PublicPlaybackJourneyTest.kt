package dev.josu.hypecar.baselineprofile

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublicPlaybackJourneyTest {
    @Test
    fun latestPlayAndOpenFullPlayerWhenCatalogIsAvailable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Opt in with -Pandroid.testInstrumentationRunnerArguments.liveCatalog=true",
            InstrumentationRegistry.getArguments().getString("liveCatalog") == "true",
        )
        val device = UiDevice.getInstance(instrumentation)
        val launchIntent = instrumentation.context.packageManager
            .getLaunchIntentForPackage(TARGET_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)

        checkNotNull(launchIntent) { "No launcher activity found for $TARGET_PACKAGE" }
        instrumentation.context.startActivity(launchIntent)

        val journeys = HypeJourneys(device)
        journeys.awaitApp()
        assumeTrue(
            "Live public catalog did not load; deterministic profile generation remains unaffected",
            journeys.playFirstTrackAndOpenPlayer(),
        )
    }
}
