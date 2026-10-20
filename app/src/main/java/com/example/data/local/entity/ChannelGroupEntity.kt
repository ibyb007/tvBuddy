package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "channel_groups",
    primaryKeys = ["playlistId", "contentType", "groupTitle"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["playlistId"])]
)
data class ChannelGroupEntity(
    val playlistId: Long,
    val groupTitle: String,
    /** TV, MOVIE or SHOW: the same group title can exist once per content type. */
    @ColumnInfo(defaultValue = "TV")
    val contentType: String = "TV",
    val isVisible: Boolean = true,
    val originalOrder: Int = 0,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
