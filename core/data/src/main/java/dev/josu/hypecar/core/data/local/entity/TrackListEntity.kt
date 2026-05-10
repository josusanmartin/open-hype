package dev.josu.hypecar.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "track_lists")
data class TrackListEntity(
    @PrimaryKey val key: String,
    val trackIdsJson: String,
    val updatedAtEpochSeconds: Long,
)
