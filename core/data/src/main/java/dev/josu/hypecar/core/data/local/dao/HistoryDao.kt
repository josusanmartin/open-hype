package dev.josu.hypecar.core.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.josu.hypecar.core.data.local.entity.HistoryEntity

@Dao
interface HistoryDao {
    @Upsert
    suspend fun upsert(item: HistoryEntity)

    @Query("SELECT * FROM play_history ORDER BY playedAtEpochSeconds DESC LIMIT :limit OFFSET :offset")
    suspend fun recent(limit: Int, offset: Int = 0): List<HistoryEntity>
}
