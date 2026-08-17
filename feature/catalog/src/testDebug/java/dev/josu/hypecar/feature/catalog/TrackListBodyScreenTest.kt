package dev.josu.hypecar.feature.catalog

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
    fun `loading state renders one accessible skeleton announcement`() {
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
        // The visual list contains six rows, but it is one loading state for
        // TalkBack instead of six identical focus stops.
        assertThat(
            composeRule.onAllNodesWithContentDescription("Loading").fetchSemanticsNodes(),
        ).hasSize(1)
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
                        error = dev.josu.hypecar.core.model.UiErrorKind.Network,
                        onTrackClick = {},
                        onBlogClick = {},
                        onRetry = { retried = true },
                    )
                }
            }
        }

        val errorMessage = "Can't reach the server. Check your connection and try again."
        composeRule.onNodeWithText(errorMessage)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, errorMessage))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()

        assertThat(retried).isTrue()
    }

    @Test
    fun `refresh error remains visible when tracks are already on screen`() {
        val message = "Can't reach the server. Check your connection and try again."
        composeRule.setContent {
            HypeTheme {
                Surface {
                    TrackListBody(
                        tracks = listOf(track("a", "Alpha")),
                        isLoading = false,
                        error = dev.josu.hypecar.core.model.UiErrorKind.Network,
                        onTrackClick = {},
                        onBlogClick = {},
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 2_000) {
            composeRule.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
    }

    @Test
    fun `hero exposes selected tab semantics and a full touch target`() {
        composeRule.setContent {
            HypeTheme {
                EditorialHeroHeader(
                    title = "Latest",
                    subtitle = "Fresh finds",
                    imageUrl = null,
                    chips = listOf("Newest", "Popular"),
                    selectedChipIndex = 1,
                    onChipSelected = {},
                    onUtilityClick = null,
                )
            }
        }

        composeRule.onNodeWithText("Popular")
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Latest")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    }

    @Test
    fun `hero grows with large text instead of clipping its filter controls`() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                HypeTheme {
                    EditorialHeroHeader(
                        title = "A deliberately long editorial heading",
                        subtitle = "A longer description that needs room at the largest supported text scale.",
                        imageUrl = null,
                        chips = listOf("First filter", "Second filter"),
                        selectedChipIndex = 0,
                        onChipSelected = {},
                        onUtilityClick = null,
                        modifier = Modifier.testTag("large-text-hero"),
                    )
                }
            }
        }

        val heroBottom = composeRule.onNodeWithTag("large-text-hero")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
            .bottom
            .value
        val chipBottom = composeRule.onNodeWithText("First filter")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
            .bottom
            .value

        assertThat(chipBottom).isAtMost(heroBottom)
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
