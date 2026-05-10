package dev.josu.hypecar.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "play_history")
data class HistoryEntity(
    @PrimaryKey val trackId: String,
    val lastPositionSeconds: Int,
    val playedAtEpochSeconds: Long,
)
