package dev.josu.hypecar.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackCollectionsTest {
    @Test
    fun `merge keeps stable order and replaces overlap with fresh metadata`() {
        val existing = listOf(track("a", "old A"), track("b", "old B"))
        val fresh = listOf(track("b", "fresh B"), track("c", "fresh C"), track("c", "newest C"))

        val merged = existing.mergePageByTrackId(fresh)

        assertThat(merged.map(Track::id)).containsExactly("a", "b", "c").inOrder()
        assertThat(merged.first { it.id == "b" }.title).isEqualTo("fresh B")
        assertThat(merged.first { it.id == "c" }.title).isEqualTo("newest C")
    }

    @Test
    fun `personal favorite state is removed without changing public track data`() {
        val loved = track("loved", "Loved", isLoved = true, lovedCount = 42)
        val neutral = track("neutral", "Neutral", isLoved = false, lovedCount = 7)

        val sanitized = listOf(loved, neutral).withoutPersonalFavoriteState()

        assertThat(sanitized.map(Track::id)).containsExactly("loved", "neutral").inOrder()
        assertThat(sanitized.map(Track::isLoved)).containsExactly(false, false).inOrder()
        assertThat(sanitized.map(Track::lovedCount)).containsExactly(42, 7).inOrder()
    }

    private fun track(
        id: String,
        title: String,
        isLoved: Boolean = false,
        lovedCount: Int = 0,
    ) = Track(
        id = id,
        artist = "artist",
        title = title,
        lovedCount = lovedCount,
        postedBy = "blog",
        postedById = 1,
        postedCount = 1,
        postDescription = "",
        datePostedEpochSeconds = 0L,
        postUrl = "",
        itunesUrl = "",
        isLoved = isLoved,
    )
}
