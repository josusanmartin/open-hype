package dev.josu.hypecar.feature.catalog

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.TrackThumbnails
import org.junit.Test

class TrackRowUiModelTest {
    @Test
    fun `build creates fixed cover art row model`() {
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
            thumbnails = TrackThumbnails(
                small = "small",
                medium = "medium",
                large = "large",
            ),
        )

        val model = TrackRowUiModel.from(track)

        assertThat(model.coverArtUrl).isEqualTo("large")
        assertThat(model.coverArtWidthDp).isEqualTo(104)
        // statsLine became raw count fields so the UI can format with
        // pluralStringResource and locale-aware separators.
        assertThat(model.lovedCount).isEqualTo(27)
        assertThat(model.postedCount).isEqualTo(3)
        assertThat(model.titleLine).isEqualTo("Music In The Neighbourhood")
        assertThat(model.artistLine).isEqualTo("L.A. Sagne")
        assertThat(model.sourceLabel).isEqualTo("Destroy//Exist")
    }
}
