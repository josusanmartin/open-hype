package dev.josu.hypecar.core.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.FeedMode
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.TrackThumbnails
import dev.josu.hypecar.core.model.repository.MeRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * End-to-end orchestration test for [DefaultOfflineRepository]. Uses a real
 * Robolectric Context with DataStore + filesystem, MockWebServer for the audio
 * download host, and a scripted [MeRepository] for favorites.
 *
 * Real-IO timing means these tests use [runBlocking] (not virtual-time runTest)
 * so DataStore writes and OkHttp calls actually complete.
 */
@RunWith(RobolectricTestRunner::class)
class DefaultOfflineRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
        // Wipe any state left by other tests.
        context.preferencesDataStoreFile("offline.preferences_pb").delete()
        File(context.filesDir, "offline_audio").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build(),
        )
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        server.shutdown()
        context.preferencesDataStoreFile("offline.preferences_pb").delete()
        File(context.filesDir, "offline_audio").deleteRecursively()
    }

    @Test
    fun `setEnabled true publishes enabled status`() = runBlocking {
        val repo = newRepo(me = EmptyMe)

        repo.setEnabled(true)

        val status = withTimeout(2_000L) {
            repo.status.first { it.isEnabled }
        }
        assertThat(status.isEnabled).isTrue()
        assertThat(status.quotaBytes).isEqualTo(500L * 1024L * 1024L)
    }

    @Test
    fun `syncFavorites downloads tracks and writes records`() = runBlocking {
        val tracks = listOf(track("a"), track("b"))
        val repo = newRepo(me = StaticMe(favorites = tracks))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        // Two successful downloads, then empty page to terminate paging.
        server.enqueue(MockResponse().setBody("aaaa"))
        server.enqueue(MockResponse().setBody("bbbbbb"))

        repo.syncFavorites()

        val status = withTimeout(2_000L) { repo.status.first { it.downloadedTrackCount == 2 } }
        assertThat(status.downloadedTrackCount).isEqualTo(2)
        assertThat(status.usedBytes).isEqualTo(10L)
        assertThat(status.error).isNull()
        assertThat(repo.cachedAudioUri("a")).isNotNull()
        assertThat(repo.cachedAudioUri("b")).isNotNull()
    }

    @Test
    fun `syncFavorites skips audioUnavailable tracks`() = runBlocking {
        val available = track("ok")
        val unavailable = track("nope").copy(audioUnavailable = true)
        val repo = newRepo(me = StaticMe(favorites = listOf(unavailable, available)))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setBody("audio"))

        repo.syncFavorites()

        val status = withTimeout(2_000L) { repo.status.first { it.downloadedTrackCount == 1 } }
        assertThat(status.downloadedTrackCount).isEqualTo(1)
        assertThat(repo.cachedAudioUri("ok")).isNotNull()
        assertThat(repo.cachedAudioUri("nope")).isNull()
        // Only one HTTP call — no request was made for the unavailable track.
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `clearDownloads removes cached files and records`() = runBlocking {
        val repo = newRepo(me = StaticMe(favorites = listOf(track("a"))))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setBody("data"))
        repo.syncFavorites()
        withTimeout(2_000L) { repo.status.first { it.downloadedTrackCount == 1 } }
        assertThat(repo.cachedAudioUri("a")).isNotNull()

        repo.clearDownloads()

        val status = withTimeout(2_000L) { repo.status.first { it.downloadedTrackCount == 0 } }
        assertThat(status.usedBytes).isEqualTo(0L)
        assertThat(repo.cachedAudioUri("a")).isNull()
    }

    @Test
    fun `cachedAudioUri returns null when offline mode is disabled`() = runBlocking {
        val repo = newRepo(me = StaticMe(favorites = listOf(track("a"))))
        repo.setEnabled(true)
        withTimeout(2_000L) { repo.status.first { it.isEnabled } }
        server.enqueue(MockResponse().setBody("x"))
        repo.syncFavorites()
        withTimeout(2_000L) { repo.status.first { it.downloadedTrackCount == 1 } }
        assertThat(repo.cachedAudioUri("a")).isNotNull()

        repo.setEnabled(false)
        withTimeout(2_000L) { repo.status.first { !it.isEnabled } }

        assertThat(repo.cachedAudioUri("a")).isNull()
    }

    private fun newRepo(me: MeRepository): DefaultOfflineRepository {
        val rewritingClient = OkHttpClient.Builder()
            .addInterceptor(StreamUrlRewriter(server.url("/serve/")))
            .build()
        return DefaultOfflineRepository(
            context = context,
            meRepository = me,
            client = rewritingClient,
            json = Json,
        )
    }

    private fun track(id: String) = Track(
        id = id,
        artist = "x",
        title = "y",
        lovedCount = 0,
        postedBy = "z",
        postedById = 0,
        postedCount = 0,
        postDescription = "",
        datePostedEpochSeconds = 0L,
        postUrl = "",
        itunesUrl = "",
        thumbnails = TrackThumbnails(),
    )
}

/** Rewrites every outbound `https://hypem.com/serve/public/...` request at the
 *  network boundary so MockWebServer can answer it. */
private class StreamUrlRewriter(private val mockBase: HttpUrl) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host == "hypem.com") {
            val rewritten = request.newBuilder()
                .url(mockBase.newBuilder().addPathSegments(request.url.encodedPath.removePrefix("/serve/")).build())
                .build()
            return chain.proceed(rewritten)
        }
        return chain.proceed(request)
    }
}

private class StaticMe(private val favorites: List<Track>) : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> =
        if (page == 1) favorites else emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private object EmptyMe : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}
