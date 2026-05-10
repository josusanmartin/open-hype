package dev.josu.hypecar.core.data.repository

import dev.josu.hypecar.core.data.local.entity.PlaylistNameEntity
import dev.josu.hypecar.core.data.local.entity.TrackEntity
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.TrackThumbnails
import dev.josu.hypecar.core.model.toDisplayText

fun Track.toEntity(): TrackEntity = TrackEntity(
    id = id,
    artist = artist,
    title = title,
    lovedCount = lovedCount,
    postedBy = postedBy,
    postedById = postedById,
    postedCount = postedCount,
    postDescription = postDescription.toDisplayText(),
    datePostedEpochSeconds = datePostedEpochSeconds,
    postUrl = postUrl,
    itunesUrl = itunesUrl,
    thumbnailSmall = thumbnails.small,
    thumbnailMedium = thumbnails.medium,
    thumbnailLarge = thumbnails.large,
    rank = rank,
    viaUser = viaUser,
    viaQuery = viaQuery,
    isLoved = isLoved,
    audioUnavailable = audioUnavailable,
    mediaType = mediaType,
)

fun TrackEntity.toModel(): Track = Track(
    id = id,
    artist = artist,
    title = title,
    lovedCount = lovedCount,
    postedBy = postedBy,
    postedById = postedById,
    postedCount = postedCount,
    postDescription = postDescription.toDisplayText(),
    datePostedEpochSeconds = datePostedEpochSeconds,
    postUrl = postUrl,
    itunesUrl = itunesUrl,
    thumbnails = TrackThumbnails(
        small = thumbnailSmall,
        medium = thumbnailMedium,
        large = thumbnailLarge,
    ),
    rank = rank,
    viaUser = viaUser,
    viaQuery = viaQuery,
    isLoved = isLoved,
    audioUnavailable = audioUnavailable,
    mediaType = mediaType,
)

fun Playlist.toEntity(nowEpochSeconds: Long): PlaylistNameEntity = PlaylistNameEntity(
    id = id,
    name = name,
    updatedAtEpochSeconds = nowEpochSeconds,
)

fun PlaylistNameEntity.toModel(): Playlist = Playlist(
    id = id,
    name = name,
)
