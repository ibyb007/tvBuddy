package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.data.local.dao.ChannelDao
import com.example.data.local.dao.ChannelGroupDao
import com.example.data.local.dao.EpgSourceDao
import com.example.data.local.dao.HistoryDao
import com.example.data.local.dao.PlaylistDao
import com.example.data.local.dao.ProgramDao
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.ChannelGroupEntity
import com.example.data.local.entity.EpgSourceEntity
import com.example.data.local.entity.HistoryEntity
import com.example.data.local.entity.PlaylistEpgCrossRef
import com.example.data.local.entity.PlaylistEntity
import com.example.data.local.entity.ProgramEntity

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        HistoryEntity::class,
        ProgramEntity::class,
        EpgSourceEntity::class,
        PlaylistEpgCrossRef::class,
        ChannelGroupEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class TvBuddyDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun historyDao(): HistoryDao
    abstract fun programDao(): ProgramDao
    abstract fun epgSourceDao(): EpgSourceDao
    abstract fun channelGroupDao(): ChannelGroupDao

    companion object {
        @Volatile
        private var INSTANCE: TvBuddyDatabase? = null

        fun getDatabase(context: Context): TvBuddyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TvBuddyDatabase::class.java,
                    "tvbuddy_database.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
