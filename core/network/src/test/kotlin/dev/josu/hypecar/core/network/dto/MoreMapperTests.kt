package dev.josu.hypecar.core.network.dto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MoreMapperTests {

    // ---- TrackDto ----

    @Test
    fun `track with blank artist title and blog returns empty strings for UI to localize`() {
        val track = TrackDto(
            id = "39v49",
            artist = "  ",
            title = "",
            siteName = null,
        ).toModel()

        assertThat(track.artist).isEmpty()
        assertThat(track.title).isEmpty()
        assertThat(track.postedBy).isEmpty()
    }

    @Test
    fun `track lovedMe presence flips isLoved regardless of value`() {
        val loved = TrackDto(id = "a", lovedMe = 12345).toModel()
        val unloved = TrackDto(id = "a", lovedMe = null).toModel()
        assertThat(loved.isLoved).isTrue()
        assertThat(unloved.isLoved).isFalse()
    }

    @Test
    fun `track thumbnails copy across to model nullable fields`() {
        val track = TrackDto(
            id = "a",
            thumbUrl = "small.jpg",
            thumbUrlMedium = "medium.jpg",
            thumbUrlLarge = null,
        ).toModel()

        assertThat(track.thumbnails.small).isEqualTo("small.jpg")
        assertThat(track.thumbnails.medium).isEqualTo("medium.jpg")
        assertThat(track.thumbnails.large).isNull()
    }

    @Test
    fun `track audioUnavailable flag round-trips`() {
        assertThat(TrackDto(id = "a", audioUnavailable = true).toModel().audioUnavailable).isTrue()
        assertThat(TrackDto(id = "a", audioUnavailable = false).toModel().audioUnavailable).isFalse()
    }

    @Test
    fun `track viaUser viaQuery rank flow through unchanged`() {
        val track = TrackDto(
            id = "a",
            viaUser = "alice",
            viaQuery = "remix night",
            rank = 7,
        ).toModel()
        assertThat(track.viaUser).isEqualTo("alice")
        assertThat(track.viaQuery).isEqualTo("remix night")
        assertThat(track.rank).isEqualTo(7)
    }

    // ---- BlogDto ----

    @Test
    fun `blog null name and url become empty strings`() {
        val blog = BlogDto(id = 1, name = null, url = null).toModel()
        assertThat(blog.name).isEmpty()
        assertThat(blog.url).isEmpty()
    }

    @Test
    fun `blog featured and following flags reflect timestamp presence`() {
        val featured = BlogDto(id = 1, featured = 99999).toModel()
        val notFeatured = BlogDto(id = 1, featured = null).toModel()
        val following = BlogDto(id = 1, lovedMe = 12345).toModel()
        val notFollowing = BlogDto(id = 1, lovedMe = null).toModel()

        assertThat(featured.featured).isTrue()
        assertThat(notFeatured.featured).isFalse()
        assertThat(following.following).isTrue()
        assertThat(notFollowing.following).isFalse()
    }

    @Test
    fun `blog counts default to zero when API omits them`() {
        val blog = BlogDto(id = 1).toModel()
        assertThat(blog.followerCount).isEqualTo(0)
        assertThat(blog.trackCount).isEqualTo(0)
    }

    // ---- UserDto ----

    @Test
    fun `user fullName is normalized to null when blank`() {
        assertThat(UserDto(username = "u", fullName = "  ").toModel().fullName).isNull()
        assertThat(UserDto(username = "u", fullName = null).toModel().fullName).isNull()
        assertThat(UserDto(username = "u", fullName = "Real Name").toModel().fullName)
            .isEqualTo("Real Name")
    }

    @Test
    fun `user favorites count maps from nested DTO sub-object`() {
        val user = UserDto(
            username = "u",
            favoritesCount = FavoritesCountDto(item = 12, followers = 3, user = 7),
        ).toModel()
        assertThat(user.favoritesCount).isEqualTo(12)
        assertThat(user.followersCount).isEqualTo(3)
        assertThat(user.followingCount).isEqualTo(7)
    }

    @Test
    fun `user counts default to zero when nested DTO is missing`() {
        val user = UserDto(username = "u", favoritesCount = null).toModel()
        assertThat(user.favoritesCount).isEqualTo(0)
        assertThat(user.followersCount).isEqualTo(0)
        assertThat(user.followingCount).isEqualTo(0)
    }

    @Test
    fun `user friend and follower booleans pass through`() {
        val user = UserDto(username = "u", isFriend = true, isFollower = false).toModel()
        assertThat(user.isFriend).isTrue()
        assertThat(user.isFollower).isFalse()
    }

    // ---- TagDto ----

    @Test
    fun `tag name and priority round-trip`() {
        val tag = TagDto(name = "techno", priority = true).toModel()
        assertThat(tag.name).isEqualTo("techno")
        assertThat(tag.priority).isTrue()
    }

    // ---- GetTokenResponseDto ----

    @Test
    fun `auth token DTO converts to AuthSession unchanged`() {
        val session = GetTokenResponseDto(username = "alice", token = "tok-123").toModel()
        assertThat(session.username).isEqualTo("alice")
        assertThat(session.token).isEqualTo("tok-123")
    }
}
