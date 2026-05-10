package dev.josu.hypecar.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.repository.AuthRepository
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import dev.josu.hypecar.feature.catalog.EditorialHeroHeader
import dev.josu.hypecar.feature.catalog.TrackListBody
import dev.josu.hypecar.feature.catalog.rememberIsAutomotiveUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab { FAVORITES, FEED, PLAYLISTS, HISTORY }

data class LibraryUiState(
    val session: AuthSession? = null,
    val selectedTab: LibraryTab = LibraryTab.FAVORITES,
    val selectedPlaylistId: Int? = null,
    val playlists: List<Playlist> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val nextPage: Int = 2,
    val hasMore: Boolean = true,
    val loadingMore: Boolean = false,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val meRepository: MeRepository,
    private val playbackRepository: PlaybackRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            authRepository.session.collect { session ->
                _state.update { it.copy(session = session) }
                refresh()
            }
        }
    }

    fun selectTab(index: Int) {
        val tab = LibraryTab.entries.getOrNull(index) ?: return
        _state.update { it.copy(selectedTab = tab, error = null) }
        refresh()
    }

    fun selectPlaylist(playlistId: Int) {
        _state.update { it.copy(selectedPlaylistId = playlistId) }
        refresh()
    }

    fun play(index: Int) {
        viewModelScope.launch {
            playbackRepository.play(_state.value.tracks, index)
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }

    fun pullToRefresh() {
        refresh(viaPull = true)
    }

    fun toggleFavorite(track: Track) {
        val newLoved = !track.isLoved
        _state.update { current ->
            current.copy(
                tracks = current.tracks.map {
                    if (it.id == track.id) {
                        it.copy(
                            isLoved = newLoved,
                            lovedCount = (it.lovedCount + if (newLoved) 1 else -1).coerceAtLeast(0),
                        )
                    } else {
                        it
                    }
                },
            )
        }
        viewModelScope.launch {
            val confirmed = meRepository.toggleFavorite(track.id)
            if (confirmed != null && confirmed != newLoved) {
                _state.update { current ->
                    current.copy(
                        tracks = current.tracks.map {
                            if (it.id == track.id) it.copy(isLoved = confirmed) else it
                        },
                    )
                }
            }
        }
    }

    private var loadMoreJob: Job? = null

    fun loadMore() {
        if (loadMoreJob?.isActive == true) return
        val current = _state.value
        if (!current.hasMore || current.loadingMore || current.loading) return
        loadMoreJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            val result = runSuspendCatchingPreservingCancellation {
                when (current.selectedTab) {
                    LibraryTab.FAVORITES -> meRepository.favorites(page = current.nextPage, count = 30)
                    LibraryTab.FEED -> meRepository.feed(page = current.nextPage, count = 30).map(FeedItem::track)
                    LibraryTab.PLAYLISTS -> current.selectedPlaylistId?.let {
                        meRepository.playlist(it, page = current.nextPage, count = 30)
                    } ?: emptyList()
                    LibraryTab.HISTORY -> meRepository.history(page = current.nextPage, count = 30)
                }
            }
            _state.update { latest ->
                if (latest.selectedTab != current.selectedTab) return@update latest
                result.fold(
                    onSuccess = { fresh ->
                        latest.copy(
                            tracks = latest.tracks + fresh,
                            nextPage = latest.nextPage + 1,
                            hasMore = fresh.size >= 30,
                            loadingMore = false,
                        )
                    },
                    // Keep hasMore=true so a transient blip can be retried by scrolling again.
                    onFailure = { latest.copy(loadingMore = false) },
                )
            }
        }
    }

    fun refresh() = refresh(viaPull = false)

    private fun refresh(viaPull: Boolean) {
        val request = _state.value
        refreshJob?.cancel()
        loadMoreJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = !viaPull && it.tracks.isEmpty(),
                    refreshing = viaPull,
                    loadingMore = false,
                    error = null,
                )
            }
            val result = runSuspendCatchingPreservingCancellation {
                when (request.selectedTab) {
                    LibraryTab.FAVORITES -> {
                        if (request.session == null) {
                            request.copy(loading = false, tracks = emptyList())
                        } else {
                            request.copy(loading = false, tracks = meRepository.favorites(count = 30))
                        }
                    }

                    LibraryTab.FEED -> {
                        if (request.session == null) {
                            request.copy(loading = false, tracks = emptyList())
                        } else {
                            request.copy(
                                loading = false,
                                tracks = meRepository.feed(count = 30).map(FeedItem::track),
                            )
                        }
                    }

                    LibraryTab.PLAYLISTS -> {
                        if (request.session == null) {
                            request.copy(loading = false, tracks = emptyList(), playlists = emptyList())
                        } else {
                            val playlists = meRepository.playlistNames()
                            val selected = request.selectedPlaylistId ?: playlists.firstOrNull()?.id
                            request.copy(
                                loading = false,
                                playlists = playlists,
                                selectedPlaylistId = selected,
                                tracks = if (selected != null) meRepository.playlist(selected, count = 30) else emptyList(),
                            )
                        }
                    }

                    LibraryTab.HISTORY -> request.copy(
                        loading = false,
                        tracks = meRepository.history(count = 30),
                    )
                }
            }
            _state.update { current ->
                if (
                    current.selectedTab != request.selectedTab ||
                    current.selectedPlaylistId != request.selectedPlaylistId ||
                    current.session != request.session
                ) {
                    current
                } else {
                    result.fold(
                        onSuccess = {
                            it.copy(
                                refreshing = false,
                                nextPage = 2,
                                hasMore = it.tracks.size >= 30,
                                loadingMore = false,
                            )
                        },
                        onFailure = {
                            request.copy(
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
fun LibraryRoute(
    onBlogClick: (Int) -> Unit,
    onUserClick: (String) -> Unit,
    onLoginClick: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isAutomotive = rememberIsAutomotiveUi()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        dev.josu.hypecar.core.model.ScrollToTopBus.events.collect { route ->
            if (route == "library") listState.animateScrollToItem(0)
        }
    }
    val tabTitles = listOf(
        stringResource(R.string.library_tab_favorites),
        stringResource(R.string.library_tab_feed),
        stringResource(R.string.library_tab_playlists),
        stringResource(R.string.library_tab_history),
    )
    val heroImage = state.tracks.firstOrNull()?.bestThumbnail()
    val subtitle = stringResource(
        when (state.selectedTab) {
            LibraryTab.FAVORITES -> R.string.library_subtitle_favorites
            LibraryTab.FEED -> R.string.library_subtitle_feed
            LibraryTab.PLAYLISTS -> R.string.library_subtitle_playlists
            LibraryTab.HISTORY -> R.string.library_subtitle_history
        },
    )
    val needsSession = state.selectedTab != LibraryTab.HISTORY

    if (needsSession && state.session == null) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item {
                EditorialHeroHeader(
                    title = stringResource(R.string.library_title),
                    subtitle = stringResource(R.string.library_signed_out_blurb),
                    imageUrl = heroImage,
                    chips = tabTitles,
                    selectedChipIndex = state.selectedTab.ordinal,
                    onChipSelected = viewModel::selectTab,
                    onUtilityClick = onLoginClick,
                    utilityLabel = stringResource(R.string.library_login_button),
                    // Shrink the hero in automotive: 246dp eats >40% of a 600dp landscape
                    // screen and pushes the sign-in CTA past the mini-player.
                    height = if (isAutomotive) 140.dp else 246.dp,
                    titleSize = if (isAutomotive) 28.sp else 42.sp,
                    titleLineHeight = if (isAutomotive) 30.sp else 42.sp,
                    compactMode = isAutomotive,
                )
            }
            item {
                SignedOutCard(
                    sectionName = tabTitles[state.selectedTab.ordinal].lowercase(),
                    onLoginClick = onLoginClick,
                    compactMode = isAutomotive,
                )
            }
        }
        return
    }

    TrackListBody(
        tracks = state.tracks,
        isLoading = state.loading,
        error = state.error,
        header = {
            Column {
                EditorialHeroHeader(
                    title = stringResource(R.string.library_title),
                    subtitle = subtitle,
                    imageUrl = heroImage,
                    chips = tabTitles,
                    selectedChipIndex = state.selectedTab.ordinal,
                    onChipSelected = viewModel::selectTab,
                    onUtilityClick = state.session?.let { { onUserClick(it.username) } },
                    utilityLabel = stringResource(R.string.library_profile_button),
                    height = if (isAutomotive) 140.dp else 246.dp,
                    titleSize = if (isAutomotive) 28.sp else 42.sp,
                    titleLineHeight = if (isAutomotive) 30.sp else 42.sp,
                    compactMode = isAutomotive,
                )
                state.session?.let { session ->
                    LibraryProfileStrip(
                        session = session,
                        selectedTab = state.selectedTab,
                        onProfileClick = { onUserClick(session.username) },
                        onLogoutClick = viewModel::logout,
                        compactMode = isAutomotive,
                    )
                }
                if (state.selectedTab == LibraryTab.PLAYLISTS && state.playlists.isNotEmpty()) {
                    PlaylistStrip(
                        playlists = state.playlists,
                        selectedPlaylistId = state.selectedPlaylistId,
                        onPlaylistSelected = viewModel::selectPlaylist,
                        compactMode = isAutomotive,
                    )
                }
            }
        },
        onTrackClick = viewModel::play,
        onBlogClick = { onBlogClick(it.postedById) },
        onToggleFavorite = viewModel::toggleFavorite,
        onLoadMore = viewModel::loadMore,
        hasMore = state.hasMore,
        onRefresh = viewModel::pullToRefresh,
        isRefreshing = state.refreshing,
        listState = listState,
    )
}

@Composable
private fun LibraryProfileStrip(
    session: AuthSession,
    selectedTab: LibraryTab,
    onProfileClick: () -> Unit,
    onLogoutClick: () -> Unit,
    compactMode: Boolean,
) {
    val outerPadding = if (compactMode) 6.dp else 12.dp
    val profileShape = if (compactMode) RoundedCornerShape(14.dp) else RoundedCornerShape(22.dp)
    val avatarSize = if (compactMode) 32.dp else 40.dp
    val rowSpacing = if (compactMode) 8.dp else 12.dp
    val buttonSize = if (compactMode) 30.dp else 36.dp
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = outerPadding, vertical = outerPadding),
        color = Color(0xFF151211),
        contentColor = Color.White,
        shape = profileShape,
        border = BorderStroke(1.dp, Color(0xFF362823)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compactMode) 14.dp else 16.dp,
                    vertical = if (compactMode) 8.dp else 12.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            Surface(
                modifier = Modifier.size(avatarSize),
                shape = CircleShape,
                color = Color(0xFF2B211E),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFFFFB07B))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.username,
                    style = if (compactMode) {
                        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                    } else {
                        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    },
                )
                Text(
                    text = when (selectedTab) {
                        LibraryTab.FAVORITES -> "@${session.username} · saved rotation"
                        LibraryTab.FEED -> "@${session.username} · following stream"
                        LibraryTab.PLAYLISTS -> "@${session.username} · playlist stacks"
                        LibraryTab.HISTORY -> "@${session.username} · recent trail"
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFE2D4C6)),
                    maxLines = if (compactMode) 1 else 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (compactMode) 2.dp else 4.dp),
                )
            }
            OutlinedButton(
                onClick = onProfileClick,
                border = BorderStroke(1.dp, Color(0xFF725446)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB07B)),
            ) {
                Text(stringResource(R.string.library_profile_button), style = if (compactMode) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium)
            }
            Surface(
                onClick = onLogoutClick,
                shape = CircleShape,
                color = Color(0xFF241B18),
            ) {
                Box(
                    modifier = Modifier
                        .size(buttonSize)
                        .background(Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = stringResource(R.string.library_logout_button), tint = Color(0xFFE8D9CD))
                }
            }
        }
    }
}

@Composable
private fun PlaylistStrip(
    playlists: List<Playlist>,
    selectedPlaylistId: Int?,
    onPlaylistSelected: (Int) -> Unit,
    compactMode: Boolean,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compactMode) 6.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compactMode) 6.dp else 10.dp),
        contentPadding = PaddingValues(bottom = if (compactMode) 4.dp else 10.dp),
    ) {
        items(playlists, key = { it.id }) { playlist ->
            val selected = playlist.id == selectedPlaylistId
            Surface(
                onClick = { onPlaylistSelected(playlist.id) },
                shape = RoundedCornerShape(if (compactMode) 9.dp else 16.dp),
                color = if (selected) Color(0xFF201816) else Color(0xFFF9F4EE),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (selected) Color(0xFFFF934A) else Color(0xFFE1C9B2),
                ),
            ) {
                Text(
                    text = playlist.name,
                    modifier = Modifier.padding(
                        horizontal = if (compactMode) 8.dp else 14.dp,
                        vertical = if (compactMode) 5.dp else 10.dp,
                    ),
                    color = if (selected) Color(0xFFFFB07B) else Color(0xFF5C463D),
                    fontWeight = FontWeight.SemiBold,
                    style = if (compactMode) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SignedOutCard(
    sectionName: String,
    onLoginClick: () -> Unit,
    compactMode: Boolean,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compactMode) 6.dp else 16.dp, vertical = if (compactMode) 6.dp else 16.dp),
        shape = RoundedCornerShape(if (compactMode) 14.dp else 30.dp),
        color = Color(0xFF151211),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (compactMode) 12.dp else 20.dp,
                vertical = if (compactMode) 10.dp else 22.dp,
            ),
        ) {
            Text(
                text = stringResource(R.string.library_signed_out_card_title),
                style = if (compactMode) {
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                } else {
                    MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                },
            )
            Text(
                text = stringResource(R.string.library_signed_out_card_body, sectionName),
                style = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFE1D3C5)),
                maxLines = if (compactMode) 2 else 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (compactMode) 4.dp else 10.dp),
            )
            Button(
                onClick = onLoginClick,
                modifier = Modifier.padding(top = if (compactMode) 8.dp else 18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF8A3D),
                    contentColor = Color(0xFF19110E),
                ),
            ) {
                Text(stringResource(R.string.library_signed_out_card_button))
            }
        }
    }
}
