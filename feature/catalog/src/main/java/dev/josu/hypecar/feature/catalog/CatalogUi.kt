package dev.josu.hypecar.feature.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.UiErrorKind
import dev.josu.hypecar.core.ui.errorLabel
import dev.josu.hypecar.core.ui.hypeTokens
import dev.josu.hypecar.core.ui.isAutomotiveUi
import dev.josu.hypecar.core.ui.pressFeedback
import androidx.compose.ui.semantics.error as errorSemantics

internal data class CatalogLayoutMetrics(
    val contentBottomPadding: Dp,
    val featuredCoverSize: Dp,
    val standardCoverSize: Dp,
    val cardCornerRadius: Dp,
    val rowHorizontalPadding: Dp,
    val rowVerticalPadding: Dp,
    val cardOuterHorizontalPadding: Dp,
    val cardOuterVerticalPadding: Dp,
    val rowSpacing: Dp,
    val sourceChipCornerRadius: Dp,
    val playCornerRadius: Dp,
    val sourceChipHorizontalPadding: Dp,
    val sourceChipVerticalPadding: Dp,
    val playPadding: Dp,
    val heroBaseHeight: Dp,
    val heroHorizontalPadding: Dp,
    val heroVerticalPadding: Dp,
    val heroTitleSize: TextUnit,
    val heroTitleLineHeight: TextUnit,
    val heroTitleMaxLines: Int,
    val heroTitleTopPadding: Dp,
    val heroSubtitleTopPadding: Dp,
    val heroChipsTopPadding: Dp,
    val heroChipHorizontalPadding: Dp,
    val heroChipVerticalPadding: Dp,
    val heroChipSpacing: Dp,
    val heroChipStartPadding: Dp,
    val utilityCornerRadius: Dp,
    val utilityHorizontalPadding: Dp,
    val utilityVerticalPadding: Dp,
    val selectedIndicatorTopPadding: Dp,
    val selectedIndicatorWidth: Dp,
    val selectedIndicatorHeight: Dp,
) {
    companion object {
        fun automotive() = CatalogLayoutMetrics(
            contentBottomPadding = 92.dp,
            featuredCoverSize = 44.dp,
            standardCoverSize = 42.dp,
            cardCornerRadius = 12.dp,
            rowHorizontalPadding = 9.dp,
            // Tighter row + outer padding on AAOS — the 600dp landscape viewport
            // otherwise only fits one card after the mini-player + system bar.
            rowVerticalPadding = 3.dp,
            cardOuterHorizontalPadding = 8.dp,
            cardOuterVerticalPadding = 1.dp,
            rowSpacing = 6.dp,
            sourceChipCornerRadius = 8.dp,
            playCornerRadius = 10.dp,
            sourceChipHorizontalPadding = 7.dp,
            sourceChipVerticalPadding = 3.dp,
            playPadding = 5.dp,
            heroBaseHeight = 78.dp,
            heroHorizontalPadding = 16.dp,
            heroVerticalPadding = 4.dp,
            heroTitleSize = 24.sp,
            heroTitleLineHeight = 26.sp,
            heroTitleMaxLines = 1,
            heroTitleTopPadding = 0.dp,
            heroSubtitleTopPadding = 0.dp,
            heroChipsTopPadding = 3.dp,
            heroChipHorizontalPadding = 10.dp,
            heroChipVerticalPadding = 3.dp,
            heroChipSpacing = 8.dp,
            heroChipStartPadding = 24.dp,
            utilityCornerRadius = 10.dp,
            utilityHorizontalPadding = 9.dp,
            utilityVerticalPadding = 5.dp,
            selectedIndicatorTopPadding = 4.dp,
            selectedIndicatorWidth = 44.dp,
            selectedIndicatorHeight = 2.dp,
        )

        fun phone(
            coverSize: Dp = 104.dp,
            heroBaseHeight: Dp = 258.dp,
            titleSize: TextUnit = 44.sp,
            titleLineHeight: TextUnit = 44.sp,
            titleMaxLines: Int = 2,
        ): CatalogLayoutMetrics {
            val compactHero = heroBaseHeight < 320.dp
            return CatalogLayoutMetrics(
                contentBottomPadding = 132.dp,
                featuredCoverSize = coverSize,
                standardCoverSize = coverSize,
                cardCornerRadius = 24.dp,
                rowHorizontalPadding = 13.dp,
                rowVerticalPadding = 11.dp,
                cardOuterHorizontalPadding = 12.dp,
                cardOuterVerticalPadding = 6.dp,
                rowSpacing = 12.dp,
                sourceChipCornerRadius = 12.dp,
                playCornerRadius = 16.dp,
                sourceChipHorizontalPadding = 12.dp,
                sourceChipVerticalPadding = 6.dp,
                playPadding = 8.dp,
                heroBaseHeight = heroBaseHeight,
                heroHorizontalPadding = 22.dp,
                heroVerticalPadding = if (compactHero) 12.dp else 22.dp,
                heroTitleSize = titleSize,
                heroTitleLineHeight = titleLineHeight,
                heroTitleMaxLines = titleMaxLines,
                heroTitleTopPadding = if (compactHero) 10.dp else 22.dp,
                heroSubtitleTopPadding = 6.dp,
                heroChipsTopPadding = if (compactHero) 12.dp else 22.dp,
                heroChipHorizontalPadding = if (compactHero) 14.dp else 18.dp,
                heroChipVerticalPadding = if (compactHero) 6.dp else 9.dp,
                heroChipSpacing = 12.dp,
                heroChipStartPadding = 0.dp,
                utilityCornerRadius = 18.dp,
                utilityHorizontalPadding = 12.dp,
                utilityVerticalPadding = if (compactHero) 6.dp else 8.dp,
                selectedIndicatorTopPadding = if (compactHero) 5.dp else 8.dp,
                selectedIndicatorWidth = 60.dp,
                selectedIndicatorHeight = 3.dp,
            )
        }
    }
}

@Composable
fun TrackListContent(
    title: String,
    subtitle: String,
    tracks: List<Track>,
    isLoading: Boolean,
    error: UiErrorKind?,
    onTrackClick: (Int) -> Unit,
    onBlogClick: (Track) -> Unit,
    modifier: Modifier = Modifier,
    chips: List<String> = emptyList(),
    selectedChipIndex: Int = 0,
    onChipSelected: (Int) -> Unit = {},
    onUtilityClick: (() -> Unit)? = null,
    onToggleFavorite: ((Track) -> Unit)? = null,
    // Forwarded to TrackListBody so the Retry button shown on error actually
    // triggers a reload. Callers that omit this still render the message but
    // the button stays hidden (rather than rendering a dead control).
    onRetry: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    hasMore: Boolean = false,
    loadMoreFailed: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    val isAutomotive = rememberIsAutomotiveUi()
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        val heroImage = tracks.firstOrNull()?.bestThumbnail()
        TrackListBody(
            tracks = tracks,
            isLoading = isLoading,
            error = error,
            header = {
                EditorialHeroHeader(
                    title = title,
                    subtitle = subtitle,
                    imageUrl = heroImage,
                    chips = chips,
                    selectedChipIndex = selectedChipIndex,
                    onChipSelected = onChipSelected,
                    onUtilityClick = onUtilityClick,
                    compactMode = isAutomotive,
                )
            },
            emphasizeFirstItem = true,
            onTrackClick = onTrackClick,
            onBlogClick = onBlogClick,
            onRetry = onRetry,
            onToggleFavorite = onToggleFavorite,
            onLoadMore = onLoadMore,
            hasMore = hasMore,
            loadMoreFailed = loadMoreFailed,
            onRefresh = onRefresh,
            isRefreshing = isRefreshing,
            listState = listState,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
fun TrackListBody(
    tracks: List<Track>,
    isLoading: Boolean,
    error: UiErrorKind?,
    onTrackClick: (Int) -> Unit,
    onBlogClick: (Track) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    emphasizeFirstItem: Boolean = false,
    emptyMessage: String? = null,
    onRetry: (() -> Unit)? = null,
    onToggleFavorite: ((Track) -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    hasMore: Boolean = false,
    loadMoreFailed: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    val isAutomotive = rememberIsAutomotiveUi()
    val metrics = if (isAutomotive) CatalogLayoutMetrics.automotive() else CatalogLayoutMetrics.phone()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val errorOverContent = if (error != null && tracks.isNotEmpty()) error.errorLabel() else null
    androidx.compose.runtime.LaunchedEffect(errorOverContent) {
        errorOverContent?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = androidx.compose.material3.SnackbarDuration.Short,
            )
        }
    }

    // Auto-load-more: when the last visible item is within 4 of the end, ask for more.
    if (onLoadMore != null && hasMore && tracks.isNotEmpty()) {
        val shouldLoadMore = androidx.compose.runtime.remember(tracks.size) {
            androidx.compose.runtime.derivedStateOf {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible >= info.totalItemsCount - 4
            }
        }
        androidx.compose.runtime.LaunchedEffect(shouldLoadMore) {
            androidx.compose.runtime.snapshotFlow { shouldLoadMore.value }
                .collect { atEnd ->
                    if (atEnd && hasMore) onLoadMore()
                }
        }
    }

    val list = @Composable {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = metrics.contentBottomPadding),
        ) {
            if (header != null) {
                item(key = "header") {
                    header()
                }
            }

            when {
                isLoading && tracks.isEmpty() -> {
                    // Skeleton placeholders match the eventual list shape, so
                    // the user gets a sense of layout instead of a centred
                    // spinner pasted onto a blank screen.
                    item(key = "loading-skeletons") {
                        // One shared pulse drives the entire placeholder list,
                        // and TalkBack announces loading once instead of six times.
                        dev.josu.hypecar.core.ui.SkeletonTrackList(count = 6)
                    }
                }

                error != null && tracks.isEmpty() -> {
                    item(key = "error") {
                        val errorMessage = error.errorLabel()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                        ) {
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                    errorSemantics(errorMessage)
                                },
                            )
                            if (onRetry != null) {
                                androidx.compose.material3.OutlinedButton(
                                    onClick = onRetry,
                                    modifier = Modifier.padding(top = 12.dp),
                                ) {
                                    Text(stringResource(R.string.catalog_action_retry))
                                }
                            }
                        }
                    }
                }

                tracks.isEmpty() -> {
                    if (emptyMessage == null || emptyMessage.isNotBlank()) {
                        item(key = "empty") {
                            Text(
                                text = emptyMessage ?: stringResource(R.string.catalog_empty_default),
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                }

                else -> {
                    itemsIndexed(tracks, key = { _, item -> item.id }) { index, track ->
                        TrackRow(
                            track = track,
                            featured = emphasizeFirstItem && index == 0,
                            onTrackClick = { onTrackClick(index) },
                            onBlogClick = { onBlogClick(track) },
                            onToggleFavorite = onToggleFavorite?.let { cb -> { cb(track) } },
                        )
                    }
                    if (hasMore) {
                        item(key = "loadingMore") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                if (loadMoreFailed && onLoadMore != null) {
                                    // A failed page must offer an explicit retry: the scroll
                                    // sentinel only fires once per position, so an eternal
                                    // spinner would otherwise sit here until the user scrolls
                                    // away and back.
                                    androidx.compose.material3.OutlinedButton(onClick = onLoadMore) {
                                        Text(stringResource(R.string.catalog_action_retry))
                                    }
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Pull-to-refresh wrapper (only when callback provided).
        if (onRefresh != null) {
            @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
            val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
            @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                state = pullState,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) { list() }
        } else {
            Box(modifier = Modifier.fillMaxSize()) { list() }
        }
        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
fun TrackRow(
    track: Track,
    featured: Boolean,
    onTrackClick: () -> Unit,
    onBlogClick: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleFavorite: (() -> Unit)? = null,
) {
    val isAutomotive = rememberIsAutomotiveUi()
    val model = TrackRowUiModel.from(track)
    val metrics = if (isAutomotive) {
        CatalogLayoutMetrics.automotive()
    } else {
        CatalogLayoutMetrics.phone(coverSize = model.coverArtWidthDp.dp)
    }
    val coverSize = if (featured) metrics.featuredCoverSize else metrics.standardCoverSize
    val cardShape = RoundedCornerShape(metrics.cardCornerRadius)
    val rowHorizontalPadding = metrics.rowHorizontalPadding
    val rowVerticalPadding = metrics.rowVerticalPadding
    val cardOuterHorizontalPadding = metrics.cardOuterHorizontalPadding
    val cardOuterVerticalPadding = metrics.cardOuterVerticalPadding
    val rowSpacing = metrics.rowSpacing
    val statsColor = if (featured) Color(0xFFE0D7CF) else Color(0xFF6B5B53)
    val playLabel = stringResource(R.string.catalog_action_play)
    val titleStyle = if (isAutomotive) {
        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    } else {
        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    }
    val artistStyle = if (isAutomotive) {
        MaterialTheme.typography.bodyLarge.copy(
            color = if (featured) hypeTokens.brand.primary else hypeTokens.brand.primaryDeep,
            fontWeight = FontWeight.SemiBold,
        )
    } else {
        MaterialTheme.typography.titleMedium.copy(
            color = if (featured) hypeTokens.brand.primary else hypeTokens.brand.primaryDeep,
        )
    }
    val descriptionStyle = if (isAutomotive) {
        MaterialTheme.typography.bodySmall.copy(
            color = if (featured) Color(0xFFD5CDC7) else Color(0xFF584A43),
        )
    } else {
        MaterialTheme.typography.bodyMedium.copy(
            color = if (featured) Color(0xFFD5CDC7) else Color(0xFF584A43),
        )
    }
    val sourceChipShape = RoundedCornerShape(metrics.sourceChipCornerRadius)
    val playShape = RoundedCornerShape(metrics.playCornerRadius)
    val sourceChipPaddingH = metrics.sourceChipHorizontalPadding
    val sourceChipPaddingV = metrics.sourceChipVerticalPadding
    val playPadding = metrics.playPadding

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = cardOuterHorizontalPadding, vertical = cardOuterVerticalPadding)
            .pressFeedback(
                pressedScale = if (featured) 0.985f else 0.99f,
                label = "trackRowPress",
            )
            .clickable(
                onClickLabel = playLabel,
                role = Role.Button,
                onClick = onTrackClick,
            ),
        color = if (featured) Color(0xFF101010) else Color(0xFFF9F4EE),
        shape = cardShape,
        border = if (featured) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1C9B2)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = rowHorizontalPadding, vertical = rowVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(rowSpacing),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier.size(coverSize),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (model.coverArtUrl != null) {
                        val coverFallback = androidx.compose.ui.graphics.painter.ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
                        AsyncImage(
                            model = rememberSizedImageRequest(model.coverArtUrl, coverSize, coverSize),
                            contentDescription = null,
                            modifier = Modifier
                                .size(coverSize)
                                .clip(RoundedCornerShape(2.dp)),
                            contentScale = ContentScale.Crop,
                            // Loading and failed covers render a tonal block instead of a
                            // fully transparent hole in the card.
                            placeholder = coverFallback,
                            error = coverFallback,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(coverSize)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                    model.rank?.let { rank ->
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF222222))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = rank.toString(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                if (onToggleFavorite != null) {
                    val favColor = hypeTokens.brand.primaryStrong
                    val favoriteContentDescription = stringResource(
                        if (track.isLoved) {
                            R.string.catalog_action_unfavorite
                        } else {
                            R.string.catalog_action_favorite
                        },
                    )
                    // The whole card plays the track, so a mis-tap on the heart must
                    // not start playback: the touch container meets the 48dp minimum
                    // on phone while the visible circle keeps its compact size.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (track.isLoved) Color(0x33FF6A21) else Color.Transparent)
                            .pressFeedback(pressedScale = 0.90f, label = "trackFavoritePress")
                            .clickable(
                                onClickLabel = favoriteContentDescription,
                                role = Role.Checkbox,
                                onClick = onToggleFavorite,
                            )
                            .clearAndSetSemantics {
                                contentDescription = favoriteContentDescription
                                role = Role.Checkbox
                                toggleableState = ToggleableState(track.isLoved)
                                onClick(label = favoriteContentDescription) {
                                    onToggleFavorite()
                                    true
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (track.isLoved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            tint = if (track.isLoved) favColor else Color(0xFF9B7C68),
                            modifier = Modifier.size(if (isAutomotive) 18.dp else 20.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (isAutomotive) 3.dp else 6.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = Color(0xFFE53935),
                        modifier = Modifier.size(14.dp),
                    )
                    // Stats line composed via pluralStringResource so that
                    // translations and pluralization (1 loved / N loved) are
                    // handled correctly. The model carries raw counts now.
                    val statsText = androidx.compose.ui.res.pluralStringResource(
                        id = R.plurals.track_row_stats,
                        count = model.lovedCount,
                        model.lovedCount,
                        model.postedCount,
                    )
                    Text(
                        text = statsText,
                        style = if (isAutomotive) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                        color = statsColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val unknownTitle = stringResource(R.string.catalog_unknown_title)
                val unknownArtist = stringResource(R.string.catalog_unknown_artist)
                val unknownBlog = stringResource(R.string.catalog_unknown_blog)
                Text(
                    text = model.titleLine.ifBlank { unknownTitle },
                    style = titleStyle,
                    maxLines = if (isAutomotive) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (featured) Color.White else Color(0xFF141414),
                )
                Text(
                    text = model.artistLine.ifBlank { unknownArtist },
                    style = artistStyle,
                    maxLines = if (isAutomotive) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!isAutomotive) {
                    Text(
                        text = model.description.ifBlank { model.sourceLabel.ifBlank { unknownBlog } },
                        style = descriptionStyle,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isAutomotive) Arrangement.End else Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isAutomotive) {
                        Box(
                            modifier = Modifier
                                .sizeIn(minHeight = 48.dp)
                                .clip(sourceChipShape)
                                .border(
                                    1.dp,
                                    if (featured) hypeTokens.brand.primary else Color(0xFFDC8D54),
                                    sourceChipShape,
                                )
                                .pressFeedback(pressedScale = 0.96f, label = "trackSourcePress")
                                .clickable(
                                    role = Role.Button,
                                    onClick = onBlogClick,
                                )
                                .padding(horizontal = sourceChipPaddingH, vertical = sourceChipPaddingV),
                        ) {
                            Text(
                                text = model.sourceLabel.ifBlank { unknownBlog },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (featured) hypeTokens.brand.primary else hypeTokens.brand.primaryDeep,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (isAutomotive) {
                        CompactAutomotivePlayButton(
                            featured = featured,
                            onClick = onTrackClick,
                        )
                    } else {
                        Surface(
                            onClick = onTrackClick,
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .pressFeedback(pressedScale = 0.92f, label = "trackPlayPress"),
                            shape = playShape,
                            color = Color.Transparent,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(playShape)
                                    .border(
                                        2.dp,
                                        if (featured) Color.White else Color(0xFF8A8A8A),
                                        playShape,
                                    )
                                    .padding(horizontal = playPadding, vertical = playPadding),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Default.PlayArrow,
                                    contentDescription = stringResource(R.string.catalog_action_play),
                                    tint = if (featured) Color.White else Color(0xFF5C5C5C),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactAutomotivePlayButton(
    featured: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    val playLabel = stringResource(R.string.catalog_action_play)
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(shape)
            .border(
                2.dp,
                if (featured) Color.White else Color(0xFF8A8A8A),
                shape,
            )
            .pressFeedback(pressedScale = 0.92f, label = "compactTrackPlayPress")
            .clickable(
                onClickLabel = playLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = if (featured) Color.White else Color(0xFF5C5C5C),
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
fun EditorialHeroHeader(
    title: String,
    subtitle: String,
    imageUrl: String?,
    chips: List<String>,
    selectedChipIndex: Int,
    onChipSelected: (Int) -> Unit,
    onUtilityClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    // Default pulls from string resources so callers that don't override the
    // label still get a localised "Filter" / "Filtrar" instead of hardcoded English.
    utilityLabel: String = stringResource(R.string.catalog_action_filter),
    height: Dp = 258.dp,
    titleSize: TextUnit = 44.sp,
    titleLineHeight: TextUnit = 44.sp,
    titleMaxLines: Int = 2,
    compactMode: Boolean = false,
) {
    val metrics = if (compactMode) {
        CatalogLayoutMetrics.automotive()
    } else {
        CatalogLayoutMetrics.phone(
            heroBaseHeight = height,
            titleSize = titleSize,
            titleLineHeight = titleLineHeight,
            titleMaxLines = titleMaxLines,
        )
    }
    if (compactMode) {
        AutomotiveEditorialHeroHeader(
            title = title,
            subtitle = subtitle,
            imageUrl = imageUrl,
            chips = chips,
            selectedChipIndex = selectedChipIndex,
            onChipSelected = onChipSelected,
            onUtilityClick = onUtilityClick,
            utilityLabel = utilityLabel,
            metrics = metrics,
            modifier = modifier,
        )
        return
    }
    val statusBarPadding = if (compactMode) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val heroHeight = metrics.heroBaseHeight + statusBarPadding

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = heroHeight),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0xFF3A150B)),
            )
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFD14F19).copy(alpha = 0.90f),
                            Color(0xFF7A220E).copy(alpha = 0.92f),
                            Color(0xFF0E0D0D),
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (compactMode) Modifier else Modifier.statusBarsPadding())
                .padding(horizontal = metrics.heroHorizontalPadding, vertical = metrics.heroVerticalPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (onUtilityClick != null) {
                    Surface(
                        onClick = onUtilityClick,
                        modifier = Modifier.pressFeedback(
                            pressedScale = 0.95f,
                            label = "heroUtilityPress",
                        ),
                        shape = RoundedCornerShape(metrics.utilityCornerRadius),
                        color = Color(0xAA572313),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x77FFE2C9)),
                    ) {
                        Text(
                            text = utilityLabel,
                            modifier = Modifier.padding(
                                horizontal = metrics.utilityHorizontalPadding,
                                vertical = metrics.utilityVerticalPadding,
                            ),
                            color = Color.White,
                            style = if (compactMode) {
                                MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                            } else {
                                MaterialTheme.typography.titleMedium
                            },
                        )
                    }
                }
            }
            Text(
                text = title,
                color = Color.White,
                fontSize = metrics.heroTitleSize,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = metrics.heroTitleLineHeight,
                maxLines = metrics.heroTitleMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = metrics.heroTitleTopPadding)
                    .semantics { heading() },
            )
            Text(
                text = subtitle,
                color = Color(0xFFFCE5D6),
                style = if (compactMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = metrics.heroSubtitleTopPadding),
                maxLines = if (compactMode) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (chips.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .padding(top = metrics.heroChipsTopPadding, start = metrics.heroChipStartPadding)
                        .horizontalScroll(rememberScrollState())
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(metrics.heroChipSpacing),
                ) {
                    chips.forEachIndexed { index, chip ->
                        val selected = index == selectedChipIndex
                        val interactionSource = remember { MutableInteractionSource() }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .sizeIn(minHeight = 48.dp)
                                .pressFeedback(pressedScale = 0.95f, label = "heroChipPress")
                                .selectable(
                                    selected = selected,
                                    interactionSource = interactionSource,
                                    indication = null,
                                    role = Role.Tab,
                                    onClick = { onChipSelected(index) },
                                ),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (selected) Color.White else Color.Transparent,
                            ) {
                                Text(
                                    text = chip,
                                    modifier = Modifier.padding(
                                        horizontal = metrics.heroChipHorizontalPadding,
                                        vertical = metrics.heroChipVerticalPadding,
                                    ),
                                    color = if (selected) hypeTokens.brand.primaryDeep else Color.White,
                                    style = if (compactMode) {
                                        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                    } else {
                                        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    },
                                )
                            }
                            if (selected) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = metrics.selectedIndicatorTopPadding)
                                        .size(
                                            width = metrics.selectedIndicatorWidth,
                                            height = metrics.selectedIndicatorHeight,
                                        )
                                        .background(Color.White, RoundedCornerShape(2.dp)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomotiveEditorialHeroHeader(
    title: String,
    subtitle: String,
    imageUrl: String?,
    chips: List<String>,
    selectedChipIndex: Int,
    onChipSelected: (Int) -> Unit,
    onUtilityClick: (() -> Unit)?,
    utilityLabel: String,
    metrics: CatalogLayoutMetrics,
    modifier: Modifier = Modifier,
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val heroHeight = metrics.heroBaseHeight + statusBarPadding
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = heroHeight),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0xFF3A150B)),
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFD14F19).copy(alpha = 0.90f),
                            Color(0xFF4B170D).copy(alpha = 0.94f),
                            Color(0xFF101010),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = metrics.heroHorizontalPadding, vertical = metrics.heroVerticalPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = metrics.heroTitleSize,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = metrics.heroTitleLineHeight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = subtitle,
                        color = Color(0xFFFCE5D6),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onUtilityClick != null) {
                    Surface(
                        onClick = onUtilityClick,
                        modifier = Modifier.pressFeedback(
                            pressedScale = 0.95f,
                            label = "automotiveHeroUtilityPress",
                        ),
                        shape = RoundedCornerShape(metrics.utilityCornerRadius),
                        color = Color(0xAA572313),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x77FFE2C9)),
                    ) {
                        Text(
                            text = utilityLabel,
                            modifier = Modifier.padding(
                                horizontal = metrics.utilityHorizontalPadding,
                                vertical = metrics.utilityVerticalPadding,
                            ),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
            if (chips.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .padding(top = metrics.heroChipsTopPadding)
                        .horizontalScroll(rememberScrollState())
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(metrics.heroChipSpacing),
                ) {
                    chips.forEachIndexed { index, chip ->
                        val selected = index == selectedChipIndex
                        val interactionSource = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .sizeIn(minHeight = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Color.White else Color(0x22FFFFFF))
                                .then(
                                    if (selected) {
                                        Modifier
                                    } else {
                                        Modifier.border(
                                            1.dp,
                                            Color(0x66FFFFFF),
                                            RoundedCornerShape(10.dp),
                                        )
                                    },
                                )
                                .pressFeedback(pressedScale = 0.95f, label = "automotiveHeroChipPress")
                                .selectable(
                                    selected = selected,
                                    interactionSource = interactionSource,
                                    indication = null,
                                    role = Role.Tab,
                                    onClick = { onChipSelected(index) },
                                ),
                        ) {
                            Text(
                                text = chip,
                                modifier = Modifier.padding(
                                    horizontal = metrics.heroChipHorizontalPadding,
                                    vertical = metrics.heroChipVerticalPadding,
                                ),
                                color = if (selected) hypeTokens.brand.primaryDeep else Color.White,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun rememberIsAutomotiveUi(): Boolean {
    val context = LocalContext.current
    val uiMode = context.resources.configuration.uiMode
    return remember(context, uiMode) {
        context.isAutomotiveUi()
    }
}

@Composable
private fun rememberSizedImageRequest(
    url: String?,
    width: Dp,
    height: Dp,
): Any? {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx().coerceAtLeast(1) }
    val heightPx = with(density) { height.roundToPx().coerceAtLeast(1) }
    return remember(context, url, widthPx, heightPx) {
        url?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(widthPx, heightPx)
                .crossfade(false)
                .build()
        }
    }
}
