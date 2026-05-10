package dev.josu.hypecar.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackContractTest {
    @Test
    fun `streamUrl uses public serve endpoint`() {
        val track = Track(
            id = "39v49",
            artist = "L.A. Sagne",
            title = "Music In The Neighbourhood",
            lovedCount = 27,
            postedBy = "Destroy//Exist",
            postedById = 22246,
            postedCount = 3,
            postDescription = "desc",
            datePostedEpochSeconds = 1774723952,
            postUrl = "https://www.destroyexist.com/2026/03/la-sagne-music-in-neighbourhood.html",
            itunesUrl = "https://hypem.com/go/itunes_search/L.A.%20Sagne",
            thumbnails = TrackThumbnails(),
        )

        assertThat(track.streamUrl()).isEqualTo("https://hypem.com/serve/public/39v49")
    }

    @Test
    fun `bestThumbnail prefers largest available asset`() {
        val track = Track(
            id = "39v49",
            artist = "L.A. Sagne",
            title = "Music In The Neighbourhood",
            lovedCount = 27,
            postedBy = "Destroy//Exist",
            postedById = 22246,
            postedCount = 3,
            postDescription = "desc",
            datePostedEpochSeconds = 1774723952,
            postUrl = "https://www.destroyexist.com/2026/03/la-sagne-music-in-neighbourhood.html",
            itunesUrl = "https://hypem.com/go/itunes_search/L.A.%20Sagne",
            thumbnails = TrackThumbnails(
                small = "small",
                medium = "medium",
                large = "large",
            ),
        )

        assertThat(track.bestThumbnail()).isEqualTo("large")
    }
}
