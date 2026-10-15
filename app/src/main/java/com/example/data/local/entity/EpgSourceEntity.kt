package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "epg_sources",
    indices = [Index(value = ["url"], unique = true)]
)
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String,
    val sourceType: String = "XMLTV", // "XMLTV" or "STALKER"
    val isDefault: Boolean = false,
    val isEnabled: Boolean = true,
    val lastUpdated: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_epg_cross_ref",
    primaryKeys = ["playlistId", "epgSourceId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = EpgSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["epgSourceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["epgSourceId"])
    ]
)
data class PlaylistEpgCrossRef(
    val playlistId: Long,
    val epgSourceId: Long,
    val priority: Int = 0
)
