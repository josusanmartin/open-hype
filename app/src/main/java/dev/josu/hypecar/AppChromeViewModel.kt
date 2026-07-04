package dev.josu.hypecar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.repository.Connectivity
import dev.josu.hypecar.core.model.repository.ConnectivityRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppChromeViewModel @Inject constructor(
    private val playbackRepository: PlaybackRepository,
    connectivityRepository: ConnectivityRepository,
) : ViewModel() {
    val queue: StateFlow<PlaybackQueue> = playbackRepository.queue
    val miniPlayer: StateFlow<MiniPlayerUiState?> = playbackRepository.queue
        .map(MiniPlayerUiState.Companion::fromQueue)
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = MiniPlayerUiState.fromQueue(playbackRepository.queue.value),
        )

    val hasActivePlayback: StateFlow<Boolean> = playbackRepository.queue
        .map { queue -> queue.isPlaying || queue.current != null }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = playbackRepository.queue.value.let { it.isPlaying || it.current != null },
        )

    /**
     * Live network state. The connectivity banner above the bottom nav
     * subscribes to this to announce offline / limited modes.
     */
    val connectivity: StateFlow<Connectivity> = connectivityRepository.connectivity

    fun togglePlayPause() {
        viewModelScope.launch { playbackRepository.togglePlayPause() }
    }

    fun skipNext() {
        viewModelScope.launch { playbackRepository.skipNext() }
    }

    fun skipPrevious() {
        viewModelScope.launch { playbackRepository.skipPrevious() }
    }
}
