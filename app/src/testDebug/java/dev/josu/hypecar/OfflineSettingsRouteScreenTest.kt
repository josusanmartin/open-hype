package dev.josu.hypecar

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.repository.OfflineDownloadStatus
import dev.josu.hypecar.core.model.repository.OfflineRepository
import dev.josu.hypecar.core.ui.HypeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp", sdk = [34])
class OfflineSettingsRouteScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `screen renders header storage limit and main actions`() {
        val repo = StubOfflineRepository(
            OfflineDownloadStatus(isEnabled = true, quotaBytes = 500L * 1024L * 1024L),
        )
        val viewModel = OfflineSettingsViewModel(repo)

        composeRule.setContent {
            HypeTheme {
                Surface { OfflineSettingsRoute(viewModel = viewModel) }
            }
        }

        composeRule.onNodeWithText("Settings")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeRule.onNodeWithText("Manage your offline listening").assertIsDisplayed()
        composeRule.onNodeWithText("Offline listening")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeRule.onNodeWithText("Storage limit")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeRule.onNodeWithText("Sync now").assertIsDisplayed()
        composeRule.onNodeWithText("Clear cached data").assertIsDisplayed().assertIsNotEnabled()
        // The selected quota appears in the row header and the discrete chip set.
        assertThat(composeRule.onAllNodesWithText("500 MB").fetchSemanticsNodes()).isNotEmpty()
    }

    @Test
    fun `clear cached data opens confirmation dialog and Cancel dismisses it`() {
        val repo = StubOfflineRepository(
            OfflineDownloadStatus(isEnabled = true, usedBytes = 1L, downloadedTrackCount = 1),
        )
        val viewModel = OfflineSettingsViewModel(repo)

        composeRule.setContent {
            HypeTheme {
                Surface { OfflineSettingsRoute(viewModel = viewModel) }
            }
        }

        composeRule.onNodeWithText("Clear cached data").performClick()

        composeRule.onNodeWithText("Clear cached downloads?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()

        // Dialog gone — only the original list "Clear cached data" row remains.
        assertThat(composeRule.onAllNodesWithText("Cancel").fetchSemanticsNodes()).isEmpty()
        assertThat(repo.clearCount).isEqualTo(0)
    }

    @Test
    fun `clear confirmation forwards to repository`() {
        val repo = StubOfflineRepository(
            OfflineDownloadStatus(isEnabled = true, usedBytes = 1L, downloadedTrackCount = 1),
        )
        val viewModel = OfflineSettingsViewModel(repo)

        composeRule.setContent {
            HypeTheme {
                Surface { OfflineSettingsRoute(viewModel = viewModel) }
            }
        }

        composeRule.onNodeWithText("Clear cached data").performClick()
        // The dialog has its own "Clear" confirm button.
        composeRule.onNodeWithText("Clear", substring = false).performClick()

        composeRule.waitUntil(timeoutMillis = 2_000) { repo.clearCount == 1 }
        assertThat(repo.clearCount).isEqualTo(1)
    }

    @Test
    @Config(qualifiers = "w800dp-h480dp")
    fun `compact quota exposes selected semantics and a full touch target`() {
        val viewModel = OfflineSettingsViewModel(
            StubOfflineRepository(
                OfflineDownloadStatus(
                    isEnabled = true,
                    quotaBytes = 500L * 1024L * 1024L,
                ),
            ),
        )

        composeRule.setContent {
            HypeTheme(darkTheme = true, isAutomotive = true) {
                OfflineSettingsRoute(viewModel = viewModel, compactMode = true)
            }
        }

        composeRule.onNodeWithText("500 MB")
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    @Config(qualifiers = "w800dp-h480dp")
    fun `repository detail is replaced by an announced friendly error`() {
        val viewModel = OfflineSettingsViewModel(
            StubOfflineRepository(
                OfflineDownloadStatus(
                    isEnabled = true,
                    error = "java.net.SocketTimeoutException: internal endpoint",
                ),
            ),
        )
        val friendlyMessage = "Couldn't update offline downloads. Try again."

        composeRule.setContent {
            HypeTheme(darkTheme = true, isAutomotive = true) {
                OfflineSettingsRoute(viewModel = viewModel, compactMode = true)
            }
        }

        composeRule.onNodeWithText(friendlyMessage)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Error,
                    friendlyMessage,
                ),
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        composeRule.onNodeWithText("java.net.SocketTimeoutException: internal endpoint")
            .assertDoesNotExist()
    }
}

private class StubOfflineRepository(initial: OfflineDownloadStatus) : OfflineRepository {
    private val _status = MutableStateFlow(initial)
    override val status: StateFlow<OfflineDownloadStatus> = _status
    var clearCount = 0
        private set

    override suspend fun setEnabled(enabled: Boolean) {
        _status.value = _status.value.copy(isEnabled = enabled)
    }
    override suspend fun setQuotaBytes(quotaBytes: Long) {
        _status.value = _status.value.copy(quotaBytes = quotaBytes)
    }
    override suspend fun syncFavorites() = Unit
    override suspend fun clearDownloads() {
        clearCount += 1
    }
    override fun cachedAudioUri(trackId: String): String? = null
}
