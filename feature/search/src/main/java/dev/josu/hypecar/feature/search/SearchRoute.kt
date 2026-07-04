package dev.josu.hypecar.feature.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.josu.hypecar.core.data.toUiErrorKind
import dev.josu.hypecar.core.model.SearchQuery
import dev.josu.hypecar.core.model.SearchSort
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.UiErrorKind
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.model.repository.SearchRepository
import dev.josu.hypecar.core.model.runSuspendCatchingPreservingCancellation
import dev.josu.hypecar.core.ui.hypeTokens
import dev.josu.hypecar.core.ui.pressFeedback
import dev.josu.hypecar.feature.catalog.EditorialHeroHeader
import dev.josu.hypecar.feature.catalog.TrackListBody
import dev.josu.hypecar.feature.catalog.rememberIsAutomotiveUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val sort: SearchSort = SearchSort.NEWEST,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = false,
    val error: UiErrorKind? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val playbackRepository: PlaybackRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    fun updateQuery(value: String) {
        if (value == _state.value.query) return
        _state.update { it.copy(query = value, loading = value.isNotBlank()) }
        debouncedSearch()
    }

    fun updateSort(sort: SearchSort) {
        _state.update { it.copy(sort = sort) }
        if (_state.value.query.isNotBlank()) {
            search()
        }
    }

    fun search() = launchSearch(debounceMs = 0L)

    private fun debouncedSearch() = launchSearch(debounceMs = SearchDebounceMs)

    private fun launchSearch(debounceMs: Long) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val query = _state.value.query.trim()
            val sort = _state.value.sort
            if (query.isBlank()) {
                _state.update { it.copy(loading = false, tracks = emptyList(), error = null) }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            val result = runSuspendCatchingPreservingCancellation {
                searchRepository.searchTracks(SearchQuery(query, sort), count = 30)
            }
            _state.update { current ->
                if (current.query.trim() != query || current.sort != sort) {
                    current.copy(loading = false)
                } else {
                    result.fold(
                        onSuccess = { tracks -> current.copy(tracks = tracks, loading = false, error = null) },
                        onFailure = { current.copy(loading = false, error = it.toUiErrorKind()) },
                    )
                }
            }
        }
    }

    private companion object {
        const val SearchDebounceMs = 350L
    }

    fun play(index: Int) {
        viewModelScope.launch {
            playbackRepository.play(_state.value.tracks, index)
        }
    }
}

@Composable
fun SearchRoute(
    onBlogClick: (Int) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var localQuery by remember { mutableStateOf(state.query) }
    val heroImage = state.tracks.firstOrNull()?.bestThumbnail()
    val isAutomotive = rememberIsAutomotiveUi()

    TrackListBody(
        tracks = state.tracks,
        isLoading = state.loading,
        error = state.error,
        emptyMessage = if (isAutomotive && state.query.isBlank()) {
            ""
        } else if (state.query.isBlank()) {
            stringResource(R.string.search_empty_initial)
        } else {
            stringResource(R.string.search_empty_no_results)
        },
        header = {
            Column {
                EditorialHeroHeader(
                    title = stringResource(R.string.search_title),
                    subtitle = if (state.query.isBlank()) {
                        stringResource(R.string.search_subtitle_idle)
                    } else {
                        stringResource(R.string.search_subtitle_results, state.query)
                    },
                    imageUrl = heroImage,
                    chips = SearchSort.entries.map { searchSortLabel(it) },
                    selectedChipIndex = state.sort.ordinal,
                    onChipSelected = { index -> SearchSort.entries.getOrNull(index)?.let(viewModel::updateSort) },
                    onUtilityClick = null,
                    height = 246.dp,
                    titleSize = 42.sp,
                    titleLineHeight = 42.sp,
                    compactMode = isAutomotive,
                )
                SearchControlPanel(
                    query = localQuery,
                    onQueryChange = {
                        localQuery = it
                        viewModel.updateQuery(it)
                    },
                    onSearch = viewModel::search,
                    compactMode = isAutomotive,
                )
            }
        },
        onTrackClick = viewModel::play,
        onBlogClick = { onBlogClick(it.postedById) },
    )
}

@Composable
private fun SearchControlPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    compactMode: Boolean,
) {
    val containerShape = if (compactMode) RoundedCornerShape(14.dp) else RoundedCornerShape(22.dp)
    val outerPadding = if (compactMode) 6.dp else 12.dp
    val innerHorizontalPadding = if (compactMode) 10.dp else 16.dp
    val innerVerticalPadding = if (compactMode) 8.dp else 14.dp
    val rowSpacing = if (compactMode) 6.dp else 10.dp
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = outerPadding, vertical = outerPadding),
        color = Color(0xFF151211),
        contentColor = Color.White,
        shape = containerShape,
        border = BorderStroke(1.dp, Color(0xFF362823)),
    ) {
        Column(modifier = Modifier.padding(horizontal = innerHorizontalPadding, vertical = innerVerticalPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(rowSpacing),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.search_field_label)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(if (compactMode) 10.dp else 18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFF934A),
                        unfocusedBorderColor = Color(0xFF58413A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = Color(0xFFFFC7A2),
                        unfocusedLabelColor = Color(0xFFC9B7A8),
                        cursorColor = Color(0xFFFF934A),
                        focusedContainerColor = Color(0xFF1A1513),
                        unfocusedContainerColor = Color(0xFF1A1513),
                    ),
                )
                Button(
                    onClick = onSearch,
                    modifier = Modifier
                        .pressFeedback(pressedScale = 0.94f, label = "searchButtonPress")
                        .padding(top = if (compactMode) 2.dp else 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = hypeTokens.brand.primary,
                        contentColor = Color(0xFF19110E),
                    ),
                    shape = RoundedCornerShape(if (compactMode) 10.dp else 18.dp),
                    contentPadding = PaddingValues(
                        horizontal = if (compactMode) 10.dp else 18.dp,
                        vertical = if (compactMode) 7.dp else 14.dp,
                    ),
                ) {
                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_action_search))
                }
            }
        }
    }
}

@Composable
private fun searchSortLabel(sort: SearchSort): String = stringResource(
    when (sort) {
        SearchSort.NEWEST -> R.string.search_sort_newest
        SearchSort.MOST_FAVORITES -> R.string.search_sort_most_favorited
        SearchSort.MOST_REBLOGGED -> R.string.search_sort_most_reblogged
    },
)
