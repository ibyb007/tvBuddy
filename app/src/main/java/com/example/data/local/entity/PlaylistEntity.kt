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
    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdated: Long = System.currentTimeMillis()
)
