package dev.josu.hypecar.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val artist: String,
    val title: String,
    val lovedCount: Int,
    val postedBy: String,
    val postedById: Int,
    val postedCount: Int,
    val postDescription: String,
    val datePostedEpochSeconds: Long,
    val postUrl: String,
    val itunesUrl: String,
    val thumbnailSmall: String?,
    val thumbnailMedium: String?,
    val thumbnailLarge: String?,
    val rank: Int?,
    val viaUser: String?,
    val viaQuery: String?,
    val isLoved: Boolean,
    val audioUnavailable: Boolean,
    val mediaType: String?,
)
