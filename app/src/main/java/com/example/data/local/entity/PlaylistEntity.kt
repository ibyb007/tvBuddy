package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.PlaylistType

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: PlaylistType,
    val sourceUrl: String,
    val username: String? = null,
    val password: String? = null,
    val macAddress: String? = null,
    // Stalker portal parameters (all optional)
    val deviceId: String? = null,
    val deviceId2: String? = null,
    val serialNumber: String? = null,
    // Portal (Stalker / Xtream) content selection
    @ColumnInfo(defaultValue = "1")
    val includeTv: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val includeVod: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val movieCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val showCount: Int = 0,
    val expirationDate: String? = null,
    val spoofPreset: String = "TIVIMATE",
    val customUserAgent: String? = null,
    val customHeadersJson: String? = null,
    val channelCount: Int = 0,
    val isActive: Boolean = true,
    val sortOrder: Int = 0,
    val logosPriority: String = "Prefer logos from playlist",
    val catchUpType: String = "None",
    val updateIntervalHours: Int = 24,
    val updateOnStart: Boolean = true,
    val groupsSorting: String = "By order in playlist",
    val showNewlyAddedGroups: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdated: Long = System.currentTimeMillis()
)
