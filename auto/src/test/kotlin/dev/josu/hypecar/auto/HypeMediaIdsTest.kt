package dev.josu.hypecar.auto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HypeMediaIdsTest {
    @Test
    fun `section ids are stable`() {
        assertThat(HypeMediaIds.latest).isEqualTo("section:latest")
        assertThat(HypeMediaIds.popular).isEqualTo("section:popular")
        assertThat(HypeMediaIds.favorites).isEqualTo("section:favorites")
        assertThat(HypeMediaIds.feed).isEqualTo("section:feed")
        assertThat(HypeMediaIds.playlists).isEqualTo("section:playlists")
        assertThat(HypeMediaIds.history).isEqualTo("section:history")
    }

    @Test
    fun `track ids round trip`() {
        val mediaId = HypeMediaIds.track("39v49")

        assertThat(HypeMediaIds.parseTrackId(mediaId)).isEqualTo("39v49")
    }

    @Test
    fun `source scoped track ids round trip`() {
        val mediaId = HypeMediaIds.track("39v49", HypeMediaIds.latest)

        assertThat(HypeMediaIds.parseTrackId(mediaId)).isEqualTo("39v49")
        assertThat(HypeMediaIds.parseTrackSourceId(mediaId)).isEqualTo(HypeMediaIds.latest)
    }

    @Test
    fun `search source ids round trip`() {
        val mediaId = HypeMediaIds.search("shara lunon")

        assertThat(HypeMediaIds.parseSearchQuery(mediaId)).isEqualTo("shara lunon")
    }

    @Test
    fun `paged source track ids round trip with page index`() {
        val mediaId = HypeMediaIds.track("39v49", HypeMediaIds.latest, sourcePage = 4)

        assertThat(HypeMediaIds.parseTrackId(mediaId)).isEqualTo("39v49")
        assertThat(HypeMediaIds.parseTrackSourceId(mediaId)).isEqualTo(HypeMediaIds.latest)
        assertThat(HypeMediaIds.parseTrackSourcePage(mediaId)).isEqualTo(4)
    }

    @Test
    fun `track without explicit page reports page zero`() {
        val mediaId = HypeMediaIds.track("39v49", HypeMediaIds.latest)

        assertThat(HypeMediaIds.parseTrackSourcePage(mediaId)).isEqualTo(0)
    }

    @Test
    fun `naked track id reports no source and page zero`() {
        val mediaId = HypeMediaIds.track("39v49")

        assertThat(HypeMediaIds.parseTrackSourceId(mediaId)).isNull()
        assertThat(HypeMediaIds.parseTrackSourcePage(mediaId)).isEqualTo(0)
    }
}
