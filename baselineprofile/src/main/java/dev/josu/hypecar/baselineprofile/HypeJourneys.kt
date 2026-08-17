package dev.josu.hypecar.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal const val TARGET_PACKAGE = "dev.josu.hypecar"
internal const val DEFAULT_ITERATIONS = 10

private const val UI_TIMEOUT_MS = 10_000L
private const val IDLE_TIMEOUT_MS = 1_000L
private val latestPattern = localizedExact("Latest", "Recientes")
private val searchPattern = localizedExact("Search", "Buscar")
private val settingsPattern = localizedExact("Settings", "Ajustes")
private val playPattern = localizedExact("Play", "Reproducir")
private val openPlayerPattern = localizedExact("Open player", "Abrir reproductor")
private val playerVisiblePattern = localizedExact("Playback position", "Posición de reproducción")

/** Stable, credential-free journeys shared by profile generation and benchmarks. */
internal class HypeJourneys(
    private val device: UiDevice,
) {
    fun awaitApp() {
        check(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS)) {
            "Hype did not become visible within ${UI_TIMEOUT_MS}ms"
        }
        device.waitForIdle(IDLE_TIMEOUT_MS)
    }

    fun scrollLatest() {
        // UiDevice swipes use physical pixels, not dp.
        val width = device.displayWidth
        val height = device.displayHeight
        val x = width / 2
        device.swipe(x, height * 3 / 4, x, height * 2 / 5, 18)
        device.waitForIdle(IDLE_TIMEOUT_MS)
        device.swipe(x, height * 2 / 5, x, height * 3 / 4, 18)
        device.waitForIdle(IDLE_TIMEOUT_MS)
    }

    fun navigateCoreTabs() {
        clickText(searchPattern, "Search")
        clickText(settingsPattern, "Settings")
        clickText(latestPattern, "Latest")
    }

    /**
     * Exercises public-catalog playback when live data is available. It is not
     * used for checked-in profile generation because the catalog is remote.
     */
    fun playFirstTrackAndOpenPlayer(): Boolean {
        val play = waitForVisibleDescription(playPattern, timeoutMs = 15_000L) ?: return false
        play.click()
        device.waitForIdle(IDLE_TIMEOUT_MS)

        val openPlayer = waitForVisibleDescription(openPlayerPattern, timeoutMs = UI_TIMEOUT_MS) ?: return false
        openPlayer.click()
        return device.wait(Until.hasObject(By.desc(playerVisiblePattern)), UI_TIMEOUT_MS)
    }

    private fun clickText(
        pattern: Pattern,
        label: String,
    ) {
        val item = device.wait(Until.findObject(By.text(pattern)), UI_TIMEOUT_MS)
        checkNotNull(item) { "$label navigation item was not visible" }
        item.click()
        device.waitForIdle(IDLE_TIMEOUT_MS)
    }

    private fun waitForVisibleDescription(
        pattern: Pattern,
        timeoutMs: Long,
    ): UiObject2? {
        device.wait(Until.hasObject(By.desc(pattern)), timeoutMs)
        return device.findObjects(By.desc(pattern))
            .firstOrNull { it.visibleBounds.centerY() < device.displayHeight * 4 / 5 }
    }
}

internal fun MacrobenchmarkScope.launchLatest(): HypeJourneys {
    startActivityAndWait()
    return HypeJourneys(device).also { it.awaitApp() }
}

private fun localizedExact(vararg labels: String): Pattern =
    Pattern.compile(labels.joinToString(prefix = "^(?:", postfix = ")$", separator = "|") { Pattern.quote(it) }, Pattern.CASE_INSENSITIVE)
