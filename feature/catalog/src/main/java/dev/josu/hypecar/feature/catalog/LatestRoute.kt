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
import dev.josu.hypecar.core.data.toUiErrorKind
import dev.josu.hypecar.core.model.LatestMode
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.UiErrorKind
import dev.josu.hypecar.core.model.mergePageByTrackId
import dev.josu.hypecar.core.model.repository.CatalogRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import dev.josu.hypecar.core.model.withoutPersonalFavoriteState
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
    val error: UiErrorKind? = null,
    val selectedIndex: Int = 0,
    val nextPage: Int = 2,
    val hasMore: Boolean = true,
    val loadingMore: Boolean = false,
    val loadMoreFailed: Boolean = false,
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
    private var observedAccountGeneration = favoriteSyncManager.accountBoundary.value.generation

    init {
        refresh()
        viewModelScope.launch {
            favoriteSyncManager.accountBoundary.collect { boundary ->
                if (boundary.generation == observedAccountGeneration) return@collect
                observedAccountGeneration = boundary.generation
                refreshJob?.cancel()
                loadMoreJob?.cancel()
                _state.update { current ->
                    current.copy(
                        tracks = current.tracks.withoutPersonalFavoriteState(),
                        loading = false,
                        refreshing = false,
                        error = null,
                        nextPage = 2,
                        loadingMore = false,
                        loadMoreFailed = false,
                    )
                }
                // Public catalog content remains usable while signed out, but
                // must be fetched again whenever its personalization context changes.
                refresh(viaPull = true)
            }
        }
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
        _state.update {
            it.copy(
                selectedIndex = index,
                tracks = emptyList(),
                loading = true,
                refreshing = false,
                error = null,
                nextPage = 2,
                hasMore = true,
                loadingMore = false,
                loadMoreFailed = false,
            )
        }
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
        val accountGeneration = favoriteSyncManager.accountBoundary.value.generation
        loadMoreJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            val favoriteRead = favoriteSyncManager.captureFavoriteRead()
            val result = runSuspendCatchingPreservingCancellation {
                favoriteSyncManager.applyToFetched(
                    catalogRepository.latest(mode = mode, page = current.nextPage, count = 30),
                    favoriteRead,
                )
            }
            _state.update { latest ->
                if (favoriteSyncManager.accountBoundary.value.generation != accountGeneration) return@update latest
                if (latest.selectedIndex != current.selectedIndex) return@update latest
                result.fold(
                    onSuccess = { fresh ->
                        latest.copy(
                            tracks = latest.tracks.mergePageByTrackId(fresh),
                            nextPage = latest.nextPage + 1,
                            hasMore = fresh.size >= 30,
                            loadingMore = false,
                            loadMoreFailed = false,
                        )
                    },
                    onFailure = {
                        // Keep hasMore=true so a transient blip can be retried by scrolling again.
                        latest.copy(loadingMore = false, loadMoreFailed = true)
                    },
                )
            }
        }
    }

    private fun refresh(viaPull: Boolean = false) {
        val selectedIndex = _state.value.selectedIndex
        val mode = modes[selectedIndex]
        val accountGeneration = favoriteSyncManager.accountBoundary.value.generation
        refreshJob?.cancel()
        loadMoreJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = !viaPull && it.tracks.isEmpty(),
                    // Switching chips over existing content shows the refresh
                    // indicator too — a silent 2s freeze reads as a dead tap.
                    refreshing = viaPull || it.tracks.isNotEmpty(),
                    loadingMore = false,
                    loadMoreFailed = false,
                    error = null,
                )
            }
            val favoriteRead = favoriteSyncManager.captureFavoriteRead()
            val result = runSuspendCatchingPreservingCancellation {
                favoriteSyncManager.applyToFetched(
                    catalogRepository.latest(mode = mode, page = 1, count = 30, forceRefresh = viaPull),
                    favoriteRead,
                )
            }
            _state.update { current ->
                if (favoriteSyncManager.accountBoundary.value.generation != accountGeneration) return@update current
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
                                error = it.toUiErrorKind(),
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
        chips = LatestMode.entries.map { latestModeLabel(it) },
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
        loadMoreFailed = state.loadMoreFailed,
        onRefresh = viewModel::pullToRefresh,
        isRefreshing = state.refreshing,
        listState = listState,
    )
}

@Composable
private fun latestModeLabel(mode: LatestMode): String = stringResource(
    when (mode) {
        LatestMode.ALL -> R.string.catalog_mode_all
        LatestMode.FRESHEST -> R.string.catalog_mode_freshest
        LatestMode.NO_REMIXES -> R.string.catalog_mode_no_remixes
        LatestMode.ONLY_REMIXES -> R.string.catalog_mode_only_remixes
    },
)
