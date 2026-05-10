package dev.josu.hypecar.feature.player

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.PlaybackItem
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.PlaybackRepeatMode
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.TrackThumbnails
import org.junit.Test

class PlayerScreenUiModelTest {
    @Test
    fun `fromQueue maps current track into player presentation`() {
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

        val model = PlayerScreenUiModel.fromQueue(
            PlaybackQueue(
                items = listOf(PlaybackItem(track)),
                currentIndex = 0,
                isPlaying = true,
                positionMs = 84_000,
                durationMs = 227_000,
                isShuffleEnabled = true,
                repeatMode = PlaybackRepeatMode.ALL,
            ),
        )

        assertThat(model).isNotNull()
        assertThat(model?.title).isEqualTo("Music In The Neighbourhood")
        assertThat(model?.artist).isEqualTo("L.A. Sagne")
        assertThat(model?.sourceLabel).isEqualTo("Destroy//Exist")
        assertThat(model?.description).isEqualTo("After a run of singles.")
        assertThat(model?.queueLabel).isEqualTo("Queue position 1 / 1")
        assertThat(model?.elapsedLabel).isEqualTo("1:24")
        assertThat(model?.remainingLabel).isEqualTo("-2:23")
        assertThat(model?.progressFraction).isEqualTo(84f / 227f)
        assertThat(model?.positionMs).isEqualTo(84_000)
        assertThat(model?.durationMs).isEqualTo(227_000)
        assertThat(model?.isPlaying).isTrue()
        assertThat(model?.isShuffleEnabled).isTrue()
        assertThat(model?.repeatMode).isEqualTo(PlaybackRepeatMode.ALL)
    }
}
