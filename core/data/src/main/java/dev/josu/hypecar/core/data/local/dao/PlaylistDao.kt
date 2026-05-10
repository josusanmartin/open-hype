package dev.josu.hypecar.core.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.josu.hypecar.core.data.local.entity.PlaylistNameEntity

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist_names ORDER BY id")
    suspend fun getAll(): List<PlaylistNameEntity>

    @Upsert
    suspend fun upsertAll(items: List<PlaylistNameEntity>)

    @Query("DELETE FROM playlist_names")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(items: List<PlaylistNameEntity>) {
        clear()
        upsertAll(items)
    }
}
