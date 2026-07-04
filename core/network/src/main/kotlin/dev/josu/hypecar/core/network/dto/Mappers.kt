package dev.josu.hypecar.core.network.dto

import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.model.Blog
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.TrackThumbnails
import dev.josu.hypecar.core.model.User
import dev.josu.hypecar.core.model.toDisplayText

fun GetTokenResponseDto.toModel(): AuthSession = AuthSession(
    username = username,
    token = token,
)

fun TrackDto.toModel(): Track = Track(
    id = id,
    artist = artist?.takeIf { it.isNotBlank() }.orEmpty(),
    title = title?.takeIf { it.isNotBlank() }.orEmpty(),
    lovedCount = lovedCount,
    postedBy = siteName?.takeIf { it.isNotBlank() }.orEmpty(),
    postedById = siteId,
    postedCount = postedCount,
    postDescription = description.orEmpty().toDisplayText(),
    datePostedEpochSeconds = datePosted,
    postUrl = postUrl.orEmpty(),
    itunesUrl = itunesUrl.orEmpty(),
    thumbnails = TrackThumbnails(
        small = thumbUrl,
        medium = thumbUrlMedium,
        large = thumbUrlLarge,
    ),
    rank = rank,
    viaUser = viaUser,
    viaQuery = viaQuery,
    isLoved = lovedMe != null,
    audioUnavailable = audioUnavailable,
    mediaType = mediaType,
)

fun BlogDto.toModel(): Blog = Blog(
    id = id,
    name = name.orEmpty(),
    url = url.orEmpty(),
    followerCount = followers,
    trackCount = totalTracks,
    imageUrl = imageUrl,
    imageUrlSmall = imageUrlSmall,
    featured = featured != null,
    following = lovedMe != null,
)

fun UserDto.toModel(): User = User(
    username = username,
    fullName = fullName?.ifBlank { null },
    avatarUrl = avatarUrl,
    favoritesCount = favoritesCount?.item ?: 0,
    followersCount = favoritesCount?.followers ?: 0,
    followingCount = favoritesCount?.user ?: 0,
    isFriend = isFriend,
    isFollower = isFollower,
)
