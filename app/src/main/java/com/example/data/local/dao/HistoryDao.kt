package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.HistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM playback_history ORDER BY watchedAt DESC LIMIT 30")
    fun getRecentHistory(): Flow<List<HistoryEntity>>

    /** Movies / shows that were watched, most recent first (the VOD pages' "History" group). */
    @Query(
        "SELECT c.* FROM channels c INNER JOIN " +
            "(SELECT channelId, MAX(watchedAt) AS w FROM playback_history GROUP BY channelId) h ON h.channelId = c.id " +
            "WHERE c.contentType = :type ORDER BY h.w DESC LIMIT 300"
    )
    fun getVodHistory(type: String): Flow<List<ChannelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: HistoryEntity)

    @Query("DELETE FROM playback_history WHERE channelId = :channelId")
    suspend fun deleteHistoryByChannel(channelId: Long)

    @Query("DELETE FROM playback_history")
    suspend fun clearHistory()
}
