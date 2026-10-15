package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.local.entity.EpgSourceEntity
import com.example.data.local.entity.PlaylistEpgCrossRef
import kotlinx.coroutines.flow.Flow

@Dao
interface EpgSourceDao {
    @Query("SELECT * FROM epg_sources ORDER BY isDefault DESC, createdAt ASC")
    fun getAllEpgSources(): Flow<List<EpgSourceEntity>>

    @Query("SELECT * FROM epg_sources WHERE id = :id LIMIT 1")
    suspend fun getEpgSourceById(id: Long): EpgSourceEntity?

    @Query("SELECT * FROM epg_sources WHERE url = :url LIMIT 1")
    suspend fun getEpgSourceByUrl(url: String): EpgSourceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpgSource(source: EpgSourceEntity): Long

    @Update
    suspend fun updateEpgSource(source: EpgSourceEntity)

    @Delete
    suspend fun deleteEpgSource(source: EpgSourceEntity)

    @Query("DELETE FROM epg_sources WHERE id = :id")
    suspend fun deleteEpgSourceById(id: Long)

    @Query("UPDATE epg_sources SET isDefault = 0")
    suspend fun clearAllDefaults()

    @Query("UPDATE epg_sources SET isDefault = 1 WHERE id = :id")
    suspend fun markAsDefault(id: Long)

    @Transaction
    suspend fun setDefaultSource(id: Long) {
        clearAllDefaults()
        markAsDefault(id)
    }

    @Query("UPDATE epg_sources SET isEnabled = :enabled WHERE id = :id")
    suspend fun setSourceEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE epg_sources SET lastUpdated = :timestamp WHERE id = :id")
    suspend fun updateLastUpdated(id: Long, timestamp: Long = System.currentTimeMillis())

    // ---- Per-Playlist EPG Source Assignments ----

    @Query("SELECT * FROM playlist_epg_cross_ref ORDER BY playlistId ASC, priority ASC")
    fun getAllPlaylistEpgCrossRefs(): Flow<List<PlaylistEpgCrossRef>>

    @Query("SELECT * FROM playlist_epg_cross_ref WHERE playlistId = :playlistId ORDER BY priority ASC")
    fun getCrossRefsForPlaylist(playlistId: Long): Flow<List<PlaylistEpgCrossRef>>

    @Query(
        """
        SELECT s.* FROM epg_sources s
        INNER JOIN playlist_epg_cross_ref ref ON s.id = ref.epgSourceId
        WHERE ref.playlistId = :playlistId
        ORDER BY ref.priority ASC
        """
    )
    fun getAssignedSourcesForPlaylist(playlistId: Long): Flow<List<EpgSourceEntity>>

    @Query(
        """
        SELECT s.* FROM epg_sources s
        INNER JOIN playlist_epg_cross_ref ref ON s.id = ref.epgSourceId
        WHERE ref.playlistId = :playlistId
        ORDER BY ref.priority ASC
        """
    )
    suspend fun getAssignedSourcesForPlaylistOnce(playlistId: Long): List<EpgSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistEpgCrossRef(crossRef: PlaylistEpgCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistEpgCrossRefs(crossRefs: List<PlaylistEpgCrossRef>)

    @Query("DELETE FROM playlist_epg_cross_ref WHERE playlistId = :playlistId AND epgSourceId = :epgSourceId")
    suspend fun removeEpgSourceFromPlaylist(playlistId: Long, epgSourceId: Long)

    @Query("DELETE FROM playlist_epg_cross_ref WHERE playlistId = :playlistId")
    suspend fun clearAssignedSourcesForPlaylist(playlistId: Long)
}
