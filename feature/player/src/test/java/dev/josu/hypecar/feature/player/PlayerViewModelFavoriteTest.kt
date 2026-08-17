package dev.josu.hypecar.feature.player

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.data.repository.AccountDataWriteGate
import dev.josu.hypecar.core.data.repository.FavoriteSyncManager
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.FeedMode
import dev.josu.hypecar.core.model.PlaybackItem
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.PlaybackRepeatMode
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelFavoriteTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `toggleFavorite updates current player heart when api succeeds`() = runTest {
        val playbackRepository = FakePlaybackRepository(sampleTrack(isLoved = false))
        val meRepository = FakeMeRepository(toggleResponse = true)
        val viewModel = newPlayerViewModel(playbackRepository, meRepository)

        viewModel.toggleFavorite()
        advanceUntilIdle()

        assertThat(meRepository.toggledTrackIds).containsExactly("39v49")
        assertThat(playbackRepository.favoriteUpdates).containsExactly("39v49" to true)
        assertThat(playbackRepository.queue.value.current?.track?.isLoved).isTrue()
    }

    @Test
    fun `toggleFavorite reverts player heart when api fails`() = runTest {
        val playbackRepository = FakePlaybackRepository(sampleTrack(isLoved = false))
        val meRepository = FakeMeRepository(toggleResponse = null)
        val viewModel = newPlayerViewModel(playbackRepository, meRepository)

        viewModel.toggleFavorite()
        advanceUntilIdle()

        assertThat(meRepository.toggledTrackIds).containsExactly("39v49")
        assertThat(playbackRepository.favoriteUpdates).containsExactly(
            "39v49" to true,
            "39v49" to false,
        ).inOrder()
        assertThat(playbackRepository.queue.value.current?.track?.isLoved).isFalse()
    }

    @Test
    fun `toggleFavorite emits a visible error when api fails`() = runTest {
        val playbackRepository = FakePlaybackRepository(sampleTrack(isLoved = false))
        val meRepository = FakeMeRepository(toggleResponse = null)
        val viewModel = newPlayerViewModel(playbackRepository, meRepository)
        val errors = async {
            withTimeout(2_000) {
                viewModel.favoriteErrors.take(1).toList()
            }
        }

        viewModel.toggleFavorite()
        advanceUntilIdle()

        assertThat(errors.await()).hasSize(1)
    }

    @Test
    fun `toggleFavorite does not ignore rapid taps while api sync is pending`() = runTest {
        val playbackRepository = FakePlaybackRepository(sampleTrack(isLoved = false))
        val meRepository = BlockingFakeMeRepository()
        val viewModel = newPlayerViewModel(playbackRepository, meRepository)

        viewModel.toggleFavorite()
        advanceUntilIdle()
        assertThat(playbackRepository.queue.value.current?.track?.isLoved).isTrue()

        viewModel.toggleFavorite()
        advanceUntilIdle()
        assertThat(playbackRepository.queue.value.current?.track?.isLoved).isFalse()

        meRepository.completeNextToggle(serverLoved = true)
        advanceUntilIdle()
        meRepository.completeNextToggle(serverLoved = false)
        advanceUntilIdle()

        assertThat(meRepository.toggledTrackIds).containsExactly("39v49", "39v49").inOrder()
        assertThat(playbackRepository.favoriteUpdates).containsExactly(
            "39v49" to true,
            "39v49" to false,
        ).inOrder()
        assertThat(playbackRepository.queue.value.current?.track?.isLoved).isFalse()
    }

    @Test
    fun `toggleFavorite uses pending desired state for immediate second tap`() = runTest {
        val playbackRepository = FakePlaybackRepository(sampleTrack(isLoved = false))
        val meRepository = FakeMeRepository(toggleResponse = true)
        val viewModel = newPlayerViewModel(playbackRepository, meRepository)

        viewModel.toggleFavorite()
        viewModel.toggleFavorite()
        advanceUntilIdle()

        assertThat(meRepository.toggledTrackIds).isEmpty()
        assertThat(playbackRepository.favoriteUpdates).containsExactly(
            "39v49" to true,
            "39v49" to false,
        ).inOrder()
        assertThat(playbackRepository.queue.value.current?.track?.isLoved).isFalse()
    }

    @Test
    fun `toggleFavorite does not blindly repeat a non-idempotent toggle without confirmation`() = runTest {
        val playbackRepository = FakePlaybackRepository(sampleTrack(isLoved = false))
        val meRepository = SequenceFakeMeRepository(serverLovedResponses = listOf(false, true))
        val viewModel = newPlayerViewModel(playbackRepository, meRepository)

        viewModel.toggleFavorite()
        advanceUntilIdle()

        assertThat(meRepository.toggledTrackIds).containsExactly("39v49")
        assertThat(playbackRepository.favoriteUpdates).containsExactly(
            "39v49" to true,
            "39v49" to false,
        ).inOrder()
        assertThat(playbackRepository.queue.value.current?.track?.isLoved).isFalse()
    }

    @Test
    fun `late favorite failure from old account cannot alter new account queue`() = runTest {
        val playbackRepository = FakePlaybackRepository(sampleTrack(isLoved = false))
        val meRepository = BlockingFakeMeRepository()
        val gate = AccountDataWriteGate()
        val favoriteSyncManager = FavoriteSyncManager(
            meRepository,
            playbackRepository,
            CoroutineScope(Dispatchers.Main.immediate),
            gate,
        )
        val viewModel = PlayerViewModel(playbackRepository, favoriteSyncManager)

        viewModel.toggleFavorite()
        advanceUntilIdle()
        assertThat(playbackRepository.queue.value.current?.track?.isLoved).isTrue()

        gate.deactivate()
        gate.activate()
        playbackRepository.replaceTrackForAccount(sampleTrack(isLoved = false))
        meRepository.completeNextToggle(serverLoved = null)
        advanceUntilIdle()

        assertThat(playbackRepository.favoriteUpdates).containsExactly("39v49" to true)
        assertThat(playbackRepository.queue.value.current?.track?.isLoved).isFalse()
    }

    @Test
    fun `seekToFraction translates selected progress into queue duration`() = runTest {
        val playbackRepository = FakePlaybackRepository(
            track = sampleTrack(isLoved = false),
            durationMs = 200_000,
        )
        val viewModel = newPlayerViewModel(playbackRepository, FakeMeRepository(toggleResponse = true))

        viewModel.seekToFraction(0.75f)
        advanceUntilIdle()

        assertThat(playbackRepository.seekRequests).containsExactly(150_000L)
    }

    @Test
    fun `seekToFraction clamps selected progress before seeking`() = runTest {
        val playbackRepository = FakePlaybackRepository(
            track = sampleTrack(isLoved = false),
            durationMs = 200_000,
        )
        val viewModel = newPlayerViewModel(playbackRepository, FakeMeRepository(toggleResponse = true))

        viewModel.seekToFraction(1.4f)
        advanceUntilIdle()

        assertThat(playbackRepository.seekRequests).containsExactly(200_000L)
    }

    @Test
    fun `toggleShuffle delegates to playback repository`() = runTest {
        val playbackRepository = FakePlaybackRepository(sampleTrack(isLoved = false))
        val viewModel = newPlayerViewModel(playbackRepository, FakeMeRepository(toggleResponse = true))

        viewModel.toggleShuffle()
        advanceUntilIdle()

        assertThat(playbackRepository.shuffleToggleCount).isEqualTo(1)
    }

    @Test
    fun `cycleRepeatMode delegates to playback repository`() = runTest {
        val playbackRepository = FakePlaybackRepository(sampleTrack(isLoved = false))
        val viewModel = newPlayerViewModel(playbackRepository, FakeMeRepository(toggleResponse = true))

        viewModel.cycleRepeatMode()
        advanceUntilIdle()

        assertThat(playbackRepository.repeatToggleCount).isEqualTo(1)
    }
}

private class FakePlaybackRepository(
    track: Track,
    durationMs: Long = 0L,
) : PlaybackRepository {
    private val _queue = MutableStateFlow(
        PlaybackQueue(
            items = listOf(PlaybackItem(track)),
            currentIndex = 0,
            isPlaying = true,
            durationMs = durationMs,
        ),
    )
    override val queue: StateFlow<PlaybackQueue> = _queue
    val favoriteUpdates = mutableListOf<Pair<String, Boolean>>()
    val seekRequests = mutableListOf<Long>()
    var shuffleToggleCount = 0
        private set
    var repeatToggleCount = 0
        private set

    fun replaceTrackForAccount(track: Track) {
        _queue.value = PlaybackQueue(
            items = listOf(PlaybackItem(track)),
            currentIndex = 0,
            isPlaying = true,
            durationMs = _queue.value.durationMs,
        )
    }

    override suspend fun play(tracks: List<Track>, startIndex: Int) = Unit
    override suspend fun playFromTrack(track: Track) = Unit
    override suspend fun togglePlayPause() = Unit
    override suspend fun skipNext() = Unit
    override suspend fun skipPrevious() = Unit
    override suspend fun seekTo(positionMs: Long) {
        seekRequests += positionMs
    }
    override suspend fun toggleShuffle() {
        shuffleToggleCount += 1
    }
    override suspend fun cycleRepeatMode() {
        repeatToggleCount += 1
        _queue.value = _queue.value.copy(repeatMode = PlaybackRepeatMode.ALL)
    }

    override suspend fun updateFavorite(trackId: String, isLoved: Boolean) {
        favoriteUpdates += trackId to isLoved
        _queue.value = _queue.value.copy(
            items = _queue.value.items.map { item ->
                if (item.track.id == trackId) {
                    item.copy(track = item.track.copy(isLoved = isLoved))
                } else {
                    item
                }
            },
        )
    }
}

private class FakeMeRepository(
    private val toggleResponse: Boolean?,
) : MeRepository {
    val toggledTrackIds = mutableListOf<String>()

    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()

    override suspend fun toggleFavorite(trackId: String): Boolean? {
        toggledTrackIds += trackId
        return toggleResponse
    }

    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private class BlockingFakeMeRepository : MeRepository {
    val toggledTrackIds = mutableListOf<String>()
    private val pendingToggles = ArrayDeque<CompletableDeferred<Boolean?>>()

    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()

    override suspend fun toggleFavorite(trackId: String): Boolean? {
        toggledTrackIds += trackId
        return CompletableDeferred<Boolean?>().also(pendingToggles::addLast).await()
    }

    fun completeNextToggle(serverLoved: Boolean?) {
        pendingToggles.removeFirst().complete(serverLoved)
    }

    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private class SequenceFakeMeRepository(
    serverLovedResponses: List<Boolean?>,
) : MeRepository {
    val toggledTrackIds = mutableListOf<String>()
    private val responses = ArrayDeque(serverLovedResponses)

    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()

    override suspend fun toggleFavorite(trackId: String): Boolean? {
        toggledTrackIds += trackId
        return responses.removeFirst()
    }

    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private fun sampleTrack(isLoved: Boolean) = Track(
    id = "39v49",
    artist = "L.A. Sagne",
    title = "Music In The Neighbourhood",
    lovedCount = if (isLoved) 28 else 27,
    postedBy = "Destroy//Exist",
    postedById = 22246,
    postedCount = 3,
    postDescription = "After a run of singles.",
    datePostedEpochSeconds = 1774723952,
    postUrl = "https://www.destroyexist.com/2026/03/la-sagne-music-in-neighbourhood.html",
    itunesUrl = "https://hypem.com/go/itunes_search/L.A.%20Sagne",
    isLoved = isLoved,
)

private fun newPlayerViewModel(playback: PlaybackRepository, me: MeRepository) = PlayerViewModel(
    playbackRepository = playback,
    favoriteSyncManager = dev.josu.hypecar.core.data.repository.FavoriteSyncManager(
        me,
        playback,
        CoroutineScope(Dispatchers.Main.immediate),
    ),
)
