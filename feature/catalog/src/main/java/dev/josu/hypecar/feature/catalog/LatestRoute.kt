package dev.josu.hypecar.feature.catalog

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.josu.hypecar.core.data.repository.FavoriteSyncManager
import dev.josu.hypecar.core.model.LatestMode
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.CatalogRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CatalogScreenState(
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val selectedIndex: Int = 0,
    val nextPage: Int = 2,
    val hasMore: Boolean = true,
    val loadingMore: Boolean = false,
)

@HiltViewModel
class LatestViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val playbackRepository: PlaybackRepository,
    private val favoriteSyncManager: FavoriteSyncManager,
) : ViewModel() {
    private val modes = LatestMode.entries
    private val _state = MutableStateFlow(CatalogScreenState())
    val state: StateFlow<CatalogScreenState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var loadMoreJob: Job? = null

    init {
        refresh()
        // Apply every favorite edit emitted anywhere in the app to this
        // screen's local track list. A heart tap in Library / Player / Feed
        // now flips the icon here too without each VM duplicating the logic.
        viewModelScope.launch {
            favoriteSyncManager.edits.collect { edit ->
                _state.update { current ->
                    current.copy(tracks = favoriteSyncManager.applyTo(current.tracks, edit))
                }
            }
        }
    }

    fun selectMode(index: Int) {
        if (index !in modes.indices || index == _state.value.selectedIndex) return
        _state.update { it.copy(selectedIndex = index) }
        refresh()
    }

    fun play(index: Int) {
        viewModelScope.launch {
            playbackRepository.play(_state.value.tracks, index)
        }
    }

    fun toggleFavorite(track: Track) {
        // FavoriteSyncManager owns the optimistic + revert dance and emits
        // FavoriteEdits that this VM's init block applies to its track list.
        favoriteSyncManager.toggle(track)
    }

    fun pullToRefresh() {
        refresh(viaPull = true)
    }

    fun loadMore() {
        if (loadMoreJob?.isActive == true) return
        val current = _state.value
        if (!current.hasMore || current.loadingMore || current.loading) return
        val mode = modes[current.selectedIndex]
        loadMoreJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            val result = runSuspendCatchingPreservingCancellation {
                catalogRepository.latest(mode = mode, page = current.nextPage, count = 30)
            }
            _state.update { latest ->
                if (latest.selectedIndex != current.selectedIndex) return@update latest
                result.fold(
                    onSuccess = { fresh ->
                        latest.copy(
                            tracks = latest.tracks + fresh,
                            nextPage = latest.nextPage + 1,
                            hasMore = fresh.size >= 30,
                            loadingMore = false,
                        )
                    },
                    onFailure = {
                        // Keep hasMore=true so a transient blip can be retried by scrolling again.
                        latest.copy(loadingMore = false)
                    },
                )
            }
        }
    }

    private fun refresh(viaPull: Boolean = false) {
        val selectedIndex = _state.value.selectedIndex
        val mode = modes[selectedIndex]
        refreshJob?.cancel()
        loadMoreJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = !viaPull && it.tracks.isEmpty(),
                    refreshing = viaPull,
                    // Clear loadingMore in case we cancelled a load-more job above.
                    loadingMore = false,
                    error = null,
                )
            }
            val result = runSuspendCatchingPreservingCancellation {
                catalogRepository.latest(mode = mode, page = 1, count = 30)
            }
            _state.update { current ->
                if (current.selectedIndex != selectedIndex) {
                    current.copy(loading = false, refreshing = false)
                } else {
                    result.fold(
                        onSuccess = { tracks ->
                            current.copy(
                                tracks = tracks,
                                loading = false,
                                refreshing = false,
                                error = null,
                                nextPage = 2,
                                hasMore = tracks.size >= 30,
                            )
                        },
                        onFailure = {
                            current.copy(
                                loading = false,
                                refreshing = false,
                                error = it.message,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun LatestRoute(
    onBlogClick: (Int) -> Unit,
    viewModel: LatestViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        dev.josu.hypecar.core.model.ScrollToTopBus.events.collect { route ->
            if (route == "latest") listState.animateScrollToItem(0)
        }
    }
    TrackListContent(
        title = stringResource(R.string.catalog_latest_title),
        subtitle = stringResource(R.string.catalog_latest_subtitle),
        tracks = state.tracks,
        isLoading = state.loading,
        error = state.error,
        chips = LatestMode.entries.map { it.displayLabel },
        selectedChipIndex = state.selectedIndex,
        onChipSelected = viewModel::selectMode,
        onTrackClick = viewModel::play,
        onBlogClick = { onBlogClick(it.postedById) },
        onToggleFavorite = viewModel::toggleFavorite,
        // Wires the Retry button rendered by TrackListBody on error. Previously
        // this callback was omitted, so the button rendered but did nothing.
        onRetry = viewModel::pullToRefresh,
        onLoadMore = viewModel::loadMore,
        hasMore = state.hasMore,
        onRefresh = viewModel::pullToRefresh,
        isRefreshing = state.refreshing,
        listState = listState,
    )
}
