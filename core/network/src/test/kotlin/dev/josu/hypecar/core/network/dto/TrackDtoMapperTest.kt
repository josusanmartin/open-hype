package dev.josu.hypecar.core.network.dto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackDtoMapperTest {
    @Test
    fun `track description is display safe text`() {
        val track = TrackDto(
            id = "39v49",
            artist = "Brooklynzhen",
            title = "Light of the Dead",
            description = """
                <a href="https://example.com/track">Light of the Dead by Brooklynzhen</a>
                &amp; a second line&nbsp;with extra     spacing.
            """.trimIndent(),
        ).toModel()

        assertThat(track.postDescription)
            .isEqualTo("Light of the Dead by Brooklynzhen & a second line with extra spacing.")
    }
}
