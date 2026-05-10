package dev.josu.hypecar.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GetTokenResponseDto(
    val username: String,
    @SerialName("hm_token") val token: String,
)

@Serializable
data class TrackDto(
    @SerialName("itemid") val id: String,
    val artist: String? = null,
    val title: String? = null,
    @SerialName("loved_count") val lovedCount: Int = 0,
    @SerialName("thumb_url") val thumbUrl: String? = null,
    @SerialName("thumb_url_medium") val thumbUrlMedium: String? = null,
    @SerialName("thumb_url_large") val thumbUrlLarge: String? = null,
    val rank: Int? = null,
    @SerialName("via_user") val viaUser: String? = null,
    @SerialName("via_query") val viaQuery: String? = null,
    @SerialName("sitename") val siteName: String? = null,
    @SerialName("siteid") val siteId: Int = 0,
    @SerialName("posted_count") val postedCount: Int = 0,
    @SerialName("description") val description: String? = null,
    @SerialName("dateposted") val datePosted: Long = 0L,
    @SerialName("pub_audio_unavail") val audioUnavailable: Boolean = false,
    @SerialName("posturl") val postUrl: String? = null,
    @SerialName("itunes_link") val itunesUrl: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("ts_loved_me") val lovedMe: Int? = null,
)

@Serializable
data class BlogDto(
    @SerialName("siteid") val id: Int,
    @SerialName("sitename") val name: String? = null,
    @SerialName("siteurl") val url: String? = null,
    @SerialName("followers") val followers: Int = 0,
    @SerialName("total_tracks") val totalTracks: Int = 0,
    @SerialName("blog_image") val imageUrl: String? = null,
    @SerialName("blog_image_small") val imageUrlSmall: String? = null,
    @SerialName("ts_featured") val featured: Int? = null,
    @SerialName("ts_loved_me") val lovedMe: Int? = null,
)

@Serializable
data class UserDto(
    val username: String,
    @SerialName("fullname") val fullName: String? = null,
    @SerialName("userpic") val avatarUrl: String? = null,
    @SerialName("favorites_count") val favoritesCount: FavoritesCountDto? = null,
    @SerialName("is_friend") val isFriend: Boolean? = null,
    @SerialName("is_follower") val isFollower: Boolean? = null,
)

@Serializable
data class FavoritesCountDto(
    @SerialName("item") val item: Int = 0,
    @SerialName("followers") val followers: Int = 0,
    @SerialName("user") val user: Int = 0,
)

@Serializable
data class TagDto(
    @SerialName("tag_name") val name: String,
    val priority: Boolean = false,
)
