package dev.josu.hypecar.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlist_names")
data class PlaylistNameEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val updatedAtEpochSeconds: Long,
)
