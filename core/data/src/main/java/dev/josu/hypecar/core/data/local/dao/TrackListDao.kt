package dev.josu.hypecar.core.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.josu.hypecar.core.data.local.entity.TrackListEntity

@Dao
interface TrackListDao {
    @Upsert
    suspend fun upsert(item: TrackListEntity)

    @Query("SELECT * FROM track_lists WHERE `key` = :key")
    suspend fun get(key: String): TrackListEntity?
}
