package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 5,
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

        /**
         * 3 -> 4: movies / shows (content type on channels and groups) and the Stalker / Xtream portal
         * parameters on playlists. Existing playlists, channels and group settings are kept.
         */
        /** 4 -> 5: poster rating and (Stalker) item details on channels. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN rating TEXT")
                db.execSQL("ALTER TABLE channels ADD COLUMN metaJson TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN contentType TEXT NOT NULL DEFAULT 'TV'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_channels_contentType ON channels(contentType)")

                db.execSQL("ALTER TABLE playlists ADD COLUMN deviceId TEXT")
                db.execSQL("ALTER TABLE playlists ADD COLUMN deviceId2 TEXT")
                db.execSQL("ALTER TABLE playlists ADD COLUMN serialNumber TEXT")
                db.execSQL("ALTER TABLE playlists ADD COLUMN includeTv INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE playlists ADD COLUMN includeVod INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlists ADD COLUMN movieCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlists ADD COLUMN showCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlists ADD COLUMN expirationDate TEXT")

                // The group primary key now includes the content type, so the table has to be rebuilt.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS channel_groups_new (" +
                        "playlistId INTEGER NOT NULL, " +
                        "groupTitle TEXT NOT NULL, " +
                        "contentType TEXT NOT NULL DEFAULT 'TV', " +
                        "isVisible INTEGER NOT NULL, " +
                        "originalOrder INTEGER NOT NULL, " +
                        "sortOrder INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(playlistId, contentType, groupTitle), " +
                        "FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO channel_groups_new (playlistId, groupTitle, contentType, isVisible, originalOrder, sortOrder, createdAt) " +
                        "SELECT playlistId, groupTitle, 'TV', isVisible, originalOrder, sortOrder, createdAt FROM channel_groups"
                )
                db.execSQL("DROP TABLE channel_groups")
                db.execSQL("ALTER TABLE channel_groups_new RENAME TO channel_groups")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_channel_groups_playlistId ON channel_groups(playlistId)")
            }
        }

        fun getDatabase(context: Context): TvBuddyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TvBuddyDatabase::class.java,
                    "tvbuddy_database.db"
                ).addMigrations(MIGRATION_3_4, MIGRATION_4_5).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
