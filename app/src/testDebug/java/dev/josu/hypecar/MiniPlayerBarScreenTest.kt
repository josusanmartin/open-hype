package dev.josu.hypecar

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp", sdk = [34])
class MiniPlayerBarScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `paused mini-player shows title artist and Play action`() {
        val state = MiniPlayerUiState(
            title = "Light of the Dead",
            artist = "Brooklynzhen",
            artworkUrl = null,
            progressFraction = 0.4f,
            isPlaying = false,
        )

        composeRule.setContent {
            MaterialTheme {
                Surface {
                    MiniPlayerBar(
                        uiState = state,
                        onOpenPlayer = {},
                        onTogglePlayPause = {},
                        onSkipNext = {},
                        onSkipPrevious = {},
                        metrics = AppChromeMetrics.phone(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Light of the Dead").assertIsDisplayed()
        composeRule.onNodeWithText("Brooklynzhen").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    @Test
    fun `playing mini-player shows Pause action`() {
        val state = MiniPlayerUiState(
            title = "x",
            artist = "y",
            artworkUrl = null,
            progressFraction = 0f,
            isPlaying = true,
        )

        composeRule.setContent {
            MaterialTheme {
                Surface {
                    MiniPlayerBar(
                        uiState = state,
                        onOpenPlayer = {},
                        onTogglePlayPause = {},
                        onSkipNext = {},
                        onSkipPrevious = {},
                        metrics = AppChromeMetrics.phone(),
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test
    fun `tapping transport controls invokes the corresponding callbacks`() {
        var pausedToggled = false
        var nextTapped = false
        var prevTapped = false
        var openTapped = false

        composeRule.setContent {
            MaterialTheme {
                Surface {
                    MiniPlayerBar(
                        uiState = MiniPlayerUiState(
                            title = "x",
                            artist = "y",
                            artworkUrl = null,
                            progressFraction = 0f,
                            isPlaying = false,
                        ),
                        onOpenPlayer = { openTapped = true },
                        onTogglePlayPause = { pausedToggled = true },
                        onSkipNext = { nextTapped = true },
                        onSkipPrevious = { prevTapped = true },
                        metrics = AppChromeMetrics.phone(),
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Previous").performClick()
        composeRule.onNodeWithContentDescription("Play").performClick()
        composeRule.onNodeWithContentDescription("Next").performClick()
        composeRule.onNodeWithContentDescription("Open player").performClick()

        assertThat(prevTapped).isTrue()
        assertThat(pausedToggled).isTrue()
        assertThat(nextTapped).isTrue()
        assertThat(openTapped).isTrue()
    }

    @Test
    fun `automotive metrics still render the same affordances`() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    MiniPlayerBar(
                        uiState = MiniPlayerUiState(
                            title = "x",
                            artist = "y",
                            artworkUrl = null,
                            progressFraction = 0.5f,
                            isPlaying = true,
                        ),
                        onOpenPlayer = {},
                        onTogglePlayPause = {},
                        onSkipNext = {},
                        onSkipPrevious = {},
                        metrics = AppChromeMetrics.automotive(),
                    )
                }
            }
        }

        // Same content descriptions render under the compact metrics.
        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Previous").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Next").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open player").assertIsDisplayed()
    }
}
