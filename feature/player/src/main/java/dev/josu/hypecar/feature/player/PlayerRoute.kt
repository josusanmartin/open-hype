package dev.josu.hypecar.feature.player

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.josu.hypecar.core.data.repository.FavoriteEdit
import dev.josu.hypecar.core.data.repository.FavoriteSyncManager
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.PlaybackRepeatMode
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.ui.hypeTokens
import dev.josu.hypecar.core.ui.pressFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

        fun phone(compact: Boolean = false) = PlayerLayoutMetrics(
            topBarHeight = 0.dp,
            showsPhoneOverlayCollapseControl = false,
            artworkWidthFraction = if (compact) 0.62f else 0.70f,
            artworkTopPadding = if (compact) 22.dp else 30.dp,
            titleHorizontalPadding = if (compact) 24.dp else 28.dp,
            titleVerticalPadding = if (compact) 2.dp else 4.dp,
            progressHorizontalPadding = if (compact) 24.dp else 28.dp,
            progressVerticalPadding = if (compact) 2.dp else 4.dp,
            artworkHorizontalPadding = 0.dp,
            artworkCornerRadius = if (compact) 22.dp else 26.dp,
            infoHorizontalPadding = 18.dp,
            infoCardSpacing = 8.dp,
            descriptionHorizontalPadding = 16.dp,
            descriptionVerticalPadding = 8.dp,
            bottomDeckHorizontalPadding = if (compact) 18.dp else 24.dp,
            bottomDeckVerticalPadding = if (compact) 10.dp else 14.dp,
            bottomDeckSpacing = 0.dp,
            bottomControlsReservedHeight = if (compact) 110.dp else 122.dp,
            secondaryControlSize = if (compact) 46.dp else 50.dp,
            secondaryControlIconSize = if (compact) 25.dp else 27.dp,
            primaryControlSize = if (compact) 68.dp else 74.dp,
            primaryControlIconSize = if (compact) 33.dp else 36.dp,
            utilityIconSize = 22.dp,
        )
    }
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackRepository: PlaybackRepository,
    private val meRepository: MeRepository,
    private val favoriteSyncManager: FavoriteSyncManager,
) : ViewModel() {
    val queue: StateFlow<PlaybackQueue> = playbackRepository.queue
    private val _favoriteErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val favoriteErrors: SharedFlow<Unit> = _favoriteErrors.asSharedFlow()
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

    /**
     * Jump to a specific item in the current queue. Used by the up-next list
     * so tapping a queued track plays it directly instead of skipping
     * sequentially with skip-next.
     */
    fun jumpToQueueIndex(index: Int) {
        val current = queue.value
        if (index < 0 || index >= current.items.size || index == current.currentIndex) return
        viewModelScope.launch {
            // Seek within the live queue instead of rebuilding it with play():
            // rebuilding reset shuffle order and playback history position.
            playbackRepository.seekToQueueItem(index)
        }
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
            // Publish instead of only updating the queue so list screens (and
            // the car session) flip their hearts for the same track too.
            favoriteSyncManager.publish(
                FavoriteEdit(trackId, targetLoved, if (targetLoved) 1 else -1),
            )
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
                        // Confirmation only aligns the absolute value — lists
                        // already counted the optimistic delta, so delta 0.
                        favoriteSyncManager.publish(FavoriteEdit(trackId, serverLoved, 0))
                    }
                } else if (syncState.desiredLoved == targetLoved) {
                    syncState.desiredLoved = syncState.confirmedLoved
                    favoriteSyncManager.publish(
                        FavoriteEdit(trackId, syncState.confirmedLoved, if (targetLoved) -1 else 1),
                    )
                    _favoriteErrors.tryEmit(Unit)
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
    val configuration = LocalConfiguration.current
    val uiMode = context.resources.configuration.uiMode
    val isAutomotive = remember(context, uiMode) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) ||
            (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_CAR ||
            Build.PRODUCT.contains("gcar", ignoreCase = true) ||
            Build.DEVICE.contains("car", ignoreCase = true) ||
            Build.FINGERPRINT.contains("gcar", ignoreCase = true)
    }
    val useCompactPhoneLayout = !isAutomotive &&
        (configuration.screenHeightDp <= 820 || configuration.fontScale > 1.05f)
    val metrics = if (isAutomotive) {
        PlayerLayoutMetrics.automotive()
    } else {
        PlayerLayoutMetrics.phone(compact = useCompactPhoneLayout)
    }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val currentMediaId = queue.current?.mediaId
    val haptics = LocalHapticFeedback.current
    val closePlayerLabel = stringResource(R.string.player_close)
    val favoriteLabel = stringResource(R.string.player_action_favorite)
    val unfavoriteLabel = stringResource(R.string.player_action_unfavorite)
    val errorSkippedLabel = stringResource(R.string.player_error_skipped)
    val errorStoppedLabel = stringResource(R.string.player_error_stopped)
    val errorDismissLabel = stringResource(R.string.player_error_dismiss)
    val favoriteErrorLabel = stringResource(R.string.player_error_favorite)
    val snackbarHostState = remember { SnackbarHostState() }
    val transientError = queue.transientError
    LaunchedEffect(transientError?.eventId) {
        val event = transientError ?: return@LaunchedEffect
        val message = if (event.recoverable) errorSkippedLabel else errorStoppedLabel
        val result = snackbarHostState.showSnackbar(
            duration = SnackbarDuration.Long,
            message = message,
            actionLabel = errorDismissLabel,
        )
        viewModel.acknowledgePlaybackError(event.eventId)
        if (result == SnackbarResult.ActionPerformed) {
            // user dismissed; nothing further to do
        }
    }
    LaunchedEffect(Unit) {
        viewModel.favoriteErrors.collect {
            snackbarHostState.showSnackbar(
                message = favoriteErrorLabel,
                actionLabel = errorDismissLabel,
                duration = SnackbarDuration.Short,
            )
        }
    }
    val transportTick: () -> Unit = remember(haptics, isAutomotive) {
        if (isAutomotive) {
            { /* no haptics on Auto head units */ }
        } else {
            { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        }
    }
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 120.dp.toPx() }
    val entryOffsetPx = with(density) { 72.dp.toPx() }
    val dismissThresholdPx = with(density) { 140.dp.toPx() }
    val scope = rememberCoroutineScope()
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var dismissOffsetPx by remember { mutableFloatStateOf(0f) }
    var pendingDirection by remember { mutableStateOf<PlayerSwipeDirection?>(null) }
    var selectedProgress by remember(currentMediaId) { mutableFloatStateOf(model?.progressFraction ?: 0f) }
    var isSeeking by remember(currentMediaId) { mutableStateOf(false) }

    // Exactly one animation may own each offset at a time: a new drag or
    // settle cancels the previous job, so a mid-settle grab never fights a
    // still-running animation over the shared value.
    val dragSettleJob = remember { mutableStateOf<Job?>(null) }
    val dismissSettleJob = remember { mutableStateOf<Job?>(null) }
    fun animateDragOffset(to: Float, spec: AnimationSpec<Float>, onFinished: (() -> Unit)? = null) {
        dragSettleJob.value?.cancel()
        dragSettleJob.value = scope.launch {
            animate(initialValue = dragOffsetPx, targetValue = to, animationSpec = spec) { value, _ ->
                dragOffsetPx = value
            }
            onFinished?.invoke()
        }
    }
    fun animateDismissOffset(to: Float, spec: AnimationSpec<Float>, onFinished: (() -> Unit)? = null) {
        dismissSettleJob.value?.cancel()
        dismissSettleJob.value = scope.launch {
            animate(initialValue = dismissOffsetPx, targetValue = to, animationSpec = spec) { value, _ ->
                dismissOffsetPx = value
            }
            onFinished?.invoke()
        }
    }

    // Swiping past the last item (repeat off) must not fling the content off
    // screen: skipNext would no-op, currentMediaId never changes, and nothing
    // would ever bring the artwork back. ONE behaves like OFF for manual
    // navigation, so only ALL wraps.
    val canSwipeToNext by rememberUpdatedState(
        queue.items.isNotEmpty() &&
            (queue.currentIndex < queue.items.lastIndex || queue.repeatMode == PlaybackRepeatMode.ALL),
    )
    val canSwipeToPrevious by rememberUpdatedState(
        queue.items.isNotEmpty() &&
            (queue.currentIndex > 0 || queue.repeatMode == PlaybackRepeatMode.ALL),
    )

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
            dragSettleJob.value?.cancel()
            dragOffsetPx = incomingOffset
            animateDragOffset(0f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
            pendingDirection = null
        }
    }

    // detectHorizontal/VerticalDragGestures wait for axis-specific touch slop
    // before claiming the gesture, so taps aren't eaten by 1px jitter and the
    // horizontal and vertical handlers never fight over a diagonal drag.
    val artworkSwipeModifier = Modifier.pointerInput(currentMediaId, swipeThresholdPx) {
        detectHorizontalDragGestures(
            onDragStart = { dragSettleJob.value?.cancel() },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                dragOffsetPx += dragAmount
            },
            onDragEnd = {
                val direction = PlayerSwipeDecision.fromOffset(
                    offsetPx = dragOffsetPx,
                    thresholdPx = swipeThresholdPx,
                )
                val allowed = when (direction) {
                    PlayerSwipeDirection.NEXT -> canSwipeToNext
                    PlayerSwipeDirection.PREVIOUS -> canSwipeToPrevious
                    null -> false
                }
                if (direction != null && allowed) {
                    pendingDirection = direction
                    val exitOffset = when (direction) {
                        PlayerSwipeDirection.NEXT -> -size.width.toFloat()
                        PlayerSwipeDirection.PREVIOUS -> size.width.toFloat()
                    }
                    animateDragOffset(exitOffset, tween(durationMillis = 180, easing = FastOutSlowInEasing)) {
                        when (direction) {
                            PlayerSwipeDirection.NEXT -> viewModel.skipNext()
                            PlayerSwipeDirection.PREVIOUS -> viewModel.skipPrevious()
                        }
                    }
                } else {
                    if (direction == PlayerSwipeDirection.PREVIOUS && !canSwipeToPrevious) {
                        // No previous item: restart the current track (matching
                        // the transport button) but keep the artwork in place.
                        viewModel.skipPrevious()
                    }
                    animateDragOffset(0f, spring(dampingRatio = 0.75f, stiffness = 420f))
                }
            },
            onDragCancel = {
                animateDragOffset(0f, spring(dampingRatio = 0.75f, stiffness = 420f))
            },
        )
    }
    val dismissSwipeModifier = Modifier.pointerInput(dismissThresholdPx) {
        detectVerticalDragGestures(
            onDragStart = { dismissSettleJob.value?.cancel() },
            onVerticalDrag = { change, dragAmount ->
                if (dragAmount > 0f || dismissOffsetPx > 0f) {
                    change.consume()
                    dismissOffsetPx = (dismissOffsetPx + dragAmount).coerceAtLeast(0f)
                }
            },
            onDragEnd = {
                if (dismissOffsetPx >= dismissThresholdPx) {
                    animateDismissOffset(size.height.toFloat(), tween(durationMillis = 180, easing = FastOutSlowInEasing)) {
                        backDispatcher?.onBackPressed()
                    }
                } else {
                    animateDismissOffset(0f, spring(dampingRatio = 0.78f, stiffness = 460f))
                }
            },
            onDragCancel = {
                animateDismissOffset(0f, spring(dampingRatio = 0.78f, stiffness = 460f))
            },
        )
    }

    Scaffold(
        containerColor = Color(0xFF0A0809),
        // Insets are applied once by the content column itself; the default
        // Scaffold insets would add a second nav-bar height under the deck.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (isAutomotive) {
                CompactPlayerTopBar(onCollapse = { backDispatcher?.onBackPressed() })
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
                    actionColor = hypeTokens.brand.primary,
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
                                    translationY = if (isAutomotive) 0f else dismissOffsetPx
                                },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = metrics.bottomControlsReservedHeight)
                                    .graphicsLayer {
                                        translationX = dragOffsetPx
                                        val progress = (abs(dragOffsetPx) / swipeThresholdPx).coerceIn(0f, 1f)
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
                                            model = rememberSizedImageRequest(model.artworkUrl, 80.dp, 80.dp),
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
                                            modifier = Modifier
                                                .size(52.dp)
                                                .pressFeedback(pressedScale = 0.90f, label = "autoPlayerFavoritePress"),
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
                                                .pressFeedback(pressedScale = 0.90f, label = "playerFavoritePress")
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
                                        // While the user is dragging the
                                        // thumb the labels track the drag
                                        // position; once released they fall
                                        // back to the live playback position.
                                        // Previously the labels stayed pinned
                                        // until release, so the thumb visually
                                        // moved against frozen time digits.
                                        val displayedElapsed = if (isSeeking) {
                                            PlayerScreenUiModel.formatMs(
                                                (selectedProgress * model.durationMs).toLong()
                                                    .coerceAtLeast(0L),
                                            )
                                        } else {
                                            model.elapsedLabel
                                        }
                                        val displayedRemaining = if (isSeeking) {
                                            val remainingMs = (model.durationMs - (selectedProgress * model.durationMs).toLong())
                                                .coerceAtLeast(0L)
                                            "-${PlayerScreenUiModel.formatMs(remainingMs)}"
                                        } else {
                                            model.remainingLabel
                                        }
                                        Text(displayedElapsed, color = Color(0xFFABA4A0), style = MaterialTheme.typography.bodyLarge)
                                        Text(displayedRemaining, color = Color(0xFFABA4A0), style = MaterialTheme.typography.bodyLarge)
                                    }
                                }

                                // Up-next strip — visible on phone only.
                                // Keep it compact because this region sits
                                // between the scrubber and fixed transport
                                // deck, and Samsung display scaling can
                                // reduce the effective vertical space.
                                if (!isAutomotive && queue.items.size - queue.currentIndex > 1) {
                                    UpNextStrip(
                                        items = queue.items,
                                        currentIndex = queue.currentIndex,
                                        onJump = { absoluteIndex ->
                                            transportTick()
                                            viewModel.jumpToQueueIndex(absoluteIndex)
                                        },
                                        modifier = Modifier.padding(
                                            top = 8.dp,
                                            start = metrics.titleHorizontalPadding,
                                            end = metrics.titleHorizontalPadding,
                                        ),
                                    )
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
    val layoutDirection = LocalLayoutDirection.current

    BoxWithConstraints(
        modifier = modifier
            .height(44.dp)
            .semantics {
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
                    var latestProgress = down.position.x.toProgress(size.width, layoutDirection)
                    onProgressChange(latestProgress)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        latestProgress = change.position.x.toProgress(size.width, layoutDirection)
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

private fun Float.toProgress(width: Int, layoutDirection: LayoutDirection): Float {
    if (width <= 0) return 0f
    val fraction = (this / width.toFloat()).coerceIn(0f, 1f)
    // The bar renders mirrored in RTL, so the raw x axis must flip too or
    // dragging right rewinds while the fill grows the other way.
    return if (layoutDirection == LayoutDirection.Rtl) 1f - fraction else fraction
}

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
            IconButton(
                onClick = onCollapse,
                modifier = Modifier
                    .size(42.dp)
                    .pressFeedback(pressedScale = 0.90f, label = "compactPlayerCollapsePress"),
            ) {
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
        modifier = Modifier.pressFeedback(
            pressedScale = if (active) 0.90f else 0.93f,
            label = "playerModePress",
        ),
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
                tint = if (active) hypeTokens.brand.primary else Color(0xFFE3DDD9),
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
                .pressFeedback(pressedScale = 0.90f, label = "playerPrimaryPress")
                // Role.Button so TalkBack announces this Box as a button.
                // Without it the bare clickable Box is announced only as
                // "double-tap to activate" with no role context.
                .clickable(
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClick = onClick,
                )
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
            modifier = Modifier.pressFeedback(pressedScale = 0.92f, label = "playerSecondaryPress"),
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

// CompactInfoCard was defined here but never invoked — removed during the
// design-review cleanup. The player surfaces source/loved data inline now.

/**
 * Horizontal up-next carousel. Skips the currently playing item and renders
 * up to 6 upcoming tracks as fixed-height queue cards.
 *
 * Tapping a tile jumps to that track via [PlayerViewModel.jumpToQueueIndex],
 * which routes through `PlaybackRepository.play(tracks, startIndex)` so
 * Media3 cleanly resets its position to the new item.
 *
 * Reorder and remove are intentionally NOT wired here — both need a design
 * pass on how to expose the affordance (long-press? swipe? drag handle?)
 * before they ship.
 */
@Composable
private fun UpNextStrip(
    items: List<dev.josu.hypecar.core.model.PlaybackItem>,
    currentIndex: Int,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val upcoming = remember(items, currentIndex) {
        items.drop(currentIndex + 1).take(6)
    }
    if (upcoming.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.player_up_next),
            color = Color(0xFFE3DDD9),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.padding(bottom = 6.dp),
        )
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp),
        ) {
            itemsIndexed(upcoming, key = { _, queued -> queued.mediaId }) { offset, item ->
                val absoluteIndex = currentIndex + 1 + offset
                UpNextTile(
                    track = item.track,
                    queueNumber = absoluteIndex + 1,
                    onClick = { onJump(absoluteIndex) },
                )
            }
        }
    }
}

@Composable
private fun UpNextTile(
    track: dev.josu.hypecar.core.model.Track,
    queueNumber: Int,
    onClick: () -> Unit,
) {
    val a11yLabel = stringResource(
        R.string.player_track_in_queue_a11y,
        track.title,
        track.artist,
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF14100E),
        border = BorderStroke(1.dp, Color(0x332A211D)),
        modifier = Modifier
            .width(238.dp)
            .height(62.dp)
            .pressFeedback(pressedScale = 0.96f, label = "upNextTilePress")
            .semantics { contentDescription = a11yLabel },
    ) {
        Row(
            modifier = Modifier.padding(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = rememberSizedImageRequest(track.bestThumbnail(), 48.dp, 48.dp),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF26201D)),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 8.dp),
            ) {
                Text(
                    text = track.title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    color = Color(0xFFBBA89C),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = queueNumber.toString(),
                color = Color(0x66E3DDD9),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
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
    // Slow ~5.5s breathing cycle on the warm halo behind the cover art. The
    // transition only runs while the player screen is composed, so the cost
    // is bounded to the screen actually being open.
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
            model = rememberPlayerArtworkRequest(artworkUrl),
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

@Composable
private fun rememberPlayerArtworkRequest(url: String?): Any? {
    val context = LocalContext.current
    return remember(context, url) {
        url?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(768, 768)
                .crossfade(false)
                .build()
        }
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
