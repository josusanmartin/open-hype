package dev.josu.hypecar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppChromeViewModel @Inject constructor(
    private val playbackRepository: PlaybackRepository,
) : ViewModel() {
    val queue: StateFlow<PlaybackQueue> = playbackRepository.queue

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
