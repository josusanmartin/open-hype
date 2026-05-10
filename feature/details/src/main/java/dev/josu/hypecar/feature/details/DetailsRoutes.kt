package dev.josu.hypecar.feature.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.josu.hypecar.core.model.Blog
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.User
import dev.josu.hypecar.core.model.repository.CatalogRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import dev.josu.hypecar.feature.catalog.EditorialHeroHeader
import dev.josu.hypecar.feature.catalog.TrackListBody
import dev.josu.hypecar.feature.catalog.rememberIsAutomotiveUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BlogDetailUiState(
    val blog: Blog? = null,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

data class UserDetailUiState(
    val user: User? = null,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

data class TagDetailUiState(
    val tag: String,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class BlogDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalogRepository: CatalogRepository,
    private val playbackRepository: PlaybackRepository,
) : ViewModel() {
    private val blogId: Int = checkNotNull(savedStateHandle["blogId"])
    private val _state = MutableStateFlow(BlogDetailUiState())
    val state: StateFlow<BlogDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = runSuspendCatchingPreservingCancellation {
                BlogDetailUiState(
                    blog = catalogRepository.blog(blogId),
                    tracks = catalogRepository.blogTracks(blogId, count = 30),
                    loading = false,
                )
            }.getOrElse { BlogDetailUiState(loading = false, error = it.message) }
        }
    }

    fun play(index: Int) {
        viewModelScope.launch { playbackRepository.play(_state.value.tracks, index) }
    }
}

@HiltViewModel
class UserDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalogRepository: CatalogRepository,
    private val playbackRepository: PlaybackRepository,
) : ViewModel() {
    private val username: String = checkNotNull(savedStateHandle["username"])
    private val _state = MutableStateFlow(UserDetailUiState())
    val state: StateFlow<UserDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = runSuspendCatchingPreservingCancellation {
                UserDetailUiState(
                    user = catalogRepository.user(username),
                    tracks = catalogRepository.userFavorites(username, count = 30),
                    loading = false,
                )
            }.getOrElse { UserDetailUiState(loading = false, error = it.message) }
        }
    }

    fun play(index: Int) {
        viewModelScope.launch { playbackRepository.play(_state.value.tracks, index) }
    }
}

@HiltViewModel
class TagDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalogRepository: CatalogRepository,
    private val playbackRepository: PlaybackRepository,
) : ViewModel() {
    private val tag: String = checkNotNull(savedStateHandle["tag"])
    private val _state = MutableStateFlow(TagDetailUiState(tag = tag))
    val state: StateFlow<TagDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = runSuspendCatchingPreservingCancellation {
                TagDetailUiState(
                    tag = tag,
                    tracks = catalogRepository.tagTracks(tag, count = 30),
                    loading = false,
                )
            }.getOrElse { TagDetailUiState(tag = tag, loading = false, error = it.message) }
        }
    }

    fun play(index: Int) {
        viewModelScope.launch { playbackRepository.play(_state.value.tracks, index) }
    }
}

@Composable
fun BlogDetailRoute(
    onBlogClick: (Int) -> Unit,
    viewModel: BlogDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val defaultBlogTitle = stringResource(R.string.details_blog_default_title)
    val followerCount = state.blog?.followerCount ?: 0
    val trackCount = state.blog?.trackCount ?: 0
    val followersFmt = pluralStringResource(R.plurals.details_blog_followers, followerCount, followerCount)
    val tracksFmt = pluralStringResource(R.plurals.details_blog_tracks, trackCount, trackCount)
    EditorialDetailFeed(
        title = state.blog?.name ?: defaultBlogTitle,
        subtitle = state.blog?.url.orEmpty(),
        imageUrl = state.blog?.imageUrl ?: state.tracks.firstOrNull()?.bestThumbnail(),
        stats = buildList {
            state.blog?.let {
                add(followersFmt)
                add(tracksFmt)
            }
        },
        tracks = state.tracks,
        isLoading = state.loading,
        error = state.error,
        onTrackClick = viewModel::play,
        onBlogClick = { onBlogClick(it.postedById) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserDetailRoute(
    onBlogClick: (Int) -> Unit,
    viewModel: UserDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile = state.user?.let(UserProfileHeaderUiModel::from)
    val isAutomotive = rememberIsAutomotiveUi()
    TrackListBody(
        tracks = state.tracks,
        isLoading = state.loading,
        error = state.error,
        header = {
            Column {
                EditorialHeroHeader(
                    title = profile?.title ?: stringResource(R.string.details_user_default_title),
                    subtitle = profile?.handle ?: stringResource(R.string.details_user_default_handle),
                    imageUrl = state.tracks.firstOrNull()?.bestThumbnail(),
                    chips = emptyList(),
                    selectedChipIndex = 0,
                    onChipSelected = {},
                    onUtilityClick = null,
                    height = 238.dp,
                    titleSize = 40.sp,
                    titleLineHeight = 40.sp,
                    compactMode = isAutomotive,
                )
                if (profile != null) {
                    Surface(
                        modifier = Modifier.padding(
                            horizontal = if (isAutomotive) 8.dp else 12.dp,
                            vertical = if (isAutomotive) 6.dp else 12.dp,
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(if (isAutomotive) 14.dp else 20.dp),
                        color = Color(0xFF151211),
                        contentColor = Color.White,
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = if (isAutomotive) 12.dp else 16.dp,
                                vertical = if (isAutomotive) 10.dp else 12.dp,
                            ),
                        ) {
                            Text(
                                text = profile.summaryLine,
                                style = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFE7D7C9)),
                            )
                            FlowRow(
                                modifier = Modifier.padding(top = 12.dp),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                            ) {
                                profile.stats.forEach { stat ->
                                    Surface(
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(if (isAutomotive) 10.dp else 16.dp),
                                        color = Color(0xFF2B211E),
                                    ) {
                                        Text(
                                            text = stat,
                                            modifier = Modifier.padding(
                                                horizontal = if (isAutomotive) 8.dp else 12.dp,
                                                vertical = if (isAutomotive) 5.dp else 8.dp,
                                            ),
                                            style = MaterialTheme.typography.labelLarge.copy(color = Color(0xFFFFC4A2)),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        onTrackClick = viewModel::play,
        onBlogClick = { onBlogClick(it.postedById) },
    )
}

@Composable
fun TagDetailRoute(
    onBlogClick: (Int) -> Unit,
    viewModel: TagDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EditorialDetailFeed(
        title = "#${state.tag}",
        subtitle = stringResource(R.string.details_tag_subtitle),
        imageUrl = state.tracks.firstOrNull()?.bestThumbnail(),
        stats = listOf(stringResource(R.string.details_tag_stat)),
        tracks = state.tracks,
        isLoading = state.loading,
        error = state.error,
        onTrackClick = viewModel::play,
        onBlogClick = { onBlogClick(it.postedById) },
    )
}

@Composable
private fun EditorialDetailFeed(
    title: String,
    subtitle: String,
    imageUrl: String?,
    stats: List<String>,
    tracks: List<Track>,
    isLoading: Boolean,
    error: String?,
    onTrackClick: (Int) -> Unit,
    onBlogClick: (Track) -> Unit,
) {
    val isAutomotive = rememberIsAutomotiveUi()
    TrackListBody(
        tracks = tracks,
        isLoading = isLoading,
        error = error,
        header = {
            Column {
                EditorialHeroHeader(
                    title = title,
                    subtitle = subtitle,
                    imageUrl = imageUrl,
                    chips = emptyList(),
                    selectedChipIndex = 0,
                    onChipSelected = {},
                    onUtilityClick = null,
                    height = 238.dp,
                    titleSize = 40.sp,
                    titleLineHeight = 40.sp,
                    compactMode = isAutomotive,
                )
                if (stats.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = if (isAutomotive) 8.dp else 12.dp,
                                vertical = if (isAutomotive) 6.dp else 12.dp,
                            ),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(if (isAutomotive) 6.dp else 10.dp),
                    ) {
                        items(stats) { stat ->
                            Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(if (isAutomotive) 10.dp else 16.dp),
                                color = Color(0xFF151211),
                                contentColor = Color(0xFFFFC4A2),
                            ) {
                                Text(
                                    text = stat,
                                    modifier = Modifier.padding(
                                        horizontal = if (isAutomotive) 9.dp else 14.dp,
                                        vertical = if (isAutomotive) 6.dp else 10.dp,
                                    ),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        },
        onTrackClick = onTrackClick,
        onBlogClick = onBlogClick,
    )
}
