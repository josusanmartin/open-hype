package dev.josu.hypecar.feature.auth

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.model.UiErrorKind
import dev.josu.hypecar.core.model.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `success calls onSuccess and clears state`() = runTest {
        val repo = FakeAuthRepository(
            response = Result.success(AuthSession(username = "j", token = "t")),
        )
        val vm = LoginViewModel(repo)
        var notified = false

        vm.login("j", "p") { notified = true }
        advanceUntilIdle()

        assertThat(notified).isTrue()
        assertThat(vm.uiState.value.isLoading).isFalse()
        assertThat(vm.uiState.value.error).isNull()
    }

    @Test
    fun `401 surfaces friendly invalid credentials message on login attempt`() = runTest {
        val response = Response.error<Any>(401, "".toResponseBody("text/plain".toMediaType()))
        val repo = FakeAuthRepository(response = Result.failure(HttpException(response)))
        val vm = LoginViewModel(repo)

        vm.login("j", "wrong") { }
        advanceUntilIdle()

        assertThat(vm.uiState.value.error).isEqualTo(UiErrorKind.InvalidCredentials)
    }

    @Test
    fun `network failure surfaces friendly offline message`() = runTest {
        val repo = FakeAuthRepository(response = Result.failure(java.net.UnknownHostException("no dns")))
        val vm = LoginViewModel(repo)

        vm.login("j", "p") { }
        advanceUntilIdle()

        assertThat(vm.uiState.value.error).isEqualTo(UiErrorKind.Network)
    }

    @Test
    fun `dismissError clears error state`() = runTest {
        val repo = FakeAuthRepository(response = Result.failure(java.net.UnknownHostException("no dns")))
        val vm = LoginViewModel(repo)

        vm.login("j", "p") { }
        advanceUntilIdle()
        assertThat(vm.uiState.value.error).isNotNull()

        vm.dismissError()
        assertThat(vm.uiState.value.error).isNull()
    }
}

private class FakeAuthRepository(
    private val response: Result<AuthSession>,
) : AuthRepository {
    override val session: Flow<AuthSession?> = MutableStateFlow(null)
    override suspend fun login(usernameOrEmail: String, password: String): Result<AuthSession> = response
    override suspend fun logout() = Unit
}
