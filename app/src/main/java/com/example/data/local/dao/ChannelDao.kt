package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.ChannelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY channelNumber ASC, id ASC")
    fun getChannelsByPlaylist(playlistId: Long): Flow<List<ChannelEntity>>

    /** Live TV channels only. Movies and shows (VOD) are stored in the same table but kept out of the live list. */
    @Query("SELECT * FROM channels WHERE contentType = 'TV' ORDER BY channelNumber ASC, id ASC")
    fun getAllChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND contentType = :contentType ORDER BY channelNumber ASC, id ASC")
    fun getChannelsByPlaylistAndType(playlistId: Long, contentType: String): Flow<List<ChannelEntity>>

    @Query("DELETE FROM channels WHERE playlistId = :playlistId AND contentType = :contentType")
    suspend fun deleteChannelsByPlaylistAndType(playlistId: Long, contentType: String)

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND contentType = :contentType")
    suspend fun countByType(playlistId: Long, contentType: String): Int

    @Query("SELECT * FROM channels WHERE isFavorite = 1 AND contentType = 'TV' ORDER BY name ASC")
    fun getFavoriteChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT DISTINCT groupTitle FROM channels WHERE playlistId = :playlistId AND contentType = 'TV' ORDER BY groupTitle ASC")
    fun getCategoriesByPlaylist(playlistId: Long): Flow<List<String>>

    @Query("SELECT DISTINCT groupTitle FROM channels WHERE contentType = 'TV' ORDER BY groupTitle ASC")
    fun getAllCategories(): Flow<List<String>>

    @Query("SELECT * FROM channels WHERE id = :id LIMIT 1")
    suspend fun getChannelById(id: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE contentType = 'TV' AND (name LIKE '%' || :query || '%' OR groupTitle LIKE '%' || :query || '%')")
    fun searchChannels(query: String): Flow<List<ChannelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE id IN (:ids)")
    suspend fun deleteChannelsByIds(ids: List<Long>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteChannelsByPlaylist(playlistId: Long)

    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: Long, isFavorite: Boolean)

    @Update
    suspend fun updateChannel(channel: ChannelEntity)
}
