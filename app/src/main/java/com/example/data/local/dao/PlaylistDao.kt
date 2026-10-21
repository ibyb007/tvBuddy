package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.local.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY sortOrder ASC, id DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE isActive = 1 ORDER BY sortOrder ASC, id DESC")
    fun getActivePlaylists(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylistById(id: Long)

    @Query("UPDATE playlists SET channelCount = :count WHERE id = :playlistId")
    suspend fun updateChannelCount(playlistId: Long, count: Int)

    @Query("UPDATE playlists SET channelCount = :tv, movieCount = :movies, showCount = :shows, expirationDate = COALESCE(:expiration, expirationDate), lastUpdated = :now WHERE id = :playlistId")
    suspend fun updateContentCounts(playlistId: Long, tv: Int, movies: Int, shows: Int, expiration: String?, now: Long)

    @Query("UPDATE playlists SET expirationDate = :expiration WHERE id = :playlistId")
    suspend fun updateExpiration(playlistId: Long, expiration: String?)

    @Query("UPDATE playlists SET sortOrder = :sortOrder WHERE id = :playlistId")
    suspend fun updatePlaylistSortOrder(playlistId: Long, sortOrder: Int)

    @Transaction
    suspend fun updatePlaylistOrders(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            updatePlaylistSortOrder(id, index)
        }
    }
}
