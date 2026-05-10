package dev.josu.hypecar.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.josu.hypecar.core.data.local.dao.HistoryDao
import dev.josu.hypecar.core.data.local.dao.PlaylistDao
import dev.josu.hypecar.core.data.local.dao.TrackDao
import dev.josu.hypecar.core.data.local.dao.TrackListDao
import dev.josu.hypecar.core.data.local.entity.HistoryEntity
import dev.josu.hypecar.core.data.local.entity.PlaylistNameEntity
import dev.josu.hypecar.core.data.local.entity.TrackEntity
import dev.josu.hypecar.core.data.local.entity.TrackListEntity

@Database(
    entities = [
        TrackEntity::class,
        TrackListEntity::class,
        PlaylistNameEntity::class,
        HistoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class HypeDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun trackListDao(): TrackListDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
}
