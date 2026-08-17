package dev.josu.hypecar

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.ui.HypeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w891dp-h411dp-car", sdk = [34])
class AutomotiveSetupRouteScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `signed out car setup keeps playback in the system media app`() {
        composeRule.setContent {
            HypeTheme(isAutomotive = true) {
                AutomotiveSetupScreen(
                    state = AutomotiveSetupState.SignedOut,
                    onSignIn = {},
                    onOpenSettings = {},
                    onSignOut = {},
                )
            }
        }

        composeRule.onNodeWithText("Open Hype")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeRule.onNodeWithText("Your music, in the car’s Media app").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertIsDisplayed()
        composeRule.onNodeWithText("Offline settings").assertIsDisplayed()
        composeRule.onNodeWithText("Only change setup while parked.").assertIsDisplayed()
    }

    @Test
    fun `signed in car setup exposes account and settings actions`() {
        var signedOut = false
        var openedSettings = false
        composeRule.setContent {
            HypeTheme(isAutomotive = true) {
                AutomotiveSetupScreen(
                    state = AutomotiveSetupState.SignedIn("JOSU"),
                    onSignIn = {},
                    onOpenSettings = { openedSettings = true },
                    onSignOut = { signedOut = true },
                )
            }
        }

        composeRule.onNodeWithText("Signed in as JOSU").assertIsDisplayed()
        composeRule.onNodeWithText("Sign out").performClick()
        composeRule.onNodeWithText("Offline settings").performClick()

        assertThat(signedOut).isTrue()
        assertThat(openedSettings).isTrue()
    }
}
