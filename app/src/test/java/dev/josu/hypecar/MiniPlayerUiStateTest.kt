package dev.josu.hypecar

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.PlaybackItem
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.TrackThumbnails
import org.junit.Test

class MiniPlayerUiStateTest {
    @Test
    fun `fromQueue returns current track details and playback state`() {
        val track = Track(
            id = "39v49",
            artist = "L.A. Sagne",
            title = "Music In The Neighbourhood",
            lovedCount = 27,
            postedBy = "Destroy//Exist",
            postedById = 22246,
            postedCount = 3,
            postDescription = "After a run of singles.",
            datePostedEpochSeconds = 1774723952,
            postUrl = "https://www.destroyexist.com/2026/03/la-sagne-music-in-neighbourhood.html",
            itunesUrl = "https://hypem.com/go/itunes_search/L.A.%20Sagne",
            thumbnails = TrackThumbnails(large = "large"),
        )

        val uiState = MiniPlayerUiState.fromQueue(
            PlaybackQueue(
                items = listOf(PlaybackItem(track)),
                currentIndex = 0,
                isPlaying = true,
                positionMs = 4_000,
                durationMs = 10_000,
            ),
        )

        assertThat(uiState).isNotNull()
        assertThat(uiState?.title).isEqualTo("Music In The Neighbourhood")
        assertThat(uiState?.artist).isEqualTo("L.A. Sagne")
        assertThat(uiState?.artworkUrl).isEqualTo("large")
        assertThat(uiState?.isPlaying).isTrue()
        assertThat(uiState?.progressFraction).isEqualTo(0.4f)
    }
}
