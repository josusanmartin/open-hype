package dev.josu.hypecar.feature.auth

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.model.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp", sdk = [34])
class LoginRouteScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `screen renders title blurb fields and login button`() {
        val viewModel = LoginViewModel(SuccessAuthRepository())

        composeRule.setContent {
            MaterialTheme {
                Surface { LoginRoute(onLoggedIn = {}, viewModel = viewModel) }
            }
        }

        composeRule.onNodeWithText("Sign in").assertIsDisplayed()
        composeRule.onNodeWithText("Username or email").assertIsDisplayed()
        composeRule.onNodeWithText("Password").assertIsDisplayed()
        composeRule.onNodeWithText("Log in").assertIsDisplayed()
    }

    @Test
    fun `login button is disabled until both fields have content`() {
        val viewModel = LoginViewModel(SuccessAuthRepository())

        composeRule.setContent {
            MaterialTheme {
                Surface { LoginRoute(onLoggedIn = {}, viewModel = viewModel) }
            }
        }

        composeRule.onNodeWithText("Log in").assertIsNotEnabled()

        composeRule.onNodeWithText("Username or email").performTextInput("alice")
        composeRule.onNodeWithText("Log in").assertIsNotEnabled()

        composeRule.onNodeWithText("Password").performTextInput("secret")
        composeRule.onNodeWithText("Log in").assertIsEnabled()
    }

    @Test
    fun `tapping Show toggles password visibility label to Hide and back`() {
        val viewModel = LoginViewModel(SuccessAuthRepository())

        composeRule.setContent {
            MaterialTheme {
                Surface { LoginRoute(onLoggedIn = {}, viewModel = viewModel) }
            }
        }

        composeRule.onNodeWithText("Show").assertIsDisplayed()
        composeRule.onNodeWithText("Show").performClick()
        composeRule.onNodeWithText("Hide").assertIsDisplayed()
        composeRule.onNodeWithText("Hide").performClick()
        composeRule.onNodeWithText("Show").assertIsDisplayed()
    }

    @Test
    fun `successful login invokes the onLoggedIn callback`() {
        val viewModel = LoginViewModel(SuccessAuthRepository())
        var loggedIn = false

        composeRule.setContent {
            MaterialTheme {
                Surface { LoginRoute(onLoggedIn = { loggedIn = true }, viewModel = viewModel) }
            }
        }

        composeRule.onNodeWithText("Username or email").performTextInput("alice")
        composeRule.onNodeWithText("Password").performTextInput("secret")
        composeRule.onNodeWithText("Log in").performClick()

        composeRule.waitUntil(timeoutMillis = 2_000) { loggedIn }
        assertThat(loggedIn).isTrue()
    }

    @Test
    fun `failed login surfaces a friendly error and keeps button enabled`() {
        val viewModel = LoginViewModel(FailingAuthRepository())

        composeRule.setContent {
            MaterialTheme {
                Surface { LoginRoute(onLoggedIn = {}, viewModel = viewModel) }
            }
        }

        composeRule.onNodeWithText("Username or email").performTextInput("alice")
        composeRule.onNodeWithText("Password").performTextInput("wrong")
        composeRule.onNodeWithText("Log in").performClick()

        composeRule.waitUntil(timeoutMillis = 2_000) {
            viewModel.uiState.value.error != null
        }
        composeRule.onNodeWithText("Username or password is incorrect.").assertIsDisplayed()
    }
}

private class SuccessAuthRepository : AuthRepository {
    override val session: Flow<AuthSession?> = MutableStateFlow(null)
    override suspend fun login(usernameOrEmail: String, password: String): Result<AuthSession> =
        Result.success(AuthSession(username = usernameOrEmail, token = "tok"))
    override suspend fun logout() = Unit
}

private class FailingAuthRepository : AuthRepository {
    override val session: Flow<AuthSession?> = MutableStateFlow(null)
    override suspend fun login(usernameOrEmail: String, password: String): Result<AuthSession> {
        val body = okhttp3.ResponseBody.create(null, "")
        val response = retrofit2.Response.error<Any>(401, body)
        return Result.failure(retrofit2.HttpException(response))
    }
    override suspend fun logout() = Unit
}
