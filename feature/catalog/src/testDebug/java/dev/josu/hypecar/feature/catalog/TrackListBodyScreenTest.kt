package dev.josu.hypecar.feature.catalog

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.ui.HypeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp", sdk = [34])
class TrackListBodyScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `loading state renders the spinner`() {
        composeRule.setContent {
            HypeTheme {
                Surface {
                    TrackListBody(
                        tracks = emptyList(),
                        isLoading = true,
                        error = null,
                        onTrackClick = {},
                        onBlogClick = {},
                    )
                }
            }
        }
        // Spinner has no text — assert tracks empty rendered nothing else.
        assertThat(composeRule.onAllNodesWithText("Nothing here yet.").fetchSemanticsNodes())
            .isEmpty()
    }

    @Test
    fun `error state renders the message and Retry button when callback is wired`() {
        var retried = false
        composeRule.setContent {
            HypeTheme {
                Surface {
                    TrackListBody(
                        tracks = emptyList(),
                        isLoading = false,
                        error = "Network unreachable",
                        onTrackClick = {},
                        onBlogClick = {},
                        onRetry = { retried = true },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Network unreachable").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()

        assertThat(retried).isTrue()
    }

    @Test
    fun `empty state falls back to localized default when no message override is given`() {
        composeRule.setContent {
            HypeTheme {
                Surface {
                    TrackListBody(
                        tracks = emptyList(),
                        isLoading = false,
                        error = null,
                        onTrackClick = {},
                        onBlogClick = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Nothing here yet.").assertIsDisplayed()
    }

    @Test
    fun `tracks render their title and tapping a row fires onTrackClick with the right index`() {
        var tappedIndex: Int? = null
        composeRule.setContent {
            HypeTheme {
                Surface {
                    TrackListBody(
                        tracks = listOf(track("a", "Alpha"), track("b", "Beta")),
                        isLoading = false,
                        error = null,
                        onTrackClick = { tappedIndex = it },
                        onBlogClick = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeRule.onNodeWithText("Beta").assertIsDisplayed()
        composeRule.onNodeWithText("Beta").performClick()

        assertThat(tappedIndex).isEqualTo(1)
    }

    @Test
    fun `blank artist title and blog fall back to localized Unknown placeholders`() {
        // Mappers leave fields empty when the API returns blanks; UI should localize.
        composeRule.setContent {
            HypeTheme {
                Surface {
                    TrackListBody(
                        tracks = listOf(track(id = "x", title = "", artist = "", postedBy = "")),
                        isLoading = false,
                        error = null,
                        onTrackClick = {},
                        onBlogClick = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Unknown track").assertIsDisplayed()
        composeRule.onNodeWithText("Unknown artist").assertIsDisplayed()
    }

    private fun track(
        id: String,
        title: String = "Title $id",
        artist: String = "Artist $id",
        postedBy: String = "Blog $id",
    ) = Track(
        id = id,
        artist = artist,
        title = title,
        lovedCount = 0,
        postedBy = postedBy,
        postedById = 0,
        postedCount = 0,
        postDescription = "",
        datePostedEpochSeconds = 0L,
        postUrl = "",
        itunesUrl = "",
    )
}
