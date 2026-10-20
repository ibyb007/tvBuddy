package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["groupTitle"]),
        Index(value = ["isFavorite"]),
        Index(value = ["contentType"])
    ]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val playlistId: Long,
    val streamId: String = "",
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val groupTitle: String = "General",
    /** TV, MOVIE or SHOW (see [com.example.data.model.ContentType]). */
    @ColumnInfo(defaultValue = "TV")
    val contentType: String = "TV",
    val channelNumber: Int = 0,
    val drmType: String? = null,
    val drmLicenseUrl: String? = null,
    val drmHeadersJson: String? = null,
    val drmKeyIdKey: String? = null,
    val httpUserAgent: String? = null,
    val httpHeadersJson: String? = null,
    val isFavorite: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)
