package dev.josu.hypecar.feature.player

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.PlaybackRepeatMode
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

internal data class PlayerLayoutMetrics(
    val topBarHeight: Dp,
    val showsPhoneOverlayCollapseControl: Boolean,
    val artworkWidthFraction: Float,
    val artworkTopPadding: Dp,
    val titleHorizontalPadding: Dp,
    val titleVerticalPadding: Dp,
    val progressHorizontalPadding: Dp,
    val progressVerticalPadding: Dp,
    val artworkHorizontalPadding: Dp,
    val artworkCornerRadius: Dp,
    val infoHorizontalPadding: Dp,
    val infoCardSpacing: Dp,
    val descriptionHorizontalPadding: Dp,
    val descriptionVerticalPadding: Dp,
    val bottomDeckHorizontalPadding: Dp,
    val bottomDeckVerticalPadding: Dp,
    val bottomDeckSpacing: Dp,
    val bottomControlsReservedHeight: Dp,
    val secondaryControlSize: Dp,
    val secondaryControlIconSize: Dp,
    val primaryControlSize: Dp,
    val primaryControlIconSize: Dp,
    val utilityIconSize: Dp,
) {
    companion object {
        fun automotive() = PlayerLayoutMetrics(
            topBarHeight = 48.dp,
            showsPhoneOverlayCollapseControl = false,
            artworkWidthFraction = 1f,
            artworkTopPadding = 0.dp,
            titleHorizontalPadding = 12.dp,
            titleVerticalPadding = 6.dp,
            progressHorizontalPadding = 12.dp,
            progressVerticalPadding = 6.dp,
            artworkHorizontalPadding = 12.dp,
            artworkCornerRadius = 16.dp,
            infoHorizontalPadding = 12.dp,
            infoCardSpacing = 8.dp,
            descriptionHorizontalPadding = 12.dp,
            descriptionVerticalPadding = 6.dp,
            bottomDeckHorizontalPadding = 8.dp,
            bottomDeckVerticalPadding = 8.dp,
            bottomDeckSpacing = 8.dp,
            bottomControlsReservedHeight = 78.dp,
            secondaryControlSize = 46.dp,
            secondaryControlIconSize = 24.dp,
            primaryControlSize = 62.dp,
            primaryControlIconSize = 32.dp,
            utilityIconSize = 18.dp,
        )

        fun phone() = PlayerLayoutMetrics(
            topBarHeight = 0.dp,
            showsPhoneOverlayCollapseControl = false,
            artworkWidthFraction = 0.70f,
            artworkTopPadding = 30.dp,
            titleHorizontalPadding = 28.dp,
            titleVerticalPadding = 4.dp,
            progressHorizontalPadding = 28.dp,
            progressVerticalPadding = 4.dp,
            artworkHorizontalPadding = 0.dp,
            artworkCornerRadius = 26.dp,
            infoHorizontalPadding = 18.dp,
            infoCardSpacing = 8.dp,
            descriptionHorizontalPadding = 16.dp,
            descriptionVerticalPadding = 8.dp,
            bottomDeckHorizontalPadding = 24.dp,
            bottomDeckVerticalPadding = 14.dp,
            bottomDeckSpacing = 0.dp,
            bottomControlsReservedHeight = 122.dp,
            secondaryControlSize = 50.dp,
            secondaryControlIconSize = 27.dp,
            primaryControlSize = 74.dp,
            primaryControlIconSize = 36.dp,
            utilityIconSize = 22.dp,
        )
    }
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackRepository: PlaybackRepository,
    private val meRepository: MeRepository,
) : ViewModel() {
    val queue: StateFlow<PlaybackQueue> = playbackRepository.queue
    private val favoriteSyncStates = mutableMapOf<String, FavoriteSyncState>()

    fun togglePlayPause() {
        viewModelScope.launch { playbackRepository.togglePlayPause() }
    }

    fun skipNext() {
        viewModelScope.launch { playbackRepository.skipNext() }
    }

    fun skipPrevious() {
        viewModelScope.launch { playbackRepository.skipPrevious() }
    }

    fun seekToFraction(progress: Float) {
        val durationMs = queue.value.durationMs
        if (durationMs <= 0L) return
        val positionMs = (progress.coerceIn(0f, 1f) * durationMs).toLong()
        viewModelScope.launch { playbackRepository.seekTo(positionMs) }
    }

    fun toggleShuffle() {
        viewModelScope.launch { playbackRepository.toggleShuffle() }
    }

    fun cycleRepeatMode() {
        viewModelScope.launch { playbackRepository.cycleRepeatMode() }
    }

    fun acknowledgePlaybackError(eventId: Long) {
        playbackRepository.acknowledgePlaybackError(eventId)
    }

    fun toggleFavorite() {
        val current = queue.value.current?.track ?: return
        val trackId = current.id
        val syncState = favoriteSyncStates.getOrPut(trackId) {
            FavoriteSyncState(
                confirmedLoved = current.isLoved,
                desiredLoved = current.isLoved,
            )
        }
        val targetLoved = !syncState.desiredLoved
        syncState.desiredLoved = targetLoved
        viewModelScope.launch {
            playbackRepository.updateFavorite(current.id, targetLoved)
            if (!syncState.isSyncing) {
                syncState.isSyncing = true
                syncFavorite(trackId)
            }
        }
    }

    private suspend fun syncFavorite(trackId: String) {
        val syncState = favoriteSyncStates[trackId] ?: return
        try {
            while (syncState.desiredLoved != syncState.confirmedLoved) {
                val targetLoved = syncState.desiredLoved
                val serverLoved = meRepository.toggleFavorite(trackId)
                if (serverLoved != null) {
                    syncState.confirmedLoved = serverLoved
                    val current = queue.value.current?.track
                    if (
                        syncState.desiredLoved == serverLoved &&
                        current?.id == trackId &&
                        current.isLoved != serverLoved
                    ) {
                        playbackRepository.updateFavorite(trackId, serverLoved)
                    }
                } else if (syncState.desiredLoved == targetLoved) {
                    syncState.desiredLoved = syncState.confirmedLoved
                    playbackRepository.updateFavorite(trackId, syncState.confirmedLoved)
                }
            }
        } finally {
            syncState.isSyncing = false
            if (syncState.desiredLoved == syncState.confirmedLoved) {
                favoriteSyncStates.remove(trackId)
            }
        }
    }
}

private data class FavoriteSyncState(
    var confirmedLoved: Boolean,
    var desiredLoved: Boolean,
    var isSyncing: Boolean = false,
)

@Composable
fun PlayerRoute(
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val model = PlayerScreenUiModel.fromQueue(queue)
    val context = LocalContext.current
    val uiMode = context.resources.configuration.uiMode
    val isAutomotive = remember(context, uiMode) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) ||
            (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_CAR ||
            Build.PRODUCT.contains("gcar", ignoreCase = true) ||
            Build.DEVICE.contains("car", ignoreCase = true) ||
            Build.FINGERPRINT.contains("gcar", ignoreCase = true)
    }
    val metrics = if (isAutomotive) PlayerLayoutMetrics.automotive() else PlayerLayoutMetrics.phone()
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val currentMediaId = queue.current?.mediaId
    val haptics = LocalHapticFeedback.current
    val closePlayerLabel = stringResource(R.string.player_close)
    val favoriteLabel = stringResource(R.string.player_action_favorite)
    val unfavoriteLabel = stringResource(R.string.player_action_unfavorite)
    val errorSkippedLabel = stringResource(R.string.player_error_skipped)
    val errorStoppedLabel = stringResource(R.string.player_error_stopped)
    val errorDismissLabel = stringResource(R.string.player_error_dismiss)
    val snackbarHostState = remember { SnackbarHostState() }
    val transientError = queue.transientError
    LaunchedEffect(transientError?.eventId) {
        val event = transientError ?: return@LaunchedEffect
        val message = if (event.recoverable) errorSkippedLabel else errorStoppedLabel
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = errorDismissLabel,
        )
        viewModel.acknowledgePlaybackError(event.eventId)
        if (result == SnackbarResult.ActionPerformed) {
            // user dismissed; nothing further to do
        }
    }
    val transportTick: () -> Unit = remember(haptics, isAutomotive) {
        if (isAutomotive) {
            { /* no haptics on Auto head units */ }
        } else {
            { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        }
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 120.dp.toPx() }
    val entryOffsetPx = with(density) { 72.dp.toPx() }
    val dismissThresholdPx = with(density) { 140.dp.toPx() }
    val dragOffset = remember { Animatable(0f) }
    val dismissOffset = remember { Animatable(0f) }
    var pendingDirection by remember { mutableStateOf<PlayerSwipeDirection?>(null) }
    var selectedProgress by remember(currentMediaId) { mutableFloatStateOf(model?.progressFraction ?: 0f) }
    var isSeeking by remember(currentMediaId) { mutableStateOf(false) }

    LaunchedEffect(currentMediaId, model?.progressFraction, isSeeking) {
        if (!isSeeking && model != null) {
            selectedProgress = model.progressFraction
        }
    }

    LaunchedEffect(currentMediaId) {
        pendingDirection?.let { direction ->
            val incomingOffset = when (direction) {
                PlayerSwipeDirection.NEXT -> entryOffsetPx
                PlayerSwipeDirection.PREVIOUS -> -entryOffsetPx
            }
            dragOffset.snapTo(incomingOffset)
            dragOffset.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            )
            pendingDirection = null
        }
    }

    val artworkSwipeModifier = Modifier.pointerInput(currentMediaId) {
        detectHorizontalDragGestures(
            onDragStart = {
                scope.launch { dragOffset.stop() }
            },
            onHorizontalDrag = { _, dragAmount ->
                scope.launch {
                    dragOffset.snapTo(dragOffset.value + dragAmount)
                }
            },
            onDragEnd = {
                val direction = PlayerSwipeDecision.fromOffset(
                    offsetPx = dragOffset.value,
                    thresholdPx = swipeThresholdPx,
                )
                scope.launch {
                    if (direction == null) {
                        dragOffset.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 0.75f, stiffness = 420f),
                        )
                    } else {
                        pendingDirection = direction
                        val exitOffset = when (direction) {
                            PlayerSwipeDirection.NEXT -> -size.width.toFloat()
                            PlayerSwipeDirection.PREVIOUS -> size.width.toFloat()
                        }
                        dragOffset.animateTo(
                            targetValue = exitOffset,
                            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                        )
                        when (direction) {
                            PlayerSwipeDirection.NEXT -> viewModel.skipNext()
                            PlayerSwipeDirection.PREVIOUS -> viewModel.skipPrevious()
                        }
                    }
                }
            },
            onDragCancel = {
                scope.launch {
                    dragOffset.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = 0.75f, stiffness = 420f),
                    )
                }
            },
        )
    }
    val dismissSwipeModifier = Modifier.pointerInput(currentMediaId) {
        detectVerticalDragGestures(
            onDragStart = {
                scope.launch { dismissOffset.stop() }
            },
            onVerticalDrag = { change, dragAmount ->
                if (dragAmount > 0f || dismissOffset.value > 0f) {
                    change.consume()
                    scope.launch {
                        dismissOffset.snapTo((dismissOffset.value + dragAmount).coerceAtLeast(0f))
                    }
                }
            },
            onDragEnd = {
                scope.launch {
                    if (dismissOffset.value >= dismissThresholdPx) {
                        dismissOffset.animateTo(
                            targetValue = size.height.toFloat(),
                            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                        )
                        backDispatcher?.onBackPressed()
                    } else {
                        dismissOffset.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 0.78f, stiffness = 460f),
                        )
                    }
                }
            },
            onDragCancel = {
                scope.launch {
                    dismissOffset.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = 0.78f, stiffness = 460f),
                    )
                }
            },
        )
    }

    Scaffold(
        containerColor = Color(0xFF0A0809),
        topBar = {
            if (isAutomotive) {
                CompactPlayerTopBar(onCollapse = { backDispatcher?.onBackPressed() })
            } else {
                NowPlayingTopBar(
                    onCollapse = { backDispatcher?.onBackPressed() },
                    onMore = { /* room for an overflow menu in a future pass */ },
                )
            }
        },
        snackbarHost = {
            // Anchor the snackbar above the transport row so playback controls stay
            // reachable while an error message is on screen — important for the
            // automotive layout where the toast otherwise covers the play button.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = metrics.bottomControlsReservedHeight),
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Color(0xFF2D211C),
                    contentColor = Color.White,
                    actionColor = Color(0xFFFF8A3D),
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    if (isAutomotive) {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF21150F),
                                Color(0xFF12100F),
                                Color(0xFF0B0B0C),
                            ),
                        )
                    } else {
                        // Near-pure-black on phone — the artwork glow does the warmth.
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF0A0809), Color(0xFF050405)),
                        )
                    },
                ),
        ) {
            // Soft animated warm haze rising from the bottom; phone-only for safety.
            if (!isAutomotive) {
                BreathingBottomHaze(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(280.dp),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                if (model == null) {
                    Text(
                        text = stringResource(R.string.player_idle),
                        color = Color.White,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (isAutomotive) Modifier else dismissSwipeModifier)
                                .then(
                                    if (isAutomotive) {
                                        Modifier
                                    } else {
                                        val closeLabel = closePlayerLabel
                                        Modifier.semantics {
                                            dismiss(label = closeLabel) {
                                                backDispatcher?.onBackPressed()
                                                true
                                            }
                                            customActions = listOf(
                                                CustomAccessibilityAction(label = closeLabel) {
                                                    backDispatcher?.onBackPressed()
                                                    true
                                                },
                                            )
                                        }
                                    },
                                )
                                .graphicsLayer {
                                    translationY = if (isAutomotive) 0f else dismissOffset.value
                                },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = metrics.bottomControlsReservedHeight)
                                    .graphicsLayer {
                                        translationX = dragOffset.value
                                        val progress = (abs(dragOffset.value) / swipeThresholdPx).coerceIn(0f, 1f)
                                        alpha = 1f - (progress * 0.18f)
                                        scaleX = 1f - (progress * 0.04f)
                                        scaleY = 1f - (progress * 0.04f)
                                    },
                                verticalArrangement = if (isAutomotive) Arrangement.Top else Arrangement.spacedBy(10.dp),
                            ) {
                                if (!isAutomotive) {
                                    GlowingArtwork(
                                        artworkUrl = model.artworkUrl,
                                        metrics = metrics,
                                        modifier = artworkSwipeModifier,
                                    )
                                    Spacer(modifier = Modifier.height(18.dp))
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = metrics.titleHorizontalPadding,
                                            vertical = metrics.titleVerticalPadding,
                                        ),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Modest car-only artwork thumbnail — picks up the
                                    // CarPlay/YT-Music idiom without competing with controls.
                                    if (isAutomotive) {
                                        AsyncImage(
                                            model = model.artworkUrl,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(80.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFF211B18)),
                                            contentScale = ContentScale.Crop,
                                        )
                                        Spacer(modifier = Modifier.size(14.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = model.title,
                                            style = if (isAutomotive) {
                                                MaterialTheme.typography.titleLarge.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                )
                                            } else {
                                                MaterialTheme.typography.headlineMedium.copy(
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color.White,
                                                )
                                            },
                                            maxLines = if (isAutomotive) 1 else 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = model.artist,
                                            style = if (isAutomotive) {
                                                MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFFFC4AA))
                                            } else {
                                                MaterialTheme.typography.titleMedium.copy(color = Color(0xFFFFB08A))
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (isAutomotive) {
                                        IconButton(
                                            onClick = {
                                                transportTick()
                                                viewModel.toggleFavorite()
                                            },
                                            modifier = Modifier.size(52.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (model.isLoved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                contentDescription = if (model.isLoved) unfavoriteLabel else favoriteLabel,
                                                tint = Color(0xFFFF9A6D),
                                                modifier = Modifier.size(34.dp),
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(52.dp)
                                                .clip(CircleShape)
                                                .background(Color(0x1AFF8A3D))
                                                .clickable {
                                                    transportTick()
                                                    viewModel.toggleFavorite()
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = if (model.isLoved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                contentDescription = if (model.isLoved) unfavoriteLabel else favoriteLabel,
                                                tint = Color(0xFFFF9A6D),
                                                modifier = Modifier.size(26.dp),
                                            )
                                        }
                                    }
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = metrics.progressHorizontalPadding,
                                            vertical = metrics.progressVerticalPadding,
                                        ),
                                ) {
                                    PlayerScrubber(
                                        progress = if (isSeeking) selectedProgress else model.progressFraction,
                                        enabled = model.durationMs > 0L,
                                        onProgressChange = {
                                            isSeeking = true
                                            selectedProgress = it
                                        },
                                        onProgressChangeFinished = {
                                            selectedProgress = it
                                            viewModel.seekToFraction(it)
                                            isSeeking = false
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth(),
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(model.elapsedLabel, color = Color(0xFFABA4A0), style = MaterialTheme.typography.bodyLarge)
                                        Text(model.remainingLabel, color = Color(0xFFABA4A0), style = MaterialTheme.typography.bodyLarge)
                                    }
                                }

                                if (!isAutomotive) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                            PlayerControlDeck(
                                model = model,
                                metrics = metrics,
                                onShuffle = {
                                    transportTick()
                                    viewModel.toggleShuffle()
                                },
                                onPrevious = {
                                    transportTick()
                                    viewModel.skipPrevious()
                                },
                                onTogglePlayPause = {
                                    transportTick()
                                    viewModel.togglePlayPause()
                                },
                                onNext = {
                                    transportTick()
                                    viewModel.skipNext()
                                },
                                onRepeat = {
                                    transportTick()
                                    viewModel.cycleRepeatMode()
                                },
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerScrubber(
    progress: Float,
    enabled: Boolean,
    onProgressChange: (Float) -> Unit,
    onProgressChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val boundedProgress = progress.coerceIn(0f, 1f)
    val thumbSize = 14.dp
    val activeColor = if (enabled) Color(0xFFFF9A6D) else Color(0xFF6E615D)
    val inactiveColor = if (enabled) Color(0xFF55463F) else Color(0xFF342D2A)
    val progressLabel = stringResource(R.string.player_progress_label)

    BoxWithConstraints(
        modifier = modifier
            .height(44.dp)
            .semantics {
                role = Role.Image
                contentDescription = progressLabel
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = boundedProgress,
                    range = 0f..1f,
                    steps = 0,
                )
                if (enabled) {
                    setProgress { target ->
                        val clamped = target.coerceIn(0f, 1f)
                        onProgressChange(clamped)
                        onProgressChangeFinished(clamped)
                        true
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var latestProgress = down.position.x.toProgress(size.width)
                    onProgressChange(latestProgress)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        latestProgress = change.position.x.toProgress(size.width)
                        onProgressChange(latestProgress)
                        change.consume()
                    }
                    onProgressChangeFinished(latestProgress)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(inactiveColor),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(boundedProgress)
                .height(6.dp)
                .clip(CircleShape)
                .background(activeColor),
        )
        Box(
            modifier = Modifier
                .offset(x = (maxWidth - thumbSize) * boundedProgress)
                .size(thumbSize)
                .clip(CircleShape)
                .background(activeColor),
        )
    }
}

private fun Float.toProgress(width: Int): Float =
    if (width <= 0) 0f else (this / width.toFloat()).coerceIn(0f, 1f)

@Composable
private fun PlayerControlDeck(
    model: PlayerScreenUiModel,
    metrics: PlayerLayoutMetrics,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = metrics.bottomDeckHorizontalPadding,
                vertical = metrics.bottomDeckVerticalPadding,
            ),
        verticalArrangement = Arrangement.spacedBy(metrics.bottomDeckSpacing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerModeButton(
                icon = Icons.Default.Shuffle,
                contentDescription = stringResource(
                    if (model.isShuffleEnabled) R.string.player_action_shuffle_disable else R.string.player_action_shuffle_enable,
                ),
                active = model.isShuffleEnabled,
                onClick = onShuffle,
                size = metrics.secondaryControlSize,
                iconSize = metrics.secondaryControlIconSize,
            )
            PlayerControlButton(
                icon = Icons.Default.SkipPrevious,
                contentDescription = stringResource(R.string.player_action_previous),
                onClick = onPrevious,
                filled = false,
                size = metrics.secondaryControlSize,
                iconSize = metrics.secondaryControlIconSize,
            )
            PlayerControlButton(
                icon = if (model.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(
                    if (model.isPlaying) R.string.player_action_pause else R.string.player_action_play,
                ),
                onClick = onTogglePlayPause,
                filled = true,
                size = metrics.primaryControlSize,
                iconSize = metrics.primaryControlIconSize,
            )
            PlayerControlButton(
                icon = Icons.Default.SkipNext,
                contentDescription = stringResource(R.string.player_action_next),
                onClick = onNext,
                filled = false,
                size = metrics.secondaryControlSize,
                iconSize = metrics.secondaryControlIconSize,
            )
            PlayerModeButton(
                icon = if (model.repeatMode == PlaybackRepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                contentDescription = stringResource(
                    when (model.repeatMode) {
                        PlaybackRepeatMode.OFF -> R.string.player_action_repeat_enable
                        PlaybackRepeatMode.ALL -> R.string.player_action_repeat_one
                        PlaybackRepeatMode.ONE -> R.string.player_action_repeat_disable
                    },
                ),
                active = model.repeatMode != PlaybackRepeatMode.OFF,
                onClick = onRepeat,
                size = metrics.secondaryControlSize,
                iconSize = metrics.secondaryControlIconSize,
            )
        }
    }
}

@Composable
private fun CompactPlayerTopBar(
    onCollapse: () -> Unit,
) {
    Surface(
        color = Color(0xFF2D211C),
        contentColor = Color.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCollapse, modifier = Modifier.size(42.dp)) {
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.player_collapse),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.size(42.dp))
        }
    }
}

@Composable
private fun PlayerModeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    size: Dp,
    iconSize: Dp,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) Color(0xFF2C1D16) else Color.Transparent,
        border = null,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (active) Color(0xFFFF8A3D) else Color(0xFFE3DDD9),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun PlayerControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    filled: Boolean,
    size: Dp = if (filled) 84.dp else 68.dp,
    iconSize: Dp = if (filled) 42.dp else 34.dp,
) {
    if (filled) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFFFFC09C), Color(0xFFD88754)),
                    ),
                )
                .clickable(onClick = onClick)
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.Black,
                modifier = Modifier.size(iconSize),
            )
        }
    } else {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color.Transparent,
            border = null,
        ) {
            Box(
                modifier = Modifier
                    .size(size)
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = Color.White,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

@Composable
private fun CompactInfoCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF181719),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFF302928)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                color = Color(0xFFFFB08A),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = value,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun NowPlayingTopBar(
    onCollapse: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onCollapse, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.player_collapse),
                tint = Color(0xFFE3DDD9),
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = stringResource(R.string.player_top_bar_title),
            color = Color(0xFFE3DDD9),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        IconButton(onClick = onMore, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.player_more_actions),
                tint = Color(0xFFE3DDD9),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun GlowingArtwork(
    artworkUrl: String?,
    metrics: PlayerLayoutMetrics,
    modifier: Modifier = Modifier,
) {
    // Slow ~5.5s breathing cycle on the warm halo behind the cover art.
    val transition = rememberInfiniteTransition(label = "playerArtworkGlow")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "artworkBreath",
    )
    val glowRadius = 760f + 260f * breath
    val glowAlpha = 0.78f + 0.22f * breath

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = metrics.artworkHorizontalPadding,
                top = metrics.artworkTopPadding,
                end = metrics.artworkHorizontalPadding,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // The signature soft warm glow that radiates from behind the cover art.
        Box(
            modifier = Modifier
                .fillMaxWidth(metrics.artworkWidthFraction)
                .aspectRatio(1f)
                .padding(8.dp)
                .graphicsLayer { alpha = glowAlpha }
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x66FF8A3D),
                            Color(0x33FF8A3D),
                            Color(0x00FF8A3D),
                        ),
                        radius = glowRadius,
                    ),
                ),
        )
        AsyncImage(
            model = artworkUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth(metrics.artworkWidthFraction)
                .aspectRatio(1f)
                .then(modifier)
                .clip(RoundedCornerShape(metrics.artworkCornerRadius))
                .background(Color(0xFF211B18)),
            contentScale = ContentScale.Crop,
        )
    }
}

/** Soft warm haze anchored to the bottom of the player background; phone-only. */
@Composable
private fun BreathingBottomHaze(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "playerBottomHaze")
    // Slightly out-of-phase with the artwork halo for an organic, layered feel.
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hazeBreath",
    )
    val hazeAlpha = 0.55f + 0.25f * breath
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = hazeAlpha }
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x40FF8A3D),
                        Color(0x18FF8A3D),
                        Color(0x00FF8A3D),
                    ),
                    radius = 700f + 220f * breath,
                ),
            ),
    )
}
