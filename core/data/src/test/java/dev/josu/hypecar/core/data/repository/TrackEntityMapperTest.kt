package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.core.data.local.entity.TrackEntity
import org.junit.Test

class TrackEntityMapperTest {
    @Test
    fun `cached track descriptions are display safe`() {
        val track = sampleTrackEntity(
            postDescription = """
                <a href="https://brooklynzhen.bandcamp.com/track/light-of-the-dead-2">Light of the Dead by Brooklynzhen</a>
                &amp; a second line&nbsp;with extra spacing.
            """.trimIndent(),
        ).toModel()

        assertThat(track.postDescription)
            .isEqualTo("Light of the Dead by Brooklynzhen & a second line with extra spacing.")
    }
}

private fun sampleTrackEntity(
    postDescription: String,
) = TrackEntity(
    id = "39v49",
    artist = "Brooklynzhen",
    title = "Light of the Dead",
    lovedCount = 0,
    postedBy = "Highclouds",
    postedById = 22246,
    postedCount = 1,
    postDescription = postDescription,
    datePostedEpochSeconds = 1774723952,
    postUrl = "https://www.highclouds.org/",
    itunesUrl = "https://hypem.com/go/itunes_search/Brooklynzhen",
    thumbnailSmall = null,
    thumbnailMedium = null,
    thumbnailLarge = null,
    rank = null,
    viaUser = null,
    viaQuery = null,
    isLoved = false,
    audioUnavailable = false,
    mediaType = null,
)
