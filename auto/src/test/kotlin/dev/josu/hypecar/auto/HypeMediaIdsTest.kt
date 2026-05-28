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

    // ---- Parser edge cases (added with the design-review refactor) -------------

    @Test
    fun `track with url-encoded source id round trips`() {
        // Source ids can include "?" and "&" inside a URL-decoded form;
        // those must survive a round-trip through HypeMediaIds.
        val sourceId = "blog:42?special=1&page=2"
        val mediaId = HypeMediaIds.track("39v49", sourceId)

        assertThat(HypeMediaIds.parseTrackId(mediaId)).isEqualTo("39v49")
        assertThat(HypeMediaIds.parseTrackSourceId(mediaId)).isEqualTo(sourceId)
    }

    @Test
    fun `search queries with reserved characters round trip`() {
        val tricky = "rock & roll?yes please"
        val mediaId = HypeMediaIds.search(tricky)

        assertThat(HypeMediaIds.parseSearchQuery(mediaId)).isEqualTo(tricky)
    }

    @Test
    fun `search queries with unicode round trip`() {
        val query = "café déjà vu — ñoño"
        val mediaId = HypeMediaIds.search(query)

        assertThat(HypeMediaIds.parseSearchQuery(mediaId)).isEqualTo(query)
    }

    @Test
    fun `playlist ids round trip`() {
        val mediaId = HypeMediaIds.playlist(7)

        assertThat(HypeMediaIds.parsePlaylistId(mediaId)).isEqualTo(7)
    }

    @Test
    fun `playlist parser rejects malformed ids`() {
        assertThat(HypeMediaIds.parsePlaylistId("playlist:")).isNull()
        assertThat(HypeMediaIds.parsePlaylistId("playlist:not-a-number")).isNull()
        assertThat(HypeMediaIds.parsePlaylistId("section:latest")).isNull()
        assertThat(HypeMediaIds.parsePlaylistId("")).isNull()
    }

    @Test
    fun `track parser rejects malformed ids`() {
        assertThat(HypeMediaIds.parseTrackId("")).isNull()
        assertThat(HypeMediaIds.parseTrackId("track:")).isNull()
        assertThat(HypeMediaIds.parseTrackId("section:latest")).isNull()
    }

    @Test
    fun `source page parser clamps negative values to zero`() {
        // Manually crafted media id with a negative page parameter. The parser
        // must clamp it to 0 rather than passing a negative apiPage downstream.
        val mediaId = "track:abc?src=section%3Alatest&pg=-3"

        assertThat(HypeMediaIds.parseTrackSourcePage(mediaId)).isEqualTo(0)
    }

    @Test
    fun `source page parser tolerates non-numeric values`() {
        val mediaId = "track:abc?src=section%3Alatest&pg=not-a-number"

        assertThat(HypeMediaIds.parseTrackSourcePage(mediaId)).isEqualTo(0)
    }

    @Test
    fun `more section id is exposed and distinct from other sections`() {
        // The "More" umbrella section is what makes the AAOS top-level fit in
        // 4 tiles — feed/playlists/history live one level deeper under it.
        assertThat(HypeMediaIds.more).isEqualTo("section:more")
        assertThat(HypeMediaIds.more).isNotEqualTo(HypeMediaIds.latest)
        assertThat(HypeMediaIds.more).isNotEqualTo(HypeMediaIds.popular)
    }
}
