package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.ChannelEntity
import kotlinx.coroutines.flow.Flow

/** The few columns needed to recognise an already stored channel again when a playlist is refreshed. */
data class ChannelIdentity(
    val id: Long,
    val contentType: String,
    val streamId: String,
    val streamUrl: String,
    val isFavorite: Boolean
)

/** Id and group of a stored live channel (to keep its group when a refresh could not read the portal's genres). */
data class ChannelGroupRef(val streamId: String, val groupTitle: String)

/** What the EPG matcher needs of a live channel. */
data class ChannelEpgRef(
    val id: Long,
    val streamId: String,
    val name: String,
    val epgChannelId: String?,
    val epgName: String?,
    val logoUrl: String?
)

@Dao
interface ChannelDao {
    @Query("SELECT id, streamId, name, epgChannelId, epgName, logoUrl FROM channels WHERE contentType = 'TV' AND (:playlistId = 0 OR playlistId = :playlistId)")
    suspend fun getTvEpgRefs(playlistId: Long): List<ChannelEpgRef>

    @Query("UPDATE channels SET epgChannelId = :epgChannelId WHERE id = :id")
    suspend fun setEpgChannelId(id: Long, epgChannelId: String?)

    @Query("UPDATE channels SET logoUrl = :logoUrl WHERE id = :id")
    suspend fun setLogoUrl(id: Long, logoUrl: String?)

    @Query("SELECT streamId, groupTitle FROM channels WHERE playlistId = :playlistId AND contentType = 'TV' AND streamId != ''")
    suspend fun getTvGroupsByStreamId(playlistId: Long): List<ChannelGroupRef>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY channelNumber ASC, id ASC")
    fun getChannelsByPlaylist(playlistId: Long): Flow<List<ChannelEntity>>

    /** Live TV channels only. Movies and shows (VOD) are stored in the same table but kept out of the live list. */
    @Query("SELECT * FROM channels WHERE contentType = 'TV' ORDER BY channelNumber ASC, id ASC")
    fun getAllChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND contentType = :contentType ORDER BY channelNumber ASC, id ASC")
    fun getChannelsByPlaylistAndType(playlistId: Long, contentType: String): Flow<List<ChannelEntity>>

    /** Movies or shows of every playlist (the Movies / Shows pages filter hidden groups themselves). */
    @Query("SELECT * FROM channels WHERE contentType = :contentType ORDER BY channelNumber ASC, id ASC")
    fun getChannelsByType(contentType: String): Flow<List<ChannelEntity>>

    /**
     * Movies or shows for the list pages WITHOUT the details blob (plot, cast ...): with tens of thousands of
     * items that blob is most of the data, and the page only needs name, poster and group. The details are
     * read for the one highlighted item through [getChannelById].
     */
    @Query(
        "SELECT id, playlistId, streamId, name, streamUrl, logoUrl, groupTitle, contentType, rating, " +
            "NULL AS metaJson, channelNumber, drmType, drmLicenseUrl, drmHeadersJson, drmKeyIdKey, " +
            "httpUserAgent, httpHeadersJson, isFavorite, addedAt " +
            "FROM channels WHERE contentType = :contentType ORDER BY channelNumber ASC, id ASC"
    )
    fun getVodListByType(contentType: String): Flow<List<ChannelEntity>>

    @Query("SELECT id, contentType, streamId, streamUrl, isFavorite FROM channels WHERE playlistId = :playlistId")
    suspend fun getIdentities(playlistId: Long): List<ChannelIdentity>

    /** Which of these ids are stored already (movies / shows arrive page by page and from two directions). */
    @Query("SELECT streamId FROM channels WHERE playlistId = :playlistId AND contentType = :contentType AND streamId IN (:ids)")
    suspend fun existingStreamIds(playlistId: Long, contentType: String, ids: List<String>): List<String>

    /** Forgets the downloaded movies / shows of a playlist but keeps the ones in My list. */
    @Query("DELETE FROM channels WHERE playlistId = :playlistId AND contentType = :contentType AND isFavorite = 0")
    suspend fun deleteNonFavoritesByType(playlistId: Long, contentType: String)

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
