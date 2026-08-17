package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.network.AuthTokenProvider
import dev.josu.hypecar.core.network.dto.toModel
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.IOException

class DefaultHistoryRepositoryTest {
    @Test
    fun `postListen always records local history even when api fails`() = runBlocking {
        val historyDao = FakeHistoryDao()
        val trackDao = FakeTrackDao()
        val api = ScriptedHistoryApi(throwIo = true)

        val repo = DefaultHistoryRepository(api, historyDao, trackDao)

        val acked = repo.postListen("xyz", positionSeconds = 30)

        assertThat(acked).isFalse()
        assertThat(historyDao.entries.map { it.trackId }).containsExactly("xyz")
        assertThat(historyDao.entries.single().lastPositionSeconds).isEqualTo(30)
    }

    @Test
    fun `postListen returns true when api ack body is 1`() = runBlocking {
        val api = ScriptedHistoryApi(response = "1")

        val repo = DefaultHistoryRepository(api, FakeHistoryDao(), FakeTrackDao())

        assertThat(repo.postListen("xyz", positionSeconds = 0)).isTrue()
        assertThat(api.requested).isEqualTo("xyz" to 0)
    }

    @Test
    fun `postListen returns false when api ack body is anything else`() = runBlocking {
        val api = ScriptedHistoryApi(response = "0")

        val repo = DefaultHistoryRepository(api, FakeHistoryDao(), FakeTrackDao())

        assertThat(repo.postListen("xyz", positionSeconds = 0)).isFalse()
    }

    @Test
    fun `account switch before remote post cannot send the new account token`() = runBlocking {
        val gate = AccountDataWriteGate(initiallyActive = true)
        val tokens = MutableHistoryTokenProvider("account-a-token")
        val api = ScriptedHistoryApi(response = "1")
        val repo = DefaultHistoryRepository(
            api = api,
            historyDao = FakeHistoryDao(),
            trackDao = FakeTrackDao(),
            accountDataWriteGate = gate,
            authTokenProvider = tokens,
            beforeRemotePost = {
                gate.deactivate()
                tokens.token = "account-b-token"
                gate.activate()
            },
        )

        val acknowledged = repo.postListen("account-a-track", positionSeconds = 30)

        assertThat(acknowledged).isFalse()
        assertThat(api.requested).isNull()
    }

    @Test
    fun `mappers convert dto to entity through toModel + toEntity round trip`() {
        val dto = sampleTrackDto("a")
        val track = dto.toModel()
        val roundTrip = track.toEntity().toModel()
        assertThat(roundTrip.id).isEqualTo("a")
        assertThat(roundTrip.title).isEqualTo("Track a")
    }
}

private class ScriptedHistoryApi(
    private val response: String = "0",
    private val throwIo: Boolean = false,
) : FakeHypeApiService() {
    var requested: Pair<String, Int>? = null
        private set

    override suspend fun postHistory(
        type: String,
        itemId: String,
        position: Int,
        authToken: String?,
    ): ResponseBody {
        requested = itemId to position
        if (throwIo) throw IOException("offline")
        return response.toResponseBody()
    }
}

private class MutableHistoryTokenProvider(var token: String?) : AuthTokenProvider {
    override fun currentToken(): String? = token
}
