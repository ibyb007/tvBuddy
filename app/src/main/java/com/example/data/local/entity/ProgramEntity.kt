package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "epg_programs",
    indices = [
        Index(value = ["epgChannelId"]),
        Index(value = ["startTimeMs"]),
        Index(value = ["endTimeMs"])
    ]
)
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val epgChannelId: String,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val iconUrl: String? = null
) {
    val progress: Float
        get() {
            val now = System.currentTimeMillis()
            if (now <= startTimeMs) return 0f
            if (now >= endTimeMs) return 1f
            val duration = (endTimeMs - startTimeMs).toFloat()
            return if (duration > 0) (now - startTimeMs) / duration else 0f
        }

    val isLive: Boolean
        get() {
            val now = System.currentTimeMillis()
            return now in startTimeMs until endTimeMs
        }
}
