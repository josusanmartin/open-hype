package dev.josu.hypecar.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() {
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true,
            maxIterations = 15,
            stableIterations = 3,
        ) {
            // End the startup trace at the first rendered frame. Waiting for
            // the network-backed Latest feed here would overfill classes.dex
            // with background fetch/parsing code that is not launch-critical.
            startActivityAndWait()
        }
    }

    @Test
    fun coreNavigation() {
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = false,
            maxIterations = 15,
            stableIterations = 3,
        ) {
            val journeys = launchLatest()
            journeys.scrollLatest()
            journeys.navigateCoreTabs()
        }
    }
}
