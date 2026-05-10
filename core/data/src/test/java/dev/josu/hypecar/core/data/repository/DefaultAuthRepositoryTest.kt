package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.network.dto.GetTokenResponseDto
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class DefaultAuthRepositoryTest {
    @Test
    fun `login persists session through session store on success`() = runBlocking {
        val store = FakeSessionStore()
        val api = object : FakeHypeApiService() {
            override suspend fun getToken(username: String, password: String, deviceId: String): GetTokenResponseDto =
                GetTokenResponseDto(username = username, token = "tok-$deviceId")
        }
        val repo = DefaultAuthRepository(api, store)

        val result = repo.login("alice", "pw")

        assertThat(result.isSuccess).isTrue()
        assertThat(store.savedSession?.username).isEqualTo("alice")
        assertThat(store.savedSession?.token).startsWith("tok-")
    }

    @Test
    fun `login surfaces failure result on auth error`() = runBlocking {
        val store = FakeSessionStore()
        val api = object : FakeHypeApiService() {
            override suspend fun getToken(username: String, password: String, deviceId: String): GetTokenResponseDto =
                throw HttpException(Response.error<Any>(401, "".toResponseBody("text/plain".toMediaType())))
        }
        val repo = DefaultAuthRepository(api, store)

        val result = repo.login("alice", "wrong")

        assertThat(result.isFailure).isTrue()
        assertThat(store.savedSession).isNull()
    }

    @Test
    fun `logout clears session through store`() = runBlocking {
        val store = FakeSessionStore().apply {
            savedSession = dev.josu.hypecar.core.model.AuthSession("alice", "tok")
        }
        val repo = DefaultAuthRepository(api = object : FakeHypeApiService() {}, sessionStore = store)

        repo.logout()

        assertThat(store.savedSession).isNull()
        assertThat(store.cleared).isTrue()
    }
}
