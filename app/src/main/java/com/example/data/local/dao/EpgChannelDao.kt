package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.EpgChannelEntity

@Dao
interface EpgChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<EpgChannelEntity>)

    @Query("SELECT * FROM epg_channels")
    suspend fun getAll(): List<EpgChannelEntity>

    @Query("DELETE FROM epg_channels")
    suspend fun clear()
}
