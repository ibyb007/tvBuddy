package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.ProgramEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgramDao {
    @Query("SELECT * FROM epg_programs WHERE epgChannelId = :epgChannelId AND endTimeMs >= :now ORDER BY startTimeMs ASC LIMIT 20")
    fun getScheduleForChannel(epgChannelId: String, now: Long = System.currentTimeMillis()): Flow<List<ProgramEntity>>

    @Query("SELECT * FROM epg_programs WHERE epgChannelId = :epgChannelId AND :now >= startTimeMs AND :now < endTimeMs LIMIT 1")
    fun getCurrentProgram(epgChannelId: String, now: Long = System.currentTimeMillis()): Flow<ProgramEntity?>

    @Query("SELECT * FROM epg_programs WHERE :now >= startTimeMs AND :now < endTimeMs")
    fun getAllCurrentPrograms(now: Long = System.currentTimeMillis()): Flow<List<ProgramEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrograms(programs: List<ProgramEntity>)

    @Query("DELETE FROM epg_programs WHERE endTimeMs < :threshold")
    suspend fun purgeOldPrograms(threshold: Long)

    @Query("DELETE FROM epg_programs WHERE epgChannelId IN (:epgChannelIds)")
    suspend fun deleteProgramsForChannels(epgChannelIds: List<String>)

    @Query("DELETE FROM epg_programs WHERE epgChannelId = :epgChannelId")
    suspend fun deleteProgramsForChannel(epgChannelId: String)

    @Query("DELETE FROM epg_programs")
    suspend fun clearAllPrograms()
}
