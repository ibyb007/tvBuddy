package com.example.data.local.entity

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
