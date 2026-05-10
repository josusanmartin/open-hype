package dev.josu.hypecar.core.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.josu.hypecar.core.data.local.entity.TrackEntity

@Dao
interface TrackDao {
    @Upsert
    suspend fun upsertAll(items: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: String): TrackEntity?
}
