package dev.josu.hypecar.feature.details

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.data.repository.FavoriteSyncManager
import dev.josu.hypecar.core.model.Blog
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.FeedMode
import dev.josu.hypecar.core.model.LatestMode
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.PopularMode
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.User
import dev.josu.hypecar.core.model.repository.CatalogRepository
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `BlogDetailViewModel loads blog and tracks on init`() = runTest {
        val catalog = ScriptedCatalog(
            blog = sampleBlog,
            blogTracks = listOf(track("a"), track("b")),
        )
        val vm = BlogDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("blogId" to 22246)),
            catalogRepository = catalog,
            playbackRepository = NoOpPlayback,
            favoriteSyncManager = detailsFavoriteSync(),
        )

        advanceUntilIdle()

        assertThat(vm.state.value.blog).isEqualTo(sampleBlog)
        assertThat(vm.state.value.tracks.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.error).isNull()
        assertThat(catalog.blogCalls).containsExactly(22246)
    }

    @Test
    fun `BlogDetailViewModel surfaces error on failed init`() = runTest {
        val catalog = ScriptedCatalog(blogError = IOException("offline"))
        val vm = BlogDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("blogId" to 1)),
            catalogRepository = catalog,
            playbackRepository = NoOpPlayback,
            favoriteSyncManager = detailsFavoriteSync(),
        )

        advanceUntilIdle()

        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.error).isEqualTo(dev.josu.hypecar.core.model.UiErrorKind.Network)
        assertThat(vm.state.value.blog).isNull()
    }

    @Test
    fun `UserDetailViewModel loads user and favorites`() = runTest {
        val catalog = ScriptedCatalog(
            user = sampleUser,
            userFavorites = listOf(track("u1")),
        )
        val vm = UserDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("username" to "alice")),
            catalogRepository = catalog,
            playbackRepository = NoOpPlayback,
            favoriteSyncManager = detailsFavoriteSync(),
        )

        advanceUntilIdle()

        assertThat(vm.state.value.user?.username).isEqualTo("alice")
        assertThat(vm.state.value.tracks.map { it.id }).containsExactly("u1")
    }

    @Test
    fun `play forwards loaded tracks at index to playback repository`() = runTest {
        val catalog = ScriptedCatalog(blog = sampleBlog, blogTracks = listOf(track("a"), track("b"), track("c")))
        val playback = RecordingPlayback()
        val vm = BlogDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("blogId" to 1)),
            catalogRepository = catalog,
            playbackRepository = playback,
            favoriteSyncManager = FavoriteSyncManager(DetailsNoOpMe, playback),
        )
        advanceUntilIdle()

        vm.play(2)
        advanceUntilIdle()

        assertThat(playback.lastPlayed?.map { it.id }).containsExactly("a", "b", "c").inOrder()
        assertThat(playback.lastStartIndex).isEqualTo(2)
    }

    private fun track(id: String) = Track(
        id = id, artist = "x", title = "y", lovedCount = 0,
        postedBy = "z", postedById = 0, postedCount = 0,
        postDescription = "", datePostedEpochSeconds = 0L,
        postUrl = "", itunesUrl = "",
    )
}

private val sampleBlog = Blog(
    id = 22246,
    name = "Destroy//Exist",
    url = "https://www.destroyexist.com",
    followerCount = 1234,
    trackCount = 567,
    imageUrl = null,
    imageUrlSmall = null,
)

private val sampleUser = User(
    username = "alice",
    fullName = "Alice Q",
    avatarUrl = null,
    favoritesCount = 12,
    followersCount = 3,
    followingCount = 7,
)

private class ScriptedCatalog(
    val blog: Blog? = null,
    val blogTracks: List<Track> = emptyList(),
    val blogError: Throwable? = null,
    val user: User? = null,
    val userFavorites: List<Track> = emptyList(),
) : CatalogRepository {
    val blogCalls = mutableListOf<Int>()

    override suspend fun blog(blogId: Int): Blog {
        blogCalls += blogId
        blogError?.let { throw it }
        return blog ?: error("no blog scripted")
    }

    override suspend fun blogTracks(blogId: Int, page: Int, count: Int): List<Track> = blogTracks
    override suspend fun user(username: String): User = user ?: error("no user scripted")
    override suspend fun userFavorites(username: String, page: Int, count: Int): List<Track> = userFavorites

    override suspend fun latest(mode: LatestMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun popular(mode: PopularMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun track(trackId: String): Track = error("not used")
    override suspend fun blogs(page: Int, count: Int): List<Blog> = emptyList()
    override suspend fun userFriends(username: String, page: Int, count: Int): List<User> = emptyList()
}

private class RecordingPlayback : PlaybackRepository {
    override val queue: StateFlow<PlaybackQueue> = MutableStateFlow(PlaybackQueue())
    var lastPlayed: List<Track>? = null
        private set
    var lastStartIndex: Int = -1
        private set

    override suspend fun play(tracks: List<Track>, startIndex: Int) {
        lastPlayed = tracks
        lastStartIndex = startIndex
    }
    override suspend fun playFromTrack(track: Track) = Unit
    override suspend fun togglePlayPause() = Unit
    override suspend fun skipNext() = Unit
    override suspend fun skipPrevious() = Unit
    override suspend fun seekTo(positionMs: Long) = Unit
    override suspend fun toggleShuffle() = Unit
    override suspend fun cycleRepeatMode() = Unit
    override suspend fun updateFavorite(trackId: String, isLoved: Boolean) = Unit
}

private object NoOpPlayback : PlaybackRepository {
    override val queue: StateFlow<PlaybackQueue> = MutableStateFlow(PlaybackQueue())
    override suspend fun play(tracks: List<Track>, startIndex: Int) = Unit
    override suspend fun playFromTrack(track: Track) = Unit
    override suspend fun togglePlayPause() = Unit
    override suspend fun skipNext() = Unit
    override suspend fun skipPrevious() = Unit
    override suspend fun seekTo(positionMs: Long) = Unit
    override suspend fun toggleShuffle() = Unit
    override suspend fun cycleRepeatMode() = Unit
    override suspend fun updateFavorite(trackId: String, isLoved: Boolean) = Unit
}

private fun detailsFavoriteSync() = FavoriteSyncManager(DetailsNoOpMe, NoOpPlayback)

private object DetailsNoOpMe : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}
