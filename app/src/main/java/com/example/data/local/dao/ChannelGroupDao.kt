package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.local.entity.ChannelGroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelGroupDao {
    @Query("SELECT * FROM channel_groups ORDER BY playlistId ASC, contentType ASC, sortOrder ASC, originalOrder ASC")
    fun getAllGroups(): Flow<List<ChannelGroupEntity>>

    /** Groups of live TV only (what the channel list / groups panel use). */
    @Query("SELECT * FROM channel_groups WHERE contentType = 'TV' ORDER BY playlistId ASC, sortOrder ASC, originalOrder ASC")
    fun getLiveGroups(): Flow<List<ChannelGroupEntity>>

    @Query("SELECT * FROM channel_groups WHERE playlistId = :playlistId ORDER BY sortOrder ASC, originalOrder ASC")
    fun getGroupsByPlaylist(playlistId: Long): Flow<List<ChannelGroupEntity>>

    @Query("SELECT * FROM channel_groups WHERE playlistId = :playlistId ORDER BY sortOrder ASC, originalOrder ASC")
    suspend fun getGroupsByPlaylistOnce(playlistId: Long): List<ChannelGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(groups: List<ChannelGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroupsIgnoreExisting(groups: List<ChannelGroupEntity>)

    @Update
    suspend fun updateGroup(group: ChannelGroupEntity)

    @Update
    suspend fun updateGroups(groups: List<ChannelGroupEntity>)

    @Query("UPDATE channel_groups SET isVisible = :isVisible WHERE playlistId = :playlistId AND contentType = :contentType AND groupTitle = :groupTitle")
    suspend fun setGroupVisibility(playlistId: Long, contentType: String, groupTitle: String, isVisible: Boolean)

    /** Applies several visibility changes in one transaction so observers are notified once. */
    @Transaction
    suspend fun setGroupsVisibility(playlistId: Long, contentType: String, changes: Map<String, Boolean>) {
        changes.forEach { (title, visible) -> setGroupVisibility(playlistId, contentType, title, visible) }
    }

    @Query("UPDATE channel_groups SET isVisible = :isVisible WHERE playlistId = :playlistId AND contentType = :contentType")
    suspend fun setAllGroupsVisibility(playlistId: Long, contentType: String, isVisible: Boolean)

    @Query("UPDATE channel_groups SET sortOrder = :sortOrder WHERE playlistId = :playlistId AND contentType = :contentType AND groupTitle = :groupTitle")
    suspend fun updateGroupSortOrder(playlistId: Long, contentType: String, groupTitle: String, sortOrder: Int)

    @Transaction
    suspend fun updateGroupOrders(playlistId: Long, contentType: String, orderedGroupTitles: List<String>) {
        orderedGroupTitles.forEachIndexed { index, title ->
            updateGroupSortOrder(playlistId, contentType, title, index)
        }
    }

    @Query("DELETE FROM channel_groups WHERE playlistId = :playlistId")
    suspend fun deleteGroupsByPlaylist(playlistId: Long)

    @Query("DELETE FROM channel_groups WHERE playlistId = :playlistId AND contentType = :contentType")
    suspend fun deleteGroupsByPlaylistAndType(playlistId: Long, contentType: String)
}
